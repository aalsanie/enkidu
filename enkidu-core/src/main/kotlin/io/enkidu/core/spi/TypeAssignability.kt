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

import io.enkidu.core.resolve.ClassResolutionOutcome
import io.enkidu.core.resolve.JvmLinkageResolver
import io.enkidu.core.resolve.ParsedClass

/**
 *  Is provider assignable to service check.
 *
 * - If service is an interface: provider must implement it (directly or via super types).
 * - If service is a class: provider must extend it (directly or via super types).
 */
object TypeAssignability {
    fun isProviderAssignableToService(
        providerBinaryName: String,
        serviceBinaryName: String,
        resolver: JvmLinkageResolver,
    ): Boolean {
        val service = resolver.resolveClass(serviceBinaryName)
        if (service !is ClassResolutionOutcome.Resolved) return false
        val provider = resolver.resolveClass(providerBinaryName)
        if (provider !is ClassResolutionOutcome.Resolved) return false

        val serviceParsed = service.parsed
        val providerParsed = provider.parsed

        return if (serviceParsed.isInterface) {
            implementsInterface(providerParsed, serviceParsed.binaryName, resolver)
        } else {
            isSubclassOf(providerParsed, serviceParsed.binaryName, resolver)
        }
    }

    private fun implementsInterface(
        start: ParsedClass,
        interfaceBinaryName: String,
        resolver: JvmLinkageResolver,
    ): Boolean {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(start.binaryName)

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur)) continue
            val resolved = resolver.resolveClass(cur)
            if (resolved !is ClassResolutionOutcome.Resolved) continue
            val parsed = resolved.parsed

            // Check direct interfaces.
            for (i in parsed.interfaces) {
                if (i == interfaceBinaryName) return true
                queue.add(i)
            }
            // Walk superclass.
            parsed.superBinaryName?.let { queue.add(it) }
        }

        return false
    }

    private fun isSubclassOf(
        start: ParsedClass,
        superBinaryName: String,
        resolver: JvmLinkageResolver,
    ): Boolean {
        var cur: String? = start.binaryName
        val seen = mutableSetOf<String>()
        while (cur != null && seen.add(cur)) {
            if (cur == superBinaryName) return true
            val resolved = resolver.resolveClass(cur)
            if (resolved !is ClassResolutionOutcome.Resolved) return false
            cur = resolved.parsed.superBinaryName
        }
        return false
    }
}
