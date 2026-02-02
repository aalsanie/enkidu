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
package io.enkidu.core.dup

import io.enkidu.artifacts.v1.DuplicateRiskLevel
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.model.JarIndex
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuplicateImpactAnalyzerTest {
    @Test
    fun `flags ABI-different duplicates as dangerous when referenced`() {
        val temp = Files.createTempDirectory("enkidu-dup-it")

        val jarA =
            buildJar(
                temp.resolve("a"),
                mapOf(
                    "demo/Dup.java" to
                        """
                        package demo;
                        public class Dup {
                          public int a() { return 1; }
                        }
                        """.trimIndent(),
                ),
            )

        val jarB =
            buildJar(
                temp.resolve("b"),
                mapOf(
                    "demo/Dup.java" to
                        """
                        package demo;
                        public class Dup {
                          public int b() { return 2; }
                        }
                        """.trimIndent(),
                ),
            )

        val snapshot = ClasspathSnapshot.fromPaths(listOf(jarA, jarB))
        val jarIndex = JarIndex.build(snapshot)

        val failures = DuplicateImpactAnalyzer(jarIndex).analyze(setOf("demo.Dup"))
        val dupFailure = failures.firstOrNull { it.type == FailureType.DUPLICATE_CLASS_SHADOWING }

        assertNotNull(dupFailure)
        assertTrue(dupFailure.evidence?.duplicate?.identicalBytecode == false)
        assertTrue(
            dupFailure.evidence
                ?.duplicate
                ?.abiDifferences
                ?.isNotEmpty() == true,
        )
        assertTrue(
            dupFailure.evidence?.duplicate?.riskLevel == DuplicateRiskLevel.HIGH ||
                dupFailure.evidence?.duplicate?.riskLevel == DuplicateRiskLevel.CRITICAL,
        )
    }

    @Test
    fun `treats identical duplicates as benign`() {
        val temp = Files.createTempDirectory("enkidu-dup-eq")

        val jarA =
            buildJar(
                temp.resolve("a"),
                mapOf(
                    "demo/Dup.java" to
                        """
                        package demo;
                        public class Dup {
                          public int a() { return 1; }
                        }
                        """.trimIndent(),
                ),
            )

        val jarB =
            temp.resolve("b/lib.jar").also {
                Files.createDirectories(it.parent)
                Files.copy(jarA, it)
            }

        val snapshot = ClasspathSnapshot.fromPaths(listOf(jarA, jarB))
        val jarIndex = JarIndex.build(snapshot)

        val failures = DuplicateImpactAnalyzer(jarIndex).analyze(setOf("demo.Dup"))
        val dupFailure = failures.firstOrNull { it.type == FailureType.DUPLICATE_CLASS_SHADOWING }

        assertNotNull(dupFailure)
        assertEquals(dupFailure.evidence?.duplicate?.identicalBytecode, true)
        assertEquals(dupFailure.evidence?.duplicate?.riskLevel, DuplicateRiskLevel.BENIGN)
        assertEquals(dupFailure.severity.name, "INFO")
    }

    private fun buildJar(
        dir: Path,
        sources: Map<String, String>,
        classpath: List<Path> = emptyList(),
    ): Path {
        val srcDir = dir.resolve("src").createDirectories()
        val outDir = dir.resolve("out").createDirectories()
        val jarPath = dir.resolve("lib.jar")

        for ((rel, code) in sources) {
            val file = srcDir.resolve(rel)
            Files.createDirectories(file.parent)
            Files.writeString(file, code, StandardCharsets.UTF_8)
        }

        compileJava(srcDir, outDir, classpath)
        createJar(outDir, jarPath)
        return jarPath
    }

    private fun compileJava(
        sourcesDir: Path,
        outputDir: Path,
        classpath: List<Path>,
    ) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK required to run tests")
        val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)

        val sourceFiles =
            Files.walk(sourcesDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                    .sorted()
                    .toList()
            }

        val compilationUnits = fileManager.getJavaFileObjectsFromFiles(sourceFiles.map { it.toFile() })

        val options = mutableListOf("-g", "-d", outputDir.toString())
        if (classpath.isNotEmpty()) {
            options.add("-classpath")
            options.add(classpath.joinToString(separator = File.pathSeparator) { it.toString() })
        }

        val ok = compiler.getTask(null, fileManager, null, options, null, compilationUnits).call()
        fileManager.close()
        check(ok) { "javac compilation failed" }
    }

    private fun createJar(
        classesDir: Path,
        jarPath: Path,
    ) {
        Files.createDirectories(jarPath.parent)
        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            val files =
                Files.walk(classesDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.sorted().toList()
                }
            for (file in files) {
                val entryName = classesDir.relativize(file).toString().replace(Char(92), '/')
                jos.putNextEntry(JarEntry(entryName))
                jos.write(Files.readAllBytes(file))
                jos.closeEntry()
            }
        }
    }
}
