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
package io.enkidu.export

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.enkidu.artifacts.v1.Evidence
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.Fingerprint
import io.enkidu.artifacts.v1.Fingerprints
import io.enkidu.artifacts.v1.FixKind
import io.enkidu.artifacts.v1.FixPlanItem
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.ReferenceSite
import io.enkidu.artifacts.v1.ReportSummary
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.SymbolId
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.artifacts.v1.ToolMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SarifWriterV1SchemaTest {
    @Test
    fun `sarif writer is deterministic and validates against enkidu sarif profile schema`() {
        val report = sampleReport().canonical()

        val bytes1 = EnkiduReportWriters.sarifV1(report)
        val bytes2 = EnkiduReportWriters.sarifV1(report)

        assertEquals(bytes1.toList(), bytes2.toList(), "SARIF output must be byte-for-byte deterministic")

        val schemaNode = readResourceJson("/enkidu-sarif-profile.schema.json")
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schemaNode)
        val sarifNode = readJson(bytes1)
        val errors = schema.validate(sarifNode)

        assertTrue(errors.isEmpty(), "SARIF output must validate. Errors: ${'$'}errors")
    }

    @Test
    fun `sarif rules are sorted and stable`() {
        val report = sampleReport().canonical()
        val sarif = readJson(EnkiduReportWriters.sarifV1(report))

        val rules = sarif["runs"][0]["tool"]["driver"]["rules"].map { it["id"].asText() }
        val sorted = rules.sorted()
        assertEquals(sorted, rules, "rules must be sorted for determinism")
    }

    private fun sampleReport(): LinkageReport {
        val tool = ToolMetadata(name = "enkidu-linkage-doctor", version = "0.1.0", resolverMode = "jvm-linkage-sim-v1")
        val fps =
            Fingerprints(
                classpath = Fingerprint("SHA-256", "cpfp"),
                targets = Fingerprint("SHA-256", "tgtfp"),
                report = null,
            )

        val failures =
            listOf(
                LinkageFailure(
                    type = FailureType.MISSING_METHOD,
                    severity = Severity.ERROR,
                    message = "NoSuchMethodError: demo/Lib.doThing(Ljava/lang/String;)V",
                    symbol =
                        SymbolId(
                            owner = "demo/Lib",
                            kind = SymbolKind.METHOD,
                            name = "doThing",
                            descriptor = "(Ljava/lang/String;)V",
                        ),
                    referenceSite =
                        ReferenceSite(
                            callerClass = "demo/App",
                            callerMethod = "main",
                            callerDescriptor = "([Ljava/lang/String;)V",
                            line = 4,
                            bytecodeOffset = 8,
                        ),
                    evidence =
                        Evidence(
                            winnerJar = "lib-v2.jar",
                            shadowedJars = listOf("lib-v1.jar"),
                            missingJarHint = null,
                        ),
                    fixPlan =
                        listOf(
                            FixPlanItem(kind = FixKind.ALIGN_VERSIONS, value = "align lib versions", confidence = 0.9),
                        ),
                ),
                LinkageFailure(
                    type = FailureType.INCOMPATIBLE_CLASS_CHANGE,
                    severity = Severity.WARN,
                    message = "IncompatibleClassChangeError: demo/Lib was interface at compile time",
                    symbol =
                        SymbolId(
                            owner = "demo/Lib",
                            kind = SymbolKind.TYPE,
                            name = "demo/Lib",
                            descriptor = "Ldemo/Lib;",
                        ),
                    referenceSite =
                        ReferenceSite(
                            callerClass = "demo/App",
                            callerMethod = "main",
                            callerDescriptor = "([Ljava/lang/String;)V",
                            line = 4,
                            bytecodeOffset = 10,
                        ),
                    evidence = Evidence(winnerJar = "lib-v2.jar", shadowedJars = emptyList(), missingJarHint = null),
                    fixPlan = emptyList(),
                ),
            )

        return LinkageReport(
            tool = tool,
            fingerprints = fps,
            summary = ReportSummary(failureCount = failures.size, failureCountByType = mapOf()),
            failures = failures,
        ).canonical()
    }

    private fun readResourceJson(path: String): JsonNode {
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) { "missing resource: ${'$'}path" }
        return io.enkidu.artifacts.v1.EnkiduJson.mapper
            .readTree(stream)
    }

    private fun readJson(bytes: ByteArray): JsonNode =
        io.enkidu.artifacts.v1.EnkiduJson.mapper
            .readTree(bytes)
}
