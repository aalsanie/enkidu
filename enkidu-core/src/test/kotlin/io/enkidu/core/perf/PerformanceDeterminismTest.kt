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

import io.enkidu.artifacts.v1.ToolMetadata
import io.enkidu.core.engine.LinkageDoctorEngine
import io.enkidu.core.engine.LinkageDoctorRequest
import io.enkidu.core.engine.PerformanceOptions
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceDeterminismTest {
    @Test
    fun `engine output is deterministic across target scan parallelism settings`() {
        val libCtSrc = Files.createTempDirectory("enkidu-perf-lib-ct-src")
        val libCtOut = Files.createTempDirectory("enkidu-perf-lib-ct-out")
        writeJava(
            libCtSrc.resolve("demo/Lib.java"),
            """
            package demo;
            public class Lib { public void foo() {} }
            """.trimIndent(),
        )
        compileJava(srcDir = libCtSrc, outDir = libCtOut)

        val appSrc = Files.createTempDirectory("enkidu-perf-app-src")
        val appOut = Files.createTempDirectory("enkidu-perf-app-out")
        writeJava(
            appSrc.resolve("demo/App.java"),
            """
            package demo;
            public class App {
              public static void run(Lib lib) { lib.foo(); }
            }
            """.trimIndent(),
        )
        compileJava(srcDir = appSrc, outDir = appOut, classpath = listOf(libCtOut))

        val libRtSrc = Files.createTempDirectory("enkidu-perf-lib-rt-src")
        val libRtOut = Files.createTempDirectory("enkidu-perf-lib-rt-out")
        writeJava(
            libRtSrc.resolve("demo/Lib.java"),
            """
            package demo;
            public class Lib { public void foo(int x) {} }
            """.trimIndent(),
        )
        compileJava(srcDir = libRtSrc, outDir = libRtOut)

        val engine = LinkageDoctorEngine()
        val baseTool = ToolMetadata(name = "enkidu", version = "test", resolverMode = "jvm-linkage-sim-v1")

        val reportSeq =
            engine.run(
                LinkageDoctorRequest(
                    tool = baseTool,
                    targets = listOf(appOut),
                    runtimeClasspath = listOf(libRtOut),
                    performance = PerformanceOptions(targetScanParallelism = 1, jarScanParallelism = 1),
                ),
            )

        val reportPar =
            engine.run(
                LinkageDoctorRequest(
                    tool = baseTool,
                    targets = listOf(appOut),
                    runtimeClasspath = listOf(libRtOut),
                    performance = PerformanceOptions(targetScanParallelism = 4, jarScanParallelism = 4, maxInFlightTargetClasses = 8),
                ),
            )

        assertEquals(reportSeq, reportPar)
    }

    private fun writeJava(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun compileJava(
        srcDir: Path,
        outDir: Path,
        classpath: List<Path> = emptyList(),
    ) {
        val compiler = ToolProvider.getSystemJavaCompiler()
        requireNotNull(compiler) { "JDK compiler is required to run tests (ToolProvider.getSystemJavaCompiler() == null)" }

        val sources =
            Files
                .walk(srcDir)
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .toList()

        check(sources.isNotEmpty()) { "no java sources found under $srcDir" }

        compiler.getStandardFileManager(null, null, null).use { fm ->
            val units = fm.getJavaFileObjectsFromFiles(sources.map { it.toFile() })
            val options = mutableListOf("-g", "-d", outDir.toString())
            if (classpath.isNotEmpty()) {
                val cp = classpath.joinToString(System.getProperty("path.separator")) { it.toString() }
                options += listOf("-classpath", cp)
            }
            val task = compiler.getTask(null, fm, null, options, null, units)
            check(task.call()) { "javac compilation failed" }
        }

        check(Files.walk(outDir).anyMatch { Files.isRegularFile(it) && it.toString().endsWith(".class") }) {
            "no class files produced under $outDir"
        }
    }
}
