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
package io.enkidu.intellij.plugin.classpath

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ClasspathFingerprint {
    /**
     * Deterministic fingerprint for the ordered classpath entries.
     *
     * We hash the exact manifest text (one entry per line, in order).
     */
    fun sha256Hex(manifestText: String): String {
        val normalized = manifestText.replace("\r\n", "\n").trimEnd()
        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }
}
