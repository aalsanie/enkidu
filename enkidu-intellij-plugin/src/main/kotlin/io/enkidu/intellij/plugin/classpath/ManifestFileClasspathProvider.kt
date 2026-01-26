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
package io.enkidu.intellij.plugin.classpath

import com.intellij.openapi.module.Module
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object ManifestFileClasspathProvider : ClasspathProvider {
    override val id: String = "manifest-file"
    override val displayName: String = "Manifest file"
    override val description: String = "Use an explicit classpath manifest file (one entry per line)."

    override fun resolve(
        module: Module,
        context: ClasspathProviderContext,
    ): ClasspathResolution {
        val file =
            context.manifestFile
                ?: throw IllegalArgumentException("Classpath manifest file is required.")

        if (!Files.isRegularFile(file)) {
            throw IllegalArgumentException("Classpath manifest not found: $file")
        }

        val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
        val raw =
            lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

        val entries = dedupePreserveOrder(raw.map { Path.of(it) })

        val manifestText =
            buildString {
                raw.forEach { appendLine(it) }
            }.trimEnd()

        return ClasspathResolution(entries = entries, manifestText = manifestText)
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
