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
package io.enkidu.core.spi

import io.enkidu.artifacts.v1.Evidence
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.FixKind
import io.enkidu.artifacts.v1.FixPlanItem
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.ReferenceSite
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.SpiEvidence
import io.enkidu.artifacts.v1.SymbolId
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.resolve.ClassResolutionOutcome
import io.enkidu.core.resolve.JvmLinkageResolver
import org.objectweb.asm.Opcodes

/**
 * Validates ServiceLoader descriptors (META-INF/services/x) against the runtime classpath.
 *
 * This catches:
 * - missing service types
 * - missing providers
 * - provider type mismatch (not assignable to service)
 * - provider not instantiable (interface/abstract/no public no-arg ctor)
 *
 * It also emits a WARN when multiple service descriptor resources exist for the same service,
 * which is a common fat-jar packaging landmine unless resources are merged.
 */

class SpiValidator(
    private val snapshot: ClasspathSnapshot,
) {
    fun validate(resolver: JvmLinkageResolver): List<LinkageFailure> {
        val index = ServiceFileIndex(snapshot).index()
        val out = mutableListOf<LinkageFailure>()

        for ((serviceBinaryName, locations) in index) {
            if (locations.size > 1) {
                out.add(
                    LinkageFailure(
                        type = FailureType.SPI_PROVIDER_BROKEN,
                        severity = Severity.WARN,
                        message =
                            "Multiple META-INF/services descriptors found for $serviceBinaryName across the runtime classpath. " +
                                "If you build a fat/uber jar, make sure these resources are merged; otherwise providers can be overwritten/ignored.",
                        symbol =
                            SymbolId(
                                owner = serviceBinaryName.replace('.', '/'),
                                kind = SymbolKind.TYPE,
                                name = serviceBinaryName,
                                descriptor = "L${serviceBinaryName.replace('.', '/')};",
                            ),
                        referenceSite = syntheticSite(serviceBinaryName),
                        evidence =
                            Evidence(
                                spi =
                                    SpiEvidence(
                                        service = serviceBinaryName,
                                        provider = null,
                                        serviceFileEntries = locations.map { it.entryPath.toString() },
                                        providerEntry = null,
                                    ),
                            ),
                        fixPlan =
                            listOf(
                                FixPlanItem(
                                    kind = FixKind.MERGE_SPI,
                                    value =
                                        "Merge META-INF/services/$serviceBinaryName " +
                                            "entries during packaging (shadowJar mergeServiceFiles(), " +
                                            "jarJar, maven-shade resource transformers).",
                                    confidence = 70.0,
                                ),
                            ),
                    ),
                )
            }

            val serviceResolved = resolver.resolveClass(serviceBinaryName)
            if (serviceResolved !is ClassResolutionOutcome.Resolved) {
                // Service type missing: all providers are broken.
                for (loc in locations) {
                    for (provider in loc.providers) {
                        out.add(
                            broken(
                                serviceBinaryName,
                                provider,
                                locations,
                                "Service type $serviceBinaryName is missing on the runtime classpath (required by provider $provider).",
                            ),
                        )
                    }
                }
                continue
            }

            // Validate providers.
            for (loc in locations) {
                for (provider in loc.providers) {
                    val providerResolved = resolver.resolveClass(provider)
                    if (providerResolved !is ClassResolutionOutcome.Resolved) {
                        out.add(
                            broken(
                                serviceBinaryName,
                                provider,
                                locations,
                                "Provider class $provider (for service $serviceBinaryName) is missing on the runtime classpath.",
                            ),
                        )
                        continue
                    }

                    val parsed = providerResolved.parsed
                    val providerEntry = providerResolved.location.entryPath.toString()

                    // Instantiability: interface/abstract/no public no-arg ctor.
                    val isInterface = (parsed.access and Opcodes.ACC_INTERFACE) != 0
                    val isAbstract = (parsed.access and Opcodes.ACC_ABSTRACT) != 0
                    if (isInterface || isAbstract) {
                        out.add(
                            broken(
                                serviceBinaryName,
                                provider,
                                locations,
                                "Provider $provider is not instantiable at runtime (interface/abstract).",
                                providerEntry = providerEntry,
                            ),
                        )
                        continue
                    }

                    val hasPublicNoArgCtor =
                        parsed.methods.entries.any { (sig, def) ->
                            sig.name == "<init>" &&
                                sig.descriptor == "()V" &&
                                (def.access and Opcodes.ACC_PUBLIC) != 0
                        }

                    if (!hasPublicNoArgCtor) {
                        out.add(
                            broken(
                                serviceBinaryName,
                                provider,
                                locations,
                                "Provider $provider does not have a public no-arg constructor, required by ServiceLoader.",
                                providerEntry = providerEntry,
                            ),
                        )
                        continue
                    }

                    // Assignability check.
                    val ok = TypeAssignability.isProviderAssignableToService(provider, serviceBinaryName, resolver)
                    if (!ok) {
                        out.add(
                            LinkageFailure(
                                type = FailureType.SPI_PROVIDER_TYPE_MISMATCH,
                                severity = Severity.ERROR,
                                message =
                                    "Provider $provider is not assignable to service $serviceBinaryName at runtime. " +
                                        "This typically happens when the provider implements " +
                                        "a different service type (shadowed/relocated) or when the service API changed.",
                                symbol =
                                    SymbolId(
                                        owner = serviceBinaryName.replace('.', '/'),
                                        kind = SymbolKind.TYPE,
                                        name = serviceBinaryName,
                                        descriptor = "L${serviceBinaryName.replace('.', '/')};",
                                    ),
                                referenceSite = syntheticSite(serviceBinaryName),
                                evidence =
                                    Evidence(
                                        winnerJar = providerEntry,
                                        spi =
                                            SpiEvidence(
                                                service = serviceBinaryName,
                                                provider = provider,
                                                serviceFileEntries = locations.map { it.entryPath.toString() },
                                                providerEntry = providerEntry,
                                            ),
                                    ),
                                fixPlan =
                                    listOf(
                                        FixPlanItem(
                                            kind = FixKind.ALIGN_VERSIONS,
                                            value =
                                                "Align service API + provider versions " +
                                                    "so $provider implements $serviceBinaryName at runtime.",
                                            confidence = 60.0,
                                        ),
                                        FixPlanItem(
                                            kind = FixKind.REMOVE_DUPLICATES,
                                            value =
                                                "Remove duplicate service API jars so only one " +
                                                    "$serviceBinaryName is present on the runtime classpath.",
                                            confidence = 55.0,
                                        ),
                                        FixPlanItem(
                                            kind = FixKind.FIX_SHADING,
                                            value =
                                                "If shading/relocation is used, " +
                                                    "ensure providers and the service API are relocated consistently.",
                                            confidence = 50.0,
                                        ),
                                    ),
                            ),
                        )
                    }
                }
            }
        }

        return out.sortedWith(LinkageFailure.CANONICAL_ORDER)
    }

    private fun broken(
        service: String,
        provider: String,
        locations: List<ServiceFileIndex.ServiceFileLocation>,
        message: String,
        providerEntry: String? = null,
    ): LinkageFailure =
        LinkageFailure(
            type = FailureType.SPI_PROVIDER_BROKEN,
            severity = Severity.ERROR,
            message = message,
            symbol =
                SymbolId(
                    owner = service.replace('.', '/'),
                    kind = SymbolKind.TYPE,
                    name = service,
                    descriptor = "L${service.replace('.', '/')};",
                ),
            referenceSite = syntheticSite(service),
            evidence =
                Evidence(
                    winnerJar = providerEntry,
                    spi =
                        SpiEvidence(
                            service = service,
                            provider = provider,
                            serviceFileEntries = locations.map { it.entryPath.toString() },
                            providerEntry = providerEntry,
                        ),
                ),
            fixPlan =
                listOf(
                    FixPlanItem(
                        kind = FixKind.ADD_MISSING_DEPENDENCY,
                        value = "Ensure $provider is present on the runtime classpath (and not removed by slimming/proguard/shading).",
                        confidence = 65.0,
                    ),
                    FixPlanItem(
                        kind = FixKind.MERGE_SPI,
                        value = "If packaging into a fat jar, merge META-INF/services/$service resources so providers are not overwritten.",
                        confidence = 55.0,
                    ),
                ),
        )

    private fun syntheticSite(serviceBinaryName: String): ReferenceSite =
        ReferenceSite(
            callerClass = "META-INF/services",
            callerMethod = serviceBinaryName,
            callerDescriptor = "()V",
            line = null,
            bytecodeOffset = null,
        )
}
