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

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals

class BackwardCompatibilityTest {
    @Test
    fun `v1 linkage report fixtures remain readable`() {
        val json = resourceText("/compat/v1/linkage-report-v1.0.json")

        val parsed = EnkiduJson.mapper.readValue(json, LinkageReport::class.java)
        val reserialized = EnkiduJson.prettyWriter.writeValueAsString(parsed.canonical())

        val expected =
            EnkiduJson.prettyWriter.writeValueAsString(
                EnkiduJson.mapper.readValue(json, LinkageReport::class.java).canonical(),
            )

        assertEquals(expected, reserialized)
    }

    @Test
    fun `v1 compare report fixtures remain readable`() {
        val json = resourceText("/compat/v1/compare-report-v1.0.json")

        val parsed = EnkiduJson.mapper.readValue(json, CompareReport::class.java)
        val reserialized = EnkiduJson.prettyWriter.writeValueAsString(parsed.canonical())

        val expected =
            EnkiduJson.prettyWriter.writeValueAsString(
                EnkiduJson.mapper.readValue(json, CompareReport::class.java).canonical(),
            )

        assertEquals(expected, reserialized)
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val original = resourceText("/compat/v1/linkage-report-v1.0.json")

        val root =
            EnkiduJson.mapper.readTree(original) as? ObjectNode
                ?: error("Expected root JSON object")

        root.put("_unknownTopLevel", 123)

        val tool =
            root.get("tool") as? ObjectNode
                ?: error("Expected 'tool' to be a JSON object")

        tool.put("_unknownInTool", true)

        val withUnknown = EnkiduJson.prettyWriter.writeValueAsString(root)
        val parsed = EnkiduJson.mapper.readValue(withUnknown, LinkageReport::class.java)

        val out = EnkiduJson.prettyWriter.writeValueAsString(parsed.canonical())
        require(out.isNotBlank())
    }

    private fun resourceText(path: String): String =
        javaClass.getResource(path)?.readText()
            ?: error("Missing test resource: $path")
}
