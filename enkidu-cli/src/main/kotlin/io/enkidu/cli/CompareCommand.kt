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
package io.enkidu.cli

import io.enkidu.artifacts.v1.ToolMetadata
import io.enkidu.core.engine.LinkageDoctorCompareRequest
import io.enkidu.core.engine.LinkageDoctorEngine
import io.enkidu.core.engine.PerformanceOptions
import io.enkidu.export.EnkiduReportWriters
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

@CommandLine.Command(
    name = "compare",
    mixinStandardHelpOptions = true,
    description = [
        "Compare linkage results for the same targets against two runtime classpaths.",
        "",
        "Typical use cases:",
        "- testRuntimeClasspath vs runtimeClasspath regressions",
        "- slimmed/shaded production classpath regressions",
    ],
)
class CompareCommand : Runnable {
    @CommandLine.Option(
        names = ["--targets"],
        required = true,
        description = ["File containing one compiled output path per line."],
    )
    lateinit var targetsManifest: Path

    @CommandLine.Option(
        names = ["--classpath-a"],
        required = true,
        description = ["Classpath manifest A (one entry per line)."],
    )
    lateinit var classpathA: Path

    @CommandLine.Option(
        names = ["--classpath-b"],
        required = true,
        description = ["Classpath manifest B (one entry per line)."],
    )
    lateinit var classpathB: Path

    @CommandLine.Option(
        names = ["--label-a"],
        description = ["Label for classpath A (defaults to 'A')."],
    )
    var labelA: String = "A"

    @CommandLine.Option(
        names = ["--label-b"],
        description = ["Label for classpath B (defaults to 'B')."],
    )
    var labelB: String = "B"

    @CommandLine.Option(
        names = ["--out"],
        description = ["Output file path (JSON). If omitted, prints to stdout."],
    )
    var out: Path? = null

    @CommandLine.Option(
        names = ["--jar-scan-cache-dir"],
        description = [
            "Enable the jar scanning cache by providing a directory.",
            "The cache is keyed by jar SHA-256 and stores jar entry scans (classes + META-INF/services).",
        ],
    )
    var jarScanCacheDir: Path? = null

    @CommandLine.Option(
        names = ["--jar-scan-parallelism"],
        defaultValue = "1",
        description = ["Parallelism for runtime classpath jar scanning (>= 1)."],
    )
    var jarScanParallelism: Int = 1

    @CommandLine.Option(
        names = ["--target-scan-parallelism"],
        defaultValue = "1",
        description = ["Parallelism for scanning target classfiles (>= 1)."],
    )
    var targetScanParallelism: Int = 1

    @CommandLine.Option(
        names = ["--max-in-flight-target-classes"],
        defaultValue = "0",
        description = [
            "Bound queued class scan work-items to limit memory.",
            "0 means use 2×target-scan-parallelism.",
        ],
    )
    var maxInFlightTargetClasses: Int = 0

    override fun run() {
        val targets = readManifestPaths(targetsManifest)
        val cpA = readManifestPaths(classpathA)
        val cpB = readManifestPaths(classpathB)

        val report =
            LinkageDoctorEngine().compare(
                LinkageDoctorCompareRequest(
                    tool = ToolMetadata(name = "enkidu-linkage-doctor", version = BuildInfo.version, resolverMode = "jvm-linkage-sim-v1"),
                    targets = targets,
                    classpathA = cpA,
                    classpathB = cpB,
                    labelA = labelA,
                    labelB = labelB,
                    performance =
                        PerformanceOptions(
                            jarScanCacheDir = jarScanCacheDir,
                            jarScanParallelism = jarScanParallelism,
                            targetScanParallelism = targetScanParallelism,
                            maxInFlightTargetClasses = maxInFlightTargetClasses.takeIf { it > 0 },
                        ),
                ),
            )

        val bytes = EnkiduReportWriters.compareJsonV1(report)

        val destination = out
        if (destination == null) {
            print(String(bytes))
            return
        }

        destination.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(destination, bytes)
        println("Wrote ${destination.toAbsolutePath()}")
    }

    private fun readManifestPaths(path: Path): List<Path> =
        Files
            .readAllLines(path)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
}
