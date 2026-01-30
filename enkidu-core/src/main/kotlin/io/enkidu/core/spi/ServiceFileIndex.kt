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
package io.enkidu.core.spi

import io.enkidu.core.model.ClasspathEntry
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.perf.JarScanRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.isDirectory

/**
 * Deterministic index of META-INF/services/x resources on a runtime classpath snapshot.
 *
 * IMPORTANT: this is not a build-time "fat-jar merger". It observes the *runtime classpath* as provided.
 **/

class ServiceFileIndex(
    private val snapshot: ClasspathSnapshot,
    private val jarScans: JarScanRepository? = null,
) {
    data class ServiceFileLocation(
        val entryIndex: Int,
        val entryPath: Path,
        val providers: List<String>,
    )

    /** Map: service binary name -> list of locations in classpath order. */
    fun index(): Map<String, List<ServiceFileLocation>> {
        val out = linkedMapOf<String, MutableList<ServiceFileLocation>>()

        snapshot.entries.forEachIndexed { idx, entry ->
            when (entry) {
                is ClasspathEntry.Directory -> indexDirectory(entry.path, idx, out)
                is ClasspathEntry.Jar -> indexJar(entry.path, idx, out)
            }
        }

        return out
            .toSortedMap()
            .mapValues { (_, v) -> v.toList() }
    }

    private fun indexDirectory(
        dir: Path,
        entryIndex: Int,
        out: MutableMap<String, MutableList<ServiceFileLocation>>,
    ) {
        val servicesDir = dir.resolve("META-INF").resolve("services")
        if (!servicesDir.isDirectory()) return

        Files.list(servicesDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { file ->
                    val service = file.fileName.toString()
                    val providers = parseProviders(Files.newInputStream(file))
                    out.getOrPut(service) { mutableListOf() }.add(ServiceFileLocation(entryIndex, dir, providers))
                }
        }
    }

    private fun indexJar(
        jarPath: Path,
        entryIndex: Int,
        out: MutableMap<String, MutableList<ServiceFileLocation>>,
    ) {
        if (!Files.isRegularFile(jarPath)) return

        val scans = jarScans
        if (scans != null) {
            val data = scans.scanJar(jarPath)
            for ((service, providers) in data.services.toSortedMap()) {
                out.getOrPut(service) { mutableListOf() }.add(ServiceFileLocation(entryIndex, jarPath, providers))
            }
            return
        }

        JarFile(jarPath.toFile()).use { jar ->
            val entries =
                jar
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") && it.name.length > "META-INF/services/".length }
                    .sortedBy { it.name }
                    .toList()

            for (e in entries) {
                val service = e.name.removePrefix("META-INF/services/")
                val providers = parseProviders(jar.getInputStream(e))
                out.getOrPut(service) { mutableListOf() }.add(ServiceFileLocation(entryIndex, jarPath, providers))
            }
        }
    }

    private fun parseProviders(input: java.io.InputStream): List<String> {
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { br ->
            val providers = mutableListOf<String>()
            br.lineSequence().forEach { line ->
                val raw = line.trim()
                if (raw.isEmpty()) return@forEach
                if (raw.startsWith("#")) return@forEach
                // Strip inline comments: "com.Foo # comment"
                val cleaned = raw.substringBefore('#').trim()
                if (cleaned.isNotEmpty()) providers.add(cleaned)
            }
            return providers.distinct().sorted()
        }
    }
}
