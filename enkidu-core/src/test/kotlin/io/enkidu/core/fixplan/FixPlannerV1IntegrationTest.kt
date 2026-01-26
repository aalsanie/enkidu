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
package io.enkidu.core.fixplan

import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.FixKind
import io.enkidu.core.engine.CallGraphIndex
import io.enkidu.core.engine.LinkageFailureClassifier
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.resolve.JvmLinkageResolver
import io.enkidu.core.scan.BytecodeReferenceScanner
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FixPlannerV1IntegrationTest {
    @Test
    fun `exclude-winner suggestion fixes a missing method caused by classpath shadowing`() {
        val compileLibV1Out = Files.createTempDirectory("enkidu-f-lib-v1")
        val compileLibV1Src = Files.createTempDirectory("enkidu-f-lib-v1-src")

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

        val targetOut = Files.createTempDirectory("enkidu-f-target")
        val targetSrc = Files.createTempDirectory("enkidu-f-target-src")
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
        compileJava(srcDir = targetSrc, outDir = targetOut, classpath = listOf(compileLibV1Out))

        // Runtime classpath has TWO copies of demo.Lib.
        // Winner (first) drops foo() entirely; shadowed (second) still has foo().
        val runtimeWinnerOut = Files.createTempDirectory("enkidu-f-runtime-winner")
        val runtimeWinnerSrc = Files.createTempDirectory("enkidu-f-runtime-winner-src")
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

        val runtimeShadowOut = Files.createTempDirectory("enkidu-f-runtime-shadow")
        val runtimeShadowSrc = Files.createTempDirectory("enkidu-f-runtime-shadow-src")
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

        val scanner = BytecodeReferenceScanner()
        val callerRefs = scanner.scanClassBytes(targetOut.resolve("demo/Caller.class").readBytes())
        val callGraph = CallGraphIndex.fromReferences(callerRefs)

        val snapshotBroken = ClasspathSnapshot.fromPaths(listOf(runtimeWinnerOut, runtimeShadowOut))

        val miss =
            JvmLinkageResolver(snapshotBroken).use { resolver ->
                val classifier = LinkageFailureClassifier(snapshotBroken)
                callerRefs
                    .mapNotNull { classifier.classify(it, resolver, callGraph) }
                    .firstOrNull { it.type == FailureType.MISSING_METHOD }
            }
        assertNotNull(miss, "expected a MISSING_METHOD failure")

        val excludeWinner = miss.fixPlan.firstOrNull { it.kind == FixKind.EXCLUDE_JAR && it.value == runtimeWinnerOut.toString() }
        assertNotNull(excludeWinner, "expected EXCLUDE_JAR fix suggestion for the winner path")

        // Apply the suggested fix at the classpath level: exclude the winner. Shadowed becomes the new winner.
        val snapshotFixed = ClasspathSnapshot.fromPaths(listOf(runtimeShadowOut))
        val failuresAfterFix =
            JvmLinkageResolver(snapshotFixed).use { resolver ->
                val classifier = LinkageFailureClassifier(snapshotFixed)
                callerRefs.mapNotNull { classifier.classify(it, resolver, callGraph) }
            }

        assertTrue(
            failuresAfterFix.none { it.type == FailureType.MISSING_METHOD },
            "expected the classpath-level fix to resolve the missing method",
        )
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
