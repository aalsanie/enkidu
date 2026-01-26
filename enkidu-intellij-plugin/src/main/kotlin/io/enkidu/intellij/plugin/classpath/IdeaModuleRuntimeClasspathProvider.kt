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
package io.enkidu.intellij.plugin.classpath

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds an ordered runtime classpath from the IntelliJ project model (selected module).
 *
 * No build-tool assumptions: we trust the IDE model to describe the resolved classpath.
 */
object IdeaModuleRuntimeClasspathProvider : ClasspathProvider {
    override val id: String = "idea-module-runtime"
    override val displayName: String = "Project (module runtime)"
    override val description: String = "Use IntelliJ project model to derive the module runtime classpath."

    override fun resolve(
        module: Module,
        context: ClasspathProviderContext,
    ): ClasspathResolution {
        val roots: Array<VirtualFile> =
            OrderEnumerator
                .orderEntries(module)
                .recursively()
                .runtimeOnly()
                .classes()
                .roots

        val rawPaths =
            roots
                .asSequence()
                .mapNotNull { toLocalPath(it) }
                .map { it.toAbsolutePath().normalize() }
                .filter { Files.exists(it) }
                .toList()

        val entries = dedupePreserveOrder(rawPaths)

        val manifestText = entries.joinToString(separator = System.lineSeparator()) { it.toString() }

        if (entries.isEmpty()) {
            throw IllegalArgumentException("Derived runtime classpath is empty for module '${module.name}'.")
        }

        return ClasspathResolution(entries = entries, manifestText = manifestText)
    }

    private fun toLocalPath(vf: VirtualFile): Path? {
        // Jar roots show up as jar://...!/ ; convert to the underlying jar file on disk.
        if (vf.fileSystem == JarFileSystem.getInstance()) {
            val jarFileVf = JarFileSystem.getInstance().getVirtualFileForJar(vf)
            if (jarFileVf != null) {
                return Path.of(jarFileVf.path)
            }
            // Fallback: try stripping the "!/" part.
            val p = vf.path
            val bang = p.indexOf("!/")
            if (bang > 0) {
                return Path.of(p.substring(0, bang))
            }
            return null
        }

        // Local directories/files.
        return try {
            Path.of(vf.path)
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> dedupePreserveOrder(items: List<T>): List<T> {
        val seen = LinkedHashSet<T>()
        val out = ArrayList<T>(items.size)
        for (i in items) {
            if (seen.add(i)) out.add(i)
        }
        return out
    }
}
