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
package io.enkidu.core.engine

import io.enkidu.artifacts.v1.ToolMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

class CompareModeStabilityTest {
    @Test
    fun `compare mode treats the same failure type and callsite as the same even when messages differ`() {
        val libCompileSrc = Files.createTempDirectory("enkidu-compare-lib-ct-src")
        val libCompileOut = Files.createTempDirectory("enkidu-compare-lib-ct-out")
        writeJava(
            libCompileSrc.resolve("demo/lib/Lib.java"),
            """
            package demo.lib;
            public class Lib {
              public void foo() {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir = libCompileSrc, outDir = libCompileOut)

        val appSrc = Files.createTempDirectory("enkidu-compare-app-src")
        val appOut = Files.createTempDirectory("enkidu-compare-app-out")
        writeJava(
            appSrc.resolve("demo/app/App.java"),
            """
            package demo.app;
            import demo.lib.Lib;
            public class App {
              public static void run(Lib lib) { lib.foo(); }
            }
            """.trimIndent(),
        )
        compileJava(srcDir = appSrc, outDir = appOut, classpath = listOf(libCompileOut))

        // Runtime A: Lib.foo(int)
        val runtimeASrc = Files.createTempDirectory("enkidu-compare-lib-a-src")
        val runtimeAOut = Files.createTempDirectory("enkidu-compare-lib-a-out")
        writeJava(
            runtimeASrc.resolve("demo/lib/Lib.java"),
            """
            package demo.lib;
            public class Lib {
              public void foo(int x) {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir = runtimeASrc, outDir = runtimeAOut)

        // Runtime B: Lib.foo(long)
        val runtimeBSrc = Files.createTempDirectory("enkidu-compare-lib-b-src")
        val runtimeBOut = Files.createTempDirectory("enkidu-compare-lib-b-out")
        writeJava(
            runtimeBSrc.resolve("demo/lib/Lib.java"),
            """
            package demo.lib;
            public class Lib {
              public void foo(long x) {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir = runtimeBSrc, outDir = runtimeBOut)

        val engine = LinkageDoctorEngine()
        val report =
            engine.compare(
                LinkageDoctorCompareRequest(
                    tool = ToolMetadata(name = "enkidu", version = "test", resolverMode = "jvm-linkage-sim-v1"),
                    targets = listOf(appOut),
                    classpathA = listOf(runtimeAOut),
                    classpathB = listOf(runtimeBOut),
                    labelA = "A",
                    labelB = "B",
                ),
            )

        assertEquals(0, report.summary.regressions)
        assertEquals(0, report.summary.fixed)
        assertEquals(0, report.regressions.size)
        assertEquals(0, report.fixed.size)
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
