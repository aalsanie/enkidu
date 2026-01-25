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
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClasspathSnapshotTest {
    @Test
    fun `fromPaths rejects empty input`() {
        assertFailsWith<IllegalArgumentException> {
            ClasspathSnapshot.fromPaths(emptyList())
        }
    }

    @Test
    fun `fromPaths rejects missing path`() {
        val missing = Path.of("definitely-missing-path-${System.nanoTime()}.jar")
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ClasspathSnapshot.fromPaths(listOf(missing))
            }
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `fromPaths classifies directories and jars`() {
        val dir = createTempDirectory("enkidu_cp_dir")
        val jar = Files.createTempFile("enkidu_cp_jar", ".jar")

        val snapshot = ClasspathSnapshot.fromPaths(listOf(dir, jar))

        assertEquals(2, snapshot.entries.size)
        assertEquals(ClasspathEntry.Directory(dir.toAbsolutePath().normalize()), snapshot.entries[0])
        assertEquals(ClasspathEntry.Jar(jar.toAbsolutePath().normalize()), snapshot.entries[1])
    }

    @Test
    fun `fromPaths rejects unsupported regular file`() {
        val txt = Files.createTempFile("enkidu_cp_file", ".txt")
        assertFailsWith<IllegalArgumentException> {
            ClasspathSnapshot.fromPaths(listOf(txt))
        }
    }
}
