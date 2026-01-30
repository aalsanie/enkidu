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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JarScanCacheTest {
    @Test
    fun `file cache stores and reloads jar scan data keyed by sha256`() {
        val tmp = Files.createTempDirectory("enkidu-jar-scan-cache")
        val cacheDir = tmp.resolve("cache")
        val jar = tmp.resolve("fixture.jar")

        writeJar(
            jar,
            entries =
                mapOf(
                    "demo/A.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
                    "demo/B.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
                    "META-INF/services/demo.Service" to "demo.Impl\n#comment\n\n".toByteArray(StandardCharsets.UTF_8),
                ),
        )

        val cache = FileJarScanCache(cacheDir)
        val repo = JarScanRepository(cache)
        val first = repo.scanJar(jar)

        assertTrue(first.sha256Hex.isNotBlank())
        assertEquals(listOf("demo.A", "demo.B"), first.classes)
        assertEquals(listOf("demo.Impl"), first.services["demo.Service"])

        val cacheHit = cache.get(first.sha256Hex)
        assertNotNull(cacheHit)
        assertEquals(first, cacheHit)
    }

    private fun writeJar(
        jar: Path,
        entries: Map<String, ByteArray>,
    ) {
        jar.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }
}
