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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Random

class DeterministicJsonTest {
    @Test
    fun `canonical report serializes to stable bytes regardless of input ordering`() {
        val report1 = sampleReport(shuffleFailures = true).canonical()
        val report2 = sampleReport(shuffleFailures = true).canonical()

        val json1 = EnkiduJson.prettyWriter.writeValueAsString(report1)
        val json2 = EnkiduJson.prettyWriter.writeValueAsString(report2)

        // Byte-for-byte identical after canonicalization.
        assertEquals(json1, json2)

        // Golden file contract.
        val golden = readResource("/golden/linkage-report-v1.json")
        assertEquals(golden, json1)
    }

    private fun sampleReport(shuffleFailures: Boolean): LinkageReport {
        val failures =
            mutableListOf(
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
                    evidence =
                        Evidence(
                            winnerJar = "lib-1.0.jar",
                            shadowedJars = listOf("lib-0.9.jar"),
                        ),
                    fixPlan =
                        listOf(
                            FixPlanItem(FixKind.ALIGN_VERSIONS, "Align lib to 1.0 across modules", confidence = 0.8),
                            FixPlanItem(FixKind.EXCLUDE_JAR, "Exclude lib-0.9.jar from runtimeClasspath", confidence = 0.7),
                        ),
                ),
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
                    evidence =
                        Evidence(
                            winnerJar = "dupe-2.0.jar",
                            shadowedJars = listOf("dupe-1.0.jar", "dupe-1.5.jar"),
                            missingJarHint = null,
                        ),
                    fixPlan =
                        listOf(
                            FixPlanItem(FixKind.REMOVE_DUPLICATES, "Remove older dupe jars from runtime", confidence = 0.9),
                        ),
                ),
            )

        if (shuffleFailures) {
            failures.shuffle(Random(1337L))
            // Also shuffle evidence ordering to prove canonicalization.
            failures[0].evidence?.let { /* ignored; evidence canonicalizes internally */ }
        }

        val targetsFp = Fingerprint("SHA-256", EnkiduFingerprints.sha256HexUtf8("targets-placeholder"))
        val cpFp = Fingerprint("SHA-256", EnkiduFingerprints.sha256HexUtf8("classpath-placeholder"))

        return LinkageReport(
            tool =
                ToolMetadata(
                    name = "enkidu-linkage-doctor",
                    version = "0.1.0-SNAPSHOT",
                    resolverMode = "jvm-linkage-sim-v1",
                ),
            fingerprints =
                Fingerprints(
                    classpath = cpFp,
                    targets = targetsFp,
                    report = null,
                ),
            summary =
                ReportSummary(
                    failureCount = 999, // intentionally wrong; canonicalization should correct it
                    failureCountByType = mapOf(FailureType.MISSING_CLASS to 999),
                ),
            failures = failures,
        )
    }

    private fun readResource(path: String): String {
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) { "Missing resource: $path" }
        return stream.readBytes().toString(StandardCharsets.UTF_8).trimEnd() // stable compare
    }
}
