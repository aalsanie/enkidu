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
package io.enkidu.core.engine

import io.enkidu.artifacts.v1.Evidence
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.FixPlanItem
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.SymbolId
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.core.fixplan.FixPlannerV1
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.model.JarIndex
import io.enkidu.core.resolve.AccessCheckResult
import io.enkidu.core.resolve.AccessChecker
import io.enkidu.core.resolve.ClassLocation
import io.enkidu.core.resolve.ClassResolutionOutcome
import io.enkidu.core.resolve.FieldResolutionOutcome
import io.enkidu.core.resolve.JvmLinkageResolver
import io.enkidu.core.resolve.MethodResolutionOutcome
import io.enkidu.core.scan.BytecodeReference

/**
 * Maps raw resolver outcomes into user-facing [LinkageFailure]s with evidence.
 *
 * Scope (Milestone E):
 * - classify failures (missing symbols, descriptor mismatch hints, ICCE-style mismatches)
 * - attach jar winner/shadowing evidence when available
 * - enrich messages with a best-effort call-chain summary (direct callsite + optional one-hop callers)
 */
class LinkageFailureClassifier(
    private val snapshot: ClasspathSnapshot,
    private val accessChecker: AccessChecker? = null,
) {
    private val jarIndex: JarIndex = JarIndex.build(snapshot)
    private val fixPlanner: FixPlannerV1 = FixPlannerV1()

    fun classify(
        reference: BytecodeReference,
        resolver: JvmLinkageResolver,
        callGraph: CallGraphIndex? = null,
    ): LinkageFailure? =
        when (reference.symbol.kind) {
            SymbolKind.TYPE -> classifyType(reference, resolver, callGraph)
            SymbolKind.METHOD -> classifyMethod(reference, resolver, callGraph)
            SymbolKind.FIELD -> classifyField(reference, resolver, callGraph)
        }

    private fun classifyType(
        reference: BytecodeReference,
        resolver: JvmLinkageResolver,
        callGraph: CallGraphIndex?,
    ): LinkageFailure? {
        val outcome = resolver.resolveClass(reference.symbol.owner)
        return when (outcome) {
            is ClassResolutionOutcome.Resolved -> null
            is ClassResolutionOutcome.Missing ->
                buildFailure(
                    type = FailureType.MISSING_CLASS,
                    severity = Severity.ERROR,
                    message = "Referenced class ${outcome.binaryName} not present on runtime classpath",
                    symbol = reference.symbol,
                    reference = reference,
                    evidence = evidenceForOwner(outcome.binaryName),
                    callGraph = callGraph,
                )
        }
    }

    private fun classifyMethod(
        reference: BytecodeReference,
        resolver: JvmLinkageResolver,
        callGraph: CallGraphIndex?,
    ): LinkageFailure? {
        val outcome =
            resolver.resolveMethod(
                symbol = reference.symbol,
                opcode = reference.opcode,
                isInterfaceInvocation = reference.isInterfaceInvocation == true,
            )

        return when (outcome) {
            is MethodResolutionOutcome.Resolved -> {
                val callerBinary = reference.site.callerClass.replace('/', '.')
                val access =
                    accessChecker?.checkMethodAccess(
                        callerBinaryName = callerBinary,
                        declaringClass = outcome.declaringClass,
                        signature = outcome.signature,
                    )
                if (access == null) {
                    null
                } else {
                    buildFailure(
                        type = FailureType.ILLEGAL_ACCESS_RISK,
                        severity = Severity.ERROR,
                        message = "Illegal access: ${access.reason} (caller=$callerBinary, target=${access.targetBinaryName})",
                        symbol = reference.symbol,
                        reference = reference,
                        evidence = evidenceForResolvedOwner(outcome.declaringClass, access),
                        callGraph = callGraph,
                    )
                }
            }
            is MethodResolutionOutcome.MissingClass ->
                buildFailure(
                    type = FailureType.MISSING_CLASS,
                    severity = Severity.ERROR,
                    message = "Referenced class ${outcome.symbolOwner} not present on runtime classpath",
                    symbol = reference.symbol,
                    reference = reference,
                    evidence = evidenceForOwner(outcome.symbolOwner),
                    callGraph = callGraph,
                )

            is MethodResolutionOutcome.MissingMethod -> {
                val (t, msg) =
                    missingMemberTypeAndMessage(
                        kind = "method",
                        owner = outcome.symbolOwner,
                        name = outcome.signature.name,
                        descriptor = outcome.signature.descriptor,
                        sameNameDescriptors = outcome.sameNameOtherDescriptors,
                    )
                buildFailure(
                    type = t,
                    severity = Severity.ERROR,
                    message = msg,
                    symbol = reference.symbol,
                    reference = reference,
                    evidence = evidenceForOwner(outcome.symbolOwner),
                    callGraph = callGraph,
                )
            }

            is MethodResolutionOutcome.IncompatibleClassChange ->
                buildFailure(
                    type = FailureType.INCOMPATIBLE_CLASS_CHANGE,
                    severity = Severity.ERROR,
                    message =
                        "Incompatible class change for " +
                            "${outcome.symbolOwner}." +
                            "${outcome.signature.name}${outcome.signature.descriptor}: ${outcome.message}",
                    symbol = reference.symbol,
                    reference = reference,
                    evidence = evidenceForOwner(outcome.symbolOwner),
                    callGraph = callGraph,
                )
        }
    }

    private fun classifyField(
        reference: BytecodeReference,
        resolver: JvmLinkageResolver,
        callGraph: CallGraphIndex?,
    ): LinkageFailure? {
        val outcome = resolver.resolveField(reference.symbol, reference.opcode)
        return when (outcome) {
            is FieldResolutionOutcome.Resolved -> {
                val callerBinary = reference.site.callerClass.replace('/', '.')
                val access =
                    accessChecker?.checkFieldAccess(
                        callerBinaryName = callerBinary,
                        declaringClass = outcome.declaringClass,
                        signature = outcome.signature,
                    )
                if (access == null) {
                    null
                } else {
                    buildFailure(
                        type = FailureType.ILLEGAL_ACCESS_RISK,
                        severity = Severity.ERROR,
                        message = "Illegal access: ${access.reason} (caller=$callerBinary, target=${access.targetBinaryName})",
                        symbol = reference.symbol,
                        reference = reference,
                        evidence = evidenceForResolvedOwner(outcome.declaringClass, access),
                        callGraph = callGraph,
                    )
                }
            }
            is FieldResolutionOutcome.MissingClass ->
                buildFailure(
                    type = FailureType.MISSING_CLASS,
                    severity = Severity.ERROR,
                    message = "Referenced class ${outcome.symbolOwner} not present on runtime classpath",
                    symbol = reference.symbol,
                    reference = reference,
                    evidence = evidenceForOwner(outcome.symbolOwner),
                    callGraph = callGraph,
                )

            is FieldResolutionOutcome.MissingField -> {
                val (t, msg) =
                    missingMemberTypeAndMessage(
                        kind = "field",
                        owner = outcome.symbolOwner,
                        name = outcome.signature.name,
                        descriptor = outcome.signature.descriptor,
                        sameNameDescriptors = outcome.sameNameOtherDescriptors,
                    )
                buildFailure(
                    type = t,
                    severity = Severity.ERROR,
                    message = msg,
                    symbol = reference.symbol,
                    reference = reference,
                    evidence = evidenceForOwner(outcome.symbolOwner),
                    callGraph = callGraph,
                )
            }

            is FieldResolutionOutcome.IncompatibleClassChange ->
                buildFailure(
                    type = FailureType.INCOMPATIBLE_CLASS_CHANGE,
                    severity = Severity.ERROR,
                    message =
                        "Incompatible class change for " +
                            "${outcome.symbolOwner}." +
                            "${outcome.signature.name}:${outcome.signature.descriptor}: ${outcome.message}",
                    symbol = reference.symbol,
                    reference = reference,
                    evidence = evidenceForOwner(outcome.symbolOwner),
                    callGraph = callGraph,
                )
        }
    }

    private fun missingMemberTypeAndMessage(
        kind: String,
        owner: String,
        name: String,
        descriptor: String,
        sameNameDescriptors: List<String>,
    ): Pair<FailureType, String> =
        if (sameNameDescriptors.isNotEmpty()) {
            val hints = sameNameDescriptors.sorted().joinToString(", ")
            Pair(
                FailureType.DESCRIPTOR_MISMATCH,
                "Referenced $kind $owner.$name$descriptor not found, but $owner.$name exists with other descriptors: [$hints]",
            )
        } else {
            val msg: String =
                when (kind) {
                    "method" -> "Referenced method $owner.$name$descriptor not found on runtime type"
                    "field" -> "Referenced field $owner.$name:$descriptor not found on runtime type"
                    else -> "Referenced member $owner.$name$descriptor not found on runtime type"
                }

            val t = if (kind == "field") FailureType.MISSING_FIELD else FailureType.MISSING_METHOD
            Pair(t, msg)
        }

    private fun buildFailure(
        type: FailureType,
        severity: Severity,
        message: String,
        symbol: SymbolId,
        reference: BytecodeReference,
        evidence: Evidence?,
        callGraph: CallGraphIndex?,
    ): LinkageFailure {
        val enriched = enrichWithCallChain(message, reference, callGraph)

        val fixPlan: List<FixPlanItem> = fixPlanner.plan(type = type, symbol = symbol, evidence = evidence)
        return LinkageFailure(
            type = type,
            severity = severity,
            message = enriched,
            symbol = symbol,
            referenceSite = reference.site,
            evidence = evidence,
            fixPlan = fixPlan,
        )
    }

    private fun evidenceForOwner(ownerInternalOrBinary: String): Evidence? {
        val binary = ownerInternalOrBinary.replace('/', '.')
        val locs = jarIndex.allLocationsOf(binary)
        if (locs.isEmpty()) {
            return Evidence(
                winnerJar = null,
                shadowedJars = emptyList(),
                missingJarHint = "Not present on runtime classpath",
            )
        }

        val winner = locs.first().entryPath.toString()
        val shadowed =
            locs
                .drop(1)
                .map { it.entryPath.toString() }
                .distinct()
                .sorted()

        // If there are no shadowed jars and this is a directory, still include winner.
        return Evidence(
            winnerJar = winner,
            shadowedJars = shadowed,
            missingJarHint = null,
        )
    }

    private fun evidenceForResolvedOwner(
        declaringClass: ClassLocation,
        access: AccessCheckResult,
    ): Evidence {
        val locs = jarIndex.allLocationsOf(declaringClass.binaryName)
        val winner = declaringClass.entryPath.toString()
        val shadowed =
            locs
                .map { it.entryPath.toString() }
                .filter { it != winner }
                .distinct()
                .sorted()

        val module = access.moduleContext
        return Evidence(
            winnerJar = winner,
            shadowedJars = shadowed,
            missingJarHint = null,
            targetModule = module?.targetModule,
            callerModule = module?.callerModule,
            packageName = module?.packageName,
            exported = module?.exported,
        )
    }

    private fun enrichWithCallChain(
        base: String,
        reference: BytecodeReference,
        callGraph: CallGraphIndex?,
    ): String {
        val callsite =
            buildString {
                append(reference.site.callerClass)
                append('.')
                append(reference.site.callerMethod)
                append(reference.site.callerDescriptor)
                if (reference.site.line != null) {
                    append(" (line ")
                    append(reference.site.line)
                    append(')')
                }
            }

        val oneHop =
            callGraph
                ?.callersOf(MethodId(reference.site.callerClass, reference.site.callerMethod, reference.site.callerDescriptor))
                ?.take(MAX_ONE_HOP_CALLERS)
                .orEmpty()

        if (oneHop.isEmpty()) {
            return "$base | Callsite: $callsite"
        }

        val callers = oneHop.joinToString("; ") { it.toString() }
        return "$base | Callsite: $callsite | One-hop callers: $callers"
    }

    private companion object {
        const val MAX_ONE_HOP_CALLERS: Int = 3
    }
}
