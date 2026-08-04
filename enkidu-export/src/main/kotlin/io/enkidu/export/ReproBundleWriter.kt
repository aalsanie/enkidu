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
package io.enkidu.export

import io.enkidu.artifacts.v1.EnkiduFingerprints
import io.enkidu.artifacts.v1.EnkiduJson
import io.enkidu.artifacts.v1.LinkageReport
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generates a local repro/support bundle for a single [LinkageReport].
 *
 * The bundle is meant to be shared between machines (no upload support built in). It focuses on:
 * - the exact ordered inputs (targets + runtime classpath manifests)
 * - tool version/mode
 * - fingerprints (path-based + content-based)
 * - a top-level failure summary
 *
 * To keep the bundle stable/deterministic, zip entry timestamps are fixed to epoch.
 */
object ReproBundleWriter {
    /**
     * Write a repro bundle to [bundleOutput].
     *
     * If [bundleOutput] ends with ".zip", a zip file is produced.
     * Otherwise, a directory is created and populated.
     */
    fun writeDoctorBundle(
        report: LinkageReport,
        targets: List<Path>,
        runtimeClasspath: List<Path>,
        bundleOutput: Path,
    ) {
        require(targets.isNotEmpty()) { "targets must not be empty" }
        require(runtimeClasspath.isNotEmpty()) { "runtimeClasspath must not be empty" }

        val classpathManifest = manifestText(runtimeClasspath)
        val targetsManifest = manifestText(targets)

        val reportJson = EnkiduReportWriters.json(report)
        val reportSha = EnkiduFingerprints.sha256Hex(reportJson)

        val bundle =
            DoctorReproBundle(
                toolName = report.tool.name,
                toolVersion = report.tool.version,
                resolverMode = report.tool.resolverMode,
                fingerprints =
                    DoctorReproBundle.FingerprintsBlock(
                        classpath = report.fingerprints.classpath,
                        targets = report.fingerprints.targets,
                        report = DoctorReproBundle.FingerprintBlock(algorithm = "SHA-256", value = reportSha),
                        classpathContent = contentFingerprints(runtimeClasspath),
                        targetsContent = contentFingerprints(targets),
                    ),
                summary = report.summary,
            )

        val metadataJson = EnkiduJson.prettyWriter.writeValueAsBytes(bundle)

        if (bundleOutput.toString().lowercase().endsWith(".zip")) {
            writeZipBundle(
                bundleOutput = bundleOutput,
                classpathManifest = classpathManifest,
                targetsManifest = targetsManifest,
                metadataJson = metadataJson,
                howToRepro = howToReproduceText(),
            )
        } else {
            writeDirBundle(
                bundleDir = bundleOutput,
                classpathManifest = classpathManifest,
                targetsManifest = targetsManifest,
                metadataJson = metadataJson,
                howToRepro = howToReproduceText(),
            )
        }
    }

    private fun writeDirBundle(
        bundleDir: Path,
        classpathManifest: String,
        targetsManifest: String,
        metadataJson: ByteArray,
        howToRepro: String,
    ) {
        Files.createDirectories(bundleDir)
        Files.writeString(bundleDir.resolve("classpath.txt"), classpathManifest, StandardCharsets.UTF_8)
        Files.writeString(bundleDir.resolve("targets.txt"), targetsManifest, StandardCharsets.UTF_8)
        Files.write(bundleDir.resolve("bundle.json"), metadataJson)
        Files.writeString(bundleDir.resolve("HOW_TO_REPRODUCE.md"), howToRepro, StandardCharsets.UTF_8)
    }

    private fun writeZipBundle(
        bundleOutput: Path,
        classpathManifest: String,
        targetsManifest: String,
        metadataJson: ByteArray,
        howToRepro: String,
    ) {
        bundleOutput.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        BufferedOutputStream(Files.newOutputStream(bundleOutput)).use { bos ->
            ZipOutputStream(bos).use { zos ->
                // Fixed timestamps make the resulting zip reproducible across machines.
                putText(zos, "classpath.txt", classpathManifest)
                putText(zos, "targets.txt", targetsManifest)
                putBytes(zos, "bundle.json", metadataJson)
                putText(zos, "HOW_TO_REPRODUCE.md", howToRepro)
            }
        }
    }

    private fun putText(
        zos: ZipOutputStream,
        name: String,
        text: String,
    ) = putBytes(zos, name, text.toByteArray(StandardCharsets.UTF_8))

    private fun putBytes(
        zos: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val e = ZipEntry(name)
        e.time = 0L
        zos.putNextEntry(e)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun howToReproduceText(): String =
        """
        # Enkidu repro bundle
        
        This bundle contains:
        - `classpath.txt`: ordered runtime classpath entries (one per line)
        - `targets.txt`: ordered scan targets (one per line)
        - `bundle.json`: tool version + fingerprints + top-level failure summary
        
        ## Reproduce
        
        1) Ensure the same files exist at the referenced paths on this machine.
        2) Run:
        
        ```bash
        enkidu doctor \\
          --targets-file targets.txt \\
          --classpath-file classpath.txt \\
          --format json \\
          --output out/report.json
        ```
        
        3) Compare the SHA-256 of `out/report.json` with `bundle.json.fingerprints.report.value`.
        
        Notes:
        - `bundle.json.fingerprints.classpath` and `bundle.json.fingerprints.targets` are path-based.
        - `bundle.json.fingerprints.*Content` are content-based and allow validating equivalence even if
          the absolute paths differ.
        """.trimIndent()

    private fun manifestText(paths: List<Path>): String =
        paths
            .map {
                it
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .replace(Char(92), '/')
            }.joinToString(separator = "\n")

    private fun contentFingerprints(paths: List<Path>): List<DoctorReproBundle.EntryFingerprint> =
        paths
            .mapIndexed { idx, p ->
                val absolute = p.toAbsolutePath().normalize()
                when {
                    Files.isRegularFile(absolute) -> {
                        DoctorReproBundle.EntryFingerprint(
                            index = idx,
                            path = absolute.toString().replace(Char(92), '/'),
                            kind = "file",
                            sha256 = sha256OfFile(absolute),
                        )
                    }

                    Files.isDirectory(absolute) -> {
                        DoctorReproBundle.EntryFingerprint(
                            index = idx,
                            path = absolute.toString().replace(Char(92), '/'),
                            kind = "dir",
                            sha256 = sha256OfDirectoryTree(absolute),
                        )
                    }

                    else -> {
                        DoctorReproBundle.EntryFingerprint(
                            index = idx,
                            path = absolute.toString().replace(Char(92), '/'),
                            kind = "missing",
                            sha256 = "",
                        )
                    }
                }
            }

    private fun sha256OfFile(path: Path): String {
        Files.newInputStream(path).use { input ->
            return sha256OfStream(input)
        }
    }

    private fun sha256OfDirectoryTree(root: Path): String {
        // Deterministic tree hash: include relative path + NUL + sha256(fileBytes) for each file,
        // in lexicographic path order (with forward slashes).
        val md = java.security.MessageDigest.getInstance("SHA-256")

        val relPaths = mutableListOf<String>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile) {
                        relPaths += root.relativize(file).toString().replace(Char(92), '/')
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )

        for (rel in relPaths.sorted()) {
            md.update(rel.toByteArray(StandardCharsets.UTF_8))
            md.update(0)

            val abs = root.resolve(rel)
            Files.newInputStream(abs).use { input ->
                val fileSha = sha256OfStream(input)
                md.update(fileSha.toByteArray(StandardCharsets.UTF_8))
            }

            md.update(0)
        }

        return md.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun sha256OfStream(input: InputStream): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            md.update(buf, 0, read)
        }
        return md.digest().joinToString(separator = "") {
            "%02x\\".format(it)
        }
    }

    /**
     * Minimal bundle schema (v1). This is intentionally separate from `enkidu-artifacts`:
     * it is a support package, not the public report DTO contract.
     */
    private data class DoctorReproBundle(
        val toolName: String,
        val toolVersion: String,
        val resolverMode: String,
        val fingerprints: FingerprintsBlock,
        val summary: io.enkidu.artifacts.v1.ReportSummary,
    ) {
        data class FingerprintsBlock(
            val classpath: io.enkidu.artifacts.v1.Fingerprint,
            val targets: io.enkidu.artifacts.v1.Fingerprint,
            val report: FingerprintBlock,
            val classpathContent: List<EntryFingerprint>,
            val targetsContent: List<EntryFingerprint>,
        )

        data class FingerprintBlock(
            val algorithm: String,
            val value: String,
        )

        data class EntryFingerprint(
            val index: Int,
            val path: String,
            val kind: String,
            val sha256: String,
        )
    }
}
