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

import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.util.EnkiduNames
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Best-effort JPMS module metadata index.
 *
 * This is intentionally conservative:
 * - If a jar is an automatic module (manifest Automatic-Module-Name), we treat it as exporting all packages.
 * - If we cannot determine exports, we do not guess "blocked".
 */
class ModuleIndex private constructor(
    private val byEntryPath: Map<Path, ModuleInfo>,
) {
    fun moduleFor(entryPath: Path): ModuleInfo? = byEntryPath[entryPath]

    companion object {
        fun build(snapshot: ClasspathSnapshot): ModuleIndex {
            val map = linkedMapOf<Path, ModuleInfo>()
            for (entry in snapshot.entries) {
                val info = readModuleInfo(entry.path)
                if (info != null) {
                    map[entry.path] = info
                }
            }
            return ModuleIndex(map.toMap())
        }

        private fun readModuleInfo(path: Path): ModuleInfo? =
            when {
                Files.isDirectory(path) -> readModuleInfoFromDir(path)
                Files.isRegularFile(path) && path.toString().endsWith(".jar") -> readModuleInfoFromJar(path)
                else -> null
            }

        private fun readModuleInfoFromDir(dir: Path): ModuleInfo? {
            val moduleInfoClass = dir.resolve("module-info.class")
            if (!Files.isRegularFile(moduleInfoClass)) return null
            return parseModuleInfo(Files.readAllBytes(moduleInfoClass), automaticName = null)
        }

        private fun readModuleInfoFromJar(jar: Path): ModuleInfo? {
            JarFile(jar.toFile()).use { jf ->
                val moduleEntry = jf.getJarEntry("module-info.class")
                if (moduleEntry != null) {
                    jf.getInputStream(moduleEntry).use { input ->
                        return parseModuleInfo(input.readBytes(), automaticName = null)
                    }
                }

                val automatic =
                    jf.manifest
                        ?.mainAttributes
                        ?.getValue("Automatic-Module-Name")
                        ?.trim()
                if (!automatic.isNullOrBlank()) {
                    // Automatic modules export all packages. We still keep the name for reporting.
                    return ModuleInfo(
                        name = automatic,
                        isAutomatic = true,
                        exports = emptyMap(),
                    )
                }

                return null
            }
        }

        private fun parseModuleInfo(
            bytes: ByteArray,
            automaticName: String?,
        ): ModuleInfo? {
            var moduleName: String? = automaticName
            val exports = linkedMapOf<String, Set<String>?>()

            val cr = ClassReader(bytes)
            cr.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitModule(
                        name: String,
                        access: Int,
                        version: String?,
                    ): ModuleVisitor {
                        moduleName = name
                        return object : ModuleVisitor(Opcodes.ASM9) {
                            override fun visitExport(
                                packaze: String,
                                access: Int,
                                modules: Array<out String>?,
                            ) {
                                val pkg = EnkiduNames.internalToBinary(packaze)
                                exports[pkg] = modules?.toSet()
                            }
                        }
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )

            val name = moduleName?.trim().takeUnless { it.isNullOrBlank() } ?: return null
            return ModuleInfo(
                name = name,
                isAutomatic = false,
                exports = exports.toMap(),
            )
        }
    }
}

/**
 * exports: map of packageName -> null (unqualified export) OR set of module names for qualified exports.
 */
data class ModuleInfo(
    val name: String,
    val isAutomatic: Boolean,
    val exports: Map<String, Set<String>?>,
) {
    fun exportsPackage(
        packageName: String,
        toModule: String?,
    ): Boolean {
        if (isAutomatic) return true
        val export = exports[packageName] ?: return false
        // Unqualified export
        if (export == null) return true
        // Qualified export to a set of modules
        if (toModule == null) return false
        return export.contains(toModule)
    }
}
