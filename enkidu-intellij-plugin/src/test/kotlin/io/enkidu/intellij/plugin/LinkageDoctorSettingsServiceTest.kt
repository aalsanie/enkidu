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
package io.enkidu.intellij.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkageDoctorSettingsServiceTest {
    @Test
    fun `defaults are stable`() {
        val s = LinkageDoctorSettingsState()
        assertEquals(null, s.classpathManifestPath)
        assertEquals(OutputFormat.JSON, s.outputFormat)
        assertEquals(FailOnPolicy.ANY, s.failOnPolicy)
    }

    @Test
    fun `state fields can be updated`() {
        val s =
            LinkageDoctorSettingsState(
                classpathManifestPath = "/tmp/cp.txt",
                outputFormat = OutputFormat.SARIF,
                failOnPolicy = FailOnPolicy.ERROR_ONLY,
            )
        assertEquals("/tmp/cp.txt", s.classpathManifestPath)
        assertEquals(OutputFormat.SARIF, s.outputFormat)
        assertEquals(FailOnPolicy.ERROR_ONLY, s.failOnPolicy)
    }
}
