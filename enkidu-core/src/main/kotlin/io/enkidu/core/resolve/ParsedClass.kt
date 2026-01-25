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

import io.enkidu.core.util.EnkiduNames
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

data class ParsedClass(
    /** Binary name, e.g. "com.foo.Bar" */
    val binaryName: String,
    val access: Int,
    val superBinaryName: String?,
    val interfaces: List<String>,
    val methods: Map<MemberSig, MemberDef>,
    val fields: Map<MemberSig, MemberDef>,
) {
    val isInterface: Boolean get() = (access and Opcodes.ACC_INTERFACE) != 0
}

data class MemberSig(
    val name: String,
    /** JVM descriptor (method or field) */
    val descriptor: String,
)

data class MemberDef(
    val access: Int,
) {
    val isStatic: Boolean get() = (access and Opcodes.ACC_STATIC) != 0
}

internal object ClassfileParser {
    fun parse(bytes: ByteArray): ParsedClass {
        var access = 0
        var internalName: String? = null
        var superInternal: String? = null
        val interfacesInternal = mutableListOf<String>()
        val methods = linkedMapOf<MemberSig, MemberDef>()
        val fields = linkedMapOf<MemberSig, MemberDef>()

        val cr = ClassReader(bytes)
        cr.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    accessFlags: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    access = accessFlags
                    internalName = name
                    superInternal = superName
                    interfacesInternal.clear()
                    if (interfaces != null) {
                        interfacesInternal.addAll(interfaces)
                    }
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    fields[MemberSig(name, descriptor)] = MemberDef(access)
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    methods[MemberSig(name, descriptor)] = MemberDef(access)
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
        )

        val inName = requireNotNull(internalName) { "ClassfileParser: missing class name" }
        return ParsedClass(
            binaryName = EnkiduNames.internalToBinary(inName),
            access = access,
            superBinaryName = superInternal?.let { EnkiduNames.internalToBinary(it) },
            interfaces = interfacesInternal.map { EnkiduNames.internalToBinary(it) },
            methods = methods.toMap(),
            fields = fields.toMap(),
        )
    }
}
