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

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Versioned DTO contract for Enkidu Linkage Doctor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LinkageReport(
    val tool: ToolMetadata,
    val fingerprints: Fingerprints,
    val summary: ReportSummary,
    val failures: List<LinkageFailure>,
    val execution: ExecutionContext? = null,
    val warnings: List<ScanWarning>? = null,
) {
    /** Returns a canonicalized copy suitable for JSON output. */
    fun canonical(): LinkageReport =
        copy(
            failures =
                failures
                    .map { it.canonical() }
                    .sortedWith(LinkageFailure.CANONICAL_ORDER),
            summary = summary.canonicalizedFor(failures),
            warnings = warnings?.canonicalWarnings(),
        )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExecutionContext(
    val runtimeJavaFeature: Int,
    val continueOnError: Boolean,
)

enum class WarningCode {
    UNREADABLE_JAR,
    MANIFEST_PARSE_FAILED,
    INVALID_BYTECODE,
    MODULE_INFO_PARSE_FAILED,
    IO_ERROR,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScanWarning(
    val code: WarningCode,
    val message: String,
    /** Absolute, normalized path when known. */
    val path: String? = null,
    /** Jar entry name (or relative path within a directory target) when known. */
    val jarEntry: String? = null,
)

private fun List<ScanWarning>.canonicalWarnings(): List<ScanWarning> =
    this
        .distinct()
        .sortedWith(
            compareBy<ScanWarning> { it.code.name }
                .thenBy { it.path ?: "" }
                .thenBy { it.jarEntry ?: "" }
                .thenBy { it.message },
        )

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolMetadata(
    val name: String,
    val version: String,
    /** E.g. "jvm-linkage-sim-v1" */
    val resolverMode: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Fingerprints(
    /** Fingerprint of the ordered runtime classpath manifest. */
    val classpath: Fingerprint,
    /** Fingerprint of targets (classes dir / jar) inputs. */
    val targets: Fingerprint,
    /** Optional fingerprint of the produced report for integrity checks. */
    val report: Fingerprint? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Fingerprint(
    val algorithm: String,
    val value: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReportSummary(
    val failureCount: Int,
    val failureCountByType: Map<FailureType, Int>,
) {
    internal fun canonicalizedFor(failures: List<LinkageFailure>): ReportSummary {
        val byType = failures.groupingBy { it.type }.eachCount()
        return copy(
            failureCount = failures.size,
            failureCountByType = byType.toSortedMap(compareBy { it.name }),
        )
    }
}

enum class Severity { INFO, WARN, ERROR }

enum class FailureType {
    MISSING_CLASS,
    MISSING_METHOD,
    MISSING_FIELD,
    INCOMPATIBLE_CLASS_CHANGE,
    DESCRIPTOR_MISMATCH,
    ILLEGAL_ACCESS_RISK,
    DUPLICATE_CLASS_SHADOWING,
    SPI_PROVIDER_BROKEN,
    SPI_PROVIDER_TYPE_MISMATCH,
    CLASSPATH_COMPARE_REGRESSION,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LinkageFailure(
    val type: FailureType,
    val severity: Severity,
    val message: String,
    val symbol: SymbolId? = null,
    val referenceSite: ReferenceSite,
    val evidence: Evidence? = null,
    val fixPlan: List<FixPlanItem> = emptyList(),
) {
    fun canonical(): LinkageFailure =
        copy(
            fixPlan = fixPlan.sortedWith(compareBy<FixPlanItem> { it.kind.name }.thenBy { it.value }),
            evidence = evidence?.canonical(),
        )

    companion object {
        /**
         * Canonical ordering for deterministic output.
         *
         * This is intentionally strict and stable: two equivalent reports should produce byte-for-byte
         * identical JSON after [LinkageReport.canonical] + [EnkiduJson.mapper] serialization.
         */
        private fun severityRank(severity: Severity): Int =
            when (severity) {
                Severity.ERROR -> 0
                Severity.WARN -> 1
                Severity.INFO -> 2
            }

        val CANONICAL_ORDER: Comparator<LinkageFailure> =
            compareBy<LinkageFailure> { it.type.name }
                .thenBy { severityRank(it.severity) }
                .thenBy { it.referenceSite.callerClass }
                .thenBy { it.referenceSite.callerMethod }
                .thenBy { it.referenceSite.callerDescriptor }
                .thenBy { it.referenceSite.line ?: Int.MAX_VALUE }
                .thenBy { it.referenceSite.bytecodeOffset ?: Int.MAX_VALUE }
                .thenBy { it.symbol?.owner ?: "" }
                .thenBy { it.symbol?.name ?: "" }
                .thenBy { it.symbol?.descriptor ?: "" }
                .thenBy { it.message }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SymbolId(
    /** Internal name, e.g. "com/foo/Bar" */
    val owner: String,
    val kind: SymbolKind,
    val name: String,
    /** JVM descriptor, e.g. "(Ljava/lang/String;)V" or "Ljava/lang/String;" */
    val descriptor: String,
)

enum class SymbolKind { METHOD, FIELD, TYPE }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReferenceSite(
    /** Internal name, e.g. "com/foo/MyCaller" */
    val callerClass: String,
    /** Method name in the caller. */
    val callerMethod: String,
    /** JVM descriptor of the caller method. */
    val callerDescriptor: String,
    /** Best-effort source line, when debug info is available. */
    val line: Int? = null,
    /** Best-effort bytecode offset (instruction index), when available. */
    val bytecodeOffset: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Evidence(
    val winnerJar: String? = null,
    val shadowedJars: List<String> = emptyList(),
    val missingJarHint: String? = null,
    /** JPMS context when available. */
    val targetModule: String? = null,
    val callerModule: String? = null,
    val packageName: String? = null,
    val exported: Boolean? = null,
    /** Optional SPI context when the failure is ServiceLoader-related. */
    val spi: SpiEvidence? = null,
    /** Optional duplicate-class analysis when the failure is classpath-shadowing related. */
    val duplicate: DuplicateEvidence? = null,
) {
    fun canonical(): Evidence =
        copy(
            shadowedJars = shadowedJars.sorted(),
            spi = spi?.canonical(),
            duplicate = duplicate?.canonical(),
        )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpiEvidence(
    /** Binary name, e.g. "com.example.Service" */
    val service: String,
    /** Binary name, e.g. "com.example.impl.ServiceImpl" */
    val provider: String? = null,
    /** Classpath entries (jar/dir paths) that define META-INF/services/<service> in resolution order. */
    val serviceFileEntries: List<String> = emptyList(),
    /** Classpath entry where the provider class was resolved from (winner), when present. */
    val providerEntry: String? = null,
) {
    fun canonical(): SpiEvidence =
        copy(
            serviceFileEntries = serviceFileEntries.sorted(),
        )
}

enum class DuplicateRiskLevel { BENIGN, LOW, MEDIUM, HIGH, CRITICAL }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DuplicateEvidence(
    /** Binary name, e.g. "com.example.Foo" */
    val className: String,
    /** True when all bytecode blobs are identical (sha256 match). */
    val identicalBytecode: Boolean,
    /** Deterministic 0..100 score. */
    val riskScore: Int,
    val riskLevel: DuplicateRiskLevel,
    /** sha256 per classpath entry path. */
    val hashes: List<JarHash> = emptyList(),
    /** ABI differences between winner and each shadowed copy (public surface only). */
    val abiDifferences: List<DuplicateAbiDifference> = emptyList(),
) {
    fun canonical(): DuplicateEvidence =
        copy(
            hashes = hashes.sortedWith(compareBy<JarHash> { it.entry }.thenBy { it.sha256 }),
            abiDifferences =
                abiDifferences.sortedWith(
                    compareBy<DuplicateAbiDifference> { it.entry }.thenBy { it.kind }.thenBy { it.member },
                ),
        )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JarHash(
    val entry: String,
    val sha256: String,
)

enum class DuplicateAbiDiffKind { SUPER_CHANGED, INTERFACES_CHANGED, METHOD_ADDED, METHOD_REMOVED, FIELD_ADDED, FIELD_REMOVED }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DuplicateAbiDifference(
    /** Shadowed entry path (jar/dir) that differs from winner. */
    val entry: String,
    val kind: DuplicateAbiDiffKind,
    /** Member signature for method/field diffs (name+descriptor). For type-level diffs, this is null. */
    val member: String? = null,
    /** Detail string for type-level diffs. */
    val detail: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FixPlanItem(
    val kind: FixKind,
    val value: String,
    val confidence: Double? = null,
)

enum class FixKind {
    ALIGN_VERSIONS,
    EXCLUDE_JAR,
    UPGRADE_DEPENDENCY,
    DOWNGRADE_DEPENDENCY,
    ADD_MISSING_DEPENDENCY,
    REMOVE_DUPLICATES,
    FIX_SHADING,
    MERGE_SPI,
}
