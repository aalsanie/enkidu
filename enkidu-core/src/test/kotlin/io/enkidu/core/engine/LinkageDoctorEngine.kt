/*
 * Copyright © 2025-2026 | Enkidu linkage doctor catches runtime linkage failures before runtime
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.enkidu.core.engine

import io.enkidu.artifacts.v1.ClasspathIdentity
import io.enkidu.artifacts.v1.CompareReport
import io.enkidu.artifacts.v1.CompareSummary
import io.enkidu.artifacts.v1.ComparedClasspaths
import io.enkidu.artifacts.v1.EnkiduFingerprints
import io.enkidu.artifacts.v1.Fingerprint
import io.enkidu.artifacts.v1.Fingerprints
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.ReportSummary
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.artifacts.v1.WinnerChange
import io.enkidu.core.dup.DuplicateImpactAnalyzer
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.model.JarIndex
import io.enkidu.core.perf.FileJarScanCache
import io.enkidu.core.perf.JarScanRepository
import io.enkidu.core.resolve.AccessChecker
import io.enkidu.core.resolve.JvmLinkageResolver
import io.enkidu.core.resolve.ModuleIndex
import io.enkidu.core.scan.BytecodeReference
import io.enkidu.core.scan.TargetReferenceScanner
import io.enkidu.core.spi.SpiValidator
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Headless, deterministic engine entrypoint.
 *
 * Given compiled targets and an explicit runtime classpath, it scans bytecode references,
 * runs JVM-like resolution, classifies linkage failures, and returns a stable [LinkageReport].
 *
 * This class intentionally does not assume any build tool (Gradle/Maven). Inputs are plain paths.
 */
class LinkageDoctorEngine(
    private val targetScanner: TargetReferenceScanner = TargetReferenceScanner(),
) {
    fun run(request: LinkageDoctorRequest): LinkageReport {
        require(request.targets.isNotEmpty()) { "targets must not be empty." }
        require(request.runtimeClasspath.isNotEmpty()) { "runtimeClasspath must not be empty." }

        val perf = request.performance

        val snapshot = ClasspathSnapshot.fromPaths(request.runtimeClasspath)
        val moduleIndex = ModuleIndex.build(snapshot)

        val jarScanCache = perf.jarScanCacheDir?.let { FileJarScanCache(it) }
        val jarScans = JarScanRepository(cache = jarScanCache)
        val jarIndex = JarIndex.build(snapshot = snapshot, jarScans = jarScans, jarScanParallelism = perf.jarScanParallelism)

        val referencedBinaryClasses = ConcurrentHashMap.newKeySet<String>()
        val failures = ConcurrentLinkedQueue<LinkageFailure>()
        val callersByCallee = ConcurrentHashMap<MethodId, ConcurrentSkipListSet<MethodId>>()

        val allFailures: List<LinkageFailure> =
            JvmLinkageResolver(snapshot).use { resolver ->
                val accessChecker = AccessChecker(resolver = resolver, moduleIndex = moduleIndex)
                val classifier = LinkageFailureClassifier(snapshot, accessChecker, jarIndex)

                scanTargetsBoundedParallel(
                    targets = request.targets,
                    perf = perf,
                    classifier = classifier,
                    resolver = resolver,
                    referencedBinaryClasses = referencedBinaryClasses,
                    callersByCallee = callersByCallee,
                    failures = failures,
                )

                val callGraph =
                    CallGraphIndex.fromCallersByCallee(
                        callersByCallee.mapValues { (_, v) -> v.toSet() },
                    )

                val linkageFailures =
                    failures
                        .toList()
                        .map { enrichOneHopCallers(it, callGraph) }

                val spiFailures = SpiValidator(snapshot, jarScans).validate(resolver)
                val duplicateFailures = DuplicateImpactAnalyzer(jarIndex).analyze(referencedBinaryClasses)

                (linkageFailures + spiFailures + duplicateFailures).sortedWith(LinkageFailure.CANONICAL_ORDER)
            }

        val report =
            LinkageReport(
                tool = request.tool,
                fingerprints =
                    Fingerprints(
                        classpath = fingerprintOfPaths(request.runtimeClasspath),
                        targets = fingerprintOfPaths(request.targets),
                        report = null,
                    ),
                summary = ReportSummary(failureCount = 0, failureCountByType = emptyMap()),
                failures = allFailures,
            )

        return report.canonical()
    }

    /**
     * Compare linkage between two runtime classpaths.
     *
     * Common use-case: "works locally" (IDE/test runtime) but fails in production (slimmed/relocated classpath).
     */
    fun compare(request: LinkageDoctorCompareRequest): CompareReport {
        require(request.targets.isNotEmpty()) { "targets must not be empty." }
        require(request.classpathA.isNotEmpty()) { "classpathA must not be empty." }
        require(request.classpathB.isNotEmpty()) { "classpathB must not be empty." }

        val reportA =
            run(
                LinkageDoctorRequest(
                    tool = request.tool,
                    targets = request.targets,
                    runtimeClasspath = request.classpathA,
                    performance = request.performance,
                ),
            )

        val reportB =
            run(
                LinkageDoctorRequest(
                    tool = request.tool,
                    targets = request.targets,
                    runtimeClasspath = request.classpathB,
                    performance = request.performance,
                ),
            )

        val keyA = reportA.failures.associateBy { failureKey(it) }
        val keyB = reportB.failures.associateBy { failureKey(it) }

        val regressions =
            keyB.keys
                .minus(keyA.keys)
                .mapNotNull { keyB[it] }
                .sortedWith(LinkageFailure.CANONICAL_ORDER)

        val fixed =
            keyA.keys
                .minus(keyB.keys)
                .mapNotNull { keyA[it] }
                .sortedWith(LinkageFailure.CANONICAL_ORDER)

        val winnerChanges = computeWinnerChangesStreaming(request.targets, request.classpathA, request.classpathB, request.performance)

        val summary =
            CompareSummary(
                totalFailuresA = reportA.failures.size,
                totalFailuresB = reportB.failures.size,
                regressions = regressions.size,
                fixed = fixed.size,
                winnerChanges = winnerChanges.size,
            )

        val normalizedTargets = normalizeForFingerprint(request.targets)
        val normalizedA = normalizeForFingerprint(request.classpathA)
        val normalizedB = normalizeForFingerprint(request.classpathB)

        val compare =
            CompareReport(
                tool = request.tool,
                compared =
                    ComparedClasspaths(
                        targets = normalizedTargets,
                        classpathA =
                            ClasspathIdentity(
                                label = request.labelA,
                                fingerprintSha256 = EnkiduFingerprints.sha256HexUtf8(normalizedA.joinToString("\n")),
                                entryCount = request.classpathA.size,
                            ),
                        classpathB =
                            ClasspathIdentity(
                                label = request.labelB,
                                fingerprintSha256 = EnkiduFingerprints.sha256HexUtf8(normalizedB.joinToString("\n")),
                                entryCount = request.classpathB.size,
                            ),
                    ),
                summary = summary,
                regressions = regressions,
                fixed = fixed,
                winnerChanges = winnerChanges,
            )

        return compare.canonical()
    }

    private fun scanTargetsBoundedParallel(
        targets: List<Path>,
        perf: PerformanceOptions,
        classifier: LinkageFailureClassifier,
        resolver: JvmLinkageResolver,
        referencedBinaryClasses: MutableSet<String>,
        callersByCallee: ConcurrentHashMap<MethodId, ConcurrentSkipListSet<MethodId>>,
        failures: ConcurrentLinkedQueue<LinkageFailure>,
    ) {
        val parallelism = perf.targetScanParallelism
        require(parallelism >= 1) { "targetScanParallelism must be >= 1" }

        fun consumeReference(ref: BytecodeReference) {
            // Track binary class owners (used later for duplicate impact analysis).
            val ownerBinary = ref.symbol.owner.replace('/', '.')
            if (ownerBinary.isNotBlank()) referencedBinaryClasses.add(ownerBinary)

            // Call graph edge (callee -> callers)
            if (ref.symbol.kind == SymbolKind.METHOD && CallGraphIndex.isInvokeOpcode(ref.opcode)) {
                val caller = MethodId(ref.site.callerClass, ref.site.callerMethod, ref.site.callerDescriptor)
                val callee = CallGraphIndex.toMethodId(ref.symbol)
                callersByCallee.computeIfAbsent(callee) { ConcurrentSkipListSet() }.add(caller)
            }

            val failure = classifier.classify(ref, resolver, callGraph = null)
            if (failure != null) failures.add(failure)
        }

        if (parallelism == 1) {
            targetScanner.scanTargetsStreaming(targets) { consumeReference(it) }
            return
        }

        val maxInFlight =
            when {
                (perf.maxInFlightTargetClasses ?: 0) > 0 -> perf.maxInFlightTargetClasses!!
                else -> parallelism * 2
            }

        val exec = Executors.newFixedThreadPool(parallelism)
        val completion = ExecutorCompletionService<Unit>(exec)
        val permits = Semaphore(maxInFlight)
        var submitted = 0

        try {
            // Stream class bytes (bounded), submit scan+classify tasks.
            targetScanner.forEachTargetClassBytes(targets) { bytes ->
                permits.acquire()
                completion.submit(
                    java.util.concurrent.Callable {
                        try {
                            val refs = targetScanner.bytecodeScanner.scanClassBytes(bytes)
                            for (r in refs) consumeReference(r)
                        } finally {
                            permits.release()
                        }
                        Unit
                    },
                )
                submitted++
            }

            repeat(submitted) {
                completion.take().get()
            }
        } finally {
            exec.shutdown()
            exec.awaitTermination(2, TimeUnit.MINUTES)
        }
    }

    private fun enrichOneHopCallers(
        f: LinkageFailure,
        callGraph: CallGraphIndex,
    ): LinkageFailure {
        if (f.message.contains("One-hop callers:")) return f
        val site = f.referenceSite
        val method = MethodId(site.callerClass, site.callerMethod, site.callerDescriptor)
        val oneHop = callGraph.callersOf(method).take(3)
        if (oneHop.isEmpty()) return f
        return f.copy(message = f.message + " | One-hop callers: " + oneHop.joinToString(", ") { it.toString() })
    }

    private fun fingerprintOfPaths(paths: List<Path>): Fingerprint {
        val normalized = normalizeForFingerprint(paths).joinToString(separator = "\n")
        return Fingerprint(
            algorithm = "SHA-256",
            value = EnkiduFingerprints.sha256HexUtf8(normalized),
        )
    }

    private fun normalizeForFingerprint(paths: List<Path>): List<String> =
        paths.map {
            it
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace(Char(92), '/')
        }

    private fun failureKey(f: LinkageFailure): String {
        val site = f.referenceSite
        val sym = f.symbol

        val owner = sym?.owner.orEmpty()
        val kind = sym?.kind?.name.orEmpty()
        val name = sym?.name.orEmpty()
        val desc = sym?.descriptor.orEmpty()

        return buildString {
            append(f.type.name)
            append('|')
            append(site.callerClass)
            append('|')
            append(site.callerMethod)
            append('|')
            append(site.callerDescriptor)
            append('|')
            append(site.line ?: -1)
            append('|')
            append(site.bytecodeOffset ?: -1)
            append('|')
            append(owner)
            append('|')
            append(kind)
            append('|')
            append(name)
            append('|')
            append(desc)
        }
    }

    private fun computeWinnerChangesStreaming(
        targets: List<Path>,
        classpathA: List<Path>,
        classpathB: List<Path>,
        perf: PerformanceOptions,
    ): List<WinnerChange> {
        val snapshotA = ClasspathSnapshot.fromPaths(classpathA)
        val snapshotB = ClasspathSnapshot.fromPaths(classpathB)

        val jarScanCache = perf.jarScanCacheDir?.let { FileJarScanCache(it) }
        val jarScans = JarScanRepository(cache = jarScanCache)

        val jarA = JarIndex.build(snapshotA, jarScans, perf.jarScanParallelism)
        val jarB = JarIndex.build(snapshotB, jarScans, perf.jarScanParallelism)

        val interesting = ConcurrentHashMap.newKeySet<String>()

        // Stream references (no in-memory list) to compute which classes are worth checking.
        targetScanner.scanTargetsStreaming(targets) { ref ->
            interesting.add(ref.site.callerClass.replace('/', '.'))
            interesting.add(ref.symbol.owner.replace('/', '.'))
        }

        val changes = mutableListOf<WinnerChange>()
        for (cls in interesting.asSequence().filter { it.isNotBlank() }.sorted()) {
            val winA = jarA.winnerOf(cls)?.entryPath?.toString()
            val winB = jarB.winnerOf(cls)?.entryPath?.toString()
            if (winA != null && winB != null && winA != winB) {
                changes += WinnerChange(className = cls, winnerA = winA, winnerB = winB)
            }
        }

        return changes.sortedBy { it.className }
    }
}
