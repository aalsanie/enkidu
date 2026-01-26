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

import io.enkidu.artifacts.v1.SymbolId
import io.enkidu.artifacts.v1.SymbolKind
import io.enkidu.core.scan.BytecodeReference

/**
 * Small, deterministic call graph index derived from scanned bytecode references.
 *
 * This is intentionally lightweight: it only records direct call edges for INVOKE* instructions.
 * It enables building a best-effort "one-hop callers" summary for a given method.
 */
class CallGraphIndex private constructor(
    private val callersByCallee: Map<MethodId, Set<MethodId>>,
) {
    fun callersOf(method: MethodId): List<MethodId> =
        callersByCallee[method]
            .orEmpty()
            .toList()
            .sorted()

    companion object {
        fun fromReferences(references: List<BytecodeReference>): CallGraphIndex {
            val map = mutableMapOf<MethodId, MutableSet<MethodId>>()

            for (ref in references) {
                if (ref.symbol.kind != SymbolKind.METHOD) continue
                if (!isInvokeOpcode(ref.opcode)) continue

                val caller = MethodId(ref.site.callerClass, ref.site.callerMethod, ref.site.callerDescriptor)
                val callee = toMethodId(ref.symbol)
                map.getOrPut(callee) { linkedSetOf() }.add(caller)
            }

            // Freeze to immutable with stable deterministic ordering.
            val frozen =
                map
                    .mapValues { (_, v) -> v.toSortedSet() }
                    .toSortedMap()

            return CallGraphIndex(frozen)
        }

        private fun toMethodId(symbol: SymbolId): MethodId = MethodId(symbol.owner, symbol.name, symbol.descriptor)

        private fun isInvokeOpcode(opcode: Int): Boolean =
            opcode == org.objectweb.asm.Opcodes.INVOKEVIRTUAL ||
                opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL ||
                opcode == org.objectweb.asm.Opcodes.INVOKESTATIC ||
                opcode == org.objectweb.asm.Opcodes.INVOKEINTERFACE ||
                opcode == org.objectweb.asm.Opcodes.INVOKEDYNAMIC
    }
}

/**
 * Deterministic identifier for a method.
 *
 * Owners are stored in JVM internal form (slashes) where available.
 */
data class MethodId(
    val owner: String,
    val name: String,
    val descriptor: String,
) : Comparable<MethodId> {
    override fun compareTo(other: MethodId): Int = compareValuesBy(this, other, MethodId::owner, MethodId::name, MethodId::descriptor)

    override fun toString(): String = "$owner.$name$descriptor"
}
