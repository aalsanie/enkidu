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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class HtmlWriterV1SnapshotTest {
    @Test
    fun `html v1 output is stable`() {
        val report = sampleReport()

        val actual = EnkiduReportWriters.htmlV1(report).toString(StandardCharsets.UTF_8)
        val expected = loadResource("html/html-writer-v1-snapshot.html")

        assertEquals(expected, actual)
    }

    private fun loadResource(path: String): String {
        val bytes =
            this::class.java.classLoader
                .getResourceAsStream(path)
                ?.use { it.readBytes() }
                ?: error("missing test resource: $path")
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun sampleReport(): LinkageReport {
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
                            line = 7,
                            bytecodeOffset = 12,
                        ),
                    evidence =
                        Evidence(
                            winnerJar = "cp/lib-1.0.jar",
                            shadowedJars = listOf("cp/lib-0.9.jar"),
                            missingJarHint = null,
                        ),
                    fixPlan =
                        listOf(
                            FixPlanItem(
                                kind = FixKind.ALIGN_VERSIONS,
                                value = "Align lib to 1.0 across the runtime classpath",
                                confidence = 0.9,
                            ),
                        ),
                ),
                LinkageFailure(
                    type = FailureType.MISSING_CLASS,
                    severity = Severity.ERROR,
                    message = "NoClassDefFoundError: demo/Missing",
                    symbol =
                        SymbolId(
                            owner = "demo/Missing",
                            kind = SymbolKind.TYPE,
                            name = "demo/Missing",
                            descriptor = "Ldemo/Missing;",
                        ),
                    referenceSite =
                        ReferenceSite(
                            callerClass = "demo/App",
                            callerMethod = "main",
                            callerDescriptor = "([Ljava/lang/String;)V",
                            line = 8,
                            bytecodeOffset = 18,
                        ),
                    evidence =
                        Evidence(
                            winnerJar = null,
                            shadowedJars = emptyList(),
                            missingJarHint = "cp/missing.jar",
                        ),
                    fixPlan =
                        listOf(
                            FixPlanItem(
                                kind = FixKind.ADD_MISSING_DEPENDENCY,
                                value = "Add cp/missing.jar to the runtime classpath",
                                confidence = 0.8,
                            ),
                        ),
                ),
            )

        return LinkageReport(
            tool = ToolMetadata(name = "enkidu-linkage-doctor", version = "test", resolverMode = "jvm-linkage-sim-v1"),
            fingerprints =
                Fingerprints(
                    classpath = Fingerprint(algorithm = "SHA-256", value = "classpath-fp"),
                    targets = Fingerprint(algorithm = "SHA-256", value = "targets-fp"),
                    report = null,
                ),
            summary = ReportSummary(failureCount = 0, failureCountByType = emptyMap()),
            failures = failures,
        )
    }
}
