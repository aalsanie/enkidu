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
package io.enkidu.core.model

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarIndexTest {
    @Test
    fun `indexes directories and chooses winner by classpath order`() {
        val dir1 = createTempDirectory("enkidu_dir1")
        val dir2 = createTempDirectory("enkidu_dir2")

        writeClassFile(dir1, "com/example/A.class")
        writeClassFile(dir2, "com/example/A.class")
        writeClassFile(dir2, "com/example/B.class")

        val snapshot = ClasspathSnapshot.fromPaths(listOf(dir1, dir2))
        val index = JarIndex.build(snapshot)

        val aWinner = index.winnerOf("com.example.A")
        assertNotNull(aWinner)
        assertEquals(0, aWinner.entryIndex)
        assertEquals(dir1.toAbsolutePath().normalize(), aWinner.entryPath)

        val bWinner = index.winnerOf("com.example.B")
        assertNotNull(bWinner)
        assertEquals(1, bWinner.entryIndex)

        val dups = index.duplicates()
        assertTrue(dups.containsKey("com.example.A"))
        assertEquals(1, dups.getValue("com.example.A").shadowed.size)
    }

    @Test
    fun `jar entries are indexed and participate in winner selection`() {
        val dir = createTempDirectory("enkidu_dir")
        writeClassFile(dir, "com/example/A.class")

        val jar = Files.createTempFile("enkidu", ".jar")
        writeJar(jar, listOf("com/example/A.class", "com/example/C.class"))

        val snapshot = ClasspathSnapshot.fromPaths(listOf(jar, dir))
        val index = JarIndex.build(snapshot)

        // A exists in both jar and dir (order may vary depending on snapshot semantics).
        val aLocations = index.allLocationsOf("com.example.A")
        assertTrue(aLocations.size >= 2, "Expected A to exist in both jar and dir")

        val normalizedJar = jar.toAbsolutePath().normalize()
        val normalizedDir = dir.toAbsolutePath().normalize()
        val normalizedPaths = aLocations.map { it.entryPath.toAbsolutePath().normalize() }.toSet()

        assertTrue(normalizedPaths.contains(normalizedJar), "Expected A to be found in jar")
        assertTrue(normalizedPaths.contains(normalizedDir), "Expected A to be found in dir")

        // Winner must equal the first location in JarIndex's ordering.
        val aWinner = index.winnerOf("com.example.A")
        assertNotNull(aWinner)
        assertEquals(aLocations.first().entryIndex, aWinner.entryIndex)
        assertEquals(
            aLocations
                .first()
                .entryPath
                .toAbsolutePath()
                .normalize(),
            aWinner.entryPath.toAbsolutePath().normalize(),
        )

        // Jar classes are indexed.
        val cWinner = index.winnerOf("com.example.C")
        assertNotNull(cWinner)

        val bMissing = index.winnerOf("com.example.B")
        assertNull(bMissing)

        val dups = index.duplicates()
        assertTrue(dups.containsKey("com.example.A"))
        assertTrue(dups.getValue("com.example.A").shadowed.isNotEmpty())
    }

    @Test
    fun `duplicates map is stable-sorted by class name`() {
        val dir1 = createTempDirectory("enkidu_d1")
        val dir2 = createTempDirectory("enkidu_d2")
        writeClassFile(dir1, "z/Z.class")
        writeClassFile(dir2, "z/Z.class")
        writeClassFile(dir1, "a/A.class")
        writeClassFile(dir2, "a/A.class")

        val snapshot = ClasspathSnapshot.fromPaths(listOf(dir1, dir2))
        val index = JarIndex.build(snapshot)

        val keys = index.duplicates().keys.toList()
        assertEquals(listOf("a.A", "z.Z"), keys)
    }

    private fun writeClassFile(
        root: Path,
        relPath: String,
    ) {
        val file = root.resolve(relPath)
        file.parent?.createDirectories()
        Files.newOutputStream(file).use { out: OutputStream ->
            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        }
    }

    private fun writeJar(
        jarPath: Path,
        entries: List<String>,
    ) {
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zos ->
            for (name in entries) {
                val e = ZipEntry(name)
                zos.putNextEntry(e)
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
    }
}
