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
package io.enkidu.core.resolve

import io.enkidu.artifacts.v1.SymbolId
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.core.model.ClasspathSnapshot
import org.objectweb.asm.Opcodes
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmLinkageResolverTest {
    @Test
    fun `missing method is detected when runtime class drops a referenced method`() {
        val outV2 = Files.createTempDirectory("enkidu-resolve-v2")
        val srcV2 = Files.createTempDirectory("enkidu-resolve-src-v2")

        // Runtime version without foo()
        writeJava(
            srcV2.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public String bar() { return "ok"; }
            }
            """.trimIndent(),
        )
        compileJava(srcV2, outV2)

        val snapshot = ClasspathSnapshot.fromPaths(listOf(outV2))

        JvmLinkageResolver(snapshot).use { resolver ->
            val outcome =
                resolver.resolveMethod(
                    symbol = SymbolId(owner = "demo.Lib", kind = SymbolKind.METHOD, name = "foo", descriptor = "()Ljava/lang/String;"),
                    opcode = Opcodes.INVOKEVIRTUAL,
                    isInterfaceInvocation = false,
                )

            assertTrue(outcome is MethodResolutionOutcome.MissingMethod)
            assertEquals("demo.Lib", (outcome as MethodResolutionOutcome.MissingMethod).symbolOwner)
        }
    }

    @Test
    fun `class vs interface mismatch is detected for invokeinterface`() {
        val outDir = Files.createTempDirectory("enkidu-resolve-icce")
        val srcDir = Files.createTempDirectory("enkidu-resolve-icce-src")

        // Runtime provides a class, but callsite expects an interface (invokeinterface)
        writeJava(
            srcDir.resolve("demo/Api.java"),
            """
            package demo;

            public class Api {
                public void ping() {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir, outDir)

        val snapshot = ClasspathSnapshot.fromPaths(listOf(outDir))

        JvmLinkageResolver(snapshot).use { resolver ->
            val outcome =
                resolver.resolveMethod(
                    symbol = SymbolId(owner = "demo.Api", kind = SymbolKind.METHOD, name = "ping", descriptor = "()V"),
                    opcode = Opcodes.INVOKEINTERFACE,
                    isInterfaceInvocation = true,
                )

            assertTrue(outcome is MethodResolutionOutcome.IncompatibleClassChange)
        }
    }

    @Test
    fun `descriptor mismatch is surfaced as missing method with same-name descriptors`() {
        val outDir = Files.createTempDirectory("enkidu-resolve-desc")
        val srcDir = Files.createTempDirectory("enkidu-resolve-desc-src")

        writeJava(
            srcDir.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public void foo(int x) {}
            }
            """.trimIndent(),
        )
        compileJava(srcDir, outDir)

        val snapshot = ClasspathSnapshot.fromPaths(listOf(outDir))

        JvmLinkageResolver(snapshot).use { resolver ->
            val outcome =
                resolver.resolveMethod(
                    symbol = SymbolId(owner = "demo.Lib", kind = SymbolKind.METHOD, name = "foo", descriptor = "()V"),
                    opcode = Opcodes.INVOKEVIRTUAL,
                    isInterfaceInvocation = false,
                )

            assertTrue(outcome is MethodResolutionOutcome.MissingMethod)
            val miss = outcome as MethodResolutionOutcome.MissingMethod
            assertTrue(miss.sameNameOtherDescriptors.contains("(I)V"))
        }
    }

    @Test
    fun `static vs instance field mismatch is detected`() {
        val outDir = Files.createTempDirectory("enkidu-resolve-field")
        val srcDir = Files.createTempDirectory("enkidu-resolve-field-src")

        writeJava(
            srcDir.resolve("demo/F.java"),
            """
            package demo;

            public class F {
                public static int X = 1;
            }
            """.trimIndent(),
        )
        compileJava(srcDir, outDir)

        val snapshot = ClasspathSnapshot.fromPaths(listOf(outDir))

        JvmLinkageResolver(snapshot).use { resolver ->
            val outcome =
                resolver.resolveField(
                    symbol = SymbolId(owner = "demo.F", kind = SymbolKind.FIELD, name = "X", descriptor = "I"),
                    opcode = Opcodes.GETFIELD,
                )

            assertTrue(outcome is FieldResolutionOutcome.IncompatibleClassChange)
        }
    }

    @Test
    fun `static vs instance method mismatch is detected for invokestatic`() {
        val outDir = Files.createTempDirectory("enkidu-resolve-mstatic")
        val srcDir = Files.createTempDirectory("enkidu-resolve-mstatic-src")

        writeJava(
            srcDir.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public String foo() { return "ok"; }
            }
            """.trimIndent(),
        )
        compileJava(srcDir, outDir)

        val snapshot = ClasspathSnapshot.fromPaths(listOf(outDir))

        JvmLinkageResolver(snapshot).use { resolver ->
            val outcome =
                resolver.resolveMethod(
                    symbol = SymbolId(owner = "demo.Lib", kind = SymbolKind.METHOD, name = "foo", descriptor = "()Ljava/lang/String;"),
                    opcode = Opcodes.INVOKESTATIC,
                    isInterfaceInvocation = false,
                )

            assertTrue(outcome is MethodResolutionOutcome.IncompatibleClassChange)
        }
    }

    @Test
    fun `static vs instance method mismatch is detected for invokevirtual`() {
        val outDir = Files.createTempDirectory("enkidu-resolve-minstance")
        val srcDir = Files.createTempDirectory("enkidu-resolve-minstance-src")

        writeJava(
            srcDir.resolve("demo/Lib.java"),
            """
            package demo;

            public class Lib {
                public static String foo() { return "ok"; }
            }
            """.trimIndent(),
        )
        compileJava(srcDir, outDir)

        val snapshot = ClasspathSnapshot.fromPaths(listOf(outDir))

        JvmLinkageResolver(snapshot).use { resolver ->
            val outcome =
                resolver.resolveMethod(
                    symbol = SymbolId(owner = "demo.Lib", kind = SymbolKind.METHOD, name = "foo", descriptor = "()Ljava/lang/String;"),
                    opcode = Opcodes.INVOKEVIRTUAL,
                    isInterfaceInvocation = false,
                )

            assertTrue(outcome is MethodResolutionOutcome.IncompatibleClassChange)
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
            val options = listOf("-g", "-d", outDir.toString())
            val task = compiler.getTask(null, fileManager, null, options, null, units)
            val ok = task.call()
            check(ok) { "javac compilation failed" }
        }

        check(Files.walk(outDir).anyMatch { Files.isRegularFile(it) && it.toString().endsWith(".class") }) {
            "no class files produced under $outDir"
        }
    }
}
