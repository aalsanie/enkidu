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

import java.nio.file.Path

/**
 * Performance and caching knobs.
 *
 * IMPORTANT: defaults keep behavior identical to earlier milestones (single-threaded, no cache).
 * Callers may opt-in to parallelism and caching by setting these explicitly.
 */
data class PerformanceOptions(
    /**
     * Max parallelism used for classpath jar scanning (JarIndex + SPI service index warmup).
     */
    val jarScanParallelism: Int = 1,
    /**
     * Max parallelism used for target class scanning + classification.
     */
    val targetScanParallelism: Int = 1,
    /**
     * Upper bound on in-flight target class tasks (bytes + scan output) to cap memory.
     *
     * If null, the engine uses (targetScanParallelism * 2) as a deterministic bound.
     */
    val maxInFlightTargetClasses: Int? = null,
    /**
     * Enables jar scan cache when set. The cache is keyed by jar SHA-256.
     *
     * This is an explicit input: callers control where (and whether) caching happens.
     */
    val jarScanCacheDir: Path? = null,
) {
    init {
        require(jarScanParallelism >= 1) { "jarScanParallelism must be >= 1" }
        require(targetScanParallelism >= 1) { "targetScanParallelism must be >= 1" }
        require(maxInFlightTargetClasses == null || maxInFlightTargetClasses >= 1) { "maxInFlightTargetClasses must be >= 1" }
    }
}
