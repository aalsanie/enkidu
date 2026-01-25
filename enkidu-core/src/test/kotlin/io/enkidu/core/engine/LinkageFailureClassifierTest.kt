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
package io.enkidu.core.engine

import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.resolve.JvmLinkageResolver
import io.enkidu.core.scan.BytecodeReferenceScanner
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkageFailureClassifierTest {
    @Test
    fun `descriptor mismatch is classified and evidence winner jar is attached`() {
        val compileLibV1Out = Files.createTempDirectory("enkidu-e-lib-v1")
        val compileLibV1Src = Files.createTempDirectory("enkidu-e-lib-v1-src")

        // Compile-time API has foo()V
        writeJava(
            compileLibV1Src.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public void foo() {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir = compileLibV1Src, outDir = compileLibV1Out)

        val callerOut = Files.createTempDirectory("enkidu-e-caller")
        val callerSrc = Files.createTempDirectory("enkidu-e-caller-src")
        writeJava(
            callerSrc.resolve("demo/Caller.java"),
            """
            package demo;

            public class Caller {
                public void run(Lib lib) {
                    lib.foo();
                }
            }
            """.trimIndent(),
        )
        compileJava(srcDir = callerSrc, outDir = callerOut, classpath = listOf(compileLibV1Out))

        // Runtime API has foo(int)V (signature changed)
        val runtimeOut = Files.createTempDirectory("enkidu-e-runtime")
        val runtimeSrc = Files.createTempDirectory("enkidu-e-runtime-src")
        writeJava(
            runtimeSrc.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public void foo(int x) {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir = runtimeSrc, outDir = runtimeOut)

        val snapshot = ClasspathSnapshot.fromPaths(listOf(runtimeOut))
        val refs = BytecodeReferenceScanner().scanClassBytes(callerOut.resolve("demo/Caller.class").readBytes())
        val callGraph = CallGraphIndex.fromReferences(refs)

        JvmLinkageResolver(snapshot).use { resolver ->
            val classifier = LinkageFailureClassifier(snapshot)
            val failures = refs.mapNotNull { classifier.classify(it, resolver, callGraph) }

            val mismatch = failures.firstOrNull { it.type == FailureType.DESCRIPTOR_MISMATCH }
            assertNotNull(mismatch, "expected a DESCRIPTOR_MISMATCH failure")
            assertEquals("demo/Lib", mismatch.symbol?.owner)
            assertEquals(SymbolKind.METHOD, mismatch.symbol?.kind)
            assertTrue(mismatch.message.contains("other descriptors"), "expected descriptor mismatch hint")
            assertEquals(runtimeOut.toString(), mismatch.evidence?.winnerJar)
        }
    }

    @Test
    fun `shadowed jars are attached and one-hop callers are included when available`() {
        val compileLibV1Out = Files.createTempDirectory("enkidu-e2-lib-v1")
        val compileLibV1Src = Files.createTempDirectory("enkidu-e2-lib-v1-src")

        // Compile-time API has foo()V
        writeJava(
            compileLibV1Src.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public void foo() {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir = compileLibV1Src, outDir = compileLibV1Out)

        val targetOut = Files.createTempDirectory("enkidu-e2-target")
        val targetSrc = Files.createTempDirectory("enkidu-e2-target-src")

        writeJava(
            targetSrc.resolve("demo/Caller.java"),
            """
            package demo;

            public class Caller {
                public void run(Lib lib) {
                    lib.foo();
                }
            }
            """.trimIndent(),
        )

        writeJava(
            targetSrc.resolve("demo/Root.java"),
            """
            package demo;

            public class Root {
                public void go(Caller c, Lib lib) {
                    c.run(lib);
                }
            }
            """.trimIndent(),
        )

        compileJava(srcDir = targetSrc, outDir = targetOut, classpath = listOf(compileLibV1Out))

        // Runtime classpath has TWO copies of demo.Lib.
        // Winner (first) drops foo() entirely; shadowed (second) still has foo().
        val runtimeWinnerOut = Files.createTempDirectory("enkidu-e2-runtime-winner")
        val runtimeWinnerSrc = Files.createTempDirectory("enkidu-e2-runtime-winner-src")
        writeJava(
            runtimeWinnerSrc.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public void bar() {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir = runtimeWinnerSrc, outDir = runtimeWinnerOut)

        val runtimeShadowOut = Files.createTempDirectory("enkidu-e2-runtime-shadow")
        val runtimeShadowSrc = Files.createTempDirectory("enkidu-e2-runtime-shadow-src")
        writeJava(
            runtimeShadowSrc.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public void foo() {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir = runtimeShadowSrc, outDir = runtimeShadowOut)

        val snapshot = ClasspathSnapshot.fromPaths(listOf(runtimeWinnerOut, runtimeShadowOut))

        val scanner = BytecodeReferenceScanner()
        val callerRefs = scanner.scanClassBytes(targetOut.resolve("demo/Caller.class").readBytes())
        val rootRefs = scanner.scanClassBytes(targetOut.resolve("demo/Root.class").readBytes())
        val allRefs = callerRefs + rootRefs
        val callGraph = CallGraphIndex.fromReferences(allRefs)

        JvmLinkageResolver(snapshot).use { resolver ->
            val classifier = LinkageFailureClassifier(snapshot)
            val failures = callerRefs.mapNotNull { classifier.classify(it, resolver, callGraph) }

            val miss = failures.firstOrNull { it.type == FailureType.MISSING_METHOD }
            assertNotNull(miss, "expected a MISSING_METHOD failure")
            assertEquals(runtimeWinnerOut.toString(), miss.evidence?.winnerJar)
            assertTrue(
                miss.evidence?.shadowedJars?.contains(runtimeShadowOut.toString()) == true,
                "expected shadowed jar path to be recorded",
            )
            assertTrue(
                miss.message.contains("One-hop callers:"),
                "expected one-hop callers summary when call graph provides callers",
            )
        }
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

        val sourceFiles =
            Files
                .walk(srcDir)
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .toList()

        check(sourceFiles.isNotEmpty()) { "no java sources found under $srcDir" }

        compiler.getStandardFileManager(null, null, null).use { fileManager ->
            val units = fileManager.getJavaFileObjectsFromFiles(sourceFiles.map { it.toFile() })
            val options = mutableListOf("-g", "-d", outDir.toString())
            if (classpath.isNotEmpty()) {
                val cp = classpath.joinToString(System.getProperty("path.separator")) { it.toString() }
                options += listOf("-classpath", cp)
            }
            val task = compiler.getTask(null, fileManager, null, options, null, units)
            val ok = task.call()
            check(ok) { "javac compilation failed" }
        }

        check(Files.walk(outDir).anyMatch { Files.isRegularFile(it) && it.toString().endsWith(".class") }) {
            "no class files produced under $outDir"
        }
    }
}
