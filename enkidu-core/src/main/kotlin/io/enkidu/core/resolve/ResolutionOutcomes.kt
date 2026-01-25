/*
 * Copyright © 2025-2026 | Enkidu
 *
 * Linkage Doctor checks whether the code you compiled will still work at runtime by
 * reading your compiled bytecode and resolving every referenced class, method, and
 * field against the exact jars on your runtime classpath
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

import io.enkidu.core.model.ClasspathEntryKind
import java.nio.file.Path

data class ClassLocation(
    val binaryName: String,
    val entryPath: Path,
    val entryKind: ClasspathEntryKind,
    /** For jars, the internal class entry, e.g. "com/foo/Bar.class" */
    val jarEntry: String? = null,
)

sealed interface ClassResolutionOutcome {
    data class Resolved(
        val parsed: ParsedClass,
        val location: ClassLocation,
    ) : ClassResolutionOutcome

    data class Missing(
        val binaryName: String,
    ) : ClassResolutionOutcome
}

sealed interface MethodResolutionOutcome {
    data class Resolved(
        val symbolOwner: String,
        val declaringClass: ClassLocation,
        val signature: MemberSig,
        val isInterfaceDeclaringClass: Boolean,
    ) : MethodResolutionOutcome

    sealed interface Failed : MethodResolutionOutcome {
        val symbolOwner: String
        val signature: MemberSig
    }

    data class MissingClass(
        override val symbolOwner: String,
        override val signature: MemberSig,
    ) : Failed

    /**
     * NoSuchMethodError scenario.
     *
     * If [sameNameOtherDescriptors] is non-empty, it's a strong hint that the method changed signature.
     */
    data class MissingMethod(
        override val symbolOwner: String,
        override val signature: MemberSig,
        val sameNameOtherDescriptors: List<String>,
    ) : Failed

    /** IncompatibleClassChangeError-style mismatch. */
    data class IncompatibleClassChange(
        override val symbolOwner: String,
        override val signature: MemberSig,
        val message: String,
    ) : Failed
}

sealed interface FieldResolutionOutcome {
    data class Resolved(
        val symbolOwner: String,
        val declaringClass: ClassLocation,
        val signature: MemberSig,
        val isStatic: Boolean,
    ) : FieldResolutionOutcome

    sealed interface Failed : FieldResolutionOutcome {
        val symbolOwner: String
        val signature: MemberSig
    }

    data class MissingClass(
        override val symbolOwner: String,
        override val signature: MemberSig,
    ) : Failed

    /** NoSuchFieldError scenario. */
    data class MissingField(
        override val symbolOwner: String,
        override val signature: MemberSig,
        val sameNameOtherDescriptors: List<String>,
    ) : Failed

    /** IncompatibleClassChangeError-style mismatch. */
    data class IncompatibleClassChange(
        override val symbolOwner: String,
        override val signature: MemberSig,
        val message: String,
    ) : Failed
}
