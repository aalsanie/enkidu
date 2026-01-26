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
import io.enkidu.artifacts.v1.SymbolKind
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * ASM-based scanner that extracts bytecode references from a classfile.
 * It records best-effort line numbers from debug information when available.
 */
public class BytecodeReferenceScanner {
    public fun scanClassBytes(classBytes: ByteArray): List<BytecodeReference> {
        val reader = ClassReader(classBytes)
        val collector = CollectingClassVisitor()
        // SKIP_FRAMES makes output more stable across compilers; debug line numbers still work.
        reader.accept(collector, ClassReader.SKIP_FRAMES)
        return collector.references
    }

    private class CollectingClassVisitor : ClassVisitor(Opcodes.ASM9) {
        private var classInternalName: String = ""

        val references: MutableList<BytecodeReference> = mutableListOf()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            classInternalName = name
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
            return CollectingMethodVisitor(
                delegate = delegate,
                ownerClassInternalName = classInternalName,
                callerMethodName = name,
                callerMethodDescriptor = descriptor,
                sink = references,
            )
        }
    }

    private class CollectingMethodVisitor(
        delegate: MethodVisitor?,
        private val ownerClassInternalName: String,
        private val callerMethodName: String,
        private val callerMethodDescriptor: String,
        private val sink: MutableList<BytecodeReference>,
    ) : MethodVisitor(Opcodes.ASM9, delegate) {
        private var currentLine: Int? = null
        private var instructionIndex: Int = 0

        override fun visitLineNumber(
            line: Int,
            start: Label,
        ) {
            currentLine = line
            super.visitLineNumber(line, start)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            record(
                symbol =
                    SymbolId(
                        kind = SymbolKind.METHOD,
                        owner = owner,
                        name = name,
                        descriptor = descriptor,
                    ),
                opcode = opcode,
                isInterfaceInvocation = isInterface,
            )
            instructionIndex += 1
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            record(
                symbol =
                    SymbolId(
                        kind = SymbolKind.FIELD,
                        owner = owner,
                        name = name,
                        descriptor = descriptor,
                    ),
                opcode = opcode,
                isInterfaceInvocation = null,
            )
            instructionIndex += 1
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            // type is an internal name for NEW/CHECKCAST/INSTANCEOF/ANEWARRAY.
            recordTypeRef(opcode = opcode, internalName = type)
            instructionIndex += 1
            super.visitTypeInsn(opcode, type)
        }

        override fun visitLdcInsn(value: Any?) {
            // Class literals compile to LDC Type.
            if (value is Type) {
                when (value.sort) {
                    Type.OBJECT -> recordTypeRef(opcode = Opcodes.LDC, internalName = value.internalName)
                    Type.ARRAY -> {
                        val element = value.elementType
                        if (element.sort == Type.OBJECT) {
                            recordTypeRef(opcode = Opcodes.LDC, internalName = element.internalName)
                        }
                    }
                }
            }
            instructionIndex += 1
            super.visitLdcInsn(value)
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            val t = Type.getType(descriptor)
            val element = t.elementType
            if (element.sort == Type.OBJECT) {
                recordTypeRef(opcode = Opcodes.MULTIANEWARRAY, internalName = element.internalName)
            }
            instructionIndex += 1
            super.visitMultiANewArrayInsn(descriptor, numDimensions)
        }

        private fun recordTypeRef(
            opcode: Int,
            internalName: String,
        ) {
            // For TYPE symbols, we keep internal names (slash-separated) to match JVM descriptors.
            val descriptor = "L$internalName;"
            record(
                symbol =
                    SymbolId(
                        kind = SymbolKind.TYPE,
                        owner = internalName,
                        name = internalName,
                        descriptor = descriptor,
                    ),
                opcode = opcode,
                isInterfaceInvocation = null,
            )
        }

        private fun record(
            symbol: SymbolId,
            opcode: Int,
            isInterfaceInvocation: Boolean?,
        ) {
            sink.add(
                BytecodeReference(
                    symbol = symbol,
                    opcode = opcode,
                    isInterfaceInvocation = isInterfaceInvocation,
                    site =
                        ReferenceSite(
                            callerClass = ownerClassInternalName,
                            callerMethod = callerMethodName,
                            callerDescriptor = callerMethodDescriptor,
                            line = currentLine,
                            bytecodeOffset = instructionIndex,
                        ),
                ),
            )
        }
    }
}
