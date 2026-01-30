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
package io.enkidu.core.perf

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Run-scoped jar scan repository.
 *
 * - Avoids rescanning the same jar multiple times within a single engine run.
 * - Optionally persists results through a [JarScanCache] keyed by jar SHA-256.
 */
class JarScanRepository(
    private val cache: JarScanCache? = null,
) {
    private val byJarPath: ConcurrentHashMap<Path, JarScanData> = ConcurrentHashMap()

    fun scanJar(jarPath: Path): JarScanData {
        return byJarPath.computeIfAbsent(jarPath.toAbsolutePath().normalize()) { normalized ->
            if (!Files.isRegularFile(normalized)) {
                return@computeIfAbsent JarScanData(sha256Hex = "", classes = emptyList(), services = emptyMap())
            }

            if (cache == null) {
                return@computeIfAbsent scanJarNoCache(normalized, sha256 = "")
            }

            val sha256 = Sha256.ofFileHex(normalized)
            val hit = cache.get(sha256)
            if (hit != null) return@computeIfAbsent hit

            val scanned = scanJarNoCache(normalized, sha256)
            cache.put(scanned)
            scanned
        }
    }

    private fun scanJarNoCache(
        jarPath: Path,
        sha256: String,
    ): JarScanData {
        val classes = mutableListOf<String>()
        val services = linkedMapOf<String, MutableSet<String>>()

        try {
            ZipFile(jarPath.toFile()).use { zip ->
                // Deterministic: sort by entry name.
                val entries =
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory }
                        .sortedBy { it.name }
                        .toList()

                for (e in entries) {
                    val name = e.name
                    if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                        val binary = name.removeSuffix(".class").replace('/', '.')
                        classes.add(binary)
                        continue
                    }

                    if (name.startsWith(SERVICES_PREFIX) && name.length > SERVICES_PREFIX.length) {
                        val service = name.removePrefix(SERVICES_PREFIX)
                        val providers = parseProviders(zip.getInputStream(e))
                        if (providers.isNotEmpty()) {
                            val set = services.getOrPut(service) { linkedSetOf() }
                            set.addAll(providers)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Unreadable jar: treat as empty. Deterministic & safe.
            return JarScanData(sha256Hex = sha256, classes = emptyList(), services = emptyMap())
        }

        val frozenServices = services.toSortedMap().mapValues { (_, v) -> v.toList().sorted() }
        return JarScanData(
            sha256Hex = sha256,
            classes = classes.distinct().sorted(),
            services = frozenServices,
        )
    }

    private fun parseProviders(input: java.io.InputStream): List<String> {
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { br ->
            val providers = mutableListOf<String>()
            br.lineSequence().forEach { line ->
                val raw = line.trim()
                if (raw.isEmpty()) return@forEach
                if (raw.startsWith("#")) return@forEach
                val cleaned = raw.substringBefore('#').trim()
                if (cleaned.isNotEmpty()) providers.add(cleaned)
            }
            return providers
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .toList()
        }
    }

    private companion object {
        const val SERVICES_PREFIX: String = "META-INF/services/"
    }
}
