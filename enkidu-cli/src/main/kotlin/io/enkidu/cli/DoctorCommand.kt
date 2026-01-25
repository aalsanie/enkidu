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
package io.enkidu.cli

import io.enkidu.artifacts.v1.EnkiduFingerprints
import io.enkidu.artifacts.v1.Fingerprint
import io.enkidu.artifacts.v1.Fingerprints
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.ReportSummary
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.ToolMetadata
import io.enkidu.core.engine.CallGraphIndex
import io.enkidu.core.engine.LinkageFailureClassifier
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.resolve.JvmLinkageResolver
import io.enkidu.core.scan.BytecodeReference
import io.enkidu.core.scan.BytecodeReferenceScanner
import io.enkidu.export.EnkiduReportWriters
import picocli.CommandLine
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

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

    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int =
        try {
            val resolvedClasspath = resolveClasspathEntries(classpathEntries.toList(), classpathFile)
            val snapshot = ClasspathSnapshot.fromPaths(resolvedClasspath)

            val references = scanTargets(targets.toList())
            val callGraph = CallGraphIndex.fromReferences(references)

            val failures =
                JvmLinkageResolver(snapshot).use { resolver ->
                    val classifier = LinkageFailureClassifier(snapshot)
                    references.mapNotNull { classifier.classify(it, resolver, callGraph) }
                }

            val report =
                LinkageReport(
                    tool =
                        ToolMetadata(
                            name = TOOL_NAME,
                            version = BuildInfo.version,
                            resolverMode = RESOLVER_MODE,
                        ),
                    fingerprints =
                        Fingerprints(
                            classpath = fingerprintOfPaths(resolvedClasspath),
                            targets = fingerprintOfPaths(targets.toList()),
                            report = null,
                        ),
                    summary = ReportSummary(failureCount = 0, failureCountByType = emptyMap()),
                    failures = failures,
                ).canonical()

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

    private fun scanTargets(targets: List<Path>): List<BytecodeReference> {
        val scanner = BytecodeReferenceScanner()
        val out = mutableListOf<BytecodeReference>()

        for (target in targets) {
            val t = target.toAbsolutePath().normalize()
            require(Files.exists(t)) { "target does not exist: $t" }
            require(Files.isReadable(t)) { "target is not readable: $t" }

            when {
                t.isDirectory() -> out.addAll(scanDirectory(scanner, t))
                t.isRegularFile() && looksLikeJar(t) -> out.addAll(scanJar(scanner, t))
                else -> throw IllegalArgumentException("unsupported target: $t")
            }
        }

        return out
    }

    private fun scanDirectory(
        scanner: BytecodeReferenceScanner,
        dir: Path,
    ): List<BytecodeReference> {
        val classFiles =
            Files.walk(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .sorted()
                    .toList()
            }

        val out = mutableListOf<BytecodeReference>()
        for (file in classFiles) {
            out.addAll(scanner.scanClassBytes(Files.readAllBytes(file)))
        }
        return out
    }

    private fun scanJar(
        scanner: BytecodeReferenceScanner,
        jar: Path,
    ): List<BytecodeReference> {
        ZipFile(jar.toFile()).use { zip ->
            val entries =
                zip
                    .entries()
                    .toList()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .sortedBy { it.name }

            val out = mutableListOf<BytecodeReference>()
            for (e in entries) {
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                out.addAll(scanner.scanClassBytes(bytes))
            }
            return out
        }
    }

    private fun looksLikeJar(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.endsWith(".jar") || name.endsWith(".zip")
    }

    private fun fingerprintOfPaths(paths: List<Path>): Fingerprint {
        val normalized =
            paths
                .map {
                    it
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                        .replace(Char(92), '/')
                }.joinToString(separator = "\n")
        return Fingerprint(algorithm = "SHA-256", value = EnkiduFingerprints.sha256HexUtf8(normalized))
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
