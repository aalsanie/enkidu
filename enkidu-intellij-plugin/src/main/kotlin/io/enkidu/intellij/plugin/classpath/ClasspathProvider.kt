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
import java.nio.file.Path

/**
 * Milestone K: pluggable classpath sources for the IntelliJ plugin.
 *
 * Providers must return an ordered runtime classpath (paths in resolution order)
 * and a manifest text that can be copied into the CLI.
 */
interface ClasspathProvider {
    /** Stable id persisted in settings (e.g., "manifest-file", "idea-module-runtime"). */
    val id: String

    /** Display name used in the UI. */
    val displayName: String

    /** Short help text for the UI. */
    val description: String

    /**
     * Resolve an ordered runtime classpath for [module].
     * Implementations must de-duplicate while preserving first-seen order.
     */
    fun resolve(
        module: Module,
        context: ClasspathProviderContext,
    ): ClasspathResolution
}

/**
 * Explicit, user-controlled inputs that are not module-specific.
 */
data class ClasspathProviderContext(
    val manifestFile: Path?,
)

/**
 * Result includes both the ordered paths and a manifest text for CLI reproduction.
 */
data class ClasspathResolution(
    val entries: List<Path>,
    val manifestText: String,
)
