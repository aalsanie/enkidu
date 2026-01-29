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
package io.enkidu.intellij.plugin

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.panel
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
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * UX goals:
 * - All configuration is always visible (scrollable), never clipped.
 * - The tool window has a clear flow: Configure -> Run -> Inspect results.
 * - Before first run, show instructions (not an empty "No results" state).
 */
class LinkageDoctorToolWindowPanel(
    private val project: Project,
) {
    val component: JComponent

    private val settingsService: LinkageDoctorSettingsService = LinkageDoctorSettingsService.get(project)

    // ----- Config controls -----
    private val moduleCombo: ComboBox<String>
    private val reloadModulesButton: JButton
    private val classpathProviderCombo: ComboBox<ClasspathProvider>
    private val classpathProviderHelp: JBTextArea
    private val classpathManifestField: TextFieldWithBrowseButton
    private val manifestDescriptor: FileChooserDescriptor
    private val formatCombo: ComboBox<OutputFormat>
    private val failOnCombo: ComboBox<FailOnPolicy>

    // ----- Toolbar actions -----
    private val runButton: JButton
    private val resetButton: JButton
    private val exportButton: JButton
    private val copyFailureButton: JButton
    private val copyClasspathButton: JButton

    // ----- Results UI -----
    private val resultsCards: JPanel
    private val statusLabel: JBLabel

    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val details: JBTextArea
    private val classpathUsed: JBTextArea
    private val classpathFingerprintLabel: JBLabel

    // ----- State -----
    private var lastReport: LinkageReport? = null
    private var lastSelectedFailure: LinkageFailure? = null
    private var lastClasspathManifest: String? = null

    init {
        reloadModulesButton = JButton("Reload")

        moduleCombo = ComboBox(emptyArray())
        moduleCombo.minimumSize = Dimension(JBUI.scale(220), moduleCombo.preferredSize.height)
        moduleCombo.renderer = SimpleListCellRenderer.create("") { it ?: "" }
        reloadModules(preserveSelection = null)

        val providers = ClasspathProviders.all().toTypedArray()
        classpathProviderCombo = ComboBox(providers)
        classpathProviderCombo.minimumSize = Dimension(JBUI.scale(220), classpathProviderCombo.preferredSize.height)
        classpathProviderCombo.renderer = SimpleListCellRenderer.create("") { it?.displayName ?: "" }

        classpathProviderHelp =
            JBTextArea().apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                border = JBUI.Borders.empty(2, 0)
            }

        classpathManifestField = TextFieldWithBrowseButton()
        classpathManifestField.text = settingsService.settings.classpathManifestPath.orEmpty()

        manifestDescriptor =
            FileChooserDescriptorFactory
                .createSingleFileDescriptor()
                .withTitle("Select Classpath Manifest")
                .withDescription("Select a manifest file that contains one classpath entry per line.")

        // Wire browse action in a deterministic, no-magic way.
        classpathManifestField.addBrowseFolderListener(
            "Select Classpath Manifest",
            "Select a manifest file that contains one classpath entry per line.",
            project,
            manifestDescriptor,
        )

        formatCombo = ComboBox(OutputFormat.entries.toTypedArray())
        formatCombo.selectedItem = settingsService.settings.outputFormat

        failOnCombo = ComboBox(FailOnPolicy.entries.toTypedArray())
        failOnCombo.selectedItem = settingsService.settings.failOnPolicy

        runButton = JButton("Run")
        resetButton = JButton("Reset")
        exportButton = JButton("Export")
        copyFailureButton = JButton("Copy failure")
        copyClasspathButton = JButton("Copy classpath")

        statusLabel = JBLabel("Configure and run a scan.")
        statusLabel.border = JBUI.Borders.empty(6, 8)

        val root = DefaultMutableTreeNode("Run a scan")
        treeModel = DefaultTreeModel(root)
        tree = Tree(treeModel)
        TreeSpeedSearch.installOn(tree)

        details = JBTextArea()
        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        details.emptyText.text = "Select a failure to see details. Double-click to navigate to the callsite."

        classpathFingerprintLabel = JBLabel("Classpath fingerprint: (not resolved)")
        classpathUsed = JBTextArea()
        classpathUsed.isEditable = false
        classpathUsed.lineWrap = false
        classpathUsed.emptyText.text = "Run a scan to see the exact resolved runtime classpath (copy-pastable for CLI)."

        resultsCards = JPanel(CardLayout())
        resultsCards.add(buildInstructionsPanel(), CARD_INSTRUCTIONS)
        resultsCards.add(buildResultsPanel(), CARD_RESULTS)

        // Restore provider selection deterministically.
        classpathProviderCombo.selectedItem = ClasspathProviders.byId(settingsService.settings.classpathProviderId)
        updateClasspathProviderHelp()

        component = buildUi()
        wireActions()
        applyClasspathUiState()
        updateButtonsEnabledState()
        showInstructions()
    }

    private fun buildUi(): JComponent {
        val toolbar = buildToolbar()

        val configScroll = ScrollPaneFactory.createScrollPane(buildConfigPanel(), true)
        configScroll.border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
        configScroll.minimumSize = Dimension(JBUI.scale(340), JBUI.scale(200))

        val splitter = JBSplitter(false, 0.38f)
        splitter.firstComponent = configScroll
        splitter.secondComponent = resultsCards

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(toolbar, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun buildToolbar(): JComponent =
        JToolBar().apply {
            isFloatable = false
            border = JBUI.Borders.empty(4, 0, 8, 0)

            add(runButton)
            add(resetButton)
            addSeparator()
            add(exportButton)
            add(copyFailureButton)
            add(copyClasspathButton)
        }

    private fun buildConfigPanel(): JComponent =
        panel {
            row {
                label("Enkidu Linkage Doctor").bold()
            }
            row {
                comment(
                    "Validate runtime linkage against the exact runtime classpath. " +
                        "Find missing symbols, descriptor mismatches, access issues, and classpath shadowing.",
                )
            }.bottomGap(BottomGap.MEDIUM)

            group("Target") {
                row("Module") {
                    cell(moduleCombo).align(AlignX.FILL)
                    cell(reloadModulesButton)
                }
                row {
                    comment("Tip: Build the project first so compiler output exists.")
                }
            }

            group("Classpath") {
                row("Provider") {
                    cell(classpathProviderCombo).align(AlignX.FILL)
                }
                row {
                    cell(classpathProviderHelp).align(AlignX.FILL)
                }
                row("Manifest") {
                    cell(classpathManifestField).align(AlignX.FILL)
                }
                row {
                    comment("Manifest is only used by the 'Manifest file' provider.")
                }
            }

            group("Output") {
                row("Format") {
                    cell(formatCombo)
                }
                row("Fail-on") {
                    cell(failOnCombo)
                }
                row {
                    comment("Fail-on controls whether the scan is treated as FAILED in the UI and notifications.")
                }
            }
        }

    private fun buildInstructionsPanel(): JComponent {
        val title = JBLabel("How to use")
        title.font = JBUI.Fonts.label(16f)

        val body =
            JBTextArea().apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                text =
                    """
                    1) Select a Module.
                    2) Pick a Classpath provider:
                       - IDE module runtime: resolves the module runtime classpath from IntelliJ.
                       - Manifest file: uses a text file containing one classpath entry per line.
                    3) Choose output format (used for Export) and Fail-on policy.
                    4) Click Run.

                    After running:
                    - Failures tab shows grouped linkage failures.
                    - Double-click a failure to navigate to the callsite (if line info is available).
                    - Classpath tab shows the exact classpath used (copyable for CLI reproduction).
                    """.trimIndent()
            }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            add(title, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    private fun buildResultsPanel(): JComponent {
        val failuresTab = buildFailuresTab()
        val classpathTab = buildClasspathTab()

        val tabs = JBTabbedPane()
        tabs.addTab("Failures", failuresTab)
        tabs.addTab("Classpath", classpathTab)

        return JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun buildFailuresTab(): JComponent {
        val left = ScrollPaneFactory.createScrollPane(tree, true)
        val right = ScrollPaneFactory.createScrollPane(details, true)
        val splitter = JBSplitter(false, 0.45f)
        splitter.firstComponent = left
        splitter.secondComponent = right
        return splitter
    }

    private fun buildClasspathTab(): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(classpathFingerprintLabel, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(classpathUsed, true), BorderLayout.CENTER)
        }

    private fun wireActions() {
        reloadModulesButton.addActionListener {
            reloadModules(preserveSelection = moduleCombo.selectedItem as? String)
        }

        classpathProviderCombo.addActionListener {
            updateClasspathProviderHelp()
            applyClasspathUiState()
            persistSettings()
        }

        // Browse button: use FileChooserFactory (avoids addBrowseFolderListener API mismatch).
        classpathManifestField.addActionListener {
            val chooser = FileChooserFactory.getInstance().createFileChooser(manifestDescriptor, project, component)
            val chosen = chooser.choose(project).firstOrNull() ?: return@addActionListener
            classpathManifestField.text = chosen.path
            persistSettings()
        }

        // Persist on Enter in the text field as well.
        classpathManifestField.textField.addActionListener {
            persistSettings()
        }

        formatCombo.addActionListener { persistSettings() }
        failOnCombo.addActionListener { persistSettings() }

        runButton.addActionListener {
            persistSettings()
            runScan()
        }

        resetButton.addActionListener {
            clearResults()
            showInstructions()
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
                updateButtonsEnabledState()
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

    private fun updateClasspathProviderHelp() {
        val provider = (classpathProviderCombo.selectedItem as? ClasspathProvider) ?: ClasspathProviders.default()
        classpathProviderHelp.text = provider.description
    }

    private fun applyClasspathUiState() {
        val provider = (classpathProviderCombo.selectedItem as? ClasspathProvider) ?: ClasspathProviders.default()
        val needsManifestFile = provider.id == "manifest-file"
        classpathManifestField.isEnabled = needsManifestFile
    }

    private fun persistSettings() {
        val provider = (classpathProviderCombo.selectedItem as? ClasspathProvider) ?: ClasspathProviders.default()
        settingsService.settings.classpathProviderId = provider.id
        settingsService.settings.classpathManifestPath = classpathManifestField.text.trim().ifEmpty { null }
        settingsService.settings.outputFormat = (formatCombo.selectedItem as? OutputFormat) ?: OutputFormat.JSON
        settingsService.settings.failOnPolicy = (failOnCombo.selectedItem as? FailOnPolicy) ?: FailOnPolicy.ANY
    }

    private fun runScan() {
        val selectedName = (moduleCombo.selectedItem as? String)?.trim().orEmpty()
        val module = resolveLiveModuleByName(selectedName)
        if (module == null) {
            val msg =
                if (selectedName.isBlank()) {
                    "No module selected. Click 'Reload' and pick a module that has compiler output."
                } else {
                    "Module '$selectedName' is not available (it may have been reloaded/disposed). Click 'Reload' and reselect."
                }
            notify("Invalid input", msg, NotificationType.ERROR)
            return
        }

        val outPath = compilerOutputPath(module)
        if (outPath == null) {
            notify(
                "No compiler output",
                "Module '${module.name}' has no configured compiler output path. " +
                    "If you just re-imported Gradle, try reloading the project and re-running.",
                NotificationType.WARNING,
            )
            return
        }

        if (!Files.exists(outPath)) {
            val msg = "Compiler output does not exist: $outPath\n\nBuild the project, then re-run."
            notify("Build required", msg, NotificationType.WARNING)
            return
        }

        val provider = (classpathProviderCombo.selectedItem as? ClasspathProvider) ?: ClasspathProviders.default()
        val manifestPath = classpathManifestField.text.trim().ifEmpty { null }

        if (provider.id == "manifest-file") {
            val p = manifestPath?.let { Path.of(it) }
            if (p == null) {
                notify("Invalid input", "Classpath manifest is required for the 'Manifest file' provider.", NotificationType.WARNING)
                return
            }
            if (!Files.isRegularFile(p)) {
                notify("Invalid input", "Classpath manifest not found: $p", NotificationType.WARNING)
                return
            }
        }

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
                    updateStatus(report)
                    showResults()

                    val (title, msg, type) = outcomeNotification(report)
                    notify(title, msg, type)
                }
            }

            override fun onThrowable(error: Throwable) {
                notify("Scan failed", error.message ?: error.toString(), NotificationType.ERROR)
            }
        }.queue()
    }

    /**
     * IntelliJ can dispose/recreate modules during Gradle re-imports or project model refresh.
     * Holding onto the old [Module] instance (e.g. via a combo box) can throw [AlreadyDisposedException]
     * when queried. Resolve a fresh module instance by name right before use.
     */
    private fun resolveLiveModule(selected: Module?): Module? {
        if (selected == null) return null

        // If the combo item is already disposed, we must resolve a fresh instance.
        val name =
            try {
                selected.name
            } catch (_: AlreadyDisposedException) {
                return null
            }

        val live = ModuleManager.getInstance(project).findModuleByName(name) ?: return null
        if (Disposer.isDisposed(live)) return null
        return live
    }

    private fun resolveLiveModuleByName(moduleName: String): Module? {
        if (moduleName.isBlank()) return null
        val live = ModuleManager.getInstance(project).findModuleByName(moduleName) ?: return null
        if (Disposer.isDisposed(live)) return null
        return live
    }

    private fun reloadModules(preserveSelection: String? = null) {
        val names =
            ModuleManager
                .getInstance(project)
                .modules
                .filterNot { Disposer.isDisposed(it) }
                .map { it.name }
                .distinct()
                .sorted()

        moduleCombo.model = DefaultComboBoxModel(names.toTypedArray())
        moduleCombo.isEnabled = names.isNotEmpty()

        val selected = preserveSelection?.takeIf { it in names } ?: names.firstOrNull()
        if (selected != null) {
            moduleCombo.selectedItem = selected
        }
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

        if (report.failures.isEmpty()) {
            root.add(DefaultMutableTreeNode("No linkage failures found."))
        } else {
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
        }

        treeModel.setRoot(root)
        treeModel.reload()

        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }

        details.text = ""
        lastSelectedFailure = null
        updateButtonsEnabledState()
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
            if (evidence.spi != null) {
                val s = evidence.spi!!
                sb.appendLine("SPI")
                sb.appendLine("  service: ${s.service}")
                if (s.provider != null) sb.appendLine("  provider: ${s.provider}")
                if (s.providerEntry != null) sb.appendLine("  providerEntry: ${s.providerEntry}")
                if (s.serviceFileEntries.isNotEmpty()) {
                    sb.appendLine("  serviceFiles:")
                    s.serviceFileEntries.forEach { sb.appendLine("    - $it") }
                }
            }
            if (evidence.duplicate != null) {
                val d = evidence.duplicate!!
                sb.appendLine("Duplicate")
                sb.appendLine("  class: ${d.className}")
                sb.appendLine("  risk: ${d.riskLevel} (${d.riskScore}/100)")
                sb.appendLine("  identicalBytecode: ${d.identicalBytecode}")
                if (d.abiDifferences.isNotEmpty()) {
                    sb.appendLine("  abiDiffs:")
                    d.abiDifferences.take(10).forEach { diff ->
                        sb.appendLine("    - ${diff.kind}: ${diff.member ?: diff.detail ?: "(n/a)"} @ ${diff.entry}")
                    }
                    if (d.abiDifferences.size > 10) sb.appendLine("    - +${d.abiDifferences.size - 10} more")
                }
            }
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
        if (Disposer.isDisposed(module)) return null
        return try {
            val ext = CompilerModuleExtension.getInstance(module) ?: return null
            val vf = ext.compilerOutputPath ?: return null
            Path.of(vf.path)
        } catch (_: AlreadyDisposedException) {
            null
        }
    }

    private fun updateStatus(report: LinkageReport) {
        val any = report.failures.size
        val errors = report.failures.count { it.severity == Severity.ERROR }
        val warns = report.failures.count { it.severity == Severity.WARN }

        val policy = (failOnCombo.selectedItem as? FailOnPolicy) ?: FailOnPolicy.ANY
        val failed =
            when (policy) {
                FailOnPolicy.ANY -> any > 0
                FailOnPolicy.ERROR_ONLY -> errors > 0
            }

        statusLabel.text =
            if (failed) {
                "FAILED • Failures: $any (errors=$errors, warnings=$warns)"
            } else {
                "OK • Failures: $any (errors=$errors, warnings=$warns)"
            }
    }

    private fun outcomeNotification(report: LinkageReport): Triple<String, String, NotificationType> {
        val any = report.failures.size
        val errors = report.failures.count { it.severity == Severity.ERROR }
        val warns = report.failures.count { it.severity == Severity.WARN }

        val policy = (failOnCombo.selectedItem as? FailOnPolicy) ?: FailOnPolicy.ANY
        val failed =
            when (policy) {
                FailOnPolicy.ANY -> any > 0
                FailOnPolicy.ERROR_ONLY -> errors > 0
            }

        val msg = "Failures: $any (errors=$errors, warnings=$warns)"
        return if (failed) {
            Triple("Scan finished (FAILED)", msg, NotificationType.ERROR)
        } else {
            Triple("Scan finished", msg, NotificationType.INFORMATION)
        }
    }

    private fun clearResults() {
        lastReport = null
        lastSelectedFailure = null
        lastClasspathManifest = null

        statusLabel.text = "Configure and run a scan."
        classpathUsed.text = ""
        classpathFingerprintLabel.text = "Classpath fingerprint: (not resolved)"
        details.text = ""

        treeModel.setRoot(DefaultMutableTreeNode("Run a scan"))
        treeModel.reload()

        updateButtonsEnabledState()
    }

    private fun updateButtonsEnabledState() {
        exportButton.isEnabled = lastReport != null
        copyFailureButton.isEnabled = lastSelectedFailure != null
        copyClasspathButton.isEnabled = !lastClasspathManifest.isNullOrBlank()
    }

    private fun showInstructions() {
        (resultsCards.layout as CardLayout).show(resultsCards, CARD_INSTRUCTIONS)
    }

    private fun showResults() {
        (resultsCards.layout as CardLayout).show(resultsCards, CARD_RESULTS)
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
        private const val CARD_INSTRUCTIONS: String = "instructions"
        private const val CARD_RESULTS: String = "results"

        private const val TOOL_NAME: String = "enkidu-linkage-doctor"
        private const val RESOLVER_MODE: String = "jvm-linkage-sim-v1"
        private const val NOTIFICATION_GROUP_ID: String = "Enkidu Linkage Doctor"
    }
}
