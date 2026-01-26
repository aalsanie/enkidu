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
package io.enkidu.core.scan

import io.enkidu.artifacts.v1.ReferenceSite
import io.enkidu.artifacts.v1.SymbolId

/**
 * A single bytecode-level reference extracted from a classfile.
 *
 * This is an engine-internal representation.
 * It carries enough evidence for JVM-like resolution:
 * - the referenced [symbol]
 * - the opcode that produced the reference
 * - best-effort source location and callsite context via [site]
 */
public data class BytecodeReference(
    val symbol: SymbolId,
    val opcode: Int,
    val site: ReferenceSite,
    /**
     * Only meaningful for method invocation instructions (INVOKE*).
     * When null, the reference is not a method invocation.
     */
    val isInterfaceInvocation: Boolean? = null,
)
