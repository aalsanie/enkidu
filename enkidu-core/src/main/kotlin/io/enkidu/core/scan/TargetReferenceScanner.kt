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
 * - jar entries are sorted by entry name
 * - targets are processed in the order they are provided
 */
class TargetReferenceScanner(
    internal val bytecodeScanner: BytecodeReferenceScanner = BytecodeReferenceScanner(),
) {
    fun scanTargets(targets: List<Path>): List<BytecodeReference> {
        val out = mutableListOf<BytecodeReference>()
        scanTargetsStreaming(targets) { out.add(it) }
        return out
    }

    /**
     * Stream references in deterministic order without accumulating the full list.
     */
    fun scanTargetsStreaming(
        targets: List<Path>,
        sink: (BytecodeReference) -> Unit,
    ) {
        forEachTargetClassBytes(targets) { bytes ->
            bytecodeScanner.scanClassBytes(bytes).forEach(sink)
        }
    }

    /**
     * Stream target class bytes in deterministic order without keeping them all in memory.
     *
     * This is the low-level hook used by Milestone O bounded-parallel scan paths.
     */
    fun forEachTargetClassBytes(
        targets: List<Path>,
        sink: (ByteArray) -> Unit,
    ) {
        for (raw in targets) {
            val t = raw.toAbsolutePath().normalize()
            require(Files.exists(t)) { "target does not exist: $t" }
            require(Files.isReadable(t)) { "target is not readable: $t" }

            when {
                t.isDirectory() -> forEachDirectoryClassBytes(t, sink)
                t.isRegularFile() && looksLikeJar(t) -> forEachJarClassBytes(t, sink)
                else -> throw IllegalArgumentException("unsupported target: $t")
            }
        }
    }

    private fun forEachDirectoryClassBytes(
        dir: Path,
        sink: (ByteArray) -> Unit,
    ) {
        val classFiles =
            Files.walk(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .sorted()
                    .toList()
            }

        for (file in classFiles) {
            sink(Files.readAllBytes(file))
        }
    }

    private fun forEachJarClassBytes(
        jar: Path,
        sink: (ByteArray) -> Unit,
    ) {
        ZipFile(jar.toFile()).use { zip ->
            val entries =
                zip
                    .entries()
                    .toList()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .sortedBy { it.name }
            for (e in entries) {
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                sink(bytes)
            }
        }
    }

    private fun looksLikeJar(path: Path): Boolean {
        val name = path.fileName.toString().lowercase(Locale.ROOT)
        return name.endsWith(".jar") || name.endsWith(".zip")
    }
}
