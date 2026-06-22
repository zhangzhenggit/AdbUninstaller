package com.lenovo.tools.apppurge

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EventObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

private const val PLUGIN_VERSION = "1.2.73"
private const val TOGGLE_DEFAULT = "Show All Installed Apps"
private const val CARD_TOGGLE = "toggle"
private const val CARD_LOADING = "loading"
private const val ACTION_BUTTON_SIZE = 38
private const val DATA_ROW_HEIGHT = 58
private const val DIVIDER_ROW_HEIGHT = 6
private val ACTION_COLS = setOf(
    UninstallTableModel.COL_REINSTALL,
    UninstallTableModel.COL_CLEAR,
    UninstallTableModel.COL_UNINSTALL,
    UninstallTableModel.COL_PUSH,
)
private const val DIVIDER_LINE_INSET = 18
private const val PROJECT_SCAN_MAX_ATTEMPTS = 20
private const val PROJECT_SCAN_RETRY_DELAY_MS = 1500L

class UninstallDialog(
    private val project: Project,
    private var projectAppInfos: List<AppInstallInfo>,
    private val deviceNames: Map<String, String>,
    private val projectBasePath: String?,
) : DialogWrapper(project, true) {

    private val adbPath = AdbService.adbPath(projectBasePath)
    private val serials = deviceNames.keys.toList()

    private val deviceCombo = ComboBox(deviceNames.values.toTypedArray())
    private val showAllToggle = JCheckBox(TOGGLE_DEFAULT)
    private val toggleWrapper = JPanel(CardLayout())
    private lateinit var tableModel: UninstallTableModel
    private lateinit var table: JBTable
    private val statusLabel = JLabel("Ready").apply {
        foreground = UIManager.getColor("Label.disabledForeground")
    }
    private val summaryLabel = JLabel()
    private var loading = false
    private val reinstallingPackages = mutableSetOf<String>()
    private val clearingPackages = mutableSetOf<String>()
    private val uninstallingPackages = mutableSetOf<String>()
    private val preparingPushPackages = mutableSetOf<String>()
    private val pushingPackages = mutableSetOf<String>()
    private val pushLocks = mutableMapOf<String, Any>()
    private var actionSpinnerTimer: Timer? = null
    private var copyStatusTimer: Timer? = null
    private var pressedActionRow = -1
    private var pressedActionCol = -1
    private val nameResolveRunId = AtomicInteger(0)
    private val uninstallBtn = JButton("Uninstall Selected").apply {
        foreground = Color(0xD3, 0x56, 0x5C)
    }
    private val cancelNameResolveBtn = JButton("Stop").apply {
        isVisible = false
        margin = Insets(0, 8, 0, 8)
        addActionListener { cancelNameResolving("Name resolving stopped") }
    }
    private var cachedSnapshot: AdbService.PackageSnapshot? = null
    private var cachedSnapshotSerial: String? = null
    private lateinit var rebootRequiredBtn: JButton

    init {
        title = "APK Manager"
        init()
        if (serials.isNotEmpty()) loadInstallStatus(serials[0], showAll = false, rescanProject = true)
    }

    override fun createCenterPanel(): JComponent {
        tableModel = UninstallTableModel()
        tableModel.addTableModelListener { updateSummary() }
        table = object : JBTable(tableModel) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val baseColor = UIManager.getColor("Separator.foreground") ?: UIManager.getColor("Component.borderColor") ?: Color.GRAY
                val lineColor = blendColors(baseColor, background, 0.55f)
                g.color = lineColor
                tableModel.rows.forEachIndexed { index, row ->
                    if (row is TableRow.Divider) {
                        val rect = getCellRect(index, 0, true)
                        val y = rect.y + rect.height / 2
                        g.drawLine(DIVIDER_LINE_INSET, y, dividerLineEndX(), y)
                    }
                }
            }

            override fun getToolTipText(e: MouseEvent): String? {
                val row = rowAtPoint(e.point)
                val col = columnAtPoint(e.point)
                val data = tableModel.rows.getOrNull(row) as? TableRow.Data ?: return null
                return when (col) {
                    UninstallTableModel.COL_REINSTALL -> actionTooltip(RowAction.REINSTALL, data.info)
                    UninstallTableModel.COL_CLEAR -> actionTooltip(RowAction.CLEAR, data.info)
                    UninstallTableModel.COL_UNINSTALL -> actionTooltip(RowAction.UNINSTALL, data.info)
                    UninstallTableModel.COL_PUSH -> actionTooltip(RowAction.PUSH, data.info)
                    UninstallTableModel.COL_STATUS -> statusTooltip(data.info)
                    else -> appTooltip(data.info)
                }
            }
        }.apply {
            rowSelectionAllowed = false
            columnSelectionAllowed = false
            cellSelectionEnabled = false
            selectionBackground = background
            selectionForeground = foreground
            intercellSpacing = Dimension(1, 1)
            rowHeight = DATA_ROW_HEIGHT
            columnModel.getColumn(UninstallTableModel.COL_CHECK).apply { maxWidth = 54; minWidth = 54 }
            columnModel.getColumn(UninstallTableModel.COL_APP).preferredWidth = 280
            columnModel.getColumn(UninstallTableModel.COL_STATUS).preferredWidth = 110
            for (col in listOf(UninstallTableModel.COL_REINSTALL, UninstallTableModel.COL_CLEAR, UninstallTableModel.COL_UNINSTALL, UninstallTableModel.COL_PUSH)) {
                columnModel.getColumn(col).apply { maxWidth = DATA_ROW_HEIGHT; minWidth = DATA_ROW_HEIGHT }
            }

            val universalRenderer = UniversalRenderer()
            columnModel.getColumn(UninstallTableModel.COL_CHECK).cellRenderer = universalRenderer
            columnModel.getColumn(UninstallTableModel.COL_APP).cellRenderer = universalRenderer
            columnModel.getColumn(UninstallTableModel.COL_STATUS).cellRenderer = universalRenderer
            columnModel.getColumn(UninstallTableModel.COL_REINSTALL).also {
                it.cellRenderer = ActionCellRenderer(RowAction.REINSTALL)
                it.cellEditor = ActionCellEditor(RowAction.REINSTALL)
            }
            columnModel.getColumn(UninstallTableModel.COL_CLEAR).also {
                it.cellRenderer = ActionCellRenderer(RowAction.CLEAR)
                it.cellEditor = ActionCellEditor(RowAction.CLEAR)
            }
            columnModel.getColumn(UninstallTableModel.COL_UNINSTALL).also {
                it.cellRenderer = ActionCellRenderer(RowAction.UNINSTALL)
                it.cellEditor = ActionCellEditor(RowAction.UNINSTALL)
            }
            columnModel.getColumn(UninstallTableModel.COL_PUSH).also {
                it.cellRenderer = ActionCellRenderer(RowAction.PUSH)
                it.cellEditor = ActionCellEditor(RowAction.PUSH)
            }

            // Merged "Options" header spanning the action button columns
            tableHeader = object : JTableHeader(columnModel) {
                init { defaultRenderer = CenterHeaderRenderer(defaultRenderer) }
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val r1 = getHeaderRect(UninstallTableModel.COL_REINSTALL)
                    val r4 = getHeaderRect(UninstallTableModel.COL_PUSH)
                    val x = r1.x; val w = r4.x + r4.width - r1.x
                    val g2 = g.create() as Graphics2D
                    g2.color = background
                    g2.fillRect(x + 1, 1, w - 2, height - 2)
                    val sep = UIManager.getColor("TableHeader.separatorColor")
                        ?: UIManager.getColor("Separator.foreground") ?: Color.GRAY
                    g2.color = sep
                    g2.drawLine(x, 0, x, height - 1)
                    g2.drawLine(x + w - 1, 0, x + w - 1, height - 1)
                    g2.color = foreground
                    g2.font = font
                    val fm = g2.fontMetrics
                    val text = "Options"
                    g2.drawString(text, x + (w - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
                    g2.dispose()
                }
            }

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    pressedActionRow = -1
                    pressedActionCol = -1
                    clearSelection()
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    val tableRow = tableModel.rows.getOrNull(row) as? TableRow.Data ?: return
                    when (col) {
                        UninstallTableModel.COL_CHECK -> return
                        UninstallTableModel.COL_APP -> copyPackageName(tableRow.info)
                        in ACTION_COLS -> {
                            pressedActionRow = row
                            pressedActionCol = col
                        }
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    val savedRow = pressedActionRow
                    val savedCol = pressedActionCol
                    pressedActionRow = -1
                    pressedActionCol = -1
                    if (savedCol in ACTION_COLS) cellEditor?.cancelCellEditing()
                    if (savedRow < 0 || savedCol !in ACTION_COLS) return
                    val releaseRow = rowAtPoint(e.point)
                    val releaseCol = columnAtPoint(e.point)
                    if (savedRow != releaseRow || savedCol != releaseCol) return
                    val info = (tableModel.rows.getOrNull(savedRow) as? TableRow.Data)?.info ?: return
                    val serial = currentSerial() ?: return
                    when (savedCol) {
                        UninstallTableModel.COL_REINSTALL -> chooseApkAndReinstall(serial, info)
                        UninstallTableModel.COL_CLEAR -> onClearData(serial, info)
                        UninstallTableModel.COL_UNINSTALL -> onUninstallOne(serial, info)
                        UninstallTableModel.COL_PUSH -> showPushDialog(serial, info)
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (pressedActionCol in ACTION_COLS) cellEditor?.cancelCellEditing()
                }
            })
        }

        val spinnerLabel = JLabel("Fetching device apps…", AnimatedIcon.Default(), SwingConstants.LEFT).apply {
            border = JBUI.Borders.empty(0, 2)
        }
        toggleWrapper.add(showAllToggle, CARD_TOGGLE)
        toggleWrapper.add(spinnerLabel, CARD_LOADING)

        val deviceControlsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            border = JBUI.Borders.empty(10, 10, 8, 10)
            add(JLabel("Device:"))
            add(deviceCombo.apply {
                preferredSize = Dimension(290, 32)
                addActionListener {
                    refreshRebootRequiredState()
                    reload()
                }
            })
            add(JButton("Refresh").apply {
                preferredSize = Dimension(96, 32)
                addActionListener {
                    refreshRebootRequiredState()
                    reload(rescanProject = true)
                }
            })
            add(toggleWrapper)
            showAllToggle.addActionListener { reload() }
        }
        val rebootPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            border = JBUI.Borders.empty(10, 10, 8, 10)
            rebootRequiredBtn = JButton(IconLoader.getIcon("/icons/action_reboot_required.svg", UninstallDialog::class.java)).apply {
                isVisible = false
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusPainted = false
                isOpaque = false
                isFocusable = false
                preferredSize = Dimension(ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)
                minimumSize = preferredSize
                maximumSize = preferredSize
                margin = Insets(0, 0, 0, 0)
                toolTipText = "Reboot required for pushed system APK changes to take effect"
                addActionListener { onRebootRequiredClicked() }
            }
            add(rebootRequiredBtn)
        }
        val devicePanel = JPanel(BorderLayout()).apply {
            add(deviceControlsPanel, BorderLayout.WEST)
            add(rebootPanel, BorderLayout.EAST)
        }

        val tableScroll = JBScrollPane(table).apply {
            preferredSize = Dimension(1040, 480)
        }

        val selectAllBtn = JButton("Select All").apply {
            preferredSize = Dimension(100, 32)
            addActionListener { tableModel.setSelectAll(true); updateSummary() }
        }
        val deselectAllBtn = JButton("Deselect All").apply {
            preferredSize = Dimension(116, 32)
            addActionListener { tableModel.setSelectAll(false); updateSummary() }
        }
        uninstallBtn.addActionListener { onBatchUninstall() }
        uninstallBtn.preferredSize = Dimension(168, 32)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(selectAllBtn); add(deselectAllBtn)
        }
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(summaryLabel); add(uninstallBtn)
        }
        val statusPanel = JPanel(GridBagLayout()).apply {
            add(statusLabel, GridBagConstraints().apply {
                gridx = 0
                insets = Insets(0, 0, 0, 6)
                anchor = GridBagConstraints.CENTER
            })
            add(cancelNameResolveBtn, GridBagConstraints().apply {
                gridx = 1
                anchor = GridBagConstraints.CENTER
            })
        }
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(14, 10, 10, 10)
            add(leftPanel, BorderLayout.WEST)
            add(statusPanel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
        }

        SwingUtilities.invokeLater { refreshRebootRequiredState() }
        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(0)
            add(devicePanel, BorderLayout.NORTH)
            add(tableScroll, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }
    }

    override fun createSouthPanel(): JComponent {
        val original = super.createSouthPanel()
        val versionLabel = JLabel("v$PLUGIN_VERSION").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            border = JBUI.Borders.empty(0, 10)
        }
        return JPanel(BorderLayout()).apply {
            add(versionLabel, BorderLayout.WEST)
            add(original, BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun doCancelAction() {
        cancelNameResolving()
        super.doCancelAction()
    }

    private fun currentSerial(): String? {
        val idx = deviceCombo.selectedIndex
        return if (idx >= 0 && idx < serials.size) serials[idx] else null
    }

    private fun reload(rescanProject: Boolean = false) {
        val serial = currentSerial() ?: return
        loadInstallStatus(serial, showAll = showAllToggle.isSelected, rescanProject = rescanProject)
    }

    private fun setLoading(loading: Boolean) {
        this.loading = loading
        uninstallBtn.isEnabled = !loading && tableModel.selectedCount > 0
        deviceCombo.isEnabled = !loading
        showAllToggle.isEnabled = !loading
        (toggleWrapper.layout as CardLayout).show(toggleWrapper, if (loading) CARD_LOADING else CARD_TOGGLE)
        updateSummary()
    }

    private fun setNameResolving(resolving: Boolean) {
        cancelNameResolveBtn.isVisible = resolving
        cancelNameResolveBtn.parent?.revalidate()
        cancelNameResolveBtn.parent?.repaint()
    }

    private fun cancelNameResolving(status: String? = null) {
        nameResolveRunId.incrementAndGet()
        setNameResolving(false)
        if (status != null) setStatus(status)
    }

    private fun runBackground(name: String, block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (e: Throwable) {
                SwingUtilities.invokeLater {
                    if (!project.isDisposed) setStatus("Error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }.also {
            it.isDaemon = true
            it.name = name
            it.start()
        }
    }

    private fun updateRowHeights() {
        tableModel.rows.forEachIndexed { i, row ->
            table.setRowHeight(i, if (row is TableRow.Divider) DIVIDER_ROW_HEIGHT else DATA_ROW_HEIGHT)
        }
    }

    private fun loadInstallStatus(serial: String, showAll: Boolean, rescanProject: Boolean = false) {
        cancelNameResolving()

        // Fast path: Show All toggle changed, but same device and project statuses are valid.
        // Just add/remove the device section without re-querying project modules.
        val snapshot = cachedSnapshot
        if (!rescanProject && serial == cachedSnapshotSerial && snapshot != null
            && projectAppInfos.isNotEmpty() && projectAppInfos.none { it.status == InstallStatus.UNKNOWN }
        ) {
            setLoading(true)
            if (!showAll) {
                SwingUtilities.invokeLater {
                    tableModel.resetItems(projectAppInfos)
                    updateRowHeights()
                    setLoading(false)
                    setStatus(projectLoadStatus(projectAppInfos))
                }
            } else {
                setStatus("Fetching all user apps…")
                runBackground("AppPurge-ADB") { resolveDeviceApps(serial, snapshot, projectAppInfos) }
            }
            return
        }

        // Full load: re-query project module statuses and (optionally) re-scan modules.
        setLoading(true)
        setStatus(if (rescanProject) "Scanning project modules…" else if (showAll) "Fetching all user apps…" else "Querying project modules…")

        runBackground("AppPurge-ADB") {
            try {
                val projectInfos = if (rescanProject) scanProjectModulesWithRetry() else projectAppInfos
                projectAppInfos = projectInfos

                projectInfos.forEach { info -> info.status = InstallStatus.UNKNOWN }
                SwingUtilities.invokeAndWait {
                    tableModel.resetItems(projectInfos)
                    updateRowHeights()
                }

                projectInfos.forEach { info ->
                    if (info.module != null) info.apkFiles = ApkFinder.findApks(info.module, info.packageName)
                }
                val projectLoadStatus = projectLoadStatus(projectInfos)
                val freshSnapshot = AdbService.getPackageSnapshot(serial, adbPath)
                if (freshSnapshot.isEmpty) {
                    SwingUtilities.invokeLater {
                        setNameResolving(false)
                        setStatus("ADB query failed — check device connection")
                        setLoading(false)
                    }
                    return@runBackground
                }
                cachedSnapshot = freshSnapshot
                cachedSnapshotSerial = serial

                projectInfos.forEach { info ->
                    info.status = AdbService.queryProjectPackageStatus(
                        serial = serial,
                        packageName = info.packageName,
                        installedPackages = freshSnapshot.installedPackages,
                        systemPackages = freshSnapshot.systemPackages,
                        adb = adbPath,
                    )
                    if (info.isInstalled) {
                        info.installTimeMs = AdbService.getInstallTime(serial, info.packageName, adbPath)
                        info.activeApkPaths = AdbService.getActivePackagePaths(serial, info.packageName, adbPath)
                    } else {
                        info.installTimeMs = 0L
                        info.activeApkPaths = emptyList()
                    }
                    SwingUtilities.invokeLater { tableModel.notifyRowChanged(info.packageName) }
                }

                if (showAll) {
                    resolveDeviceApps(serial, freshSnapshot, projectInfos)
                } else {
                    SwingUtilities.invokeLater {
                        tableModel.resetItems(projectInfos)
                        updateRowHeights()
                        setLoading(false)
                        setStatus(projectLoadStatus)
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setNameResolving(false)
                    setStatus("Error: ${e.message ?: e.javaClass.simpleName}")
                    setLoading(false)
                }
            }
        }
    }

    private fun resolveDeviceApps(serial: String, snapshot: AdbService.PackageSnapshot, projectInfos: List<AppInstallInfo>) {
        val projectPkgs = projectInfos.map { it.packageName }.toSet()
        val userPkgs = (snapshot.userPackages - projectPkgs).sorted()
        val deviceItems = userPkgs.map { pkg ->
            AppInstallInfo(
                module = null,
                moduleName = displayNameFromPackage(pkg),
                packageName = pkg,
                status = InstallStatus.USER_APP,
            )
        }
        val runId = nameResolveRunId.incrementAndGet()
        SwingUtilities.invokeAndWait {
            tableModel.resetItems(projectInfos, deviceItems)
            updateRowHeights()
            setLoading(false)
            setNameResolving(true)
            setStatus("Project Apps: ${projectInfos.size} · Installed Apps: ${userPkgs.size} · resolving names 0 / ${userPkgs.size}")
        }
        val completed = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "AppPurge-NameResolver").apply { isDaemon = true }
        }
        userPkgs.forEach { pkg ->
            pool.execute {
                if (runId != nameResolveRunId.get()) return@execute
                val label = AdbService.getApplicationLabel(serial, pkg, adbPath)
                val activePaths = AdbService.getActivePackagePaths(serial, pkg, adbPath)
                if (runId != nameResolveRunId.get()) return@execute
                val done = completed.incrementAndGet()
                SwingUtilities.invokeLater {
                    if (runId == nameResolveRunId.get()) {
                        tableModel.updateDeviceLabel(pkg, label)
                        tableModel.updateActiveApkPaths(pkg, activePaths)
                        setStatus("Project Apps: ${projectInfos.size} · Installed Apps: ${userPkgs.size} · resolving names $done / ${userPkgs.size}")
                    }
                }
            }
        }
        pool.shutdown()
        while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
            if (runId != nameResolveRunId.get()) {
                pool.shutdownNow()
                return
            }
        }
        SwingUtilities.invokeLater {
            if (runId == nameResolveRunId.get()) {
                setNameResolving(false)
                setStatus(
                    buildString {
                        append("Project Apps: ${projectInfos.size} · Installed Apps: ${userPkgs.size}")
                        val ps = projectLoadStatus(projectInfos)
                        if (ps.isNotBlank()) append(" · ").append(ps)
                    }
                )
            }
        }
    }

    private fun scanProjectModulesWithRetry(): List<AppInstallInfo> {
        repeat(PROJECT_SCAN_MAX_ATTEMPTS) { index ->
            val infos = AppModuleScanner.scan(project)
            if (infos.isNotEmpty()) return infos
            val attempt = index + 1
            SwingUtilities.invokeLater {
                setStatus("Waiting for Android application modules to load… $attempt / $PROJECT_SCAN_MAX_ATTEMPTS")
            }
            if (attempt < PROJECT_SCAN_MAX_ATTEMPTS) Thread.sleep(PROJECT_SCAN_RETRY_DELAY_MS)
        }
        return emptyList()
    }

    private fun projectLoadStatus(projectInfos: List<AppInstallInfo>): String = when {
        projectInfos.isEmpty() -> "No Android application modules found. Project model may still be loading; try Refresh after Gradle sync finishes."
        projectInfos.all { it.apkFiles.isEmpty() } -> "Application modules found, but no APK files found. Build the module first."
        else -> ""
    }

    private fun onBatchUninstall() {
        val selected = tableModel.selectedItems
        if (selected.isEmpty()) { Messages.showInfoMessage("No installable packages selected.", "AppPurge"); return }
        val serial = currentSerial() ?: run { Messages.showErrorDialog("No device connected.", "AppPurge"); return }
        if (Messages.showOkCancelDialog(
                "Uninstall ${selected.size} package(s)?",
                "AppPurge", "Uninstall", "Cancel", Messages.getQuestionIcon(),
            ) != Messages.OK) return

        uninstallBtn.isEnabled = false
        setStatus("Uninstalling…")
        data class BatchUninstallResult(
            val info: AppInstallInfo,
            val result: AdbService.CommandResult,
            val status: InstallStatus,
            val activePaths: List<String>,
        )
        runBackground("AppPurge-BatchUninstall") {
            val results = selected.map { info -> info to AdbService.uninstallPackage(serial, info.packageName, adbPath) }
            val snapshot = AdbService.getPackageSnapshot(serial, adbPath)
            val statuses = results.map { (info, result) ->
                val newStatus = AdbService.queryProjectPackageStatus(
                    serial, info.packageName, snapshot.installedPackages, snapshot.systemPackages, adbPath,
                )
                BatchUninstallResult(info, result, newStatus, activePathsForStatus(serial, info.packageName, newStatus))
            }
            var successCnt = 0
            SwingUtilities.invokeLater {
                statuses.forEach { (info, result, newStatus, activePaths) ->
                    val ok = result.success || newStatus == InstallStatus.NOT_INSTALLED || newStatus == InstallStatus.SYSTEM_APP
                    if (ok) successCnt++
                    applyPostOperationStatus(info, newStatus, activePaths)
                }
                uninstallBtn.isEnabled = true
                setStatus("Uninstalled $successCnt / ${selected.size}")
            }
        }
    }

    private fun chooseApkAndReinstall(serial: String, info: AppInstallInfo) {
        if (!isActionEnabled(RowAction.REINSTALL, info)) return
        if (info.apkFiles.isEmpty()) {
            Messages.showInfoMessage(
                "No APK found for ${info.moduleName.ifEmpty { info.packageName }}.\nPlease build the module first.",
                "AppPurge",
            )
            return
        }
        val apk = if (info.apkFiles.size == 1) {
            info.apkFiles[0]
        } else {
            val options = info.apkFiles.map { apkLabel(it) }.toTypedArray()
            val choice = Messages.showChooseDialog(
                "Multiple APKs found for ${info.moduleName.ifEmpty { info.packageName }}:",
                "Choose APK",
                options,
                options[0],
                Messages.getQuestionIcon(),
            )
            if (choice < 0) return
            info.apkFiles[choice]
        }
        onReinstall(serial, info, apk)
    }

    private fun onClearData(serial: String, info: AppInstallInfo) {
        if (!isActionEnabled(RowAction.CLEAR, info)) return
        if (Messages.showOkCancelDialog(
                "Clear app data for ${info.packageName}?",
                "AppPurge", "Clear Data", "Cancel", Messages.getQuestionIcon(),
            ) != Messages.OK) return
        if (!clearingPackages.add(info.packageName)) return
        refreshActionRendering()
        runBackground("AppPurge-ClearData") {
            val result = AdbService.clearAppData(serial, info.packageName, adbPath)
            val newStatus = AdbService.getPackageState(serial, info.packageName, adbPath)
            val activePaths = activePathsForStatus(serial, info.packageName, newStatus)
            val currentUser = if (!result.success) AdbService.getCurrentUser(serial, adbPath) else ""
            SwingUtilities.invokeLater {
                clearingPackages.remove(info.packageName)
                refreshActionRendering()
                tableModel.updateRow(info.packageName, newStatus, activeApkPaths = activePaths)
                if (result.success) {
                    setStatus("")
                } else {
                    setStatus("Clear data failed: ${info.packageName}")
                    Messages.showErrorDialog(commandFailureMessage(currentUser, "Failed to clear app data.", result.output, info), "AppPurge Clear Data Failed")
                }
            }
        }
    }

    private fun onUninstallOne(serial: String, info: AppInstallInfo) {
        if (!isActionEnabled(RowAction.UNINSTALL, info)) return
        if (Messages.showOkCancelDialog(
                "Uninstall ${info.packageName}?",
                "AppPurge", "Uninstall", "Cancel", Messages.getQuestionIcon(),
            ) != Messages.OK) return
        if (!uninstallingPackages.add(info.packageName)) return
        refreshActionRendering()
        runBackground("AppPurge-Uninstall") {
            val result = AdbService.uninstallPackage(serial, info.packageName, adbPath)
            val newStatus = AdbService.getPackageState(serial, info.packageName, adbPath)
            val activePaths = activePathsForStatus(serial, info.packageName, newStatus)
            val currentUser = if (!result.success && newStatus != InstallStatus.NOT_INSTALLED && newStatus != InstallStatus.SYSTEM_APP) AdbService.getCurrentUser(serial, adbPath) else ""
            SwingUtilities.invokeLater {
                uninstallingPackages.remove(info.packageName)
                refreshActionRendering()
                applyPostOperationStatus(info, newStatus, activePaths)
                if (result.success || newStatus == InstallStatus.NOT_INSTALLED || newStatus == InstallStatus.SYSTEM_APP) {
                    setStatus("")
                } else {
                    setStatus("Uninstall failed: ${info.packageName}")
                    Messages.showErrorDialog(commandFailureMessage(currentUser, "Failed to uninstall app.", result.output, info), "AppPurge Uninstall Failed")
                }
            }
        }
    }

    private fun onReinstall(serial: String, info: AppInstallInfo, apk: File) {
        if (!reinstallingPackages.add(info.packageName)) return
        refreshActionRendering()
        runBackground("AppPurge-Reinstall") {
            val result = AdbService.installApk(serial, apk.absolutePath, adbPath)
            val newStatus = AdbService.getPackageState(serial, info.packageName, adbPath)
            val currentUser = if (!result.success) AdbService.getCurrentUser(serial, adbPath) else ""
            val installTimeMs = if (newStatus.isInstalled) AdbService.getInstallTime(serial, info.packageName, adbPath) else 0L
            val activePaths = activePathsForStatus(serial, info.packageName, newStatus)
            SwingUtilities.invokeLater {
                reinstallingPackages.remove(info.packageName)
                refreshActionRendering()
                if (newStatus.isInstalled) {
                    tableModel.updateRow(info.packageName, newStatus, installTimeMs, activePaths)
                    setStatus("")
                } else {
                    tableModel.updateRow(info.packageName, newStatus, activeApkPaths = activePaths)
                    setStatus("Install failed: ${info.packageName}")
                    Messages.showErrorDialog(
                        commandFailureMessage(currentUser, "Failed to install APK with adb install -r -t.", result.output, info),
                        "AppPurge Install Failed",
                    )
                }
            }
        }
    }

    private fun showPushDialog(serial: String, info: AppInstallInfo) {
        if (!isActionEnabled(RowAction.PUSH, info)) return
        if (!preparingPushPackages.add(info.packageName)) return
        refreshActionRendering()
        runBackground("AppPurge-SystemTarget") {
            val targetResult = runCatching { AdbService.getSystemApkTarget(serial, info.packageName, info.systemPathName, adbPath) }
            SwingUtilities.invokeLater {
                preparingPushPackages.remove(info.packageName)
                refreshActionRendering()
                if (currentSerial() != serial) return@invokeLater
                val target = targetResult.getOrElse {
                    Messages.showErrorDialog("Failed to resolve system APK target for ${info.packageName}.", "AppPurge")
                    return@invokeLater
                }
                val dialog = PushSystemApkDialog(project, projectBasePath, adbPath, serial, info, target)
                if (dialog.showAndGet()) {
                    onPushSystemApk(serial, info, dialog.request())
                }
            }
        }
    }

    private fun onPushSystemApk(serial: String, info: AppInstallInfo, request: SystemPushRequest) {
        if (!pushingPackages.add(info.packageName)) return
        refreshActionRendering()
        setStatus("Preparing device...")
        runBackground("AppPurge-SystemPush") {
            val pushLock = synchronized(pushLocks) { pushLocks.getOrPut(serial) { Any() } }
            synchronized(pushLock) {
                val remount = AdbService.prepareRootRemount(serial, adbPath)
                if (!remount.success) {
                    SwingUtilities.invokeLater {
                        pushingPackages.remove(info.packageName)
                        refreshActionRendering()
                        setStatus("Remount failed: ${info.packageName}")
                        showRemountFailure(serial, remount)
                    }
                    return@runBackground
                }

                SwingUtilities.invokeLater { setStatus("Pushing APK...") }
                val result = AdbService.pushSystemApk(request)
                val bootId = if (result.success) AdbService.getBootId(serial, adbPath) else null
                val newStatus = AdbService.getPackageState(serial, info.packageName, adbPath)
                val activePaths = activePathsForStatus(serial, info.packageName, newStatus)
                SwingUtilities.invokeLater {
                    pushingPackages.remove(info.packageName)
                    refreshActionRendering()
                    tableModel.updateRow(info.packageName, newStatus, activeApkPaths = activePaths)
                    if (result.success) {
                        if (bootId != null) pendingRebootBootIds[serial] = bootId
                        refreshRebootRequiredState()
                        setStatus("Push completed, reboot required")
                        showPushSuccess(serial, request, result)
                    } else {
                        setStatus("Push failed: ${info.packageName}")
                        Messages.showErrorDialog(
                            pushFailureMessage(info, request, result),
                            "AppPurge Push Failed",
                        )
                    }
                }
            }
        }
    }

    private fun showRemountFailure(serial: String, remount: RemountResult) {
        val message = """
            System partition is not writable.
            The device may require reboot after adb root/remount, then run Push again.

            ADB output:
            ${remount.output.ifBlank { "ADB returned no output." }}
        """.trimIndent()
        val choice = Messages.showOkCancelDialog(
            message,
            "AppPurge Remount Failed",
            "Reboot Now",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (choice == Messages.OK) rebootDevice(serial, clearPending = false)
    }

    private fun showPushSuccess(serial: String, request: SystemPushRequest, result: SystemPushResult) {
        val message = buildString {
            append("Push completed.\nReboot is required for the system app to take effect.")
            if (request.removeDataOverlay && !result.dataOverlayRemoved) {
                append("\n\nWarning: /data/app overlay could not be removed. After reboot, the system version may not take effect if the user-installed overlay persists.")
            }
            if (request.clearData && !result.dataCleared) {
                append("\n\nWarning: app data could not be cleared. The APK was pushed, but existing app data may remain.")
            }
        }
        val choice = Messages.showOkCancelDialog(
            message,
            "AppPurge",
            "Reboot Now",
            "Later",
            Messages.getInformationIcon(),
        )
        if (choice == Messages.OK) rebootDevice(serial, clearPending = true)
    }

    private fun rebootDevice(serial: String, clearPending: Boolean) {
        if (clearPending) pendingRebootBootIds.remove(serial)
        refreshRebootRequiredState()
        setStatus("Rebooting device...")
        runBackground("AppPurge-Reboot") {
            AdbService.exec(listOf(adbPath, "-s", serial, "reboot"), timeoutSec = 10)
            SwingUtilities.invokeLater { setStatus("") }
        }
    }

    private fun activePathsForStatus(serial: String, packageName: String, status: InstallStatus): List<String> =
        if (status.isInstalled) AdbService.getActivePackagePaths(serial, packageName, adbPath) else emptyList()

    private fun applyPostOperationStatus(info: AppInstallInfo, status: InstallStatus, activePaths: List<String> = emptyList()) {
        if (!info.isFromProject && (status == InstallStatus.NOT_INSTALLED || status == InstallStatus.SYSTEM_APP)) {
            tableModel.removeDeviceItem(info.packageName)
            updateRowHeights()
        } else {
            tableModel.updateRow(info.packageName, status, activeApkPaths = activePaths)
        }
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
        updateSummary()
    }

    private fun copyPackageName(info: AppInstallInfo) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(info.packageName), null)
        copyStatusTimer?.stop()
        val normalColor = UIManager.getColor("Label.disabledForeground")
        statusLabel.foreground = Color(0x64, 0xB5, 0xF6)
        setStatus("Copied package: ${info.packageName}")
        copyStatusTimer = Timer(1800) {
            statusLabel.foreground = normalColor
            setStatus("")
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun updateSummary() {
        if (!this::tableModel.isInitialized) return
        val visible = tableModel.visibleItems
        val installed = visible.count { it.isInstalled }
        val selected = tableModel.selectedCount
        summaryLabel.text = buildString {
            if (selected > 0) append("$selected selected · ")
            append("$installed / ${visible.size} installed")
        }
        summaryLabel.foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        uninstallBtn.text = if (selected > 0) "Uninstall Selected ($selected)" else "Uninstall Selected"
        uninstallBtn.isEnabled = !loading && selected > 0
    }

    private fun refreshRebootRequiredState() {
        if (!this::rebootRequiredBtn.isInitialized) return
        val serial = currentSerial()
        if (serial == null) {
            rebootRequiredBtn.isVisible = false
            return
        }
        val expectedBootId = pendingRebootBootIds[serial]
        if (expectedBootId == null) {
            rebootRequiredBtn.isVisible = false
            return
        }
        runBackground("AppPurge-BootId") {
            val currentBootId = AdbService.getBootId(serial, adbPath)
            SwingUtilities.invokeLater {
                val stillPending = currentBootId != null && pendingRebootBootIds[serial] == currentBootId
                if (!stillPending) pendingRebootBootIds.remove(serial)
                rebootRequiredBtn.isVisible = stillPending
                rebootRequiredBtn.parent?.revalidate()
                rebootRequiredBtn.parent?.repaint()
            }
        }
    }

    private fun onRebootRequiredClicked() {
        val serial = currentSerial() ?: return
        refreshRebootRequiredState()
        if (!pendingRebootBootIds.containsKey(serial)) return
        if (Messages.showOkCancelDialog(
                "Reboot device now?",
                "AppPurge",
                "Reboot Now",
                "Cancel",
                Messages.getQuestionIcon(),
            ) == Messages.OK) {
            rebootDevice(serial, clearPending = true)
        }
    }

    private fun dividerLineEndX(): Int {
        if (!this::table.isInitialized) return 0
        val lastColRect = table.getCellRect(0, UninstallTableModel.COL_PUSH, true)
        return (lastColRect.x + lastColRect.width - DIVIDER_LINE_INSET).coerceAtMost(table.width - DIVIDER_LINE_INSET)
    }

    private fun activeAction(info: AppInstallInfo): RowAction? = when (info.packageName) {
        in reinstallingPackages -> RowAction.REINSTALL
        in clearingPackages -> RowAction.CLEAR
        in uninstallingPackages -> RowAction.UNINSTALL
        in preparingPushPackages -> RowAction.PUSH
        in pushingPackages -> RowAction.PUSH
        else -> null
    }

    private fun isActionEnabled(action: RowAction, info: AppInstallInfo): Boolean {
        if (currentSerial() == null) return false
        if (activeAction(info) != null) return false
        return when (action) {
            RowAction.REINSTALL -> info.isFromProject && info.apkFiles.isNotEmpty()
            RowAction.CLEAR -> info.isClearDataEnabled
            RowAction.UNINSTALL -> info.isUninstallable
            RowAction.PUSH -> info.isFromProject && info.apkFiles.isNotEmpty()
        }
    }

    private fun refreshActionRendering() {
        if (this::table.isInitialized) table.repaint()
        if (hasActiveAction()) {
            if (actionSpinnerTimer == null) {
                actionSpinnerTimer = Timer(120) {
                    if (this::table.isInitialized) table.repaint()
                    if (!hasActiveAction()) {
                        actionSpinnerTimer?.stop()
                        actionSpinnerTimer = null
                    }
                }.apply { start() }
            }
        } else {
            actionSpinnerTimer?.stop()
            actionSpinnerTimer = null
        }
    }

    private fun hasActiveAction(): Boolean =
        reinstallingPackages.isNotEmpty() || clearingPackages.isNotEmpty() || uninstallingPackages.isNotEmpty() ||
                preparingPushPackages.isNotEmpty() || pushingPackages.isNotEmpty()

    private fun appTooltip(info: AppInstallInfo): String {
        val module = info.moduleName.ifEmpty { "Device-only app" }
        val installTime = if (info.installTimeMs > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(info.installTimeMs))
        } else {
            "Unknown"
        }
        val activeApkText = if (info.activeApkPaths.isEmpty()) {
            "Unknown"
        } else {
            info.activeApkPaths.joinToString("<br>") { htmlEscape(it) }
        }
        val apkText = if (info.apkFiles.isEmpty()) {
            "No APK found"
        } else {
            info.apkFiles.joinToString("<br>") { htmlEscape(apkLabelWithoutTime(it)) }
        }
        return """
            <html>
            <b>${htmlEscape(module)}</b><br>
            Package: ${htmlEscape(info.packageName)}<br>
            Status: ${htmlEscape(statusText(info.status))}<br>
            ${statusNote(info.status)}
            Install time: ${htmlEscape(installTime)}<br>
            Active APK: $activeApkText<br>
            APK: $apkText
            </html>
        """.trimIndent()
    }

    private fun statusText(status: InstallStatus): String = when (status) {
        InstallStatus.USER_APP -> "Installed"
        InstallStatus.UPDATED_SYSTEM_APP -> "Installed"
        InstallStatus.SYSTEM_APP -> "Installed (system)"
        InstallStatus.NOT_INSTALLED -> "Not installed"
        InstallStatus.UNKNOWN -> "Querying..."
    }

    private fun statusNote(status: InstallStatus): String = when (status) {
        InstallStatus.UPDATED_SYSTEM_APP -> "Note: uninstall removes the updated APK and restores the system version.<br>"
        InstallStatus.SYSTEM_APP -> "Note: system app; uninstall is disabled but data can be cleared.<br>"
        else -> ""
    }

    private fun statusTooltip(info: AppInstallInfo): String {
        val activeApkText = if (info.activeApkPaths.isEmpty()) {
            "Unknown"
        } else {
            info.activeApkPaths.joinToString("<br>") { htmlEscape(it) }
        }
        return """
            <html>
            Status: ${htmlEscape(statusText(info.status))}<br>
            Active APK:<br>
            $activeApkText
            </html>
        """.trimIndent()
    }

    private fun statusColor(status: InstallStatus): Color = when (status) {
        InstallStatus.USER_APP -> Color(0x82, 0xCC, 0x8D)
        InstallStatus.UPDATED_SYSTEM_APP -> Color(0x82, 0xCC, 0x8D)
        InstallStatus.SYSTEM_APP -> Color(0x64, 0xB5, 0xF6)
        else -> UIManager.getColor("Label.disabledForeground") ?: Color(0x9A, 0x9D, 0xA4)
    }

    private fun primaryActiveApkPath(paths: List<String>): String? =
        paths.firstOrNull { it.endsWith("/base.apk", ignoreCase = true) }
            ?: paths.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
            ?: paths.firstOrNull()

    private fun compactApkPath(path: String): String {
        if (path.length <= 48) return path
        val fileName = path.substringAfterLast('/')
        val prefix = when {
            path.startsWith("/data/app/") -> "/data/app"
            path.startsWith("/system/priv-app/") -> "/system/priv-app"
            path.startsWith("/system/app/") -> "/system/app"
            path.startsWith("/system_ext/priv-app/") -> "/system_ext/priv-app"
            path.startsWith("/system_ext/app/") -> "/system_ext/app"
            path.startsWith("/product/priv-app/") -> "/product/priv-app"
            path.startsWith("/product/app/") -> "/product/app"
            path.startsWith("/vendor/priv-app/") -> "/vendor/priv-app"
            path.startsWith("/vendor/app/") -> "/vendor/app"
            path.startsWith("/odm/priv-app/") -> "/odm/priv-app"
            path.startsWith("/odm/app/") -> "/odm/app"
            else -> ""
        }
        return if (prefix.isNotBlank()) "$prefix/.../$fileName" else ".../$fileName"
    }

    private fun actionTooltip(action: RowAction, info: AppInstallInfo): String = when (action) {
        RowAction.REINSTALL -> reinstallTooltip(info)
        RowAction.CLEAR -> "Clear app data"
        RowAction.UNINSTALL -> "Uninstall"
        RowAction.PUSH -> pushTooltip(info)
    }

    private fun reinstallTooltip(info: AppInstallInfo): String = when {
        !info.isFromProject -> "Device-only app, cannot reinstall"
        info.apkFiles.isEmpty() -> "No APK found — build first"
        else -> {
            val actionText = if (info.isInstalled) "Reinstall" else "Install"
            """
                <html>
                ${htmlEscape(actionText)}<br>
                APK: ${htmlEscape(relativeApkPath(info.apkFiles.first()))}
                </html>
            """.trimIndent()
        }
    }

    private fun pushTooltip(info: AppInstallInfo): String = when {
        !info.isFromProject -> "Device-only app, cannot push"
        info.apkFiles.isEmpty() -> "No APK found — build first"
        else -> {
            """
                <html>
                Push to system partition<br>
                APK: ${htmlEscape(relativeApkPath(info.apkFiles.first()))}
                </html>
            """.trimIndent()
        }
    }

    private fun relativeApkPath(apk: File): String {
        val basePath = projectBasePath?.takeIf(String::isNotBlank) ?: return apk.absolutePath
        return runCatching {
            File(basePath).canonicalFile.toPath()
                .relativize(apk.canonicalFile.toPath())
                .toString()
                .replace(File.separatorChar, '/')
        }.getOrDefault(apk.absolutePath)
    }

    private fun htmlEscape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun commandFailureMessage(currentUser: String, summary: String, output: String, info: AppInstallInfo): String {
        val details = output.takeIf(String::isNotBlank) ?: "ADB returned no output."
        return """
            $summary

            Package: ${info.packageName}
            Current user: $currentUser
            Status: ${statusText(info.status)}
            Uninstallable: ${info.isUninstallable}
            Clear data enabled: ${info.isClearDataEnabled}

            ADB output:
            $details
        """.trimIndent()
    }

    private fun pushFailureMessage(info: AppInstallInfo, request: SystemPushRequest, result: SystemPushResult): String {
        val details = result.output.takeIf(String::isNotBlank) ?: "ADB returned no output."
        return """
            Push failed while ${result.step}.

            Package: ${info.packageName}
            Local APK: ${request.localApk.absolutePath}
            Target: ${request.targetPath}

            ADB output:
            $details
        """.trimIndent()
    }

    private fun apkLabel(f: File): String {
        val folder = f.parentFile?.name ?: ""
        val time = SimpleDateFormat("MM-dd HH:mm").format(Date(f.lastModified()))
        return "${f.name}  [$folder]  $time"
    }

    private fun apkLabelWithoutTime(f: File): String {
        val folder = f.parentFile?.name ?: ""
        return "${f.name}  [$folder]"
    }

    // ── Renderers ─────────────────────────────────────────────────────────────

    private inner class UniversalRenderer : TableCellRenderer {
        private val textRenderer = DefaultTableCellRenderer()
        private val checkbox = JCheckBox().apply {
            isOpaque = true
            horizontalAlignment = SwingConstants.CENTER
            isFocusable = false
            isRequestFocusEnabled = false
            model.isRollover = false
        }

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component = when (val r = tableModel.rows[row]) {
            is TableRow.Divider -> JPanel().apply { background = tbl.background; isOpaque = true }
            is TableRow.Data -> dataCell(r, tbl, value, row, col)
        }

        private fun dataCell(r: TableRow.Data, tbl: JTable, value: Any?, row: Int, col: Int): Component =
            when (col) {
                UninstallTableModel.COL_CHECK -> checkbox.apply {
                    this.isSelected = r.selected
                    isEnabled = r.info.isUninstallable
                    background = tbl.background
                    model.isRollover = false
                    model.isArmed = false
                    model.isPressed = false
                }
                UninstallTableModel.COL_APP -> appCell(r, tbl)
                UninstallTableModel.COL_STATUS -> statusCell(r, tbl)
                else -> {
                    val c = textRenderer.getTableCellRendererComponent(tbl, value, false, false, row, col)
                    c.background = tbl.background
                    (c as? JLabel)?.border = JBUI.Borders.empty()
                    (c as? JLabel)?.horizontalAlignment = SwingConstants.CENTER
                    (c as? JLabel)?.foreground = statusColor(r.info.status)
                    c
                }
            }

        private fun statusCell(rowData: TableRow.Data, tbl: JTable): Component =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(5, 4, 5, 4)
                background = tbl.background
                isOpaque = true

                val info = rowData.info
                add(JLabel(statusText(info.status)).apply {
                    alignmentX = Component.CENTER_ALIGNMENT
                    horizontalAlignment = SwingConstants.CENTER
                    foreground = statusColor(info.status)
                    font = font.deriveFont(Font.PLAIN, 12.5f)
                })

                val activePath = primaryActiveApkPath(info.activeApkPaths)
                if (info.status.isInstalled && activePath != null) {
                    add(Box.createVerticalStrut(1))
                    add(JLabel(compactApkPath(activePath)).apply {
                        alignmentX = Component.CENTER_ALIGNMENT
                        horizontalAlignment = SwingConstants.CENTER
                        foreground = UIManager.getColor("Label.foreground")?.darker()
                            ?: UIManager.getColor("Label.disabledForeground")
                            ?: tbl.foreground.darker()
                        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                    })
                }
            }

        private fun appCell(rowData: TableRow.Data, tbl: JTable): Component =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(5, 10, 5, 16)
                background = tbl.background
                isOpaque = true
                val info = rowData.info
                val primary = info.moduleName.ifEmpty { displayNameFromPackage(info.packageName) }
                add(JLabel(primary).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    foreground = tbl.foreground
                    font = font.deriveFont(Font.PLAIN, 13.5f)
                })
                add(Box.createVerticalStrut(1))
                add(JLabel(info.packageName).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    foreground = UIManager.getColor("Label.foreground")?.darker()
                        ?: UIManager.getColor("Label.disabledForeground")
                        ?: tbl.foreground.darker()
                    font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                })
            }
    }

    private inner class ActionCellRenderer(private val action: RowAction) : TableCellRenderer {
        private val spinnerIcon = AnimatedIcon.Default()
        private val panel = JPanel(GridBagLayout()).apply { isOpaque = true }
        private val btn = JButton().apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            isFocusable = false
            preferredSize = Dimension(ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)
            minimumSize = preferredSize
            maximumSize = preferredSize
            margin = Insets(0, 0, 0, 0)
            text = ""
        }
        init { panel.add(btn) }

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component {
            val r = tableModel.rows[row]
            if (r is TableRow.Divider || (r is TableRow.Data && action.hiddenFor(r.info))) {
                btn.isVisible = false
                panel.background = tbl.background
                return panel
            }
            r as TableRow.Data
            btn.isVisible = true
            val isActive = activeAction(r.info) == action
            btn.icon = if (isActive) spinnerIcon else action.enabledIcon
            btn.disabledIcon = if (isActive) spinnerIcon else action.disabledIcon
            btn.isEnabled = isActionEnabled(action, r.info)
            panel.background = tbl.background
            return panel
        }
    }

    private inner class ActionCellEditor(private val action: RowAction) : AbstractCellEditor(), TableCellEditor {
        private val spinnerIcon = AnimatedIcon.Default()
        private val panel = JPanel(GridBagLayout()).apply { isOpaque = true }
        private val btn = JButton().apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            preferredSize = Dimension(ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)
            minimumSize = preferredSize
            maximumSize = preferredSize
            margin = Insets(0, 0, 0, 0)
            text = ""
        }
        init { panel.add(btn) }

        override fun isCellEditable(e: EventObject?): Boolean {
            val me = e as? MouseEvent ?: return true
            val tbl = me.source as? JTable ?: return false
            val row = tbl.rowAtPoint(me.point)
            val r = tableModel.rows.getOrNull(row) as? TableRow.Data ?: return false
            return isActionEnabled(action, r.info)
        }

        override fun getCellEditorValue(): Any = ""

        override fun getTableCellEditorComponent(tbl: JTable, value: Any?, isSelected: Boolean, row: Int, col: Int): Component {
            val r = tableModel.rows.getOrNull(row) as? TableRow.Data
            val isActive = r?.info?.let { activeAction(it) } == action
            btn.icon = if (isActive) spinnerIcon else action.enabledIcon
            btn.disabledIcon = if (isActive) spinnerIcon else action.disabledIcon
            btn.isEnabled = r != null && isActionEnabled(action, r.info)
            // Highlight the entire cell to signal the click — same depth as JBTable's editing indicator
            panel.background = blendColors(
                UIManager.getColor("Button.focusedBorderColor") ?: Color(0x5D, 0x8D, 0xFF),
                tbl.background,
                0.22f,
            )
            return panel
        }
    }

    private class CenterHeaderRenderer(private val delegate: TableCellRenderer) : TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            (c as? JLabel)?.horizontalAlignment = SwingConstants.CENTER
            return c
        }
    }

    companion object {
        private val pendingRebootBootIds = mutableMapOf<String, String>()
    }
}

// ── Extension helper used in onReinstall ──────────────────────────────────────
private val InstallStatus.isInstalled: Boolean
    get() = this == InstallStatus.USER_APP || this == InstallStatus.UPDATED_SYSTEM_APP || this == InstallStatus.SYSTEM_APP

private fun displayNameFromPackage(packageName: String): String =
    packageName.substringAfterLast('.')
        .split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercaseChar() } }
        .ifBlank { packageName }

private fun blendColors(foreground: Color, background: Color, foregroundWeight: Float): Color {
    val fg = foregroundWeight.coerceIn(0f, 1f)
    val bg = 1f - fg
    return Color(
        (foreground.red * fg + background.red * bg).toInt(),
        (foreground.green * fg + background.green * bg).toInt(),
        (foreground.blue * fg + background.blue * bg).toInt(),
    )
}

private enum class RowAction {
    REINSTALL,
    CLEAR,
    UNINSTALL,
    PUSH;

    val enabledIcon: Icon
        get() = IconLoader.getIcon("/icons/${svgName}.svg", RowAction::class.java)

    val disabledIcon: Icon
        get() = IconLoader.getIcon("/icons/${svgName}_disabled.svg", RowAction::class.java)

    private val svgName get() = when (this) {
        REINSTALL -> "action_reinstall"
        UNINSTALL -> "action_uninstall"
        CLEAR     -> "action_cleardata"
        PUSH      -> "action_push"
    }

    val loadingTooltip: String
        get() = when (this) {
            REINSTALL -> "Installing..."
            CLEAR -> "Clearing data..."
            UNINSTALL -> "Uninstalling..."
            PUSH -> "Pushing..."
        }

    fun hiddenFor(info: AppInstallInfo): Boolean = when (this) {
        REINSTALL, PUSH -> !info.isFromProject
        CLEAR, UNINSTALL -> false
    }
}
