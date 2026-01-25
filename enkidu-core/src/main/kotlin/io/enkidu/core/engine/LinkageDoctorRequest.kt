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
package io.enkidu.core.engine

import io.enkidu.artifacts.v1.ToolMetadata
import java.nio.file.Path

/**
 * Explicit input for a single Linkage Doctor run.
 *
 * Enkidu makes no build-tool assumptions. Callers must provide:
 * - the compiled targets to scan (classes directory and/or jar)
 * - the exact runtime classpath in resolution order
 * - tool metadata to embed in the resulting report
 */
data class LinkageDoctorRequest(
    val tool: ToolMetadata,
    /** One or more targets to scan: classes directory or jar. */
    val targets: List<Path>,
    /** Runtime classpath entries (directories or jars) in JVM resolution order. */
    val runtimeClasspath: List<Path>,
)
