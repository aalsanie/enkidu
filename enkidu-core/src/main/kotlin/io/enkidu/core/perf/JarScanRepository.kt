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

import io.enkidu.core.util.MultiReleaseSupport
import io.enkidu.core.util.WarningCode
import io.enkidu.core.util.WarningCollector
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.Runtime
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
    private val runtimeJavaFeature: Int = Runtime.version().feature(),
    private val warnings: WarningCollector? = null,
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
        val bestClassByLogical = mutableMapOf<String, Pair<Int, String>>()
        val bestServiceByName = mutableMapOf<String, Pair<Int, List<String>>>()

        try {
            ZipFile(jarPath.toFile()).use { zip ->
                val isMr =
                    try {
                        MultiReleaseSupport.isMultiRelease(zip)
                    } catch (e: Exception) {
                        // Treat as non-MR but surface a warning for supportability.
                        warnings?.warn(
                            code = WarningCode.MANIFEST_PARSE_FAILED,
                            message = "Failed to parse jar manifest (MRJAR detection): ${e.javaClass.simpleName}: ${e.message}",
                            path = jarPath,
                            jarEntry = "META-INF/MANIFEST.MF",
                        )
                        false
                    }

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

                    // ---- Classes (MR-aware, effective view) ----
                    if (name.endsWith(".class")) {
                        val (ver, logical) =
                            MultiReleaseSupport.parseVersionedName(name)?.let { (v, l) ->
                                if (!isMr || runtimeJavaFeature < 9) return@let null
                                if (v < 9 || v > runtimeJavaFeature) return@let null
                                v to l
                            } ?: (0 to name)

                        // Do not index module-info as a normal type.
                        if (logical == "module-info.class") continue

                        // Preserve previous behavior: ignore META-INF/* *logical* classes.
                        if (logical.startsWith("META-INF/")) continue

                        val prev = bestClassByLogical[logical]
                        if (prev == null || ver > prev.first || (ver == prev.first && name < prev.second)) {
                            bestClassByLogical[logical] = ver to name
                        }
                        continue
                    }

                    // ---- Services (MR-aware, effective view) ----
                    val parsed = MultiReleaseSupport.parseVersionedName(name)
                    val logicalCandidate = parsed?.second ?: name

                    if (logicalCandidate.startsWith(SERVICES_PREFIX) && logicalCandidate.length > SERVICES_PREFIX.length) {
                        val (ver, logical) =
                            parsed?.let { (v, l) ->
                                if (!isMr || runtimeJavaFeature < 9) return@let null
                                if (v < 9 || v > runtimeJavaFeature) return@let null
                                v to l
                            } ?: (0 to name)

                        // `logical` is the effective base path (META-INF/services/...)
                        val service = logical.removePrefix(SERVICES_PREFIX)

                        val providers =
                            try {
                                parseProviders(zip.getInputStream(e))
                            } catch (ex: Exception) {
                                warnings?.warn(
                                    code = WarningCode.IO_ERROR,
                                    message = "Failed to read META-INF/services/$service: ${ex.javaClass.simpleName}: ${ex.message}",
                                    path = jarPath,
                                    jarEntry = name,
                                )
                                emptyList()
                            }

                        val prev = bestServiceByName[service]
                        if (prev == null ||
                            ver > prev.first ||
                            (ver == prev.first && providers.joinToString("\n") < prev.second.joinToString("\n"))
                        ) {
                            bestServiceByName[service] = ver to providers
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Unreadable jar: treat as empty. Deterministic & safe.
            warnings?.warn(
                code = WarningCode.UNREADABLE_JAR,
                message = "Unreadable jar (bad zip / I/O error). Treating as empty for scanning/indexing.",
                path = jarPath,
                jarEntry = null,
            )
            return JarScanData(sha256Hex = sha256, classes = emptyList(), services = emptyMap())
        }

        val classes =
            bestClassByLogical.keys
                .asSequence()
                .sorted()
                .map { it.removeSuffix(".class").replace('/', '.') }
                .distinct()
                .toList()

        val frozenServices =
            bestServiceByName
                .toSortedMap()
                .mapValues { (_, v) -> v.second }
        return JarScanData(
            sha256Hex = sha256,
            classes = classes,
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
