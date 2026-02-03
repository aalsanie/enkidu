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
import io.enkidu.core.util.MultiReleaseSupport
import io.enkidu.core.util.WarningCode
import io.enkidu.core.util.WarningCollector
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

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
        fun build(snapshot: ClasspathSnapshot): ModuleIndex =
            build(snapshot = snapshot, runtimeJavaFeature = Runtime.version().feature(), warnings = null)

        fun build(
            snapshot: ClasspathSnapshot,
            runtimeJavaFeature: Int = Runtime.version().feature(),
            warnings: WarningCollector? = null,
        ): ModuleIndex {
            val map = linkedMapOf<Path, ModuleInfo>()
            for (entry in snapshot.entries) {
                val info = readModuleInfo(entry.path, runtimeJavaFeature = runtimeJavaFeature, warnings = warnings)
                if (info != null) {
                    map[entry.path] = info
                }
            }
            return ModuleIndex(map.toMap())
        }

        private fun readModuleInfo(
            path: Path,
            runtimeJavaFeature: Int,
            warnings: WarningCollector?,
        ): ModuleInfo? =
            when {
                Files.isDirectory(path) -> readModuleInfoFromDir(path, warnings)
                Files.isRegularFile(path) && path.toString().endsWith(".jar") -> readModuleInfoFromJar(path, runtimeJavaFeature, warnings)
                else -> null
            }

        private fun readModuleInfoFromDir(
            dir: Path,
            warnings: WarningCollector?,
        ): ModuleInfo? {
            val moduleInfoClass = dir.resolve("module-info.class")
            if (!Files.isRegularFile(moduleInfoClass)) return null
            return try {
                parseModuleInfo(Files.readAllBytes(moduleInfoClass), automaticName = null)
            } catch (e: Exception) {
                warnings?.warn(
                    code = WarningCode.MODULE_INFO_PARSE_FAILED,
                    message = "Failed to parse module-info.class: ${e.javaClass.simpleName}: ${e.message}",
                    path = moduleInfoClass,
                    jarEntry = null,
                )
                null
            }
        }

        private fun readModuleInfoFromJar(
            jar: Path,
            runtimeJavaFeature: Int,
            warnings: WarningCollector?,
        ): ModuleInfo? {
            if (runtimeJavaFeature < 9) {
                // JPMS does not exist pre-9.
                return null
            }

            ZipFile(jar.toFile()).use { zip ->
                val automatic =
                    try {
                        MultiReleaseSupport.manifestAttribute(zip, "Automatic-Module-Name")?.trim()
                    } catch (e: Exception) {
                        warnings?.warn(
                            code = WarningCode.MANIFEST_PARSE_FAILED,
                            message = "Failed to parse jar manifest (Automatic-Module-Name): ${e.javaClass.simpleName}: ${e.message}",
                            path = jar,
                            jarEntry = "META-INF/MANIFEST.MF",
                        )
                        null
                    }

                val isMr =
                    try {
                        MultiReleaseSupport.isMultiRelease(zip)
                    } catch (e: Exception) {
                        warnings?.warn(
                            code = WarningCode.MANIFEST_PARSE_FAILED,
                            message = "Failed to parse jar manifest (MRJAR detection): ${e.javaClass.simpleName}: ${e.message}",
                            path = jar,
                            jarEntry = "META-INF/MANIFEST.MF",
                        )
                        false
                    }

                val effectiveEntryName =
                    try {
                        MultiReleaseSupport.effectiveEntryName(zip, "module-info.class", runtimeJavaFeature, isMr)
                    } catch (e: Exception) {
                        warnings?.warn(
                            code = WarningCode.IO_ERROR,
                            message = "Failed to locate module-info.class: ${e.javaClass.simpleName}: ${e.message}",
                            path = jar,
                            jarEntry = "module-info.class",
                        )
                        null
                    }

                if (effectiveEntryName != null) {
                    val je = zip.getEntry(effectiveEntryName)
                    if (je != null && !je.isDirectory) {
                        val bytes = zip.getInputStream(je).use { it.readBytes() }
                        return try {
                            parseModuleInfo(bytes, automaticName = null)
                        } catch (e: Exception) {
                            warnings?.warn(
                                code = WarningCode.MODULE_INFO_PARSE_FAILED,
                                message = "Failed to parse $effectiveEntryName: ${e.javaClass.simpleName}: ${e.message}",
                                path = jar,
                                jarEntry = effectiveEntryName,
                            )
                            null
                        }
                    }
                }

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

        // exports map uses null value to represent an *unqualified* export.
        // so we must distinguish "key missing" from "key present with null value".
        if (!exports.containsKey(packageName)) return false

        val export = exports[packageName] ?: return true

        // Unqualified export (common case): exported to everyone

        // Qualified export: exported only to listed modules
        if (toModule == null) return false
        return export.contains(toModule)
    }
}
