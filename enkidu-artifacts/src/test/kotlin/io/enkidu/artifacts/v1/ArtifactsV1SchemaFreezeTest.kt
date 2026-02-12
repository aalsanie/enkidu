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

import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtifactsV1SchemaFreezeTest {
    @Test
    fun `artifacts v1 schema is frozen`() {
        val actual = normalizeNewlines(ArtifactsV1SchemaSignature.generate()).trimEnd()
        val expected =
            normalizeNewlines(
                javaClass.getResource("/schema/artifacts-v1.schema.sig")?.readText()
                    ?: error("Missing schema snapshot resource: /schema/artifacts-v1.schema.sig"),
            ).trimEnd()

        assertEquals(expected, actual)
    }

    private fun normalizeNewlines(s: String): String = s.replace("\r\n", "\n").replace("\r", "\n")

    private object ArtifactsV1SchemaSignature {
        private val V1_SOURCES: List<java.nio.file.Path> =
            listOf(
                Path("src/main/kotlin/io/enkidu/artifacts/v1/ReportV1.kt"),
                Path("src/main/kotlin/io/enkidu/artifacts/v1/CompareV1.kt"),
                Path("src/main/kotlin/io/enkidu/artifacts/v1/EnkiduFingerprints.kt"),
                Path("src/main/kotlin/io/enkidu/artifacts/v1/EnkiduJson.kt"),
            )

        fun generate(): String {
            val lines = mutableListOf<String>()
            for (p in V1_SOURCES) {
                val src =
                    try {
                        p.readText()
                    } catch (e: Exception) {
                        error("Failed to read artifacts source file $p. Tests must run from repo root. Cause: ${e.message}")
                    }
                val cleaned = stripComments(src)

                extractDataClasses(cleaned).forEach { (name, params) ->
                    lines += "data class $name(${normalize(params)})"
                }
                extractEnums(cleaned).forEach { (name, constants) ->
                    lines += "enum class $name{${constants.joinToString(", ")}}"
                }
            }

            val body = lines.sorted().joinToString(separator = "\n", postfix = "\n")
            val sha = sha256Hex(body)
            return "# sha256: $sha\n$body"
        }

        private fun stripComments(src: String): String =
            src
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
                .replace(Regex("//.*"), "")

        private fun normalize(params: String): String = params.split(Regex("\\s+")).joinToString(" ").trim()

        private fun extractDataClasses(src: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            val re = Regex("\\bdata\\s+class\\s+([A-Za-z0-9_]+)\\s*\\(")
            for (m in re.findAll(src)) {
                val name = m.groupValues[1]
                val start = m.range.last
                var depth = 0
                var i = start
                while (i < src.length) {
                    val ch = src[i]
                    if (ch == '(') depth++
                    if (ch == ')') {
                        depth--
                        if (depth == 0) {
                            val params = src.substring(start + 1, i)
                            out += name to params
                            break
                        }
                    }
                    i++
                }
            }
            return out
        }

        private fun extractEnums(src: String): List<Pair<String, List<String>>> {
            val out = mutableListOf<Pair<String, List<String>>>()
            val re = Regex("\\benum\\s+class\\s+([A-Za-z0-9_]+)\\s*\\{")
            for (m in re.findAll(src)) {
                val name = m.groupValues[1]
                val start = m.range.last + 1
                var depth = 1
                var i = start
                while (i < src.length) {
                    val ch = src[i]
                    if (ch == '{') depth++
                    if (ch == '}') {
                        depth--
                        if (depth == 0) {
                            val body = src.substring(start, i)
                            val head = body.substringBefore(";")
                            val constants =
                                head
                                    .split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                            out += name to constants
                            break
                        }
                    }
                    i++
                }
            }
            return out
        }

        private fun sha256Hex(body: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(body.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
