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
package io.enkidu.core.scan

import io.enkidu.core.util.MultiReleaseSupport
import io.enkidu.core.util.WarningCode
import io.enkidu.core.util.WarningCollector
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Scans one or more compiled targets (classes directories and/or jars) into [BytecodeReference]s.
 *
 * Ordering is deterministic:
 * - directory classfiles are walked and sorted by path
 * - jars are read in an MR-aware "effective" view, then sorted by logical class path
 * - targets are processed in the order they are provided
 */
class TargetReferenceScanner(
    internal val bytecodeScanner: BytecodeReferenceScanner = BytecodeReferenceScanner(),
) {
    data class Options(
        val runtimeJavaFeature: Int = Runtime.version().feature(),
        val continueOnError: Boolean = false,
        val warnings: WarningCollector? = null,
    )

    data class ClassBytesSource(
        val bytes: ByteArray,
        /** Absolute, normalized path of the directory/jar that contributed this class. */
        val sourcePath: Path,
        /** Jar entry name when [sourcePath] is a jar; null for directory classfiles. */
        val jarEntry: String? = null,
    )

    fun scanTargets(
        targets: List<Path>,
        options: Options = Options(),
    ): List<BytecodeReference> {
        val out = mutableListOf<BytecodeReference>()
        scanTargetsStreaming(targets, options) { out.add(it) }
        return out
    }

    /**
     * Stream references in deterministic order without accumulating the full list.
     */
    fun scanTargetsStreaming(
        targets: List<Path>,
        options: Options = Options(),
        sink: (BytecodeReference) -> Unit,
    ) {
        forEachTargetClassBytes(targets, options) { src ->
            val refs =
                try {
                    bytecodeScanner.scanClassBytes(src.bytes)
                } catch (e: Exception) {
                    options.warnings?.warn(
                        code = WarningCode.INVALID_BYTECODE,
                        message = "Failed to parse target class bytes: ${e.javaClass.simpleName}: ${e.message}",
                        path = src.sourcePath,
                        jarEntry = src.jarEntry,
                    )
                    if (!options.continueOnError) throw e
                    emptyList()
                }

            refs.forEach(sink)
        }
    }

    /**
     * Stream target class bytes in deterministic order without keeping them all in memory.
     *
     * This is the low-level hook used by Milestone O bounded-parallel scan paths.
     */
    fun forEachTargetClassBytes(
        targets: List<Path>,
        options: Options = Options(),
        sink: (ClassBytesSource) -> Unit,
    ) {
        for (raw in targets) {
            val t = raw.toAbsolutePath().normalize()
            require(Files.exists(t)) { "target does not exist: $t" }
            require(Files.isReadable(t)) { "target is not readable: $t" }

            when {
                t.isDirectory() -> forEachDirectoryClassBytes(t, options, sink)
                t.isRegularFile() && looksLikeJar(t) -> forEachJarClassBytes(t, options, sink)
                else -> throw IllegalArgumentException("unsupported target: $t")
            }
        }
    }

    private fun forEachDirectoryClassBytes(
        dir: Path,
        options: Options,
        sink: (ClassBytesSource) -> Unit,
    ) {
        val classFiles =
            Files.walk(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .sorted()
                    .toList()
            }

        for (file in classFiles) {
            val bytes =
                try {
                    Files.readAllBytes(file)
                } catch (e: Exception) {
                    options.warnings?.warn(
                        code = WarningCode.IO_ERROR,
                        message = "Failed to read target classfile: ${e.javaClass.simpleName}: ${e.message}",
                        path = file,
                        jarEntry = null,
                    )
                    if (!options.continueOnError) throw e
                    continue
                }

            sink(ClassBytesSource(bytes = bytes, sourcePath = file.toAbsolutePath().normalize(), jarEntry = null))
        }
    }

    private fun forEachJarClassBytes(
        jar: Path,
        options: Options,
        sink: (ClassBytesSource) -> Unit,
    ) {
        // MRJAR effective view: pick the highest eligible versioned entry per logical class path.
        val bestByLogical: MutableMap<String, Pair<Int, String>> = linkedMapOf()

        val jarAbs = jar.toAbsolutePath().normalize()

        try {
            ZipFile(jarAbs.toFile()).use { zip ->
                val isMr =
                    try {
                        MultiReleaseSupport.isMultiRelease(zip)
                    } catch (e: Exception) {
                        options.warnings?.warn(
                            code = WarningCode.MANIFEST_PARSE_FAILED,
                            message = "Failed to parse jar manifest (MRJAR detection): ${e.javaClass.simpleName}: ${e.message}",
                            path = jarAbs,
                            jarEntry = "META-INF/MANIFEST.MF",
                        )
                        false
                    }

                val entries =
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .sortedBy { it.name }
                        .toList()

                for (e in entries) {
                    val name = e.name

                    val (ver, logical) =
                        MultiReleaseSupport.parseVersionedName(name)?.let { (v, l) ->
                            if (!isMr || options.runtimeJavaFeature < 9) return@let null
                            if (v < 9 || v > options.runtimeJavaFeature) return@let null
                            v to l
                        } ?: (0 to name)

                    // module-info has no code and tends to confuse downstream expectations.
                    if (logical == "module-info.class") continue

                    val prev = bestByLogical[logical]
                    if (prev == null || ver > prev.first || (ver == prev.first && name < prev.second)) {
                        bestByLogical[logical] = ver to name
                    }
                }

                // Deterministic: sort by logical class path (stable across MR vs base choice).
                val effective =
                    bestByLogical
                        .toSortedMap()
                        .map { (_, pair) -> pair.second }

                for (entryName in effective) {
                    val je = zip.getEntry(entryName) ?: continue
                    val bytes =
                        try {
                            zip.getInputStream(je).use { it.readBytes() }
                        } catch (e: Exception) {
                            options.warnings?.warn(
                                code = WarningCode.IO_ERROR,
                                message = "Failed to read target jar entry $entryName: ${e.javaClass.simpleName}: ${e.message}",
                                path = jarAbs,
                                jarEntry = entryName,
                            )
                            if (!options.continueOnError) throw e
                            continue
                        }

                    sink(ClassBytesSource(bytes = bytes, sourcePath = jarAbs, jarEntry = entryName))
                }
            }
        } catch (e: Exception) {
            options.warnings?.warn(
                code = WarningCode.UNREADABLE_JAR,
                message = "Unreadable target jar (bad zip / I/O error): ${e.javaClass.simpleName}: ${e.message}",
                path = jarAbs,
                jarEntry = null,
            )
            if (!options.continueOnError) throw e
        }
    }

    private fun looksLikeJar(path: Path): Boolean {
        val name = path.fileName.toString().lowercase(Locale.ROOT)
        return name.endsWith(".jar") || name.endsWith(".zip")
    }
}
