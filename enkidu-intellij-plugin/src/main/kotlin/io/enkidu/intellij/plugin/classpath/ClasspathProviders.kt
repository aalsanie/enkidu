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

/**
 * Deterministic registry for Milestone K.
 *
 * This is intentionally simple (no SPI yet). When we need extensibility, Milestone W
 * will move this to a public hook surface.
 */
object ClasspathProviders {
    private val providers: List<ClasspathProvider> =
        listOf(
            IdeaModuleRuntimeClasspathProvider,
            ManifestFileClasspathProvider,
        )

    fun all(): List<ClasspathProvider> = providers

    fun byId(id: String?): ClasspathProvider {
        if (id.isNullOrBlank()) return IdeaModuleRuntimeClasspathProvider
        return providers.firstOrNull { it.id == id } ?: IdeaModuleRuntimeClasspathProvider
    }

    fun default(): ClasspathProvider = IdeaModuleRuntimeClasspathProvider

    fun resolveOrThrow(
        module: Module,
        providerId: String?,
        context: ClasspathProviderContext,
    ): ClasspathResolution {
        val provider = byId(providerId)
        return provider.resolve(module, context)
    }
}
