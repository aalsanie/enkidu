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
package io.enkidu.core.util

import java.util.zip.ZipFile

/**
 * Minimal Multi-Release JAR (MRJAR) helpers.
 *
 * We treat MRJARs conservatively:
 * - Only when the manifest contains `Multi-Release: true` (case-insensitive).
 * - Only version directories `META-INF/versions/<n>/` where n is an integer and 9 <= n <= runtimeJavaFeature.
 * - The *highest* eligible version wins.
 */
internal object MultiReleaseSupport {
    private const val MANIFEST_PATH = "META-INF/MANIFEST.MF"
    private const val MR_PREFIX = "META-INF/versions/"

    fun isMultiRelease(zip: ZipFile): Boolean {
        val e = zip.getEntry(MANIFEST_PATH) ?: return false
        val text = zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        // Manifest keys are case-insensitive; scan line-by-line.
        return text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Multi-Release:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.equals("true", ignoreCase = true)
            ?: false
    }

    fun manifestAttribute(
        zip: ZipFile,
        attributeName: String,
    ): String? {
        val e = zip.getEntry(MANIFEST_PATH) ?: return null
        val text = zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        return text
            .lineSequence()
            .map { it.trimEnd() }
            .firstOrNull { it.startsWith("$attributeName:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Select the effective entry name for [basePath] considering MRJAR rules.
     * Returns null if the base entry does not exist and no versioned entry exists.
     */
    fun effectiveEntryName(
        zip: ZipFile,
        basePath: String,
        runtimeJavaFeature: Int,
        isMultiRelease: Boolean,
    ): String? {
        if (basePath.isBlank()) return null

        // Fast path: non-MR jars.
        if (!isMultiRelease || runtimeJavaFeature < 9) {
            return zip.getEntry(basePath)?.name
        }

        // Highest eligible MR version wins.
        for (v in runtimeJavaFeature downTo 9) {
            val candidate = "$MR_PREFIX$v/$basePath"
            val e = zip.getEntry(candidate)
            if (e != null && !e.isDirectory) return e.name
        }

        return zip.getEntry(basePath)?.name
    }

    /**
     * If [name] is a versioned entry (META-INF/versions/<n>/...), returns (n, logicalName).
     */
    fun parseVersionedName(name: String): Pair<Int, String>? {
        if (!name.startsWith(MR_PREFIX)) return null
        val rest = name.removePrefix(MR_PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val verStr = rest.substring(0, slash)
        val ver = verStr.toIntOrNull() ?: return null
        val logical = rest.substring(slash + 1)
        if (logical.isBlank()) return null
        return ver to logical
    }
}
