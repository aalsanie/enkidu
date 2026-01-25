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
package io.enkidu.core.model

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * A normalized, validated representation of a runtime classpath.
 *
 * Order matters: earlier entries win during resolution.
 */
data class ClasspathSnapshot(
    val entries: List<ClasspathEntry>,
) {
    init {
        require(entries.isNotEmpty()) { "ClasspathSnapshot.entries must not be empty." }
    }

    companion object {
        /**
         * Build a snapshot from an ordered list of filesystem paths (directories and jars).
         *
         * @throws IllegalArgumentException if any path is missing, unreadable, or of an unsupported type.
         */
        fun fromPaths(paths: List<Path>): ClasspathSnapshot {
            require(paths.isNotEmpty()) { "paths must not be empty." }

            val entries =
                paths.mapIndexed { idx, raw ->
                    val p = raw.toAbsolutePath().normalize()
                    require(Files.exists(p)) { "Classpath entry does not exist at index $idx: $p" }
                    require(Files.isReadable(p)) { "Classpath entry is not readable at index $idx: $p" }

                    when {
                        Files.isDirectory(p) -> ClasspathEntry.Directory(p)
                        Files.isRegularFile(p) && looksLikeJar(p) -> ClasspathEntry.Jar(p)
                        else -> throw IllegalArgumentException("Unsupported classpath entry at index $idx: $p")
                    }
                }
            return ClasspathSnapshot(entries)
        }

        private fun looksLikeJar(path: Path): Boolean {
            val name = path.fileName.toString().lowercase(Locale.ROOT)
            return name.endsWith(".jar") || name.endsWith(".zip")
        }
    }
}
