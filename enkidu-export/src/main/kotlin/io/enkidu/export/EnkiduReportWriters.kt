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
package io.enkidu.export

import io.enkidu.artifacts.v1.CompareReport
import io.enkidu.artifacts.v1.EnkiduJson
import io.enkidu.artifacts.v1.FailureType
import io.enkidu.artifacts.v1.FixPlanItem
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.Severity
import java.nio.charset.StandardCharsets

/**
 * Exporters are pure transformations: LinkageReport -> bytes.
 *
 * Determinism rule: given a canonical LinkageReport, these writers must emit
 * byte-for-byte identical output.
 */
object EnkiduReportWriters {
    fun json(report: LinkageReport): ByteArray = EnkiduJson.prettyWriter.writeValueAsBytes(report.canonical())

    fun compareJsonV1(report: CompareReport): ByteArray = EnkiduJson.prettyWriter.writeValueAsBytes(report.canonical())

    fun sarifV1(report: LinkageReport): ByteArray = SarifWriterV1.write(report.canonical())

    fun htmlV1(report: LinkageReport): ByteArray = HtmlWriterV1.write(report.canonical())
}

/**
 * HTML report v1.
 *
 * Single-file output designed to be shareable.
 *
 * Determinism rules:
 * - No timestamps.
 * - No random IDs.
 * - Stable ordering follows [LinkageReport.canonical].
 * - No trailing newline at EOF (snapshot stability across platforms/editors).
 */
internal object HtmlWriterV1 {
    fun write(report: LinkageReport): ByteArray {
        val sb = StringBuilder(16_384)
        val failures = report.failures

        sb.append("<!doctype html>\n")
        sb.append("<html lang=\"en\">\n")
        sb.append("<head>\n")
        sb.append("  <meta charset=\"utf-8\">\n")
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("  <title>Enkidu Linkage Doctor Report</title>\n")
        sb.append("  <style>")
        sb.append(
            "body{font-family:system-ui,-apple-system," +
                "Segoe UI,Roboto,sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;line-height:1.45}" +
                "h1{margin:0 0 8px 0}" +
                "h2{margin:28px 0 8px 0;border-top:1px solid #e5e7eb;padding-top:18px}" +
                "h3{margin:18px 0 6px 0}" +
                "code{background:#f6f8fa;padding:2px 4px;border-radius:4px}" +
                "pre{background:#f6f8fa;padding:12px;border-radius:8px;white-space:pre-wrap;word-break:break-word}" +
                ".meta{color:#374151}" +
                ".k{color:#111827;font-weight:600}" +
                ".badge{display:inline-block;padding:2px 8px;border-radius:999px;" +
                "font-size:12px;vertical-align:middle;background:#eef2ff}" +
                ".badge.err{background:#fee2e2}" +
                ".badge.warn{background:#fef3c7}" +
                ".badge.info{background:#e0f2fe}" +
                "table{border-collapse:collapse;width:100%;margin:10px 0}" +
                "th,td{border:1px solid #e5e7eb;padding:8px;vertical-align:top}" +
                "th{background:#f9fafb;text-align:left}" +
                "ul{margin:6px 0 0 20px}" +
                "a{color:inherit}",
        )
        sb.append("</style>\n")
        sb.append("</head>\n")
        sb.append("<body>\n")

        sb.append("  <h1>Enkidu Linkage Doctor</h1>\n")
        sb.append("  <div class=\"meta\">\n")
        sb.append("    <div><span class=\"k\">Tool</span>: ")
        sb.append(HtmlEscaper.escape(report.tool.name))
        sb.append(" ")
        sb.append(HtmlEscaper.escape(report.tool.version))
        sb.append("</div>\n")
        sb.append("    <div><span class=\"k\">Resolver mode</span>: ")
        sb.append(HtmlEscaper.escape(report.tool.resolverMode))
        sb.append("</div>\n")
        sb.append("    <div><span class=\"k\">Classpath fingerprint</span>: <code>")
        sb.append(HtmlEscaper.escape(report.fingerprints.classpath.value))
        sb.append("</code></div>\n")
        sb.append("    <div><span class=\"k\">Targets fingerprint</span>: <code>")
        sb.append(HtmlEscaper.escape(report.fingerprints.targets.value))
        sb.append("</code></div>\n")
        sb.append("  </div>\n")

        sb.append("  <h2>Summary</h2>\n")
        sb.append("  <p><span class=\"k\">Failures</span>: ")
        sb.append(report.summary.failureCount)
        sb.append("</p>\n")

        sb.append("  <h3>By type</h3>\n")
        if (report.summary.failureCountByType.isEmpty()) {
            sb.append("  <p>No failures.</p>\n")
        } else {
            sb.append("  <table>\n")
            sb.append("    <thead><tr><th>Type</th><th>Count</th></tr></thead>\n")
            sb.append("    <tbody>\n")
            for ((type, count) in report.summary.failureCountByType.entries) {
                sb.append("      <tr><td><code>")
                sb.append(HtmlEscaper.escape(type.name))
                sb.append("</code></td><td>")
                sb.append(count)
                sb.append("</td></tr>\n")
            }
            sb.append("    </tbody>\n")
            sb.append("  </table>\n")
        }

        sb.append("  <h2>Failures</h2>\n")
        if (failures.isEmpty()) {
            sb.append("  <p>No failures.</p>\n")
            sb.append("</body>\n</html>")
            return sb.toString().toByteArray(StandardCharsets.UTF_8)
        }

        // Group by failure type while preserving canonical ordering.
        val groups = LinkedHashMap<FailureType, MutableList<LinkageFailure>>()
        for (f in failures) {
            groups.computeIfAbsent(f.type) { mutableListOf() }.add(f)
        }

        for ((type, list) in groups) {
            sb.append("  <h3><code>")
            sb.append(HtmlEscaper.escape(type.name))
            sb.append("</code> (")
            sb.append(list.size)
            sb.append(")</h3>\n")

            for ((idx, f) in list.withIndex()) {
                sb.append("  <div>\n")
                sb.append("    <p><span class=\"badge ")
                sb.append(badgeClass(f.severity))
                sb.append("\">")
                sb.append(HtmlEscaper.escape(f.severity.name))
                sb.append("</span> ")
                sb.append("<span class=\"k\">")
                sb.append(idx + 1)
                sb.append(".</span></p>\n")

                sb.append("    <pre>")
                sb.append(HtmlEscaper.escape(f.message))
                sb.append("</pre>\n")

                sb.append("    <p><span class=\"k\">Callsite</span>: <code>")
                sb.append(HtmlEscaper.escape(f.referenceSite.callerClass))
                sb.append(".")
                sb.append(HtmlEscaper.escape(f.referenceSite.callerMethod))
                sb.append(HtmlEscaper.escape(f.referenceSite.callerDescriptor))
                if (f.referenceSite.line != null) {
                    sb.append(":")
                    sb.append(f.referenceSite.line)
                }
                if (f.referenceSite.bytecodeOffset != null) {
                    sb.append(" @")
                    sb.append(f.referenceSite.bytecodeOffset)
                }
                sb.append("</code></p>\n")

                if (f.symbol != null) {
                    sb.append("    <p><span class=\"k\">Symbol</span>: <code>")
                    sb.append(HtmlEscaper.escape(f.symbol!!.owner))
                    sb.append(" ")
                    sb.append(HtmlEscaper.escape(f.symbol!!.kind.name))
                    sb.append(" ")
                    sb.append(HtmlEscaper.escape(f.symbol!!.name))
                    sb.append(HtmlEscaper.escape(f.symbol!!.descriptor))
                    sb.append("</code></p>\n")
                }

                if (f.evidence != null) {
                    sb.append(renderEvidence(f.evidence!!))
                }

                if (f.fixPlan.isNotEmpty()) {
                    sb.append(renderFixPlan(f.fixPlan))
                }

                sb.append("  </div>\n")
            }
        }

        sb.append("</body>\n")
        sb.append("</html>")
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun badgeClass(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> "badge err"
            Severity.WARN -> "badge warn"
            Severity.INFO -> "badge info"
        }

    private fun renderEvidence(e: io.enkidu.artifacts.v1.Evidence): String {
        val sb = StringBuilder(256)
        sb.append("    <p><span class=\"k\">Evidence</span>:</p>\n")
        sb.append("    <ul>\n")
        if (e.winnerJar != null) {
            sb.append("      <li><span class=\"k\">Winner jar</span>: <code>")
            sb.append(HtmlEscaper.escape(e.winnerJar!!))
            sb.append("</code></li>\n")
        }
        if (e.shadowedJars.isNotEmpty()) {
            sb.append("      <li><span class=\"k\">Shadowed jars</span>: <code>")
            sb.append(HtmlEscaper.escape(e.shadowedJars.joinToString(" | ")))
            sb.append("</code></li>\n")
        }
        if (e.missingJarHint != null) {
            sb.append("      <li><span class=\"k\">Missing jar hint</span>: <code>")
            sb.append(HtmlEscaper.escape(e.missingJarHint!!))
            sb.append("</code></li>\n")
        }
        sb.append("    </ul>\n")
        return sb.toString()
    }

    private fun renderFixPlan(items: List<FixPlanItem>): String {
        val sb = StringBuilder(256)
        sb.append("    <p><span class=\"k\">Fix plan</span>:</p>\n")
        sb.append("    <ul>\n")
        for (i in items) {
            sb.append("      <li><code>")
            sb.append(HtmlEscaper.escape(i.kind.name))
            sb.append("</code>: ")
            sb.append(HtmlEscaper.escape(i.value))
            if (i.confidence != null) {
                sb.append(" <span class=\"meta\">(confidence ")
                sb.append(i.confidence)
                sb.append(")</span>")
            }
            sb.append("</li>\n")
        }
        sb.append("    </ul>\n")
        return sb.toString()
    }
}

/**
 * SARIF 2.1.0 output profile.
 *
 * Notes:
 * - Enkidu's ReferenceSite v1 does not include source file paths, so we emit logical locations.
 * - We still include line/bytecode offset and jar evidence as SARIF properties.
 */
internal object SarifWriterV1 {
    fun write(report: LinkageReport): ByteArray {
        val failures = report.failures
        val ruleIds = failures.map { it.type.name }.distinct().sorted()

        val rules =
            ruleIds.map { id ->
                linkedMapOf(
                    "id" to id,
                    "name" to id,
                    "shortDescription" to linkedMapOf("text" to id),
                )
            }

        val results =
            failures.map { failure ->
                val callsiteFqn =
                    "${failure.referenceSite.callerClass}.${failure.referenceSite.callerMethod}${failure.referenceSite.callerDescriptor}"

                val props =
                    linkedMapOf<String, Any?>(
                        "callerClass" to failure.referenceSite.callerClass,
                        "callerMethod" to failure.referenceSite.callerMethod,
                        "callerDescriptor" to failure.referenceSite.callerDescriptor,
                        "line" to failure.referenceSite.line,
                        "bytecodeOffset" to failure.referenceSite.bytecodeOffset,
                        "symbolOwner" to failure.symbol?.owner,
                        "symbolName" to failure.symbol?.name,
                        "symbolDescriptor" to failure.symbol?.descriptor,
                        "winnerJar" to failure.evidence?.winnerJar,
                        "shadowedJars" to failure.evidence?.shadowedJars?.takeIf { it.isNotEmpty() },
                        "missingJarHint" to failure.evidence?.missingJarHint,
                        "fingerprintClasspath" to report.fingerprints.classpath.value,
                        "fingerprintTargets" to report.fingerprints.targets.value,
                        "resolverMode" to report.tool.resolverMode,
                    ).filterValues { it != null }

                linkedMapOf(
                    "ruleId" to failure.type.name,
                    "level" to sarifLevel(failure.severity),
                    "message" to linkedMapOf("text" to failure.message),
                    "locations" to
                        listOf(
                            linkedMapOf(
                                "logicalLocations" to
                                    listOf(
                                        linkedMapOf(
                                            "fullyQualifiedName" to callsiteFqn,
                                            "kind" to "function",
                                        ),
                                    ),
                            ),
                        ),
                    "properties" to props,
                )
            }

        val run =
            linkedMapOf(
                "tool" to
                    linkedMapOf(
                        "driver" to
                            linkedMapOf(
                                "name" to report.tool.name,
                                "version" to report.tool.version,
                                "rules" to rules,
                            ),
                    ),
                "results" to results,
                "properties" to
                    linkedMapOf(
                        "toolName" to report.tool.name,
                        "toolVersion" to report.tool.version,
                        "resolverMode" to report.tool.resolverMode,
                        "fingerprintClasspath" to report.fingerprints.classpath.value,
                        "fingerprintTargets" to report.fingerprints.targets.value,
                    ),
            )

        val sarif =
            linkedMapOf(
                "version" to "2.1.0",
                "\$schema" to "https://json.schemastore.org/sarif-2.1.0.json",
                "runs" to listOf(run),
            )

        return EnkiduJson.prettyWriter.writeValueAsBytes(sarif)
    }

    private fun sarifLevel(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> "error"
            Severity.WARN -> "warning"
            Severity.INFO -> "note"
        }
}
