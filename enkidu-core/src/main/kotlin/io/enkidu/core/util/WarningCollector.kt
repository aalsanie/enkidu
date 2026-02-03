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

import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Run-scoped warning collector for "best-effort" scanning/resolution.
 * - module-info parsing failures
 *
 * This collector is intentionally:
 * - thread-safe (used by bounded-parallel scan paths)
 * - deterministic when materialized (stable sorting)
 */
class WarningCollector {
    private val q: ConcurrentLinkedQueue<ScanWarning> = ConcurrentLinkedQueue()

    fun warn(
        code: WarningCode,
        message: String,
        path: Path? = null,
        jarEntry: String? = null,
    ) {
        val normalizedPath =
            path
                ?.toAbsolutePath()
                ?.normalize()
                ?.toString()
                ?.replace(Char(92), '/') // windows '\' -> '/'

        q.add(
            ScanWarning(
                code = code,
                message = message,
                path = normalizedPath,
                jarEntry = jarEntry,
            ),
        )
    }

    /**
     * Deterministic snapshot: stable ordering + stable de-duplication.
     */
    fun snapshotSorted(): List<ScanWarning> {
        val list = q.toList()
        if (list.isEmpty()) return emptyList()
        return list
            .distinct()
            .sortedWith(
                compareBy<ScanWarning> { it.code.name }
                    .thenBy { it.path ?: "" }
                    .thenBy { it.jarEntry ?: "" }
                    .thenBy { it.message },
            )
    }
}

enum class WarningCode {
    UNREADABLE_JAR,
    MANIFEST_PARSE_FAILED,
    INVALID_BYTECODE,
    MODULE_INFO_PARSE_FAILED,
    IO_ERROR,
}

data class ScanWarning(
    val code: WarningCode,
    val message: String,
    val path: String? = null,
    val jarEntry: String? = null,
)
