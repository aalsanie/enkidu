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

import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.zip.ZipInputStream
import kotlin.io.path.isRegularFile

/**
 * Indexes all .class files available on a classpath snapshot.
 *
 * This does not parse bytecode; it only discovers *where* each class file exists and
 * determines the "winner" by classpath order.
 */
class JarIndex private constructor(
    private val classToLocations: Map<String, List<ClassLocation>>,
) {
    fun classes(): Set<String> = classToLocations.keys

    /**
     * Returns the winning location for [binaryClassName], or null if it does not exist on the classpath.
     *
     * [binaryClassName] must be in JVM binary form, e.g. "com.example.Foo".
     */
    fun winnerOf(binaryClassName: String): ClassLocation? = classToLocations[binaryClassName]?.firstOrNull()

    /**
     * Returns all locations for [binaryClassName] in classpath order.
     */
    fun allLocationsOf(binaryClassName: String): List<ClassLocation> = classToLocations[binaryClassName].orEmpty()

    /**
     * Returns a stable, sorted map of duplicate classes (classes present in more than one classpath entry).
     */
    fun duplicates(): Map<String, DuplicateClass> {
        val out = mutableMapOf<String, DuplicateClass>()
        for ((clazz, locations) in classToLocations) {
            if (locations.size > 1) {
                out[clazz] = DuplicateClass(clazz, locations.first(), locations.drop(1))
            }
        }
        return out.toSortedMap()
    }

    data class ClassLocation(
        val className: String,
        val entryIndex: Int,
        val entryPath: Path,
    )

    data class DuplicateClass(
        val className: String,
        val winner: ClassLocation,
        val shadowed: List<ClassLocation>,
    )

    companion object {
        fun build(snapshot: ClasspathSnapshot): JarIndex {
            val map = mutableMapOf<String, MutableList<ClassLocation>>()

            snapshot.entries.forEachIndexed { idx, entry ->
                when (entry) {
                    is ClasspathEntry.Directory -> indexDirectory(entry.path, idx, map)
                    is ClasspathEntry.Jar -> indexJar(entry.path, idx, map)
                }
            }

            // Freeze to immutable with stable ordering of location lists (classpath order already).
            val frozen =
                map
                    .mapValues { (_, v) -> Collections.unmodifiableList(v.toList()) }
                    .toSortedMap()

            return JarIndex(Collections.unmodifiableMap(frozen))
        }

        private fun indexDirectory(
            dir: Path,
            entryIndex: Int,
            into: MutableMap<String, MutableList<ClassLocation>>,
        ) {
            // Deterministic walk order.
            val files =
                Files.walk(dir).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                        .sorted()
                        .toList()
                }

            for (file in files) {
                val rel = dir.relativize(file)
                val className =
                    rel
                        .toString()
                        .replace('\\', '/')
                        .removeSuffix(".class")
                        .replace('/', '.')
                addLocation(into, className, entryIndex, dir)
            }
        }

        private fun indexJar(
            jar: Path,
            entryIndex: Int,
            into: MutableMap<String, MutableList<ClassLocation>>,
        ) {
            if (!jar.isRegularFile()) return

            try {
                BufferedInputStream(Files.newInputStream(jar)).use { input ->
                    ZipInputStream(input).use { zis ->
                        while (true) {
                            val e = zis.nextEntry ?: break
                            if (!e.isDirectory && e.name.endsWith(".class")) {
                                val className =
                                    e.name
                                        .removeSuffix(".class")
                                        .replace('/', '.')
                                addLocation(into, className, entryIndex, jar)
                            }
                            zis.closeEntry()
                        }
                    }
                }
            } catch (_: IOException) {
                // Treat unreadable/non-zip jars as empty; higher layers may choose to fail hard later.
                return
            }
        }

        private fun addLocation(
            into: MutableMap<String, MutableList<ClassLocation>>,
            className: String,
            entryIndex: Int,
            entryPath: Path,
        ) {
            val list = into.getOrPut(className) { mutableListOf() }
            list.add(ClassLocation(className, entryIndex, entryPath))
        }
    }
}
