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
package io.enkidu.cli

import io.enkidu.artifacts.v1.EnkiduJson
import io.enkidu.artifacts.v1.LinkageReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class EnkiduDoctorCliIntegrationTest {
    @Test
    fun `doctor returns failures and exit code 2 when policy is any`() {
        val temp = Files.createTempDirectory("enkidu-cli-it")
        val libJar = buildLibJar(temp.resolve("lib"))
        val appClasses = buildAppClasses(temp.resolve("app"), libJar)

        val (exitCode, stdout, stderr) =
            runCli(
                "doctor",
                "--targets",
                appClasses.toString(),
                "--classpath",
                appClasses.toString(),
                "--fail-on",
                "any",
                "--format",
                "json",
            )

        assertEquals(2, exitCode, "Expected failures under policy any")
        assertTrue(stderr.isBlank(), "Unexpected stderr: $stderr")

        val report = parseReport(stdout)
        assertTrue(report.failures.isNotEmpty(), "Expected at least one failure")
    }

    @Test
    fun `doctor returns exit code 0 when policy is none even if failures exist`() {
        val temp = Files.createTempDirectory("enkidu-cli-it")
        val libJar = buildLibJar(temp.resolve("lib"))
        val appClasses = buildAppClasses(temp.resolve("app"), libJar)

        val (exitCode, stdout, _) =
            runCli(
                "doctor",
                "--targets",
                appClasses.toString(),
                "--classpath",
                appClasses.toString(),
                "--fail-on",
                "none",
                "--format",
                "json",
            )

        assertEquals(0, exitCode)
        val report = parseReport(stdout)
        assertTrue(report.failures.isNotEmpty(), "Expected failures but policy none")
    }

    @Test
    fun `doctor returns usage exit code when classpath is missing`() {
        val temp = Files.createTempDirectory("enkidu-cli-it")
        val appClasses = temp.resolve("app").createDirectories()

        val (exitCode, _, _) =
            runCli(
                "doctor",
                "--targets",
                appClasses.toString(),
                "--format",
                "json",
            )

        assertEquals(3, exitCode)
    }

    private fun parseReport(json: String): LinkageReport = EnkiduJson.mapper.readValue(json, LinkageReport::class.java)

    private fun runCli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val cl = EnkiduCli.commandLine()
        cl.out = PrintWriter(out, true, StandardCharsets.UTF_8)
        cl.err = PrintWriter(err, true, StandardCharsets.UTF_8)

        val exitCode = cl.execute(*args)
        return Triple(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))
    }

    private fun buildLibJar(dir: Path): Path {
        val srcDir = dir.resolve("src").createDirectories()
        val outDir = dir.resolve("out").createDirectories()
        val jarPath = dir.resolve("lib.jar")

        val pkgDir = srcDir.resolve("demo").createDirectories()
        pkgDir.resolve("Lib.java").writeText(
            """
            package demo;

            public class Lib {
              public static void doThing(String s) {
                System.out.println(s);
              }
            }
            """.trimIndent(),
        )

        compileJava(srcDir, outDir, classpath = emptyList())
        createJar(outDir, jarPath)
        return jarPath
    }

    private fun buildAppClasses(
        dir: Path,
        libJar: Path,
    ): Path {
        val srcDir = dir.resolve("src").createDirectories()
        val outDir = dir.resolve("out").createDirectories()

        val pkgDir = srcDir.resolve("demo").createDirectories()
        pkgDir.resolve("App.java").writeText(
            """
            package demo;

            public class App {
              public static void main(String[] args) {
                Lib.doThing("hello");
              }
            }
            """.trimIndent(),
        )

        compileJava(srcDir, outDir, classpath = listOf(libJar))
        return outDir
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

        val options = mutableListOf("-d", outputDir.toString())
        if (classpath.isNotEmpty()) {
            options.add("-classpath")
            options.add(classpath.joinToString(separator = System.getProperty("path.separator")) { it.toString() })
        }

        val task = compiler.getTask(null, fileManager, null, options, null, compilationUnits)
        val ok = task.call()
        fileManager.close()

        assertTrue(ok, "Java compilation failed")
    }

    private fun createJar(
        classesDir: Path,
        jarPath: Path,
    ) {
        Files.createDirectories(jarPath.toAbsolutePath().normalize().parent)
        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            val files =
                Files.walk(classesDir).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .sorted()
                        .toList()
                }

            for (file in files) {
                val entryName = classesDir.relativize(file).toString().replace(Char(92), '/')
                val entry = JarEntry(entryName)
                jos.putNextEntry(entry)
                jos.write(Files.readAllBytes(file))
                jos.closeEntry()
            }
        }
    }
}
