/*
 * Copyright © 2025-2026 | Enkidu
 *
 * Linkage Doctor checks whether the code you compiled will still work at runtime by
 * reading your compiled bytecode and resolving every referenced class, method, and
 * field against the exact jars on your runtime classpath
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

import io.enkidu.artifacts.v1.EnkiduFingerprints
import io.enkidu.artifacts.v1.Fingerprint
import io.enkidu.artifacts.v1.Fingerprints
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.ReportSummary
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.resolve.JvmLinkageResolver
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
        val references: List<BytecodeReference> = targetScanner.scanTargets(request.targets)

        val callGraph = CallGraphIndex.fromReferences(references)
        val failures =
            JvmLinkageResolver(snapshot).use { resolver ->
                val classifier = LinkageFailureClassifier(snapshot)
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

    private fun fingerprintOfPaths(paths: List<Path>): Fingerprint {
        val normalized =
            paths.joinToString(separator = "\n") {
                it
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .replace(Char(92), '/')
            }

        return Fingerprint(
            algorithm = "SHA-256",
            value = EnkiduFingerprints.sha256HexUtf8(normalized),
        )
    }
}
