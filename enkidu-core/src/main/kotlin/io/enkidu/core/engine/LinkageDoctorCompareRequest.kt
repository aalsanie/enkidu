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
package io.enkidu.core.engine

import io.enkidu.artifacts.v1.ToolMetadata
import java.nio.file.Path

data class LinkageDoctorCompareRequest(
    val tool: ToolMetadata,
    val targets: List<Path>,
    val classpathA: List<Path>,
    val classpathB: List<Path>,
    val runtimeJavaFeature: Int? = null,
    val continueOnError: Boolean = false,
    val labelA: String = "A",
    val labelB: String = "B",
    val performance: PerformanceOptions = PerformanceOptions(),
)
