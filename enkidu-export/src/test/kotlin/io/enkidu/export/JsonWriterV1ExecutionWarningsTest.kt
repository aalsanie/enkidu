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

import io.enkidu.artifacts.v1.ExecutionContext
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.Fingerprint
import io.enkidu.artifacts.v1.Fingerprints
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.ReferenceSite
import io.enkidu.artifacts.v1.ReportSummary
import io.enkidu.artifacts.v1.ScanWarning
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.ToolMetadata
import io.enkidu.artifacts.v1.WarningCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JsonWriterV1ExecutionWarningsTest {
    @Test
    fun `json includes execution context and warnings when present`() {
        val report =
            LinkageReport(
                tool = ToolMetadata(name = "enkidu-linkage-doctor", version = "test", resolverMode = "jvm-linkage-sim-v1"),
                fingerprints =
                    Fingerprints(
                        classpath = Fingerprint("SHA-256", "cpfp"),
                        targets = Fingerprint("SHA-256", "tgtfp"),
                        report = null,
                    ),
                summary = ReportSummary(failureCount = 0, failureCountByType = mapOf()),
                failures =
                    listOf(
                        LinkageFailure(
                            type = FailureType.MISSING_CLASS,
                            severity = Severity.ERROR,
                            message = "NoClassDefFoundError: demo/Missing",
                            symbol = null,
                            referenceSite = ReferenceSite("demo/App", "main", "()V", line = null, bytecodeOffset = null),
                            evidence = null,
                            fixPlan = emptyList(),
                        ),
                    ),
                execution = ExecutionContext(runtimeJavaFeature = 21, continueOnError = true),
                warnings =
                    listOf(
                        ScanWarning(
                            code = WarningCode.INVALID_BYTECODE,
                            message = "Invalid classfile (ASM parse failed)",
                            path = "/abs/cp/bad.jar",
                            jarEntry = "demo/Broken.class",
                        ),
                    ),
            )

        val jsonBytes = EnkiduReportWriters.json(report)
        val node =
            io.enkidu.artifacts.v1.EnkiduJson.mapper
                .readTree(jsonBytes)

        val exec = node["execution"]
        assertNotNull(exec, "execution must be present")
        assertEquals(21, exec["runtimeJavaFeature"].asInt())
        assertEquals(true, exec["continueOnError"].asBoolean())

        val warnings = node["warnings"]
        assertNotNull(warnings, "warnings must be present")
        assertEquals(1, warnings.size())
        assertEquals("INVALID_BYTECODE", warnings[0]["code"].asText())
    }
}
