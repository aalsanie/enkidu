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

import java.util.Locale
import java.util.jar.Manifest

/**
 * Multi-Release JAR (MRJAR) helpers.
 *
 * MRJAR selection rules:
 * - Only apply versioned entries when manifest contains `Multi-Release: true`.
 * - Prefer the highest versioned entry `META-INF/versions/<n>/...` where `9 <= n <= runtimeJavaFeature`.
 * - Fall back to the base entry when no applicable version exists.
 */
object MultiReleaseJar {
    private const val MR_PREFIX: String = "META-INF/versions/"
    private const val MULTI_RELEASE: String = "Multi-Release"

    fun isMultiRelease(manifest: Manifest?): Boolean {
        val value = manifest?.mainAttributes?.getValue(MULTI_RELEASE) ?: return false
        return value.trim().lowercase(Locale.ROOT) == "true"
    }

    /**
     * Returns the effective jar entry path for [basePath] under [runtimeJavaFeature], or null if no entry exists.
     *
     * [entryExists] should be a fast existence check (JarFile.getJarEntry != null).
     */
    fun effectiveEntryPath(
        basePath: String,
        isMultiRelease: Boolean,
        runtimeJavaFeature: Int,
        entryExists: (String) -> Boolean,
    ): String? {
        if (!isMultiRelease) {
            return if (entryExists(basePath)) basePath else null
        }

        // MRJAR versioned entries start at Java 9.
        val upper = runtimeJavaFeature.coerceAtLeast(0)
        if (upper >= 9) {
            for (v in upper downTo 9) {
                val candidate = "$MR_PREFIX$v/$basePath"
                if (entryExists(candidate)) return candidate
            }
        }

        return if (entryExists(basePath)) basePath else null
    }

    /**
     * If the given [jarEntryName] is a versioned MR entry, return its base-path without the MR prefix.
     * Otherwise returns null.
     */
    fun basePathOfVersionedEntry(jarEntryName: String): String? {
        if (!jarEntryName.startsWith(MR_PREFIX)) return null
        val rest = jarEntryName.removePrefix(MR_PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        // Ensure version segment is numeric.
        val ver = rest.substring(0, slash)
        if (ver.any { !it.isDigit() }) return null
        return rest.substring(slash + 1)
    }

    /**
     * If the given [jarEntryName] is a versioned MR entry, return its version, else null.
     */
    fun versionOfVersionedEntry(jarEntryName: String): Int? {
        if (!jarEntryName.startsWith(MR_PREFIX)) return null
        val rest = jarEntryName.removePrefix(MR_PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val ver = rest.substring(0, slash)
        return ver.toIntOrNull()
    }
}
