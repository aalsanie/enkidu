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
package io.enkidu.intellij.plugin

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.enkidu.artifacts.v1.EnkiduJson
import io.enkidu.artifacts.v1.LinkageFailure
import io.enkidu.artifacts.v1.LinkageReport
import io.enkidu.artifacts.v1.Severity
import io.enkidu.artifacts.v1.ToolMetadata
import io.enkidu.core.engine.LinkageDoctorEngine
import io.enkidu.core.engine.LinkageDoctorRequest
import io.enkidu.export.EnkiduReportWriters
import io.enkidu.intellij.plugin.classpath.ClasspathFingerprint
import io.enkidu.intellij.plugin.classpath.ClasspathProvider
import io.enkidu.intellij.plugin.classpath.ClasspathProviderContext
import io.enkidu.intellij.plugin.classpath.ClasspathProviders
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class LinkageDoctorToolWindowPanel(
    private val project: Project,
) {
    val component: JComponent

    private val settingsService: LinkageDoctorSettingsService = LinkageDoctorSettingsService.get(project)

    private val moduleCombo: ComboBox<Module>
    private val classpathProviderCombo: ComboBox<ClasspathProvider>
    private val classpathField: JBTextField
    private val formatCombo: ComboBox<OutputFormat>
    private val failOnCombo: ComboBox<FailOnPolicy>

    private val runButton: JButton
    private val exportButton: JButton
    private val copyFailureButton: JButton
    private val copyClasspathButton: JButton

    private val tree: Tree
    private val treeModel: DefaultTreeModel

    private val classpathUsed: JBTextArea
    private val classpathFingerprintLabel: JBLabel
    private val details: JBTextArea

    private var lastReport: LinkageReport? = null
    private var lastSelectedFailure: LinkageFailure? = null
    private var lastClasspathManifest: String? = null

    init {
        val modules = ModuleManager.getInstance(project).modules.sortedBy { it.name }
        moduleCombo = ComboBox(modules.toTypedArray())
        moduleCombo.preferredSize = Dimension(JBUI.scale(260), moduleCombo.preferredSize.height)

        val providers = ClasspathProviders.all().toTypedArray()
        classpathProviderCombo = ComboBox(providers)
        classpathProviderCombo.preferredSize = Dimension(JBUI.scale(220), classpathProviderCombo.preferredSize.height)

        classpathField = JBTextField(settingsService.settings.classpathManifestPath.orEmpty())
        classpathField.emptyText.text = "Classpath manifest (one entry per line)"

        formatCombo = ComboBox(OutputFormat.entries.toTypedArray())
        formatCombo.selectedItem = settingsService.settings.outputFormat

        failOnCombo = ComboBox(FailOnPolicy.entries.toTypedArray())
        failOnCombo.selectedItem = settingsService.settings.failOnPolicy

        runButton = JButton("Run")
        exportButton = JButton("Export")
        copyFailureButton = JButton("Copy failure")
        copyClasspathButton = JButton("Copy classpath")

        val root = DefaultMutableTreeNode("No results")
        treeModel = DefaultTreeModel(root)
        tree = Tree(treeModel)

        // Newer, non-deprecated install method (constructor is deprecated).
        TreeSpeedSearch.installOn(tree)

        classpathFingerprintLabel = JBLabel("Classpath: (not resolved)")

        classpathUsed = JBTextArea()
        classpathUsed.isEditable = false
        classpathUsed.lineWrap = false
        classpathUsed.emptyText.text = "Run a scan to see the exact classpath used (copy-pastable for CLI)."

        details = JBTextArea()
        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        details.emptyText.text = "Select a failure to see details. Double-click a failure to navigate."

        // Restore provider selection deterministically.
        val savedProvider = ClasspathProviders.byId(settingsService.settings.classpathProviderId)
        classpathProviderCombo.selectedItem = savedProvider

        component = buildUi()
        wireActions()
        applyClasspathUiState()
    }

    private fun buildUi(): JComponent {
        val toolbar = JToolBar()
        toolbar.isFloatable = false
        toolbar.border = JBUI.Borders.empty(6)

        val browseButton = JButton("Browse")

        val form = JPanel(HorizontalLayout(JBUI.scale(8)))
        form.border = JBUI.Borders.emptyRight(8)
        form.add(JBLabel("Module:"))
        form.add(moduleCombo)

        val classpathPanel = JPanel(HorizontalLayout(JBUI.scale(6)))
        classpathPanel.add(JBLabel("Classpath:"))
        classpathPanel.add(classpathProviderCombo)
        classpathPanel.add(classpathField)
        classpathField.preferredSize = Dimension(JBUI.scale(360), classpathField.preferredSize.height)
        classpathPanel.add(browseButton)

        val opts = JPanel(HorizontalLayout(JBUI.scale(6)))
        opts.add(JBLabel("Format:"))
        opts.add(formatCombo)
        opts.add(JBLabel("Fail-on:"))
        opts.add(failOnCombo)

        toolbar.add(form)
        toolbar.addSeparator()
        toolbar.add(classpathPanel)
        toolbar.addSeparator()
        toolbar.add(opts)
        toolbar.addSeparator()
        toolbar.add(runButton)
        toolbar.add(exportButton)
        toolbar.add(copyFailureButton)
        toolbar.add(copyClasspathButton)

        // Left: failures tree
        // Right: vertical splitter -> classpath used (top) + details (bottom)
        val right = JBSplitter(true, 0.35f)
        right.firstComponent = buildClasspathUsedPanel()
        right.secondComponent = ScrollPaneFactory.createScrollPane(details, true)

        val splitter = JBSplitter(false, 0.55f)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
        splitter.secondComponent = right

        val panel = JPanel(BorderLayout())
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(splitter, BorderLayout.CENTER)

        browseButton.addActionListener {
            val chooser =
                FileChooserFactory.getInstance().createFileChooser(
                    FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Select classpath manifest")
                        .withDescription("Text file containing one runtime classpath entry per line"),
                    project,
                    null,
                )

            val start =
                settingsService.settings.classpathManifestPath
                    ?.let { LocalFileSystem.getInstance().findFileByPath(it) }

            chooser.choose(project, start).firstOrNull()?.let { vf ->
                classpathField.text = vf.path
                persistSettings()
            }
        }

        return panel
    }

    private fun buildClasspathUsedPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)
        panel.add(classpathFingerprintLabel, BorderLayout.NORTH)
        panel.add(ScrollPaneFactory.createScrollPane(classpathUsed, true), BorderLayout.CENTER)
        return panel
    }

    private fun wireActions() {
        classpathProviderCombo.addActionListener {
            applyClasspathUiState()
            persistSettings()
        }

        runButton.addActionListener {
            persistSettings()
            runScan()
        }

        exportButton.addActionListener {
            persistSettings()
            exportLastReport()
        }

        copyFailureButton.addActionListener {
            val failure = lastSelectedFailure
            if (failure == null) {
                notify("Nothing to copy", "Select a failure first.", NotificationType.INFORMATION)
                return@addActionListener
            }
            val json = EnkiduJson.mapper.writeValueAsString(failure.canonical())
            java.awt.Toolkit
                .getDefaultToolkit()
                .systemClipboard
                .setContents(StringSelection(json), null)
            notify("Copied", "Failure JSON copied to clipboard.", NotificationType.INFORMATION)
        }

        copyClasspathButton.addActionListener {
            val manifest = lastClasspathManifest
            if (manifest.isNullOrBlank()) {
                notify("Nothing to copy", "Run a scan first to resolve the classpath.", NotificationType.INFORMATION)
                return@addActionListener
            }
            java.awt.Toolkit
                .getDefaultToolkit()
                .systemClipboard
                .setContents(StringSelection(manifest), null)
            notify("Copied", "Classpath manifest copied to clipboard.", NotificationType.INFORMATION)
        }

        tree.addTreeSelectionListener(
            TreeSelectionListener { e: TreeSelectionEvent? ->
                val node = e?.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@TreeSelectionListener
                val userObject = node.userObject
                if (userObject is LinkageFailure) {
                    lastSelectedFailure = userObject
                    details.text = renderFailureDetails(userObject)
                } else {
                    lastSelectedFailure = null
                    details.text = ""
                }
            },
        )

        tree.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount != 2) return
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val failure = node.userObject as? LinkageFailure ?: return
                    navigateToReferenceSite(failure)
                }
            },
        )
    }

    private fun applyClasspathUiState() {
        val provider = (classpathProviderCombo.selectedItem as? ClasspathProvider) ?: ClasspathProviders.default()
        val needsManifestFile = provider.id == "manifest-file"
        classpathField.isEnabled = needsManifestFile
    }

    private fun persistSettings() {
        val provider = (classpathProviderCombo.selectedItem as? ClasspathProvider) ?: ClasspathProviders.default()
        settingsService.settings.classpathProviderId = provider.id
        settingsService.settings.classpathManifestPath = classpathField.text.trim().ifEmpty { null }
        settingsService.settings.outputFormat = (formatCombo.selectedItem as? OutputFormat) ?: OutputFormat.JSON
        settingsService.settings.failOnPolicy = (failOnCombo.selectedItem as? FailOnPolicy) ?: FailOnPolicy.ANY
    }

    private fun runScan() {
        val module = moduleCombo.selectedItem as? Module
        if (module == null) {
            notify("Invalid input", "No module selected.", NotificationType.ERROR)
            return
        }

        val outPath = compilerOutputPath(module)
        if (outPath == null) {
            notify("No compiler output", "Module '${module.name}' has no configured compiler output path.", NotificationType.WARNING)
            return
        }

        if (!Files.exists(outPath)) {
            val msg = "Compiler output does not exist: $outPath\n\nBuild the project, then re-run."
            notify("Build required", msg, NotificationType.WARNING)
            return
        }

        val provider = (classpathProviderCombo.selectedItem as? ClasspathProvider) ?: ClasspathProviders.default()
        val manifestPath = classpathField.text.trim().ifEmpty { null }

        val ctx =
            ClasspathProviderContext(
                manifestFile = manifestPath?.let { Path.of(it) },
            )

        object : Task.Backgroundable(project, "Enkidu Linkage Doctor", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val resolved = provider.resolve(module, ctx)
                val runtimeClasspath = resolved.entries

                val report =
                    LinkageDoctorEngine().run(
                        LinkageDoctorRequest(
                            tool = ToolMetadata(name = TOOL_NAME, version = pluginVersion(), resolverMode = RESOLVER_MODE),
                            targets = listOf(outPath),
                            runtimeClasspath = runtimeClasspath,
                        ),
                    )

                ApplicationManager.getApplication().invokeLater {
                    lastReport = report
                    lastClasspathManifest = resolved.manifestText

                    classpathUsed.text = resolved.manifestText
                    val fp = ClasspathFingerprint.sha256Hex(resolved.manifestText)
                    classpathFingerprintLabel.text = "Classpath fingerprint (sha256): $fp"

                    renderReport(report)
                    notify("Scan finished", statusText(report), NotificationType.INFORMATION)
                }
            }

            override fun onThrowable(error: Throwable) {
                notify("Scan failed", error.message ?: error.toString(), NotificationType.ERROR)
            }
        }.queue()
    }

    private fun exportLastReport() {
        val report = lastReport
        if (report == null) {
            notify("Nothing to export", "Run a scan first.", NotificationType.INFORMATION)
            return
        }

        val format = (formatCombo.selectedItem as? OutputFormat) ?: OutputFormat.JSON
        val extension =
            when (format) {
                OutputFormat.JSON -> "json"
                OutputFormat.SARIF -> "sarif"
                OutputFormat.HTML -> "html"
            }

        val fileName = "enkidu-linkage-report.$extension"

        val descriptor =
            FileChooserDescriptor(false, true, false, false, false, false)
                .withTitle("Choose export directory")
                .withDescription("Enkidu will write $fileName")

        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, component)
        val chosenDir = chooser.choose(project).firstOrNull() ?: return

        val outDir = Path.of(chosenDir.path)
        val outFile = outDir.resolve(fileName)

        try {
            Files.createDirectories(outDir)
            val bytes =
                when (format) {
                    OutputFormat.JSON -> EnkiduReportWriters.json(report)
                    OutputFormat.SARIF -> EnkiduReportWriters.sarifV1(report)
                    OutputFormat.HTML -> EnkiduReportWriters.htmlV1(report)
                }
            Files.write(outFile, bytes)
            notify("Exported", "Wrote ${outFile.toAbsolutePath()}", NotificationType.INFORMATION)
        } catch (e: Exception) {
            notify("Export failed", e.message ?: e.toString(), NotificationType.ERROR)
        }
    }

    private fun renderReport(report: LinkageReport) {
        val root = DefaultMutableTreeNode("Failures (${report.failures.size})")

        val grouped = report.failures.groupBy { it.type }
        grouped.toSortedMap(compareBy { it.name }).forEach { (type, failures) ->
            val typeNode = DefaultMutableTreeNode("${type.name} (${failures.size})")
            failures
                .sortedWith(LinkageFailure.CANONICAL_ORDER)
                .forEach { failure ->
                    typeNode.add(DefaultMutableTreeNode(failure))
                }
            root.add(typeNode)
        }

        treeModel.setRoot(root)
        treeModel.reload()

        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }

        details.text = ""
        lastSelectedFailure = null
    }

    private fun renderFailureDetails(failure: LinkageFailure): String {
        val sb = StringBuilder()
        sb.appendLine("${failure.severity} • ${failure.type}")
        sb.appendLine(failure.message)
        sb.appendLine()

        val site = failure.referenceSite
        sb.appendLine("Callsite")
        sb.appendLine("  ${site.callerClass}.${site.callerMethod}${site.callerDescriptor}")
        if (site.line != null) sb.appendLine("  line: ${site.line}")
        if (site.bytecodeOffset != null) sb.appendLine("  bci: ${site.bytecodeOffset}")
        sb.appendLine()

        val evidence = failure.evidence
        if (evidence != null) {
            sb.appendLine("Evidence")
            if (evidence.winnerJar != null) sb.appendLine("  winner: ${evidence.winnerJar}")
            if (evidence.shadowedJars.isNotEmpty()) {
                sb.appendLine("  shadowed:")
                evidence.shadowedJars.forEach { sb.appendLine("    - $it") }
            }
            if (evidence.missingJarHint != null) sb.appendLine("  missing: ${evidence.missingJarHint}")
            sb.appendLine()
        }

        if (failure.fixPlan.isNotEmpty()) {
            sb.appendLine("Fix plan")
            failure.fixPlan.forEach { item ->
                val conf = item.confidence?.let { " (confidence=${"%.2f".format(it)})" } ?: ""
                sb.appendLine("  - ${item.kind}: ${item.value}$conf")
            }
            sb.appendLine()
        }

        sb.appendLine("Raw")
        sb.appendLine(EnkiduJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(failure.canonical()))

        return sb.toString()
    }

    private fun navigateToReferenceSite(failure: LinkageFailure) {
        val line = failure.referenceSite.line
        if (line == null) {
            notify("Navigation unavailable", "No line info for this callsite.", NotificationType.INFORMATION)
            return
        }

        val fqn = failure.referenceSite.callerClass.replace('/', '.')
        val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.projectScope(project))
        val vf = psiClass?.containingFile?.virtualFile
        if (vf == null) {
            notify("Navigation unavailable", "Could not find source for $fqn", NotificationType.INFORMATION)
            return
        }

        val navigatable =
            com.intellij.openapi.fileEditor
                .OpenFileDescriptor(project, vf, line - 1, 0) as Navigatable
        if (navigatable.canNavigate()) {
            navigatable.navigate(true)
        }
    }

    private fun compilerOutputPath(module: Module): Path? {
        val ext = CompilerModuleExtension.getInstance(module) ?: return null
        val vf = ext.compilerOutputPath ?: return null
        return Path.of(vf.path)
    }

    private fun statusText(report: LinkageReport): String {
        val any = report.failures.size
        val errors = report.failures.count { it.severity == Severity.ERROR }
        val warns = report.failures.count { it.severity == Severity.WARN }
        return "Failures: $any (errors=$errors, warnings=$warns)"
    }

    private fun pluginVersion(): String = this::class.java.`package`?.implementationVersion ?: "dev"

    private fun notify(
        title: String,
        content: String,
        type: NotificationType,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    private companion object {
        const val TOOL_NAME: String = "enkidu-linkage-doctor"
        const val RESOLVER_MODE: String = "jvm-linkage-sim-v1"
        const val NOTIFICATION_GROUP_ID: String = "Enkidu Linkage Doctor"
    }
}
