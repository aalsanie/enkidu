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
package io.enkidu.core.resolve

import io.enkidu.core.model.ClasspathEntry
import io.enkidu.core.model.ClasspathEntryKind
import io.enkidu.core.model.ClasspathSnapshot
import java.nio.file.Files
import java.util.jar.JarFile

/**
 * Loads class file bytes from the runtime classpath snapshot.
 *
 * Important: resolution must reflect *classpath order*. The first match wins.
 */
class ClasspathBytecodeLoader(
    private val snapshot: ClasspathSnapshot,
) : AutoCloseable {
    override fun close() {
        // No-op: we open jars per lookup to avoid holding file handles on Windows.
    }

    /**
     * Finds the first (winner) class definition for the given binary name, based on
     * runtime classpath order.
     */
    fun findClass(binaryName: String): ClassBytesWithLocation? = findClassBytes(binaryName)

    /**
     * Same as [findClass], kept for call-site clarity in lower-level code.
     */
    fun findClassBytes(binaryName: String): ClassBytesWithLocation? {
        val resourcePath = binaryName.replace('.', '/') + ".class"
        for (entry in snapshot.entries) {
            when (entry) {
                is ClasspathEntry.Directory -> {
                    val file = entry.path.resolve(resourcePath)
                    if (Files.isRegularFile(file)) {
                        return ClassBytesWithLocation(
                            bytes = Files.readAllBytes(file),
                            location =
                                ClassLocation(
                                    binaryName = binaryName,
                                    entryKind = ClasspathEntryKind.DIRECTORY,
                                    entryPath = entry.path,
                                    jarEntry = null,
                                ),
                        )
                    }
                }

                is ClasspathEntry.Jar -> {
                    JarFile(entry.path.toFile()).use { jar ->
                        val je = jar.getJarEntry(resourcePath)
                        if (je != null) {
                            val bytes = jar.getInputStream(je).use { it.readBytes() }
                            return ClassBytesWithLocation(
                                bytes = bytes,
                                location =
                                    ClassLocation(
                                        binaryName = binaryName,
                                        entryKind = ClasspathEntryKind.JAR,
                                        entryPath = entry.path,
                                        jarEntry = resourcePath,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        return null
    }
}

data class ClassBytesWithLocation(
    val bytes: ByteArray,
    val location: ClassLocation,
)
