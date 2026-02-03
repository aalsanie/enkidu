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
package io.enkidu.core.hardening

import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.perf.JarScanRepository
import io.enkidu.core.resolve.ClassResolutionOutcome
import io.enkidu.core.resolve.ClasspathBytecodeLoader
import io.enkidu.core.resolve.JvmLinkageResolver
import io.enkidu.core.resolve.ModuleIndex
import io.enkidu.core.scan.TargetReferenceScanner
import io.enkidu.core.util.WarningCode
import io.enkidu.core.util.WarningCollector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test

class MultiReleaseAndInvalidBytecodeTest {
    @Test
    fun `MRJAR - runtime loader selects effective class by runtimeJavaFeature`() {
        val tmp = Files.createTempDirectory("enkidu-hardening-mr")
        val jar = tmp.resolve("mr.jar")
        writeMrJar(
            jar,
            entries =
                mapOf(
                    // Base class contains "base" string constant.
                    "p/Foo.class" to klassBytes("p/Foo", "base", classVersion = Opcodes.V1_8),
                    // Versioned class contains "v11" string constant.
                    "META-INF/versions/11/p/Foo.class" to klassBytes("p/Foo", "v11", classVersion = Opcodes.V11),
                ),
        )

        val snap = ClasspathSnapshot.fromPaths(listOf(jar))
        val warnings = WarningCollector()

        // JDK 9 should select base (11 is not eligible).
        ClasspathBytecodeLoader(snap, runtimeJavaFeature = 9, warnings = warnings).use { loader ->
            val bytes = loader.findClassBytes("p.Foo")!!.bytes
            assertTrue(containsAscii(bytes, "base"), "expected base bytes")
            assertTrue(!containsAscii(bytes, "v11"), "should not select v11 bytes on feature 9")
        }

        // JDK 11 should select v11.
        ClasspathBytecodeLoader(snap, runtimeJavaFeature = 11, warnings = warnings).use { loader ->
            val bytes = loader.findClassBytes("p.Foo")!!.bytes
            assertTrue(containsAscii(bytes, "v11"), "expected v11 bytes")
        }

        // No warnings expected in a clean jar.
        assertTrue(warnings.snapshotSorted().isEmpty())
    }

    @Test
    fun `MRJAR - JarScanRepository selects effective META-INF services by runtimeJavaFeature`() {
        val tmp = Files.createTempDirectory("enkidu-hardening-svc")
        val jar = tmp.resolve("mr-services.jar")
        writeMrJar(
            jar,
            entries =
                mapOf(
                    "META-INF/services/p.Service" to "p.ImplBase\n".toByteArray(StandardCharsets.UTF_8),
                    "META-INF/versions/11/META-INF/services/p.Service" to "p.ImplV11\n".toByteArray(StandardCharsets.UTF_8),
                ),
        )

        val warnings = WarningCollector()
        val repo9 = JarScanRepository(cache = null, runtimeJavaFeature = 9, warnings = warnings)
        val scan9 = repo9.scanJar(jar)
        assertEquals(listOf("p.ImplBase"), scan9.services["p.Service"], "feature 9 should use base service file")

        val repo11 = JarScanRepository(cache = null, runtimeJavaFeature = 11, warnings = warnings)
        val scan11 = repo11.scanJar(jar)
        assertEquals(listOf("p.ImplV11"), scan11.services["p.Service"], "feature 11 should use versioned service file")

        assertTrue(warnings.snapshotSorted().isEmpty())
    }

    @Test
    fun `MRJAR - ModuleIndex selects effective module-info by runtimeJavaFeature`() {
        val tmp = Files.createTempDirectory("enkidu-hardening-mod")
        val jar = tmp.resolve("mr-module.jar")
        writeMrJar(
            jar,
            entries =
                mapOf(
                    // Base module-info exports only package "p".
                    "module-info.class" to moduleInfoBytes("m.test", exports = listOf("p"), classVersion = Opcodes.V9),
                    // Versioned module-info exports "p" and "q".
                    "META-INF/versions/11/module-info.class" to
                        moduleInfoBytes("m.test", exports = listOf("p", "q"), classVersion = Opcodes.V11),
                ),
        )

        val snap = ClasspathSnapshot.fromPaths(listOf(jar))
        val warnings = WarningCollector()

        val idx9 = ModuleIndex.build(snapshot = snap, runtimeJavaFeature = 9, warnings = warnings)
        val m9 = idx9.moduleFor(jar)
        assertNotNull(m9)
        assertTrue(m9!!.exports.keys.contains("p"))
        assertTrue(!m9.exports.keys.contains("q"), "feature 9 should not select versioned module-info")

        val idx11 = ModuleIndex.build(snapshot = snap, runtimeJavaFeature = 11, warnings = warnings)
        val m11 = idx11.moduleFor(jar)
        assertNotNull(m11)
        assertTrue(m11!!.exports.keys.containsAll(listOf("p", "q")), "feature 11 should select versioned module-info")

        assertTrue(warnings.snapshotSorted().isEmpty())
    }

    @Test
    fun `Invalid bytecode - runtime resolution throws in fail-fast, warns and continues in continue-on-error`() {
        val tmp = Files.createTempDirectory("enkidu-hardening-runtime")
        val jar = tmp.resolve("bad-runtime.jar")
        writeMrJar(
            jar,
            entries = mapOf("p/Bad.class" to ByteArray(32) { 0x01 }),
        )

        val snap = ClasspathSnapshot.fromPaths(listOf(jar))

        // Fail-fast: throws.
        assertThrows<Exception> {
            val warnings = WarningCollector()
            JvmLinkageResolver(snapshot = snap, runtimeJavaFeature = 11, continueOnError = false, warnings = warnings).use { r ->
                r.resolveClass("p.Bad")
            }
        }

        // Continue-on-error: warning + Unparseable.
        val warnings2 = WarningCollector()
        val out =
            JvmLinkageResolver(snapshot = snap, runtimeJavaFeature = 11, continueOnError = true, warnings = warnings2).use { r ->
                r.resolveClass("p.Bad")
            }
        assertTrue(out is ClassResolutionOutcome.Unparseable)

        val ws = warnings2.snapshotSorted()
        assertEquals(1, ws.size)
        assertEquals(WarningCode.INVALID_BYTECODE, ws[0].code)
        assertEquals("p/Bad.class", ws[0].jarEntry)
        assertTrue(ws[0].path!!.endsWith("bad-runtime.jar"))
    }

    @Test
    fun `Invalid bytecode - target scanning throws in fail-fast, warns and continues in continue-on-error`() {
        val tmp = Files.createTempDirectory("enkidu-hardening-target")
        val classesDir = tmp.resolve("classes")
        val bad = classesDir.resolve("p/Bad.class")
        Files.createDirectories(bad.parent)
        Files.write(bad, ByteArray(64) { 0x02 })

        val scanner = TargetReferenceScanner()

        // Fail-fast
        assertThrows<Exception> {
            scanner.scanTargets(
                listOf(classesDir),
                options = TargetReferenceScanner.Options(continueOnError = false, warnings = WarningCollector()),
            )
        }

        // Continue-on-error
        val warnings = WarningCollector()
        val refs =
            scanner.scanTargets(
                listOf(classesDir),
                options = TargetReferenceScanner.Options(continueOnError = true, warnings = warnings),
            )
        assertTrue(refs.isEmpty())
        val ws = warnings.snapshotSorted()
        assertEquals(1, ws.size)
        assertEquals(WarningCode.INVALID_BYTECODE, ws[0].code)
        assertTrue(ws[0].path!!.endsWith("p/Bad.class"))
    }

    private fun writeMrJar(
        jarPath: Path,
        entries: Map<String, ByteArray>,
    ) {
        Files.createDirectories(jarPath.parent)
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zos ->
            // Minimal manifest with Multi-Release: true.
            val manifest = "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(manifest)
            zos.closeEntry()

            for ((name, bytes) in entries.toSortedMap()) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun klassBytes(
        internalName: String,
        id: String,
        classVersion: Int,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(classVersion, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)

        // public <init>()V
        run {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        // public static String id()
        run {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "id", "()Ljava/lang/String;", null, null)
            mv.visitCode()
            mv.visitLdcInsn(id)
            mv.visitInsn(Opcodes.ARETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun moduleInfoBytes(
        moduleName: String,
        exports: List<String>,
        classVersion: Int,
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(classVersion, Opcodes.ACC_MODULE, "module-info", null, null, null)
        val mv: ModuleVisitor = cw.visitModule(moduleName, 0, null)
        for (pkg in exports) {
            mv.visitExport(pkg.replace('.', '/'), 0)
        }
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun containsAscii(
        bytes: ByteArray,
        needle: String,
    ): Boolean {
        val n = needle.toByteArray(StandardCharsets.UTF_8)
        outer@ for (i in 0..(bytes.size - n.size)) {
            for (j in n.indices) {
                if (bytes[i + j] != n[j]) continue@outer
            }
            return true
        }
        return false
    }
}
