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
import io.enkidu.artifacts.v1.WinnerChange
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.model.JarIndex
import io.enkidu.core.resolve.AccessChecker
import io.enkidu.core.resolve.JvmLinkageResolver
import io.enkidu.core.resolve.ModuleIndex
import io.enkidu.core.scan.BytecodeReference
import io.enkidu.core.scan.TargetReferenceScanner
import java.nio.file.Path

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

        val snapshot = ClasspathSnapshot.fromPaths(request.runtimeClasspath)
        val moduleIndex = ModuleIndex.build(snapshot)
        val references: List<BytecodeReference> = targetScanner.scanTargets(request.targets)

        val callGraph = CallGraphIndex.fromReferences(references)
        val failures =
            JvmLinkageResolver(snapshot).use { resolver ->
                val accessChecker = AccessChecker(resolver = resolver, moduleIndex = moduleIndex)
                val classifier = LinkageFailureClassifier(snapshot, accessChecker)
                references.mapNotNull { classifier.classify(it, resolver, callGraph) }
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
                failures = failures,
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

        val references = targetScanner.scanTargets(request.targets)

        val reportA =
            run(
                LinkageDoctorRequest(
                    tool = request.tool,
                    targets = request.targets,
                    runtimeClasspath = request.classpathA,
                ),
            )

        val reportB =
            run(
                LinkageDoctorRequest(
                    tool = request.tool,
                    targets = request.targets,
                    runtimeClasspath = request.classpathB,
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

        val winnerChanges = computeWinnerChanges(references, request.classpathA, request.classpathB)

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

        // A stable identity that survives evidence/fix-plan differences.
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

    private fun computeWinnerChanges(
        references: List<BytecodeReference>,
        classpathA: List<Path>,
        classpathB: List<Path>,
    ): List<WinnerChange> {
        val jarA = JarIndex.build(ClasspathSnapshot.fromPaths(classpathA))
        val jarB = JarIndex.build(ClasspathSnapshot.fromPaths(classpathB))

        val interestingBinaryClasses: Set<String> =
            references
                .asSequence()
                .flatMap { seqOf(it.site.callerClass, it.symbol.owner) }
                .map { it.replace('/', '.') }
                .filter { it.isNotBlank() }
                .toSet()

        val changes = mutableListOf<WinnerChange>()
        for (cls in interestingBinaryClasses.sorted()) {
            val winA = jarA.winnerOf(cls)?.entryPath?.toString()
            val winB = jarB.winnerOf(cls)?.entryPath?.toString()
            if (winA != null && winB != null && winA != winB) {
                changes += WinnerChange(className = cls, winnerA = winA, winnerB = winB)
            }
        }
        return changes
    }

    private fun <T> seqOf(
        a: T,
        b: T,
    ): Sequence<T> = sequenceOf(a, b)
}
