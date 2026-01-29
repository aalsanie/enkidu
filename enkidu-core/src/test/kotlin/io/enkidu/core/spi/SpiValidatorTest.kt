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

import io.enkidu.artifacts.v1.FailureType
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.resolve.JvmLinkageResolver
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertTrue

class SpiValidatorTest {
    @Test
    fun `detects missing providers type mismatch and warns on multiple descriptors`() {
        val temp = Files.createTempDirectory("enkidu-spi-it")

        val serviceJar =
            buildJar(
                temp.resolve("service"),
                mapOf(
                    "demo/Service.java" to
                        """
                        package demo;
                        public interface Service {}
                        """.trimIndent(),
                ),
                serviceFiles = emptyMap(),
            )

        val goodProviderJar =
            buildJar(
                temp.resolve("good"),
                mapOf(
                    "demo/impl/Good.java" to
                        """
                        package demo.impl;
                        import demo.Service;
                        public class Good implements Service {
                          public Good() {}
                        }
                        """.trimIndent(),
                ),
                serviceFiles = mapOf("demo.Service" to listOf("demo.impl.Good")),
                classpath = listOf(serviceJar),
            )

        val missingProviderJar =
            buildJar(
                temp.resolve("missing"),
                sources = emptyMap(),
                serviceFiles = mapOf("demo.Service" to listOf("demo.impl.Missing")),
                classpath = listOf(serviceJar),
            )

        val mismatchProviderJar =
            buildJar(
                temp.resolve("mismatch"),
                mapOf(
                    "demo/impl/Wrong.java" to
                        """
                        package demo.impl;
                        public class Wrong implements java.lang.Runnable {
                          public Wrong() {}
                          @Override public void run() {}
                        }
                        """.trimIndent(),
                ),
                serviceFiles = mapOf("demo.Service" to listOf("demo.impl.Wrong")),
                classpath = listOf(serviceJar),
            )

        val snapshot =
            ClasspathSnapshot.fromPaths(
                listOf(
                    serviceJar,
                    goodProviderJar,
                    missingProviderJar,
                    mismatchProviderJar,
                ),
            )

        val failures =
            JvmLinkageResolver(snapshot).use { resolver ->
                SpiValidator(snapshot).validate(resolver)
            }

        assertTrue(failures.any { it.type == FailureType.SPI_PROVIDER_BROKEN && it.message.contains("missing", ignoreCase = true) })
        assertTrue(failures.any { it.type == FailureType.SPI_PROVIDER_TYPE_MISMATCH })
        assertTrue(
            failures.any {
                it.type == FailureType.SPI_PROVIDER_BROKEN && it.severity.name == "WARN" &&
                    it.message.contains("Multiple META-INF/services", ignoreCase = true)
            },
        )
    }

    private fun buildJar(
        dir: Path,
        sources: Map<String, String>,
        serviceFiles: Map<String, List<String>>,
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

        if (sources.isNotEmpty()) {
            compileJava(srcDir, outDir, classpath)
        }

        for ((service, providers) in serviceFiles) {
            val f = outDir.resolve("META-INF/services/$service")
            Files.createDirectories(f.parent)
            Files.writeString(f, providers.joinToString(separator = "\n") + "\n", StandardCharsets.UTF_8)
        }

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
            options.add(classpath.joinToString(separator = System.getProperty("path.separator")) { it.toString() })
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
