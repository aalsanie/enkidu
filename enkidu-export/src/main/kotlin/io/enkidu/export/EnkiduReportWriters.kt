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
package io.enkidu.export

import io.enkidu.artifacts.v1.EnkiduJson
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.Severity

/**
 * Exporters are pure transformations: LinkageReport -> bytes.
 *
 * Determinism rule: given a canonical LinkageReport, these writers must emit
 * byte-for-byte identical output.
 */
object EnkiduReportWriters {
    fun json(report: LinkageReport): ByteArray = EnkiduJson.prettyWriter.writeValueAsBytes(report.canonical())

    fun sarifV1(report: LinkageReport): ByteArray = SarifWriterV1.write(report.canonical())
}

/**
 * SARIF 2.1.0 output profile.
 *
 * Notes:
 * - Enkidu's ReferenceSite v1 does not include source file paths, so we emit logical locations.
 * - We still include line/bytecode offset and jar evidence as SARIF properties.
 */
internal object SarifWriterV1 {
    fun write(report: LinkageReport): ByteArray {
        val failures = report.failures
        val ruleIds = failures.map { it.type.name }.distinct().sorted()

        val rules =
            ruleIds.map { id ->
                linkedMapOf(
                    "id" to id,
                    "name" to id,
                    "shortDescription" to linkedMapOf("text" to id),
                )
            }

        val results =
            failures.map { failure ->
                val callsiteFqn =
                    "${failure.referenceSite.callerClass}.${failure.referenceSite.callerMethod}${failure.referenceSite.callerDescriptor}"

                val props =
                    linkedMapOf<String, Any?>(
                        "callerClass" to failure.referenceSite.callerClass,
                        "callerMethod" to failure.referenceSite.callerMethod,
                        "callerDescriptor" to failure.referenceSite.callerDescriptor,
                        "line" to failure.referenceSite.line,
                        "bytecodeOffset" to failure.referenceSite.bytecodeOffset,
                        "symbolOwner" to failure.symbol?.owner,
                        "symbolName" to failure.symbol?.name,
                        "symbolDescriptor" to failure.symbol?.descriptor,
                        "winnerJar" to failure.evidence?.winnerJar,
                        "shadowedJars" to failure.evidence?.shadowedJars?.takeIf { it.isNotEmpty() },
                        "missingJarHint" to failure.evidence?.missingJarHint,
                        "fingerprintClasspath" to report.fingerprints.classpath.value,
                        "fingerprintTargets" to report.fingerprints.targets.value,
                        "resolverMode" to report.tool.resolverMode,
                    ).filterValues { it != null }

                linkedMapOf(
                    "ruleId" to failure.type.name,
                    "level" to sarifLevel(failure.severity),
                    "message" to linkedMapOf("text" to failure.message),
                    "locations" to
                        listOf(
                            linkedMapOf(
                                "logicalLocations" to
                                    listOf(
                                        linkedMapOf(
                                            "fullyQualifiedName" to callsiteFqn,
                                            "kind" to "function",
                                        ),
                                    ),
                            ),
                        ),
                    "properties" to props,
                )
            }

        val run =
            linkedMapOf(
                "tool" to
                    linkedMapOf(
                        "driver" to
                            linkedMapOf(
                                "name" to report.tool.name,
                                "version" to report.tool.version,
                                "rules" to rules,
                            ),
                    ),
                "results" to results,
                "properties" to
                    linkedMapOf(
                        "toolName" to report.tool.name,
                        "toolVersion" to report.tool.version,
                        "resolverMode" to report.tool.resolverMode,
                        "fingerprintClasspath" to report.fingerprints.classpath.value,
                        "fingerprintTargets" to report.fingerprints.targets.value,
                    ),
            )

        val sarif =
            linkedMapOf(
                "version" to "2.1.0",
                "\$schema" to "https://json.schemastore.org/sarif-2.1.0.json",
                "runs" to listOf(run),
            )

        return EnkiduJson.prettyWriter.writeValueAsBytes(sarif)
    }

    private fun sarifLevel(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> "error"
            Severity.WARN -> "warning"
            Severity.INFO -> "note"
        }
}
