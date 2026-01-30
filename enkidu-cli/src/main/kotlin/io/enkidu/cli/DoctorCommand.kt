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

import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.ToolMetadata
import io.enkidu.core.engine.LinkageDoctorEngine
import io.enkidu.core.engine.LinkageDoctorRequest
import io.enkidu.core.engine.PerformanceOptions
import io.enkidu.export.EnkiduReportWriters
import picocli.CommandLine
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "doctor",
    mixinStandardHelpOptions = true,
    description = [
        "Run Enkidu Linkage Doctor against compiled targets using an explicit runtime classpath.",
        "\n",
        "Exit codes:",
        "  0: no failures under policy",
        "  2: failures found under policy",
        "  3: invalid input or usage",
    ],
)
internal class DoctorCommand : Callable<Int> {
    @CommandLine.Option(
        names = ["--targets"],
        required = true,
        arity = "1..*",
        description = ["One or more targets to scan: classes directory or jar."],
    )
    private lateinit var targets: Array<Path>

    @CommandLine.Option(
        names = ["--classpath"],
        arity = "1..*",
        description = ["One or more runtime classpath entries (directories or jars) in resolution order."],
    )
    private var classpathEntries: Array<Path> = emptyArray()

    @CommandLine.Option(
        names = ["--classpath-file"],
        description = ["Path to a file containing the runtime classpath manifest (one entry per line)."],
    )
    private var classpathFile: Path? = null

    @CommandLine.Option(
        names = ["--format"],
        defaultValue = "json",
        description = ["Output format: json, sarif, html."],
    )
    private var format: OutputFormat = OutputFormat.JSON

    @CommandLine.Option(
        names = ["--output"],
        description = ["Write output to a file instead of stdout."],
    )
    private var output: Path? = null

    @CommandLine.Option(
        names = ["--fail-on"],
        defaultValue = "any",
        description = ["Failure policy: any, error-only, none."],
    )
    private var failOn: FailOnPolicy = FailOnPolicy.ANY

    @CommandLine.Option(
        names = ["--jar-scan-cache-dir"],
        description = [
            "Enable the jar scanning cache by providing a directory.",
            "The cache is keyed by jar SHA-256 and stores jar entry scans (classes + META-INF/services).",
        ],
    )
    private var jarScanCacheDir: Path? = null

    @CommandLine.Option(
        names = ["--jar-scan-parallelism"],
        defaultValue = "1",
        description = ["Parallelism for runtime classpath jar scanning (>= 1)."],
    )
    private var jarScanParallelism: Int = 1

    @CommandLine.Option(
        names = ["--target-scan-parallelism"],
        defaultValue = "1",
        description = ["Parallelism for scanning target classfiles (>= 1)."],
    )
    private var targetScanParallelism: Int = 1

    @CommandLine.Option(
        names = ["--max-in-flight-target-classes"],
        defaultValue = "0",
        description = [
            "Bound queued class scan work-items to limit memory.",
            "0 means use 2×target-scan-parallelism.",
        ],
    )
    private var maxInFlightTargetClasses: Int = 0

    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int =
        try {
            val resolvedClasspath = resolveClasspathEntries(classpathEntries.toList(), classpathFile)
            val engine = LinkageDoctorEngine()

            val report =
                engine.run(
                    LinkageDoctorRequest(
                        tool =
                            ToolMetadata(
                                name = TOOL_NAME,
                                version = BuildInfo.version,
                                resolverMode = RESOLVER_MODE,
                            ),
                        targets = targets.toList(),
                        runtimeClasspath = resolvedClasspath,
                        performance =
                            PerformanceOptions(
                                jarScanCacheDir = jarScanCacheDir,
                                jarScanParallelism = jarScanParallelism,
                                targetScanParallelism = targetScanParallelism,
                                maxInFlightTargetClasses = maxInFlightTargetClasses.takeIf { it > 0 },
                            ),
                    ),
                )

            val bytes =
                when (format) {
                    OutputFormat.JSON -> EnkiduReportWriters.json(report)
                    OutputFormat.SARIF -> EnkiduReportWriters.sarifV1(report)
                    OutputFormat.HTML -> EnkiduReportWriters.htmlV1(report)
                }

            writeOutput(bytes)

            exitCodeFor(report)
        } catch (e: IllegalArgumentException) {
            spec.commandLine().err.println(e.message ?: "Invalid input")
            EXIT_INVALID_INPUT
        } catch (e: Exception) {
            spec.commandLine().err.println(e.message ?: "Unexpected error")
            EXIT_INVALID_INPUT
        }

    private fun writeOutput(bytes: ByteArray) {
        val out = output
        if (out == null) {
            // picocli sets stdout writer, but we need bytes. Use UTF-8 for text formats.
            val writer = spec.commandLine().out
            writer.print(bytes.toString(StandardCharsets.UTF_8))
            writer.flush()
            return
        }

        val parent = out.toAbsolutePath().normalize().parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(out, bytes)
    }

    private fun exitCodeFor(report: LinkageReport): Int {
        val hasAny = report.failures.isNotEmpty()
        val hasError = report.failures.any { it.severity == Severity.ERROR }

        val failed =
            when (failOn) {
                FailOnPolicy.ANY -> hasAny
                FailOnPolicy.ERROR_ONLY -> hasError
                FailOnPolicy.NONE -> false
            }

        return if (failed) 2 else 0
    }

    private fun resolveClasspathEntries(
        direct: List<Path>,
        manifestFile: Path?,
    ): List<Path> {
        val fileEntries =
            if (manifestFile == null) {
                emptyList()
            } else {
                require(Files.isRegularFile(manifestFile)) { "classpath file does not exist: $manifestFile" }
                Files
                    .readAllLines(manifestFile, StandardCharsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { Path.of(it) }
            }

        val combined = mutableListOf<Path>()
        combined.addAll(fileEntries)
        combined.addAll(direct)

        require(combined.isNotEmpty()) {
            "runtime classpath must be provided via --classpath and or --classpath-file"
        }

        return combined
    }

    private companion object {
        const val TOOL_NAME: String = "enkidu-linkage-doctor"
        const val RESOLVER_MODE: String = "jvm-linkage-sim-v1"

        const val EXIT_INVALID_INPUT: Int = 3
    }
}

internal enum class OutputFormat {
    JSON,
    SARIF,
    HTML,
}

internal enum class FailOnPolicy {
    ANY,
    ERROR_ONLY,
    NONE,
}

internal object BuildInfo {
    val version: String = DoctorCommand::class.java.`package`?.implementationVersion ?: "dev"
}
