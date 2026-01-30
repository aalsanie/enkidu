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

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Simple, production-safe on-disk cache.
 *
 * - Key is jar SHA-256 (hex).
 * - Value is a compact gzipped binary.
 * - Writes are atomic (tmp + move) when supported by the FS.
 */
class FileJarScanCache(
    private val cacheDir: Path,
) : JarScanCache {
    override fun get(sha256Hex: String): JarScanData? {
        val file = fileFor(sha256Hex)
        if (!Files.isRegularFile(file)) return null

        try {
            Files.newInputStream(file).use { raw ->
                GZIPInputStream(BufferedInputStream(raw)).use { gz ->
                    DataInputStream(gz).use { input ->
                        val magic = input.readInt()
                        if (magic != MAGIC) return null
                        val version = input.readInt()
                        if (version != VERSION) return null

                        val storedHash = input.readUTF()
                        if (storedHash != sha256Hex) return null

                        val classCount = input.readInt()
                        if (classCount < 0) return null
                        val classes = ArrayList<String>(classCount)
                        repeat(classCount) { classes.add(input.readUTF()) }

                        val svcCount = input.readInt()
                        if (svcCount < 0) return null
                        val services = linkedMapOf<String, List<String>>()
                        repeat(svcCount) {
                            val service = input.readUTF()
                            val provCount = input.readInt()
                            if (provCount < 0) return null
                            val providers = ArrayList<String>(provCount)
                            repeat(provCount) { providers.add(input.readUTF()) }
                            services[service] = providers
                        }

                        return JarScanData(
                            sha256Hex = sha256Hex,
                            classes = classes,
                            services = services,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Corrupt/partial cache: treat as miss.
            return null
        }
    }

    override fun put(data: JarScanData) {
        Files.createDirectories(cacheDir)
        val target = fileFor(data.sha256Hex)
        Files.createDirectories(target.parent)

        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        try {
            Files.newOutputStream(tmp).use { raw ->
                GZIPOutputStream(BufferedOutputStream(raw)).use { gz ->
                    DataOutputStream(gz).use { out ->
                        out.writeInt(MAGIC)
                        out.writeInt(VERSION)
                        out.writeUTF(data.sha256Hex)

                        out.writeInt(data.classes.size)
                        for (c in data.classes) out.writeUTF(c)

                        val services = data.services.toSortedMap()
                        out.writeInt(services.size)
                        for ((service, providers) in services) {
                            out.writeUTF(service)
                            out.writeInt(providers.size)
                            for (p in providers) out.writeUTF(p)
                        }
                    }
                }
            }

            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // Fallback when ATOMIC_MOVE not supported.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun fileFor(sha256Hex: String): Path {
        val normalized = sha256Hex.lowercase()
        val prefix = normalized.take(2).ifEmpty { "00" }
        return cacheDir
            .resolve("jar-scan-v1")
            .resolve(prefix)
            .resolve(normalized + ".bin.gz")
    }

    private companion object {
        const val MAGIC: Int = 0x454E4B44 // 'ENKD'
        const val VERSION: Int = 1
    }
}
