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
package io.enkidu.core.fixplan

import io.enkidu.artifacts.v1.Evidence
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.FixKind
import io.enkidu.artifacts.v1.FixPlanItem
import io.enkidu.artifacts.v1.SymbolId

/**
 * Deterministic, path-first fix suggestions.
 *
 * Milestone F v1 rules:
 * - never assume build-tool coordinates (Gradle/Maven) unless integrations supply them
 * - be deterministic (no randomness, stable ordering)
 * - prefer actionable classpath-level guidance (exclude/move jar, remove duplicates)
 */
class FixPlannerV1 {
    fun plan(
        type: FailureType,
        symbol: SymbolId,
        evidence: Evidence?,
    ): List<FixPlanItem> {
        val ownerBinary = symbol.owner.replace('/', '.')

        val winner = evidence?.winnerJar
        val shadowed = evidence?.shadowedJars.orEmpty().sorted()
        val missingHint = evidence?.missingJarHint

        val items = mutableListOf<FixPlanItem>()

        // 1) If we have a shadowed copy, "exclude winner" is often the smallest change that makes
        //    the other copy win, restoring ABI expected by callsites.
        if (winner != null && shadowed.isNotEmpty() && type in SHADOWING_SENSITIVE_TYPES) {
            items +=
                FixPlanItem(
                    kind = FixKind.EXCLUDE_JAR,
                    value = winner,
                    confidence = 0.90,
                )

            items +=
                FixPlanItem(
                    kind = FixKind.REMOVE_DUPLICATES,
                    value = duplicateValue(ownerBinary = ownerBinary, winner = winner, shadowed = shadowed),
                    confidence = 0.80,
                )
        }

        // 2) Missing symbols: add a dependency that provides the owner type.
        if (type == FailureType.MISSING_CLASS || (missingHint != null && winner == null)) {
            items +=
                FixPlanItem(
                    kind = FixKind.ADD_MISSING_DEPENDENCY,
                    value = ownerBinary,
                    confidence = 0.85,
                )
        }

        // 3) Version alignment is a safe catch-all for binary incompatibilities when a class exists.
        if (winner != null && type in BINARY_INCOMPAT_TYPES) {
            items +=
                FixPlanItem(
                    kind = FixKind.ALIGN_VERSIONS,
                    value = winner,
                    confidence = 0.70,
                )

            // Direction (upgrade/downgrade) is not knowable without coordinates/version context.
            // Keep this as a low-confidence hint, but deterministic.
            items +=
                FixPlanItem(
                    kind = FixKind.UPGRADE_DEPENDENCY,
                    value = winner,
                    confidence = 0.55,
                )
        }

        // Deterministic order: the DTO canonicalizer will sort by (kind, value),
        // but we avoid generating duplicate entries.
        return items.distinctBy { it.kind to it.value }
    }

    private fun duplicateValue(
        ownerBinary: String,
        winner: String,
        shadowed: List<String>,
    ): String {
        val s = shadowed.joinToString(",")
        return "Duplicate class $ownerBinary | winner=$winner | shadowed=$s"
    }

    private companion object {
        val SHADOWING_SENSITIVE_TYPES: Set<FailureType> =
            setOf(
                FailureType.MISSING_METHOD,
                FailureType.MISSING_FIELD,
                FailureType.DESCRIPTOR_MISMATCH,
                FailureType.INCOMPATIBLE_CLASS_CHANGE,
                FailureType.ILLEGAL_ACCESS_RISK,
            )

        val BINARY_INCOMPAT_TYPES: Set<FailureType> =
            setOf(
                FailureType.MISSING_METHOD,
                FailureType.MISSING_FIELD,
                FailureType.DESCRIPTOR_MISMATCH,
                FailureType.INCOMPATIBLE_CLASS_CHANGE,
                FailureType.ILLEGAL_ACCESS_RISK,
            )
    }
}
