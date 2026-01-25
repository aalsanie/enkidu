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
    private val classScanner: BytecodeReferenceScanner = BytecodeReferenceScanner(),
) {
    fun scanTargets(targets: List<Path>): List<BytecodeReference> {
        val out = mutableListOf<BytecodeReference>()

        for (raw in targets) {
            val t = raw.toAbsolutePath().normalize()
            require(Files.exists(t)) { "target does not exist: $t" }
            require(Files.isReadable(t)) { "target is not readable: $t" }

            when {
                t.isDirectory() -> out.addAll(scanDirectory(t))
                t.isRegularFile() && looksLikeJar(t) -> out.addAll(scanJar(t))
                else -> throw IllegalArgumentException("unsupported target: $t")
            }
        }

        return out
    }

    private fun scanDirectory(dir: Path): List<BytecodeReference> {
        val classFiles =
            Files.walk(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .sorted()
                    .toList()
            }

        val out = mutableListOf<BytecodeReference>()
        for (file in classFiles) {
            out.addAll(classScanner.scanClassBytes(Files.readAllBytes(file)))
        }
        return out
    }

    private fun scanJar(jar: Path): List<BytecodeReference> {
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
                out.addAll(classScanner.scanClassBytes(bytes))
            }
            return out
        }
    }

    private fun looksLikeJar(path: Path): Boolean {
        val name = path.fileName.toString().lowercase(Locale.ROOT)
        return name.endsWith(".jar") || name.endsWith(".zip")
    }
}
