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

import org.objectweb.asm.Opcodes

/**
 * Access/visibility checker for resolved members.
 *
 * This is deterministic and best-effort:
 * - It flags only cases we can prove will fail at runtime as IllegalAccessError.
 * - For protected-access subclass rules, it only flags if it can resolve the caller type and prove it's not a subclass.
 */
class AccessChecker(
    private val resolver: JvmLinkageResolver,
    private val moduleIndex: ModuleIndex,
) {
    fun checkMethodAccess(
        callerBinaryName: String,
        declaringClass: ClassLocation,
        signature: MemberSig,
    ): AccessCheckResult? {
        val declaringRes = resolver.resolveClass(declaringClass.binaryName)
        val declaringParsed = (declaringRes as? ClassResolutionOutcome.Resolved)?.parsed ?: return null

        val def = declaringParsed.methods[signature] ?: return null
        return checkMemberAccess(callerBinaryName, declaringParsed, def.access, declaringClass.entryPath)
    }

    fun checkFieldAccess(
        callerBinaryName: String,
        declaringClass: ClassLocation,
        signature: MemberSig,
    ): AccessCheckResult? {
        val declaringRes = resolver.resolveClass(declaringClass.binaryName)
        val declaringParsed = (declaringRes as? ClassResolutionOutcome.Resolved)?.parsed ?: return null

        val def = declaringParsed.fields[signature] ?: return null
        return checkMemberAccess(callerBinaryName, declaringParsed, def.access, declaringClass.entryPath)
    }

    private fun checkMemberAccess(
        callerBinaryName: String,
        declaringParsed: ParsedClass,
        memberAccess: Int,
        declaringEntryPath: java.nio.file.Path,
    ): AccessCheckResult? {
        val moduleContext = moduleContext(callerBinaryName, declaringParsed.binaryName, declaringEntryPath)

        // JPMS rules: even for public members, the package must be exported.
        if (moduleContext != null) {
            // Non-public access never crosses module boundaries (including unnamed → named).
            val sameModule = moduleContext.callerModule == moduleContext.targetModule
            if (!isPublic(memberAccess) && !sameModule) {
                return AccessCheckResult(
                    reason = "not accessible: JPMS blocks non-public member across modules",
                    callerBinaryName = callerBinaryName,
                    targetBinaryName = declaringParsed.binaryName,
                    memberAccess = memberAccess,
                    moduleContext = moduleContext.copy(isBlockedByModules = true, exported = moduleContext.exported),
                )
            }

            if (isPublic(memberAccess) && moduleContext.isBlockedByModules) {
                return AccessCheckResult(
                    reason = "not accessible: JPMS blocks access because package is not exported to caller module",
                    callerBinaryName = callerBinaryName,
                    targetBinaryName = declaringParsed.binaryName,
                    memberAccess = memberAccess,
                    moduleContext = moduleContext,
                )
            }
        }

        // First: class access
        if (!isClassAccessible(callerBinaryName, declaringParsed)) {
            return AccessCheckResult(
                reason = "not accessible: caller in different package cannot access non-public class",
                callerBinaryName = callerBinaryName,
                targetBinaryName = declaringParsed.binaryName,
                memberAccess = memberAccess,
                moduleContext = moduleContext,
            )
        }

        // Then: member access
        if (isPublic(memberAccess)) return null

        val samePkg = samePackage(callerBinaryName, declaringParsed.binaryName)

        if (isPrivate(memberAccess)) {
            // Java 11+ nestmates can legally access private members directly.
            if (callerBinaryName == declaringParsed.binaryName) return null
            if (declaringParsed.isNestmateOf(callerBinaryName)) return null
            return AccessCheckResult(
                reason = "not accessible: caller cannot access private member of a different class",
                callerBinaryName = callerBinaryName,
                targetBinaryName = declaringParsed.binaryName,
                memberAccess = memberAccess,
                moduleContext = moduleContext,
            )
        }

        if (isProtected(memberAccess)) {
            if (samePkg) return null

            // Only flag if we can resolve the caller type and prove it's not a subclass.
            val callerRes = resolver.resolveClass(callerBinaryName)
            val callerParsed = (callerRes as? ClassResolutionOutcome.Resolved)?.parsed ?: return null
            if (isSubclassOf(callerParsed, declaringParsed.binaryName)) return null

            return AccessCheckResult(
                reason = "not accessible: caller is neither in the same package nor a subclass for protected access",
                callerBinaryName = callerBinaryName,
                targetBinaryName = declaringParsed.binaryName,
                memberAccess = memberAccess,
                moduleContext = moduleContext,
            )
        }

        // Package-private
        if (!samePkg) {
            return AccessCheckResult(
                reason = "not accessible: caller in different package cannot access package-private member",
                callerBinaryName = callerBinaryName,
                targetBinaryName = declaringParsed.binaryName,
                memberAccess = memberAccess,
                moduleContext = moduleContext,
            )
        }

        return null
    }

    private fun isClassAccessible(
        callerBinaryName: String,
        targetParsed: ParsedClass,
    ): Boolean {
        if (isPublic(targetParsed.access)) return true
        return samePackage(callerBinaryName, targetParsed.binaryName)
    }

    private fun moduleContext(
        callerBinaryName: String,
        targetBinaryName: String,
        targetEntryPath: java.nio.file.Path,
    ): ModuleAccessContext? {
        val targetModule = moduleIndex.moduleFor(targetEntryPath)
        if (targetModule == null || targetModule.isAutomatic) return null

        // Try to resolve caller module via classpath. If not present, treat as unnamed.
        val callerRes = resolver.resolveClass(callerBinaryName)
        val callerEntry = (callerRes as? ClassResolutionOutcome.Resolved)?.location?.entryPath
        val callerModule = callerEntry?.let { moduleIndex.moduleFor(it) }

        val callerModuleName = callerModule?.name
        val targetModuleName = targetModule.name

        if (callerModuleName == targetModuleName) {
            return ModuleAccessContext(
                targetModule = targetModuleName,
                callerModule = callerModuleName,
                packageName = packageOf(targetBinaryName),
                exported = true,
                isBlockedByModules = false,
            )
        }

        val pkg = packageOf(targetBinaryName)
        val exported = targetModule.exportsPackage(pkg, callerModuleName)

        return ModuleAccessContext(
            targetModule = targetModuleName,
            callerModule = callerModuleName,
            packageName = pkg,
            exported = exported,
            isBlockedByModules = !exported,
        )
    }

    private fun isSubclassOf(
        parsedCaller: ParsedClass,
        targetBinaryName: String,
    ): Boolean {
        var current: ParsedClass? = parsedCaller
        var guard = 0
        while (current != null && guard++ < 128) {
            val superName = current.superBinaryName ?: return false
            if (superName == targetBinaryName) return true
            val superRes = resolver.resolveClass(superName)
            current = (superRes as? ClassResolutionOutcome.Resolved)?.parsed
        }
        return false
    }

    private fun samePackage(
        a: String,
        b: String,
    ): Boolean = packageOf(a) == packageOf(b)

    private fun packageOf(binaryName: String): String = binaryName.substringBeforeLast('.', missingDelimiterValue = "")

    private fun isPublic(access: Int): Boolean = (access and Opcodes.ACC_PUBLIC) != 0

    private fun isPrivate(access: Int): Boolean = (access and Opcodes.ACC_PRIVATE) != 0

    private fun isProtected(access: Int): Boolean = (access and Opcodes.ACC_PROTECTED) != 0
}

data class AccessCheckResult(
    val reason: String,
    val callerBinaryName: String,
    val targetBinaryName: String,
    val memberAccess: Int,
    val moduleContext: ModuleAccessContext?,
)

data class ModuleAccessContext(
    val targetModule: String,
    val callerModule: String?,
    val packageName: String,
    val exported: Boolean,
    val isBlockedByModules: Boolean,
)
