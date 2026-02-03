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
package io.enkidu.core.resolve

import io.enkidu.artifacts.v1.SymbolId
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.core.model.ClasspathSnapshot
import io.enkidu.core.util.WarningCode
import io.enkidu.core.util.WarningCollector
import org.objectweb.asm.Opcodes
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM-ish resolver for predicting linkage outcomes.
 *
 * This is deliberately conservative: when unsure, it prefers returning a deterministic failure
 * rather than guessing.
 */
class JvmLinkageResolver(
    private val snapshot: ClasspathSnapshot,
    private val runtimeJavaFeature: Int = Runtime.version().feature(),
    private val continueOnError: Boolean = false,
    private val warnings: WarningCollector? = null,
) : Closeable {
    private val loader = ClasspathBytecodeLoader(snapshot, runtimeJavaFeature = runtimeJavaFeature, warnings = warnings)
    private val parsedCache = ConcurrentHashMap<String, ClassResolutionOutcome>()

    override fun close() {
        loader.close()
    }

    fun resolveClass(binaryName: String): ClassResolutionOutcome =
        parsedCache.computeIfAbsent(binaryName) {
            val located = loader.findClass(binaryName) ?: return@computeIfAbsent ClassResolutionOutcome.Missing(binaryName)
            try {
                val parsed = ClassfileParser.parse(located.bytes)
                ClassResolutionOutcome.Resolved(parsed, located.location)
            } catch (e: Exception) {
                warnings?.warn(
                    code = WarningCode.INVALID_BYTECODE,
                    message = "Failed to parse runtime class bytes: ${e.javaClass.simpleName}: ${e.message}",
                    path = located.location.entryPath,
                    jarEntry = located.location.jarEntry,
                )
                if (!continueOnError) {
                    throw e
                }
                ClassResolutionOutcome.Unparseable(
                    binaryName = binaryName,
                    location = located.location,
                    message = e.message ?: e.javaClass.simpleName,
                )
            }
        }

    fun resolveMethod(
        symbol: SymbolId,
        opcode: Int,
        isInterfaceInvocation: Boolean,
    ): MethodResolutionOutcome {
        require(symbol.kind == SymbolKind.METHOD) { "resolveMethod expects SymbolKind.METHOD but got ${symbol.kind}" }

        val signature = MemberSig(name = symbol.name, descriptor = symbol.descriptor)
        val owner = symbol.owner
        val ownerRes = resolveClass(owner)
        val ownerParsed =
            (ownerRes as? ClassResolutionOutcome.Resolved)?.parsed
                ?: return MethodResolutionOutcome.MissingClass(owner, signature)
        val ownerLoc = (ownerRes as ClassResolutionOutcome.Resolved).location

        // Class vs interface invocation mismatch is an ICCE-class failure.
        if (isInterfaceInvocation || opcode == Opcodes.INVOKEINTERFACE) {
            if (!ownerParsed.isInterface) {
                return MethodResolutionOutcome.IncompatibleClassChange(
                    symbolOwner = owner,
                    signature = signature,
                    message = "invokeinterface against non-interface ${ownerLoc.binaryName}",
                )
            }
        } else {
            // For invokevirtual/invokespecial against an interface owner, treat as mismatch.
            if (ownerParsed.isInterface && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL)) {
                return MethodResolutionOutcome.IncompatibleClassChange(
                    symbolOwner = owner,
                    signature = signature,
                    message = "${opcodeName(opcode)} against interface ${ownerLoc.binaryName}",
                )
            }
        }

        val lookup = methodLookup(ownerParsed, signature, opcode)
        return when (lookup) {
            is LookupOutcome.Found -> {
                // Static ↔ instance mismatch is a classic ICCE-class failure.
                //
                // - invokestatic requires a static method
                // - invokevirtual/invokeinterface/invokespecial require a non-static method
                val def = lookup.parsed.methods[signature]
                if (def != null) {
                    val expectsStatic = opcode == Opcodes.INVOKESTATIC
                    val expectsInstance =
                        opcode == Opcodes.INVOKEVIRTUAL ||
                            opcode == Opcodes.INVOKEINTERFACE ||
                            opcode == Opcodes.INVOKESPECIAL

                    if (expectsStatic && !def.isStatic) {
                        return MethodResolutionOutcome.IncompatibleClassChange(
                            symbolOwner = owner,
                            signature = signature,
                            message =
                                "invokestatic against instance method " +
                                    "${lookup.location.binaryName}.${signature.name}${signature.descriptor}",
                        )
                    }
                    if (expectsInstance && def.isStatic) {
                        return MethodResolutionOutcome.IncompatibleClassChange(
                            symbolOwner = owner,
                            signature = signature,
                            message =
                                "${opcodeName(opcode)} against static method " +
                                    "${lookup.location.binaryName}.${signature.name}${signature.descriptor}",
                        )
                    }
                }
                MethodResolutionOutcome.Resolved(
                    symbolOwner = owner,
                    declaringClass = lookup.location,
                    signature = signature,
                    isInterfaceDeclaringClass = lookup.parsed.isInterface,
                )
            }

            is LookupOutcome.Missing -> {
                MethodResolutionOutcome.MissingMethod(
                    symbolOwner = owner,
                    signature = signature,
                    sameNameOtherDescriptors = lookup.sameNameOtherDescriptors,
                )
            }

            is LookupOutcome.Incompatible -> {
                MethodResolutionOutcome.IncompatibleClassChange(
                    symbolOwner = owner,
                    signature = signature,
                    message = lookup.message,
                )
            }
        }
    }

    fun resolveField(
        symbol: SymbolId,
        opcode: Int,
    ): FieldResolutionOutcome {
        require(symbol.kind == SymbolKind.FIELD) { "resolveField expects SymbolKind.FIELD but got ${symbol.kind}" }

        val signature = MemberSig(name = symbol.name, descriptor = symbol.descriptor)
        val owner = symbol.owner
        val ownerRes = resolveClass(owner)
        val ownerParsed =
            (ownerRes as? ClassResolutionOutcome.Resolved)?.parsed
                ?: return FieldResolutionOutcome.MissingClass(owner, signature)

        val lookup = fieldLookup(ownerParsed, signature)
        return when (lookup) {
            is LookupOutcome.Found -> {
                val isStatic = (lookup.parsed.fields[signature]?.access ?: 0) and Opcodes.ACC_STATIC != 0
                val wantsStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
                val wantsInstance = opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD

                if (wantsStatic && !isStatic) {
                    return FieldResolutionOutcome.IncompatibleClassChange(
                        symbolOwner = owner,
                        signature = signature,
                        message = "${opcodeName(opcode)} on non-static field ${lookup.location.binaryName}.${signature.name}",
                    )
                }
                if (wantsInstance && isStatic) {
                    return FieldResolutionOutcome.IncompatibleClassChange(
                        symbolOwner = owner,
                        signature = signature,
                        message = "${opcodeName(opcode)} on static field ${lookup.location.binaryName}.${signature.name}",
                    )
                }

                FieldResolutionOutcome.Resolved(
                    symbolOwner = owner,
                    declaringClass = lookup.location,
                    signature = signature,
                    isStatic = isStatic,
                )
            }

            is LookupOutcome.Missing ->
                FieldResolutionOutcome.MissingField(
                    symbolOwner = owner,
                    signature = signature,
                    sameNameOtherDescriptors = lookup.sameNameOtherDescriptors,
                )

            is LookupOutcome.Incompatible ->
                FieldResolutionOutcome.IncompatibleClassChange(
                    symbolOwner = owner,
                    signature = signature,
                    message = lookup.message,
                )
        }
    }

    private sealed interface LookupOutcome {
        data class Found(
            val parsed: ParsedClass,
            val location: ClassLocation,
        ) : LookupOutcome

        data class Missing(
            val sameNameOtherDescriptors: List<String>,
        ) : LookupOutcome

        data class Incompatible(
            val message: String,
        ) : LookupOutcome
    }

    private fun methodLookup(
        start: ParsedClass,
        signature: MemberSig,
        opcode: Int,
    ): LookupOutcome {
        val sameNameDescriptors = linkedSetOf<String>()

        // Special-case constructors: only in the owner.
        if (signature.name == "<init>") {
            val hit = start.methods[signature]
            if (hit != null) {
                val loc = (resolveClass(start.binaryName) as ClassResolutionOutcome.Resolved).location
                return LookupOutcome.Found(start, loc)
            }
            collectSameName(start.methods, signature.name, sameNameDescriptors)
            return LookupOutcome.Missing(sameNameDescriptors.toList())
        }

        // 1) Search class chain (or interface chain for interface owners).
        val chain = if (start.isInterface) interfaceHierarchy(start) else classHierarchy(start)
        for (c in chain) {
            val hit = c.parsed.methods[signature]
            if (hit != null) return LookupOutcome.Found(c.parsed, c.location)
            collectSameName(c.parsed.methods, signature.name, sameNameDescriptors)
        }

        // 2) For invokevirtual/invokespecial/invokestatic: allow interface fallback.
        // For invokeinterface: the owner is already an interface, but runtime behavior allows Object methods too.
        if (!start.isInterface) {
            val itfHit = findInInterfaces(start, signature, sameNameDescriptors)
            if (itfHit != null) return itfHit
        } else {
            // Try Object method resolution (best-effort): many interface calls allow Object methods.
            if (opcode == Opcodes.INVOKEINTERFACE) {
                val obj = resolveClass("java.lang.Object")
                val objParsed = (obj as? ClassResolutionOutcome.Resolved)?.parsed
                if (objParsed != null) {
                    val objLoc = (obj as ClassResolutionOutcome.Resolved).location
                    val hit = objParsed.methods[signature]
                    if (hit != null) return LookupOutcome.Found(objParsed, objLoc)
                    collectSameName(objParsed.methods, signature.name, sameNameDescriptors)
                }
            }
        }

        return LookupOutcome.Missing(sameNameDescriptors.toList().sorted())
    }

    private fun fieldLookup(
        start: ParsedClass,
        signature: MemberSig,
    ): LookupOutcome {
        val sameNameDescriptors = linkedSetOf<String>()

        // 1) Class chain (or interface chain).
        val chain = if (start.isInterface) interfaceHierarchy(start) else classHierarchy(start)
        for (c in chain) {
            val hit = c.parsed.fields[signature]
            if (hit != null) return LookupOutcome.Found(c.parsed, c.location)
            collectSameName(c.parsed.fields, signature.name, sameNameDescriptors)
        }

        // 2) For classes, search interfaces after superclass chain.
        if (!start.isInterface) {
            val itfHit = findFieldInInterfaces(start, signature, sameNameDescriptors)
            if (itfHit != null) return itfHit
        }

        return LookupOutcome.Missing(sameNameDescriptors.toList().sorted())
    }

    private data class LocatedParsedClass(
        val parsed: ParsedClass,
        val location: ClassLocation,
    )

    private fun classHierarchy(start: ParsedClass): List<LocatedParsedClass> {
        val out = ArrayList<LocatedParsedClass>(8)
        var cur: ParsedClass? = start
        while (cur != null) {
            val res = resolveClass(cur.binaryName)
            val resolved = res as? ClassResolutionOutcome.Resolved ?: break
            out.add(LocatedParsedClass(resolved.parsed, resolved.location))
            val next = resolved.parsed.superBinaryName
            cur = if (next != null) (resolveClass(next) as? ClassResolutionOutcome.Resolved)?.parsed else null
        }
        return out
    }

    private fun interfaceHierarchy(start: ParsedClass): List<LocatedParsedClass> {
        val visited = linkedSetOf<String>()
        val out = ArrayList<LocatedParsedClass>(8)

        fun dfs(binary: String) {
            if (!visited.add(binary)) return
            val res = resolveClass(binary) as? ClassResolutionOutcome.Resolved ?: return
            out.add(LocatedParsedClass(res.parsed, res.location))
            for (itf in res.parsed.interfaces) dfs(itf)
        }

        dfs(start.binaryName)
        return out
    }

    private fun findInInterfaces(
        start: ParsedClass,
        signature: MemberSig,
        sameNameDescriptors: MutableSet<String>,
    ): LookupOutcome.Found? {
        val allInterfaces = collectAllInterfaces(start)
        val ordered = allInterfaces.sorted()
        for (itfBinary in ordered) {
            val res = resolveClass(itfBinary) as? ClassResolutionOutcome.Resolved ?: continue
            val hit = res.parsed.methods[signature]
            if (hit != null) return LookupOutcome.Found(res.parsed, res.location)
            collectSameName(res.parsed.methods, signature.name, sameNameDescriptors)
        }
        return null
    }

    private fun findFieldInInterfaces(
        start: ParsedClass,
        signature: MemberSig,
        sameNameDescriptors: MutableSet<String>,
    ): LookupOutcome.Found? {
        val allInterfaces = collectAllInterfaces(start)
        val ordered = allInterfaces.sorted()
        for (itfBinary in ordered) {
            val res = resolveClass(itfBinary) as? ClassResolutionOutcome.Resolved ?: continue
            val hit = res.parsed.fields[signature]
            if (hit != null) return LookupOutcome.Found(res.parsed, res.location)
            collectSameName(res.parsed.fields, signature.name, sameNameDescriptors)
        }
        return null
    }

    private fun collectAllInterfaces(start: ParsedClass): Set<String> {
        val out = linkedSetOf<String>()
        val stack = ArrayDeque<String>()

        fun pushAll(parsed: ParsedClass) {
            for (itf in parsed.interfaces) stack.addLast(itf)
        }

        // Walk the class chain and accumulate interfaces.
        var cur: ParsedClass? = start
        while (cur != null) {
            pushAll(cur)
            val next = cur.superBinaryName
            cur = if (next != null) (resolveClass(next) as? ClassResolutionOutcome.Resolved)?.parsed else null
        }

        while (stack.isNotEmpty()) {
            val itf = stack.removeFirst()
            if (!out.add(itf)) continue
            val parsed = (resolveClass(itf) as? ClassResolutionOutcome.Resolved)?.parsed ?: continue
            pushAll(parsed)
        }

        return out
    }

    private fun opcodeName(opcode: Int): String =
        when (opcode) {
            Opcodes.INVOKEVIRTUAL -> "invokevirtual"
            Opcodes.INVOKESTATIC -> "invokestatic"
            Opcodes.INVOKESPECIAL -> "invokespecial"
            Opcodes.INVOKEINTERFACE -> "invokeinterface"
            Opcodes.GETFIELD -> "getfield"
            Opcodes.PUTFIELD -> "putfield"
            Opcodes.GETSTATIC -> "getstatic"
            Opcodes.PUTSTATIC -> "putstatic"
            else -> "opcode($opcode)"
        }

    private fun <T : Any> collectSameName(
        members: Map<MemberSig, T>,
        name: String,
        out: MutableSet<String>,
    ) {
        for ((sig, _) in members) {
            if (sig.name == name) out.add(sig.descriptor)
        }
    }
}
