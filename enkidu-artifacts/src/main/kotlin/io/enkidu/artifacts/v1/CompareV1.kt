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
package io.enkidu.artifacts.v1

data class CompareReport(
    val tool: ToolMetadata,
    val compared: ComparedClasspaths,
    val summary: CompareSummary,
    val regressions: List<LinkageFailure>,
    val fixed: List<LinkageFailure>,
    val winnerChanges: List<WinnerChange>,
) {
    fun canonical(): CompareReport =
        copy(
            compared = compared.canonical(),
            summary = summary.canonical(),
            regressions = regressions.sortedWith(LinkageFailure.CANONICAL_ORDER).map { it.canonical() },
            fixed = fixed.sortedWith(LinkageFailure.CANONICAL_ORDER).map { it.canonical() },
            winnerChanges = winnerChanges.sortedWith(WinnerChange.CANONICAL_ORDER).map { it.canonical() },
        )
}

data class ComparedClasspaths(
    val targets: List<String>,
    val classpathA: ClasspathIdentity,
    val classpathB: ClasspathIdentity,
) {
    fun canonical(): ComparedClasspaths =
        copy(
            targets = targets.sorted(),
            classpathA = classpathA.canonical(),
            classpathB = classpathB.canonical(),
        )
}

data class ClasspathIdentity(
    val label: String,
    val fingerprintSha256: String,
    val entryCount: Int,
) {
    fun canonical(): ClasspathIdentity = copy(label = label.trim())
}

data class CompareSummary(
    val totalFailuresA: Int,
    val totalFailuresB: Int,
    val regressions: Int,
    val fixed: Int,
    val winnerChanges: Int,
) {
    fun canonical(): CompareSummary = this
}

data class WinnerChange(
    val className: String,
    val winnerA: String?,
    val winnerB: String?,
) {
    fun canonical(): WinnerChange = copy(className = className.trim())

    companion object {
        val CANONICAL_ORDER: Comparator<WinnerChange> =
            compareBy<WinnerChange> { it.className }
                .thenBy { it.winnerA ?: "" }
                .thenBy { it.winnerB ?: "" }
    }
}
