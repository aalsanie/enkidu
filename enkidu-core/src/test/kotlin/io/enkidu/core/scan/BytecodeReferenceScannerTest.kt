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
package io.enkidu.core.scan

import io.enkidu.artifacts.v1.SymbolId
import io.enkidu.artifacts.v1.SymbolKind
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BytecodeReferenceScannerTest {
    @Test
    fun `extracts method field and type references with best-effort line numbers`() {
        val outDir = Files.createTempDirectory("enkidu-bc-scan")
        val srcDir = Files.createTempDirectory("enkidu-bc-src")

        writeJava(
            srcDir.resolve("demo/Target.java"),
            """
            package demo;

            public class Target {
                public static int staticField = 7;
                public int value = 3;

                public Target() {}

                public String hello(String s) {
                    return s.toUpperCase();
                }

                public static String stat(String s) {
                    return s.trim();
                }
            }
            """.trimIndent(),
        )

        writeJava(
            srcDir.resolve("demo/Caller.java"),
            """
            package demo;

            public class Caller {
                public String run(Target t) {
                    int x = Target.staticField;
                    t.value = x;
                    String a = Target.stat(" hi ");
                    String b = t.hello(a);
                    Target n = new Target();
                    Object o = n;
                    Target t2 = (Target) o;
                    boolean ok = o instanceof Target;
                    Class<?> c = Target.class;
                    return b + c.getName() + t2.toString() + ok;
                }
            }
            """.trimIndent(),
        )

        compileJava(srcDir, outDir)

        val callerClass = outDir.resolve("demo/Caller.class")
        val refs = BytecodeReferenceScanner().scanClassBytes(callerClass.readBytes())

        // Scanner emits JVM internal names (slashes), not dotted binary names.
        val targetInternal = "demo/Target"

        // Core expectations (exact JVM descriptors)
        assertContains(
            refs.map { it.symbol }.toSet(),
            SymbolId(owner = targetInternal, kind = SymbolKind.FIELD, name = "staticField", descriptor = "I"),
        )
        assertContains(
            refs.map { it.symbol }.toSet(),
            SymbolId(owner = targetInternal, kind = SymbolKind.FIELD, name = "value", descriptor = "I"),
        )
        assertContains(
            refs.map { it.symbol }.toSet(),
            SymbolId(
                owner = targetInternal,
                kind = SymbolKind.METHOD,
                name = "stat",
                descriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        assertContains(
            refs.map { it.symbol }.toSet(),
            SymbolId(
                owner = targetInternal,
                kind = SymbolKind.METHOD,
                name = "hello",
                descriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        assertContains(
            refs.map { it.symbol }.toSet(),
            SymbolId(
                owner = targetInternal,
                kind = SymbolKind.METHOD,
                name = "<init>",
                descriptor = "()V",
            ),
        )

        // At least one TYPE reference to demo/Target should be recorded (NEW/CHECKCAST/INSTANCEOF/LDC)
        assertTrue(
            refs.any { it.symbol.kind == SymbolKind.TYPE && it.symbol.owner == targetInternal },
            "expected at least one TYPE reference to $targetInternal",
        )

        // Best-effort line numbers: ensure at least one recorded reference has a source line.
        val anyWithLine = refs.firstOrNull { it.site.line != null }
        assertNotNull(anyWithLine, "expected at least one reference to carry a line number")
        assertTrue(anyWithLine.site.line!! > 0, "line numbers must be positive")
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

        compiler.getStandardFileManager(null, null, null).use { fileManager ->
            val units = fileManager.getJavaFileObjectsFromFiles(sourceFiles.map { it.toFile() })
            val options = listOf("-g", "-d", outDir.toString())
            val task = compiler.getTask(null, fileManager, null, options, null, units)
            val ok = task.call()
            check(ok) { "javac compilation failed" }
        }
    }
}
