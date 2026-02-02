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

import io.enkidu.artifacts.v1.EnkiduJson
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.Fingerprint
import io.enkidu.artifacts.v1.Fingerprints
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.ReferenceSite
import io.enkidu.artifacts.v1.ReportSummary
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.ToolMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipFile

class ReproBundleWriterTest {
    @Test
    fun `zip bundle is deterministic and includes required files`() {
        val dir = Files.createTempDirectory("enkidu-bundle-test")

        val cpFile = dir.resolve("dep.jar")
        Files.write(cpFile, "fake-jar".toByteArray(StandardCharsets.UTF_8))

        val targetsDir = dir.resolve("targets")
        Files.createDirectories(targetsDir)
        Files.write(targetsDir.resolve("A.class"), byteArrayOf(1, 2, 3, 4))

        val report = sampleReport()

        val zip1 = dir.resolve("bundle1.zip")
        val zip2 = dir.resolve("bundle2.zip")

        ReproBundleWriter.writeDoctorBundle(
            report = report,
            targets = listOf(targetsDir),
            runtimeClasspath = listOf(cpFile),
            bundleOutput = zip1,
        )

        ReproBundleWriter.writeDoctorBundle(
            report = report,
            targets = listOf(targetsDir),
            runtimeClasspath = listOf(cpFile),
            bundleOutput = zip2,
        )

        val bytes1 = Files.readAllBytes(zip1)
        val bytes2 = Files.readAllBytes(zip2)
        assertEquals(bytes1.size, bytes2.size)
        assertTrue(bytes1.contentEquals(bytes2), "zip bytes should be deterministic")

        ZipFile(zip1.toFile()).use { zf ->
            val names =
                zf
                    .entries()
                    .toList()
                    .map { it.name }
                    .sorted()
            assertEquals(
                listOf(
                    "HOW_TO_REPRODUCE.md",
                    "bundle.json",
                    "classpath.txt",
                    "targets.txt",
                ),
                names,
            )

            val bundleJson = zf.getInputStream(zf.getEntry("bundle.json")).readAllBytes()
            val tree = EnkiduJson.mapper.readTree(bundleJson)
            assertEquals("enkidu-linkage-doctor", tree["toolName"].asText())
            assertEquals("1.2.3", tree["toolVersion"].asText())
            assertTrue(tree["fingerprints"]["classpathContent"].isArray)
            assertTrue(tree["summary"].has("failureCount"))
        }
    }

    private fun sampleReport(): LinkageReport {
        val failure =
            LinkageFailure(
                type = FailureType.MISSING_CLASS,
                severity = Severity.ERROR,
                message = "Missing class: com/example/Missing",
                symbol = null,
                referenceSite =
                    ReferenceSite(
                        callerClass = "com/example/Caller",
                        callerMethod = "call",
                        callerDescriptor = "()V",
                        line = 12,
                        bytecodeOffset = 7,
                    ),
            )

        return LinkageReport(
            tool = ToolMetadata(name = "enkidu-linkage-doctor", version = "1.2.3", resolverMode = "jvm-linkage-sim-v1"),
            fingerprints =
                Fingerprints(
                    classpath = Fingerprint(algorithm = "SHA-256", value = "cp"),
                    targets = Fingerprint(algorithm = "SHA-256", value = "tg"),
                    report = null,
                ),
            summary = ReportSummary(failureCount = 0, failureCountByType = emptyMap()),
            failures = listOf(failure),
        )
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> {
        val out = mutableListOf<T>()
        while (this.hasMoreElements()) out += this.nextElement()
        return out
    }
}
