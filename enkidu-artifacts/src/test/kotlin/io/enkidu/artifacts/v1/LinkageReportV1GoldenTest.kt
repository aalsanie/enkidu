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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-file contract tests for Enkidu artifacts v1.
 */
class LinkageReportV1GoldenTest {
    @Test
    fun `linkage report v1 json is deterministic and matches golden`() {
        val report =
            LinkageReport(
                tool = ToolMetadata(name = "enkidu-linkage-doctor", version = "0.1.0-SNAPSHOT", resolverMode = "jvm-linkage-sim-v1"),
                fingerprints =
                    Fingerprints(
                        classpath = Fingerprint("SHA-256", "23a01d813e83299b511f330b7de645113c7328a5ad758363bfe88d1839e948e0"),
                        targets = Fingerprint("SHA-256", "19448f113494a4fb9c8628f915d5975358d5f78a8d0f1549e77d6cc1da3e4a91"),
                    ),
                summary = ReportSummary(failureCount = 0, failureCountByType = emptyMap()),
                failures =
                    listOf(
                        LinkageFailure(
                            type = FailureType.DUPLICATE_CLASS_SHADOWING,
                            severity = Severity.WARN,
                            message = "Duplicate class found; runtime winner may be ABI-incompatible.",
                            symbol =
                                SymbolId(
                                    owner = "com/example/Dupe",
                                    kind = SymbolKind.TYPE,
                                    name = "com/example/Dupe",
                                    descriptor = "Lcom/example/Dupe;",
                                ),
                            referenceSite =
                                ReferenceSite(
                                    callerClass = "com/example/App",
                                    callerMethod = "init",
                                    callerDescriptor = "()V",
                                    line = 7,
                                    bytecodeOffset = 3,
                                ),
                            evidence = Evidence(winnerJar = "dupe-2.0.jar", shadowedJars = listOf("dupe-1.0.jar", "dupe-1.5.jar")),
                            fixPlan =
                                listOf(
                                    FixPlanItem(
                                        kind = FixKind.REMOVE_DUPLICATES,
                                        value = "Remove older dupe jars from runtime",
                                        confidence = 0.9,
                                    ),
                                ),
                        ),
                        LinkageFailure(
                            type = FailureType.MISSING_METHOD,
                            severity = Severity.ERROR,
                            message = "Referenced method not found on resolved runtime type.",
                            symbol =
                                SymbolId(
                                    owner = "com/example/Lib",
                                    kind = SymbolKind.METHOD,
                                    name = "doThing",
                                    descriptor = "(Ljava/lang/String;)V",
                                ),
                            referenceSite =
                                ReferenceSite(
                                    callerClass = "com/example/App",
                                    callerMethod = "main",
                                    callerDescriptor = "([Ljava/lang/String;)V",
                                    line = 12,
                                    bytecodeOffset = 34,
                                ),
                            evidence = Evidence(winnerJar = "lib-1.0.jar", shadowedJars = listOf("lib-0.9.jar")),
                            fixPlan =
                                listOf(
                                    FixPlanItem(kind = FixKind.ALIGN_VERSIONS, value = "Align lib to 1.0 across modules", confidence = 0.8),
                                    FixPlanItem(
                                        kind = FixKind.EXCLUDE_JAR,
                                        value = "Exclude lib-0.9.jar from runtimeClasspath",
                                        confidence = 0.7,
                                    ),
                                ),
                        ),
                    ),
            ).canonical()

        val actual =
            EnkiduJson.prettyWriter
                .writeValueAsString(report)
                .canonicalizeForGolden()

        val golden =
            requireNotNull(javaClass.classLoader.getResource("golden/linkage-report-v1.json"))
                .readText()
                .canonicalizeForGolden()

        assertEquals(golden, actual)
    }

    /**
     * Golden files are often polluted by invisible differences:
     * - UTF-8 BOM (especially on Windows)
     * - line endings
     * - trailing whitespace
     * - trailing newline at EOF
     *
     * We canonicalize both sides to keep the test strict on *structure/content* while ignoring those artifacts.
     */
    private fun String.canonicalizeForGolden(): String {
        val noBom = removePrefix("\uFEFF")
        val lf = noBom.replace("\r\n", "\n")
        val noTrailingSpaces = lf.lines().joinToString("\n") { it.trimEnd() }
        return noTrailingSpaces.trimEnd()
    }
}
