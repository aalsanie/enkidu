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

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "EnkiduLinkageDoctorSettings", storages = [Storage("enkidu-linkage-doctor.xml")])
class LinkageDoctorSettingsService : PersistentStateComponent<LinkageDoctorSettingsState> {
    private var internalState: LinkageDoctorSettingsState = LinkageDoctorSettingsState()

    override fun getState(): LinkageDoctorSettingsState = internalState

    override fun loadState(state: LinkageDoctorSettingsState) {
        internalState = state
    }

    /**
     * Avoid JVM signature clash with PersistentStateComponent#getState().
     */
    val settings: LinkageDoctorSettingsState
        get() = internalState

    companion object {
        fun get(project: Project): LinkageDoctorSettingsService = project.service()
    }
}

/**
 * Keep all inputs explicit.
 */
data class LinkageDoctorSettingsState(
    var classpathProviderId: String? = null,
    var classpathManifestPath: String? = null,
    var outputFormat: OutputFormat = OutputFormat.JSON,
    var failOnPolicy: FailOnPolicy = FailOnPolicy.ANY,
)
