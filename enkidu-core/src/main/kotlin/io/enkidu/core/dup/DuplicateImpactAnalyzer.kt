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
package io.enkidu.core.dup

import io.enkidu.artifacts.v1.DuplicateAbiDiffKind
import io.enkidu.artifacts.v1.DuplicateAbiDifference
import io.enkidu.artifacts.v1.DuplicateEvidence
import io.enkidu.artifacts.v1.DuplicateRiskLevel
import io.enkidu.artifacts.v1.Evidence
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.FixKind
import io.enkidu.artifacts.v1.FixPlanItem
import io.enkidu.artifacts.v1.JarHash
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.ReferenceSite
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.SymbolId
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.core.model.JarIndex
import io.enkidu.core.resolve.ClassfileParser
import io.enkidu.core.resolve.ParsedClass
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.math.min

/**
 * Duplicate-class impact analysis.
 *
 * It ranks duplicates as benign vs dangerous by:
 * - bytecode equality (sha256)
 * - public ABI surface differences (methods/fields) + super/interfaces changes
 * - whether the duplicated class is actually referenced by scanned targets
 */
class DuplicateImpactAnalyzer(
    private val jarIndex: JarIndex,
) {
    fun analyze(referencedBinaryClasses: Set<String>): List<LinkageFailure> {
        val out = mutableListOf<LinkageFailure>()

        for ((className, dup) in jarIndex.duplicates()) {
            val locations = listOf(dup.winner) + dup.shadowed
            val bytesByEntry = locations.associate { it.entryPath.toString() to loadClassBytes(it) }

            val hashes =
                bytesByEntry
                    .map { (entry, bytes) ->
                        JarHash(entry = entry, sha256 = sha256(bytes))
                    }.sortedWith(compareBy<JarHash> { it.entry }.thenBy { it.sha256 })

            val uniqueHashes = hashes.map { it.sha256 }.distinct()
            val identical = uniqueHashes.size == 1

            val referenced = referencedBinaryClasses.contains(className)
            val (diffs, abiDiffPresent) =
                if (identical) {
                    Pair(emptyList(), false)
                } else {
                    val winnerBytes = bytesByEntry[dup.winner.entryPath.toString()]!!
                    val winnerParsed = ClassfileParser.parse(winnerBytes)
                    val allDiffs = mutableListOf<DuplicateAbiDifference>()
                    var anyAbi = false

                    for (shadow in dup.shadowed) {
                        val shadowBytes = bytesByEntry[shadow.entryPath.toString()]!!
                        val shadowParsed = ClassfileParser.parse(shadowBytes)
                        val d = abiDiff(winnerParsed, shadowParsed, shadow.entryPath.toString())
                        if (d.isNotEmpty()) anyAbi = true
                        allDiffs.addAll(d)
                    }
                    Pair(
                        allDiffs.sortedWith(
                            compareBy<DuplicateAbiDifference> { it.entry }.thenBy { it.kind.name }.thenBy { it.member ?: "" }.thenBy {
                                it.detail
                                    ?: ""
                            },
                        ),
                        anyAbi,
                    )
                }

            val score = riskScore(identical, abiDiffPresent, referenced, dup.shadowed.size)
            val level = riskLevel(score)

            val severity =
                when (level) {
                    DuplicateRiskLevel.BENIGN -> Severity.INFO
                    DuplicateRiskLevel.LOW -> Severity.WARN
                    DuplicateRiskLevel.MEDIUM -> Severity.WARN
                    DuplicateRiskLevel.HIGH -> Severity.ERROR
                    DuplicateRiskLevel.CRITICAL -> Severity.ERROR
                }

            val msg =
                buildString {
                    append("Duplicate class on runtime classpath: ")
                    append(className)
                    append(". Winner: ")
                    append(dup.winner.entryPath)
                    append(". Shadowed copies: ")
                    append(dup.shadowed.size)
                    append(". Risk: ")
                    append(level.name)
                    append(" (")
                    append(score)
                    append("/100).")
                    if (identical) {
                        append(" All bytecode blobs are identical.")
                    } else {
                        append(" Bytecode differs between winner and at least one shadowed copy.")
                    }
                    if (referenced) append(" This class is referenced by scanned bytecode.")
                }

            out.add(
                LinkageFailure(
                    type = FailureType.DUPLICATE_CLASS_SHADOWING,
                    severity = severity,
                    message = msg,
                    symbol =
                        SymbolId(
                            owner = className.replace('.', '/'),
                            kind = SymbolKind.TYPE,
                            name = className,
                            descriptor = "L${className.replace('.', '/')};",
                        ),
                    referenceSite = syntheticSite(className),
                    evidence =
                        Evidence(
                            winnerJar = dup.winner.entryPath.toString(),
                            shadowedJars = dup.shadowed.map { it.entryPath.toString() },
                            duplicate =
                                DuplicateEvidence(
                                    className = className,
                                    identicalBytecode = identical,
                                    riskScore = score,
                                    riskLevel = level,
                                    hashes = hashes,
                                    abiDifferences = diffs,
                                ),
                        ),
                    fixPlan =
                        listOf(
                            FixPlanItem(
                                kind = FixKind.REMOVE_DUPLICATES,
                                value = "Remove duplicate copies of $className from the runtime classpath so the winner is unambiguous.",
                                confidence =
                                    when (severity) {
                                        Severity.ERROR -> 80.0
                                        Severity.WARN -> 70.0
                                        Severity.INFO -> 50.0
                                    },
                            ),
                            FixPlanItem(
                                kind = FixKind.ALIGN_VERSIONS,
                                value = "Align versions of dependencies that ship $className to avoid ABI skew between duplicates.",
                                confidence =
                                    when (severity) {
                                        Severity.ERROR -> 70.0
                                        Severity.WARN -> 55.0
                                        Severity.INFO -> 40.0
                                    },
                            ),
                        ),
                ),
            )
        }

        return out.sortedWith(LinkageFailure.CANONICAL_ORDER)
    }

    private fun abiDiff(
        winner: ParsedClass,
        shadow: ParsedClass,
        shadowEntry: String,
    ): List<DuplicateAbiDifference> {
        val out = mutableListOf<DuplicateAbiDifference>()

        if (winner.superBinaryName != shadow.superBinaryName) {
            out.add(
                DuplicateAbiDifference(
                    entry = shadowEntry,
                    kind = DuplicateAbiDiffKind.SUPER_CHANGED,
                    detail = "winner super=${winner.superBinaryName} shadow super=${shadow.superBinaryName}",
                ),
            )
        }

        val wi = winner.interfaces.toSet()
        val si = shadow.interfaces.toSet()
        if (wi != si) {
            out.add(
                DuplicateAbiDifference(
                    entry = shadowEntry,
                    kind = DuplicateAbiDiffKind.INTERFACES_CHANGED,
                    detail = "winner=${winner.interfaces.sorted()} shadow=${shadow.interfaces.sorted()}",
                ),
            )
        }

        val winnerPubMethods = publicMethods(winner)
        val shadowPubMethods = publicMethods(shadow)

        val addedM = shadowPubMethods.minus(winnerPubMethods).sorted()
        val removedM = winnerPubMethods.minus(shadowPubMethods).sorted()

        for (m in addedM) {
            out.add(DuplicateAbiDifference(entry = shadowEntry, kind = DuplicateAbiDiffKind.METHOD_ADDED, member = m))
        }
        for (m in removedM) {
            out.add(DuplicateAbiDifference(entry = shadowEntry, kind = DuplicateAbiDiffKind.METHOD_REMOVED, member = m))
        }

        val winnerPubFields = publicFields(winner)
        val shadowPubFields = publicFields(shadow)

        val addedF = shadowPubFields.minus(winnerPubFields).sorted()
        val removedF = winnerPubFields.minus(shadowPubFields).sorted()

        for (f in addedF) {
            out.add(DuplicateAbiDifference(entry = shadowEntry, kind = DuplicateAbiDiffKind.FIELD_ADDED, member = f))
        }
        for (f in removedF) {
            out.add(DuplicateAbiDifference(entry = shadowEntry, kind = DuplicateAbiDiffKind.FIELD_REMOVED, member = f))
        }

        return out
    }

    private fun publicMethods(c: ParsedClass): Set<String> =
        c.methods.entries
            .filter { (_, def) -> (def.access and Opcodes.ACC_PUBLIC) != 0 }
            .map { (sig, _) -> sig.name + sig.descriptor }
            .toSet()

    private fun publicFields(c: ParsedClass): Set<String> =
        c.fields.entries
            .filter { (_, def) -> (def.access and Opcodes.ACC_PUBLIC) != 0 }
            .map { (sig, _) -> sig.name + ":" + sig.descriptor }
            .toSet()

    private fun loadClassBytes(loc: JarIndex.ClassLocation): ByteArray {
        val resource = loc.className.replace('.', '/') + ".class"
        return if (loc.entryPath.toString().endsWith(".jar")) {
            JarFile(loc.entryPath.toFile()).use { jar ->
                val e = jar.getJarEntry(resource) ?: error("Class $resource not found in ${loc.entryPath}")
                jar.getInputStream(e).use { it.readBytes() }
            }
        } else {
            val f = loc.entryPath.resolve(resource)
            require(Files.isRegularFile(f)) { "Class $resource not found under ${loc.entryPath}" }
            Files.readAllBytes(f)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun riskScore(
        identical: Boolean,
        abiDiff: Boolean,
        referenced: Boolean,
        shadowedCount: Int,
    ): Int {
        if (identical) return 0
        var score = 10
        if (abiDiff) score += 40
        if (referenced) score += 25
        score += min(15, shadowedCount * 5)
        return score.coerceIn(0, 100)
    }

    private fun riskLevel(score: Int): DuplicateRiskLevel =
        when {
            score <= 0 -> DuplicateRiskLevel.BENIGN
            score <= 25 -> DuplicateRiskLevel.LOW
            score <= 55 -> DuplicateRiskLevel.MEDIUM
            score <= 80 -> DuplicateRiskLevel.HIGH
            else -> DuplicateRiskLevel.CRITICAL
        }

    private fun syntheticSite(classBinaryName: String): ReferenceSite =
        ReferenceSite(
            callerClass = classBinaryName.replace('.', '/'),
            callerMethod = "<classpath>",
            callerDescriptor = "()V",
            line = null,
            bytecodeOffset = null,
        )
}
