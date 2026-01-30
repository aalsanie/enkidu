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
package io.enkidu.core.perf

/**
 * Cached scan product for a single jar, keyed by [sha256Hex].
 *
 * This is intentionally minimal: enough to avoid re-opening and re-walking jar central directories
 * for JarIndex + SPI validation.
 */
data class JarScanData(
    /** SHA-256 of the *jar file bytes* (not of decompressed entries). */
    val sha256Hex: String,
    /** Binary class names (java.lang.String) contained in this jar. */
    val classes: List<String>,
    /** Map: service binary name -> providers list (already normalized/deduped/sorted). */
    val services: Map<String, List<String>>,
)
