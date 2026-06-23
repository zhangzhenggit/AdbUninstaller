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
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

private const val PLUGIN_VERSION = "1.2.89"
private const val ACTION_BUTTON_SIZE = 38
private const val DATA_ROW_HEIGHT = 52
private const val TABLE_WIDTH = 1040
private const val TABLE_MIN_VISIBLE_ROWS = 3
private const val TABLE_MAX_VISIBLE_ROWS = 7
private val ACTION_COLS = setOf(
    UninstallTableModel.COL_REINSTALL,
    UninstallTableModel.COL_CLEAR,
    UninstallTableModel.COL_UNINSTALL,
    UninstallTableModel.COL_PUSH,
)
private const val PROJECT_SCAN_MAX_ATTEMPTS = 20
private const val PROJECT_SCAN_RETRY_DELAY_MS = 1500L
private const val REBOOT_MONITOR_TIMEOUT_MS = 120_000L

class UninstallDialog(
    private val project: Project,
    private var projectAppInfos: List<AppInstallInfo>,
    private val deviceNames: Map<String, String>,
    private val projectBasePath: String?,
) : DialogWrapper(project, true) {

    private val adbPath = AdbService.adbPath(projectBasePath)
    private val serials = deviceNames.keys.toList()

    private val deviceCombo = ComboBox(deviceNames.values.toTypedArray())
    private lateinit var tableModel: UninstallTableModel
    private lateinit var table: JBTable
    private lateinit var tableScroll: JBScrollPane
    private lateinit var refreshBtn: JButton
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
    private val pushProgressByPackage = mutableMapOf<String, SystemPushProgress>()
    private val pendingPushSuccessPrompts = mutableMapOf<String, PushSuccessPrompt>()
    private val pendingRemountFailurePrompts = mutableMapOf<String, RemountResult>()
    private var devicePromptDialogShowing = false
    private var batchOperationActive = false
    private var rebootCommandActive = false
    private var pushDialogOpen = false
    private var actionSpinnerTimer: Timer? = null
    private var deviceStateTimer: Timer? = null
    private var deviceStateCheckRunning = false
    private var deviceStateGeneration = 0
    private val deviceObservations = mutableMapOf<String, DeviceObservation>()
    private val pendingDeviceRefresh = mutableSetOf<String>()
    private val awaitingRebootSerials = mutableSetOf<String>()
    private val rebootTransitionSeen = mutableSetOf<String>()
    private val rebootStartedAt = mutableMapOf<String, Long>()
    private var copyStatusTimer: Timer? = null
    private var loadGeneration = 0
    private var bootIdCheckGeneration = 0
    private var initialTableHeightApplied = false
    private var pressedActionRow = -1
    private var pressedActionCol = -1
    private val uninstallBtn = JButton("Uninstall Selected").apply {
        foreground = Color(0xD3, 0x56, 0x5C)
    }
    private lateinit var rebootRequiredBtn: JButton

    init {
        title = "APK Manager"
        init()
        if (serials.isNotEmpty()) loadInstallStatus(serials[0], rescanProject = projectAppInfos.isEmpty())
        startDeviceStateMonitor()
    }

    override fun createCenterPanel(): JComponent {
        tableModel = UninstallTableModel()
        tableModel.addTableModelListener { updateSummary() }
        table = object : JBTable(tableModel) {
            override fun getToolTipText(e: MouseEvent): String? {
                val row = rowAtPoint(e.point)
                val col = columnAtPoint(e.point)
                val data = tableModel.rows.getOrNull(row) ?: return null
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
            columnModel.getColumn(UninstallTableModel.COL_APP).preferredWidth = 230
            columnModel.getColumn(UninstallTableModel.COL_STATUS).preferredWidth = 200
            for (col in listOf(UninstallTableModel.COL_REINSTALL, UninstallTableModel.COL_CLEAR, UninstallTableModel.COL_UNINSTALL, UninstallTableModel.COL_PUSH)) {
                columnModel.getColumn(col).apply { maxWidth = DATA_ROW_HEIGHT; minWidth = DATA_ROW_HEIGHT }
            }

            val universalRenderer = UniversalRenderer()
            columnModel.getColumn(UninstallTableModel.COL_CHECK).cellRenderer = universalRenderer
            columnModel.getColumn(UninstallTableModel.COL_APP).cellRenderer = universalRenderer
            columnModel.getColumn(UninstallTableModel.COL_STATUS).cellRenderer = StatusCellRenderer()
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
                    val tableRow = tableModel.rows.getOrNull(row) ?: return
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
                    val info = tableModel.rows.getOrNull(savedRow)?.info ?: return
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
            refreshBtn = JButton("Refresh").apply {
                preferredSize = Dimension(96, 32)
                addActionListener {
                    refreshRebootRequiredState()
                    reload(rescanProject = true)
                }
            }
            add(refreshBtn)
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

        tableScroll = JBScrollPane(table).apply {
            preferredSize = Dimension(TABLE_WIDTH, tableViewportHeight(projectAppInfos.size))
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
        if (hasActiveAction()) {
            Messages.showInfoMessage(
                "A device operation is still running. Wait for it to finish before closing APK Manager.",
                "AppPurge",
            )
            return
        }
        loadGeneration++
        bootIdCheckGeneration++
        stopDeviceStateMonitor()
        super.doCancelAction()
    }

    override fun dispose() {
        loadGeneration++
        bootIdCheckGeneration++
        stopDeviceStateMonitor()
        super.dispose()
    }

    private fun currentSerial(): String? {
        val idx = deviceCombo.selectedIndex
        return if (idx >= 0 && idx < serials.size) serials[idx] else null
    }

    private fun reload(rescanProject: Boolean = false) {
        val serial = currentSerial() ?: return
        loadInstallStatus(serial, rescanProject = rescanProject)
    }

    private fun startDeviceStateMonitor() {
        deviceStateTimer = Timer(2_000) { checkCurrentDeviceState() }.apply {
            initialDelay = 1_000
            start()
        }
    }

    private fun stopDeviceStateMonitor() {
        deviceStateTimer?.stop()
        deviceStateTimer = null
        deviceStateGeneration++
    }

    private fun checkCurrentDeviceState() {
        if (deviceStateCheckRunning || loading || hasActiveAction()) return
        val serial = currentSerial() ?: return
        if (DeviceOperationCoordinator.snapshot(serial).hasAnyWork) return
        val generation = deviceStateGeneration
        deviceStateCheckRunning = true
        val submission = DeviceOperationCoordinator.submitMutation(serial) {
            val online = AdbService.isDeviceOnline(serial, adbPath)
            if (online) {
                DeviceObservation(
                    online = true,
                    bootCompleted = AdbService.isBootCompleted(serial, adbPath),
                    bootId = AdbService.getBootId(serial, adbPath),
                )
            } else {
                DeviceObservation(online = false, bootCompleted = false, bootId = null)
            }
        }
        submission.future.whenComplete { observation, error ->
            SwingUtilities.invokeLater {
                deviceStateCheckRunning = false
                if (isDisposed || generation != deviceStateGeneration || currentSerial() != serial) return@invokeLater
                if (error == null && observation != null) applyDeviceObservation(serial, observation)
            }
        }
    }

    private fun applyDeviceObservation(serial: String, observation: DeviceObservation) {
        val previous = deviceObservations.put(serial, observation)
        val rebooted = previous?.bootId != null && observation.bootId != null && previous.bootId != observation.bootId
        if (rebooted) DeviceOperationCoordinator.invalidateDevice(serial)

        if (serial in awaitingRebootSerials) {
            if (!observation.online || rebooted) rebootTransitionSeen += serial
            if (System.currentTimeMillis() - (rebootStartedAt[serial] ?: 0L) > REBOOT_MONITOR_TIMEOUT_MS) {
                clearRebootMonitoring(serial)
                setStatus("Device did not reconnect after reboot")
                refreshActionRendering()
                return
            }
            if (serial !in rebootTransitionSeen) return
            if (!observation.online) {
                setStatus("Waiting for device to reconnect...")
                refreshActionRendering()
                return
            }
            if (!observation.bootCompleted) {
                setStatus("Waiting for device to finish booting...")
                refreshActionRendering()
                return
            }
            clearRebootMonitoring(serial)
            pendingDeviceRefresh += serial
        } else {
            if (previous != null && previous.online && !observation.online) pendingDeviceRefresh += serial
            if (previous != null && !previous.online && observation.online) pendingDeviceRefresh += serial
            if (rebooted) pendingDeviceRefresh += serial
        }

        if (!observation.online) {
            DeviceOperationCoordinator.invalidateDevice(serial)
            setStatus("Device disconnected")
            refreshActionRendering()
            return
        }
        if (!observation.bootCompleted) {
            setStatus("Device is booting...")
            refreshActionRendering()
            return
        }

        if (serial in pendingDeviceRefresh && !loading && !hasActiveAction()) {
            pendingDeviceRefresh.remove(serial)
            refreshRebootRequiredState()
            setStatus("Device ready, refreshing apps...")
            reload()
        }
        refreshActionRendering()
    }

    private fun clearRebootMonitoring(serial: String) {
        awaitingRebootSerials.remove(serial)
        rebootTransitionSeen.remove(serial)
        rebootStartedAt.remove(serial)
    }

    private fun setLoading(loading: Boolean) {
        this.loading = loading
        uninstallBtn.isEnabled = !loading && tableModel.selectedCount > 0
        deviceCombo.isEnabled = !loading && !hasActiveAction()
        if (this::refreshBtn.isInitialized) refreshBtn.isEnabled = !loading && !hasActiveAction()
        updateSummary()
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
        tableModel.rows.indices.forEach { table.setRowHeight(it, DATA_ROW_HEIGHT) }
    }

    private fun tableViewportHeight(moduleCount: Int): Int {
        val rows = moduleCount.coerceIn(TABLE_MIN_VISIBLE_ROWS, TABLE_MAX_VISIBLE_ROWS)
        val headerHeight = table.tableHeader?.preferredSize?.height?.coerceAtLeast(24) ?: 24
        return headerHeight + rows * DATA_ROW_HEIGHT + 2
    }

    private fun updateTableViewportHeight(moduleCount: Int) {
        if (!this::tableScroll.isInitialized) return
        tableScroll.preferredSize = Dimension(TABLE_WIDTH, tableViewportHeight(moduleCount))
        tableScroll.revalidate()
        if (!initialTableHeightApplied && moduleCount > 0) {
            initialTableHeightApplied = true
            SwingUtilities.getWindowAncestor(tableScroll)?.pack()
        }
    }

    private fun loadInstallStatus(serial: String, rescanProject: Boolean = false) {
        val generation = ++loadGeneration
        setLoading(true)
        setStatus(if (rescanProject) "Scanning project modules…" else "Querying project modules…")

        runBackground("AppPurge-ADB") {
            try {
                val sourceInfos = if (rescanProject) scanProjectModulesWithRetry(serial, generation) else projectAppInfos
                val projectInfos = sourceInfos.map { info ->
                    info.copy(status = InstallStatus.UNKNOWN, activeApkPaths = emptyList())
                }
                SwingUtilities.invokeAndWait {
                    if (!isCurrentLoad(serial, generation)) return@invokeAndWait
                    tableModel.resetItems(projectInfos)
                    updateRowHeights()
                    updateTableViewportHeight(projectInfos.size)
                }

                projectInfos.forEach { info ->
                    info.apkFiles = ApkFinder.findApks(info.module, info.packageName)
                }
                val projectLoadStatus = projectLoadStatus(projectInfos)
                val querySubmission = DeviceOperationCoordinator.submitMutation(serial) {
                    val freshSnapshot = AdbService.getPackageSnapshot(serial, adbPath)
                    if (freshSnapshot.isEmpty) return@submitMutation false
                    projectInfos.forEach { info ->
                        info.status = AdbService.queryProjectPackageStatus(
                            serial = serial,
                            packageName = info.packageName,
                            installedPackages = freshSnapshot.installedPackages,
                            systemPackages = freshSnapshot.systemPackages,
                            adb = adbPath,
                        )
                        info.activeApkPaths = if (info.isInstalled) {
                            AdbService.getActivePackagePaths(serial, info.packageName, adbPath)
                        } else {
                            emptyList()
                        }
                    }
                    true
                }
                if (querySubmission.queued) {
                    SwingUtilities.invokeLater {
                        if (isCurrentLoad(serial, generation)) setStatus("Waiting for current device operation…")
                    }
                }
                if (!querySubmission.future.get()) {
                    SwingUtilities.invokeLater {
                        if (!isCurrentLoad(serial, generation)) return@invokeLater
                        setStatus("ADB query failed — check device connection")
                        setLoading(false)
                    }
                    return@runBackground
                }

                SwingUtilities.invokeLater {
                    if (!isCurrentLoad(serial, generation)) return@invokeLater
                    projectAppInfos = projectInfos
                    tableModel.resetItems(projectInfos)
                    updateRowHeights()
                    updateTableViewportHeight(projectInfos.size)
                    setLoading(false)
                    setStatus(projectLoadStatus)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    if (!isCurrentLoad(serial, generation)) return@invokeLater
                    setStatus("Error: ${e.message ?: e.javaClass.simpleName}")
                    setLoading(false)
                }
            }
        }
    }

    private fun isCurrentLoad(serial: String, generation: Int): Boolean =
        !isDisposed && generation == loadGeneration && currentSerial() == serial

    private fun scanProjectModulesWithRetry(serial: String, generation: Int): List<AppInstallInfo> {
        repeat(PROJECT_SCAN_MAX_ATTEMPTS) { index ->
            val infos = AppModuleScanner.scan(project)
            if (infos.isNotEmpty()) return infos
            val attempt = index + 1
            SwingUtilities.invokeLater {
                if (!isCurrentLoad(serial, generation)) return@invokeLater
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
        batchOperationActive = true
        refreshActionRendering()
        setStatus("Uninstalling…")
        data class BatchUninstallResult(
            val info: AppInstallInfo,
            val result: AdbService.CommandResult,
            val status: InstallStatus,
            val activePaths: List<String>,
        )
        val submission = DeviceOperationCoordinator.submitMutation(serial) {
            val results = selected.map { info -> info to AdbService.uninstallPackage(serial, info.packageName, adbPath) }
            val snapshot = AdbService.getPackageSnapshot(serial, adbPath)
            results.map { (info, result) ->
                val newStatus = AdbService.queryProjectPackageStatus(
                    serial, info.packageName, snapshot.installedPackages, snapshot.systemPackages, adbPath,
                )
                BatchUninstallResult(info, result, newStatus, activePathsForStatus(serial, info.packageName, newStatus))
            }
        }
        if (submission.queued) setStatus("Batch uninstall queued…")
        submission.future.whenComplete { statuses, error ->
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                batchOperationActive = false
                refreshActionRendering()
                if (error != null) {
                    setStatus("Batch uninstall failed: ${error.message ?: error.javaClass.simpleName}")
                    return@invokeLater
                }
                var successCnt = 0
                statuses.orEmpty().forEach { (info, result, newStatus, activePaths) ->
                    val ok = result.success || newStatus == InstallStatus.NOT_INSTALLED || newStatus == InstallStatus.SYSTEM_APP
                    if (ok) successCnt++
                    applyPostOperationStatus(info, newStatus, activePaths)
                }
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
        val submission = DeviceOperationCoordinator.submitMutation(serial) {
            val result = AdbService.clearAppData(serial, info.packageName, adbPath)
            val newStatus = AdbService.getPackageState(serial, info.packageName, adbPath)
            val activePaths = activePathsForStatus(serial, info.packageName, newStatus)
            val currentUser = if (!result.success) AdbService.getCurrentUser(serial, adbPath) else ""
            PackageMutationResult(result, newStatus, activePaths, currentUser)
        }
        if (submission.queued) setStatus("Clear data queued: ${info.packageName}")
        submission.future.whenComplete { outcome, error ->
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                clearingPackages.remove(info.packageName)
                refreshActionRendering()
                if (currentSerial() != serial) {
                    pendingDeviceRefresh += serial
                    return@invokeLater
                }
                if (error != null || outcome == null) {
                    setStatus("Clear data failed: ${error?.message ?: info.packageName}")
                    return@invokeLater
                }
                tableModel.updateRow(info.packageName, outcome.status, activeApkPaths = outcome.activePaths)
                if (outcome.command.success) {
                    setStatus("")
                } else {
                    setStatus("Clear data failed: ${info.packageName}")
                    Messages.showErrorDialog(commandFailureMessage(outcome.currentUser, "Failed to clear app data.", outcome.command.output, info), "AppPurge Clear Data Failed")
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
        val submission = DeviceOperationCoordinator.submitMutation(serial) {
            val result = AdbService.uninstallPackage(serial, info.packageName, adbPath)
            val newStatus = AdbService.getPackageState(serial, info.packageName, adbPath)
            val activePaths = activePathsForStatus(serial, info.packageName, newStatus)
            val currentUser = if (!result.success && newStatus != InstallStatus.NOT_INSTALLED && newStatus != InstallStatus.SYSTEM_APP) AdbService.getCurrentUser(serial, adbPath) else ""
            PackageMutationResult(result, newStatus, activePaths, currentUser)
        }
        if (submission.queued) setStatus("Uninstall queued: ${info.packageName}")
        submission.future.whenComplete { outcome, error ->
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                uninstallingPackages.remove(info.packageName)
                refreshActionRendering()
                if (currentSerial() != serial) {
                    pendingDeviceRefresh += serial
                    return@invokeLater
                }
                if (error != null || outcome == null) {
                    setStatus("Uninstall failed: ${error?.message ?: info.packageName}")
                    return@invokeLater
                }
                applyPostOperationStatus(info, outcome.status, outcome.activePaths)
                if (outcome.command.success || outcome.status == InstallStatus.NOT_INSTALLED || outcome.status == InstallStatus.SYSTEM_APP) {
                    setStatus("")
                } else {
                    setStatus("Uninstall failed: ${info.packageName}")
                    Messages.showErrorDialog(commandFailureMessage(outcome.currentUser, "Failed to uninstall app.", outcome.command.output, info), "AppPurge Uninstall Failed")
                }
            }
        }
    }

    private fun onReinstall(serial: String, info: AppInstallInfo, apk: File) {
        if (!reinstallingPackages.add(info.packageName)) return
        refreshActionRendering()
        val submission = DeviceOperationCoordinator.submitMutation(serial) {
            val result = AdbService.installApk(serial, apk.absolutePath, adbPath)
            val newStatus = AdbService.getPackageState(serial, info.packageName, adbPath)
            val currentUser = if (!result.success) AdbService.getCurrentUser(serial, adbPath) else ""
            val activePaths = activePathsForStatus(serial, info.packageName, newStatus)
            PackageMutationResult(result, newStatus, activePaths, currentUser)
        }
        if (submission.queued) setStatus("Install queued: ${info.packageName}")
        submission.future.whenComplete { outcome, error ->
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                reinstallingPackages.remove(info.packageName)
                refreshActionRendering()
                if (currentSerial() != serial) {
                    pendingDeviceRefresh += serial
                    return@invokeLater
                }
                if (error != null || outcome == null) {
                    setStatus("Install failed: ${error?.message ?: info.packageName}")
                    return@invokeLater
                }
                if (outcome.status.isInstalled) {
                    tableModel.updateRow(info.packageName, outcome.status, activeApkPaths = outcome.activePaths)
                    setStatus("")
                } else {
                    tableModel.updateRow(info.packageName, outcome.status, activeApkPaths = outcome.activePaths)
                    setStatus("Install failed: ${info.packageName}")
                    Messages.showErrorDialog(
                        commandFailureMessage(outcome.currentUser, "Failed to install APK with adb install -r -t.", outcome.command.output, info),
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
        val submission = DeviceOperationCoordinator.submitMutation(serial) {
            runCatching { AdbService.getSystemApkTarget(serial, info.packageName, info.systemPathName, adbPath) }
        }
        submission.future.whenComplete { targetResult, error ->
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                preparingPushPackages.remove(info.packageName)
                refreshActionRendering()
                if (currentSerial() != serial) return@invokeLater
                val target = targetResult?.getOrElse {
                    Messages.showErrorDialog("Failed to resolve system APK target for ${info.packageName}.", "AppPurge")
                    return@invokeLater
                } ?: run {
                    Messages.showErrorDialog(error?.message ?: "Failed to resolve system APK target.", "AppPurge")
                    return@invokeLater
                }
                val dialog = PushSystemApkDialog(project, projectBasePath, adbPath, serial, info, target)
                pushDialogOpen = true
                try {
                    if (dialog.showAndGet()) {
                        onPushSystemApk(serial, info, dialog.request())
                    }
                } finally {
                    pushDialogOpen = false
                    maybeShowPendingDevicePrompt()
                }
            }
        }
    }

    private fun onPushSystemApk(serial: String, info: AppInstallInfo, request: SystemPushRequest) {
        if (!pushingPackages.add(info.packageName)) return
        val previousStatus = info.status
        val previousActivePaths = info.activeApkPaths.toList()
        pushProgressByPackage[info.packageName] = SystemPushProgress(
            SystemPushStage.PREPARING,
            message = "Preparing device",
        )
        refreshActionRendering()
        setStatus("Preparing device...")
        val submission = DeviceOperationCoordinator.submitSystemPush(
            request = request,
            onProgress = { progress ->
                SwingUtilities.invokeLater {
                    if (isDisposed || currentSerial() != serial || info.packageName !in pushingPackages) return@invokeLater
                    pushProgressByPackage[info.packageName] = progress
                    setStatus(progress.message)
                    refreshActionRendering()
                }
            },
            postProcess = { execution ->
                if (!execution.preparation.success || execution.result == null) {
                    return@submitSystemPush PushOperationOutcome(
                        execution,
                        bootId = null,
                        status = previousStatus,
                        activePaths = previousActivePaths,
                    )
                }
                val result = execution.result
                val bootId = if (result.success) AdbService.getBootId(serial, adbPath) else null
                val status = AdbService.getPackageState(serial, info.packageName, adbPath)
                val activePaths = activePathsForStatus(serial, info.packageName, status)
                PushOperationOutcome(execution, bootId, status, activePaths)
            },
        )
        if (submission.queued) {
            pushProgressByPackage[info.packageName] = SystemPushProgress(
                SystemPushStage.PREPARING,
                message = "Queued for device",
            )
            setStatus("Push queued: ${info.packageName}")
        }
        submission.future.whenComplete { outcome, error ->
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                if (currentSerial() != serial) {
                    pushingPackages.remove(info.packageName)
                    pushProgressByPackage.remove(info.packageName)
                    pendingDeviceRefresh += serial
                    refreshActionRendering()
                    return@invokeLater
                }
                if (error != null || outcome == null) {
                    setStatus("Push failed: ${info.packageName}")
                    showTerminalPushState(
                        info.packageName,
                        SystemPushProgress(SystemPushStage.FAILED, message = "Push failed"),
                    ) {
                        Messages.showErrorDialog(error?.message ?: "Unknown device operation failure.", "AppPurge Push Failed")
                    }
                    return@invokeLater
                }
                val execution = outcome.execution
                val result = execution.result
                if (!execution.preparation.success || result == null) {
                    setStatus("Remount failed: ${info.packageName}")
                    showTerminalPushState(
                        info.packageName,
                        SystemPushProgress(SystemPushStage.FAILED, message = "Remount failed"),
                    ) {
                        pendingRemountFailurePrompts[serial] = execution.preparation
                        maybeShowPendingDevicePrompt(serial)
                    }
                } else if (result.success) {
                    setStatus("Push completed, reboot required")
                    showTerminalPushState(
                        info.packageName,
                        SystemPushProgress(SystemPushStage.COMPLETED, 100, "Push completed"),
                    ) {
                        tableModel.updateRow(info.packageName, outcome.status, activeApkPaths = outcome.activePaths)
                        if (outcome.bootId != null) pendingRebootBootIds[serial] = outcome.bootId
                        refreshRebootRequiredState()
                        pendingPushSuccessPrompts[serial] = PushSuccessPrompt(request, result)
                        maybeShowPendingDevicePrompt(serial)
                    }
                } else {
                    setStatus("Push failed: ${info.packageName}")
                    showTerminalPushState(
                        info.packageName,
                        SystemPushProgress(SystemPushStage.FAILED, message = "Push failed"),
                    ) {
                        tableModel.updateRow(info.packageName, outcome.status, activeApkPaths = outcome.activePaths)
                        Messages.showErrorDialog(pushFailureMessage(info, request, result), "AppPurge Push Failed")
                    }
                }
            }
        }
    }

    private fun showTerminalPushState(
        packageName: String,
        progress: SystemPushProgress,
        afterDisplay: () -> Unit,
    ) {
        pushProgressByPackage[packageName] = progress
        refreshActionRendering()
        Timer(650) {
            pushingPackages.remove(packageName)
            pushProgressByPackage.remove(packageName)
            refreshActionRendering()
            afterDisplay()
            maybeShowPendingDevicePrompt()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun showRemountFailure(serial: String, remount: RemountResult) {
        val deviceName = deviceNames[serial] ?: serial
        val message = """
            System partition is not writable on $deviceName.
            The device may require reboot after adb root/remount, then run Push again.

            Device: $deviceName

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

    private fun maybeShowPendingDevicePrompt(serial: String? = null) {
        if (devicePromptDialogShowing) return
        if (maybeShowPendingRemountFailure(serial)) return
        maybeShowPendingPushSuccess(serial)
    }

    private fun maybeShowPendingRemountFailure(serial: String? = null): Boolean {
        val targetSerial = serial?.takeIf { it in pendingRemountFailurePrompts }
            ?: pendingRemountFailurePrompts.keys.firstOrNull()
            ?: return false
        val remount = pendingRemountFailurePrompts[targetSerial] ?: return false
        val snapshot = DeviceOperationCoordinator.snapshot(targetSerial)
        val morePushWorkNow = snapshot.hasPushWork || preparingPushPackages.isNotEmpty() || pushingPackages.isNotEmpty() || pushDialogOpen
        if (morePushWorkNow) {
            setStatus("Remount failed; waiting for device operations to finish…")
            return true
        }
        pendingRemountFailurePrompts.remove(targetSerial)
        devicePromptDialogShowing = true
        try {
            showRemountFailure(targetSerial, remount)
        } finally {
            devicePromptDialogShowing = false
        }
        SwingUtilities.invokeLater { maybeShowPendingDevicePrompt() }
        return true
    }

    private fun maybeShowPendingPushSuccess(serial: String? = null) {
        val targetSerial = serial?.takeIf { it in pendingPushSuccessPrompts }
            ?: pendingPushSuccessPrompts.keys.firstOrNull()
            ?: return
        val prompt = pendingPushSuccessPrompts[targetSerial] ?: return
        val snapshot = DeviceOperationCoordinator.snapshot(targetSerial)
        val morePushWorkNow = snapshot.hasPushWork || preparingPushPackages.isNotEmpty() || pushingPackages.isNotEmpty() || pushDialogOpen
        if (morePushWorkNow) {
            setStatus("Push completed; continuing queued device operations…")
            return
        }
        pendingPushSuccessPrompts.remove(targetSerial)
        devicePromptDialogShowing = true
        try {
            showPushSuccess(targetSerial, prompt.request, prompt.result)
        } finally {
            devicePromptDialogShowing = false
        }
        SwingUtilities.invokeLater { maybeShowPendingDevicePrompt() }
    }

    private fun showPushSuccess(serial: String, request: SystemPushRequest, result: SystemPushResult) {
        val deviceName = deviceNames[serial] ?: serial
        val message = buildString {
            append("Push completed on $deviceName.\n")
            append("Reboot is required for the pushed system APK to take effect.\n\n")
            append("Device: $deviceName")
            if (request.removeDataOverlay && !result.dataOverlayRemoved) {
                append("\n\nWarning: /data/app overlay could not be removed. After reboot, the system version may not take effect if the user-installed overlay persists.")
            }
            if (request.clearData && !result.dataCleared) {
                append("\n\nWarning: app data could not be cleared. The APK was pushed, but existing app data may remain.")
            }
        }
        val choice = Messages.showOkCancelDialog(
            message,
            "AppPurge - Reboot Required",
            "Reboot Now",
            "Later",
            Messages.getInformationIcon(),
        )
        if (choice == Messages.OK) rebootDevice(serial, clearPending = true)
    }

    private fun rebootDevice(serial: String, clearPending: Boolean) {
        val submission = DeviceOperationCoordinator.submitExclusiveIfIdle(serial) {
            DeviceOperationCoordinator.invalidateDevice(serial)
            AdbService.execResult(listOf(adbPath, "-s", serial, "reboot"), timeoutSec = 10)
        }
        if (submission == null) {
            Messages.showInfoMessage(
                "A device operation is still running. Wait for it to finish before rebooting.",
                "AppPurge",
            )
            return
        }
        if (clearPending) pendingRebootBootIds.remove(serial)
        awaitingRebootSerials += serial
        rebootTransitionSeen.remove(serial)
        rebootStartedAt[serial] = System.currentTimeMillis()
        pendingDeviceRefresh += serial
        refreshRebootRequiredState()
        rebootCommandActive = true
        refreshActionRendering()
        setStatus("Rebooting device...")
        submission.future.whenComplete { result, error ->
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                rebootCommandActive = false
                refreshActionRendering()
                if (error != null || result == null || !result.completed) {
                    clearRebootMonitoring(serial)
                    pendingDeviceRefresh.remove(serial)
                    setStatus("Failed to reboot device")
                    Messages.showErrorDialog(
                        result?.output?.ifBlank { "ADB reboot failed with no output." }
                            ?: error?.message
                            ?: "ADB reboot failed with no output.",
                        "AppPurge Reboot Failed",
                    )
                }
            }
        }
    }

    private fun activePathsForStatus(serial: String, packageName: String, status: InstallStatus): List<String> =
        if (status.isInstalled) AdbService.getActivePackagePaths(serial, packageName, adbPath) else emptyList()

    private fun applyPostOperationStatus(info: AppInstallInfo, status: InstallStatus, activePaths: List<String> = emptyList()) {
        tableModel.updateRow(info.packageName, status, activeApkPaths = activePaths)
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
        val generation = ++bootIdCheckGeneration
        val serial = currentSerial()
        if (serial == null) {
            rebootRequiredBtn.isVisible = false
            return
        }
        if (!DeviceOperationCoordinator.snapshot(serial).canRebootSafely) return
        val expectedBootId = pendingRebootBootIds[serial]
        if (expectedBootId == null) {
            rebootRequiredBtn.isVisible = false
            return
        }
        val submission = DeviceOperationCoordinator.submitMutation(serial) {
            val currentBootId = AdbService.getBootId(serial, adbPath)
            currentBootId
        }
        submission.future.whenComplete { currentBootId, error ->
            SwingUtilities.invokeLater {
                if (isDisposed || generation != bootIdCheckGeneration || currentSerial() != serial) return@invokeLater
                if (error != null) return@invokeLater
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

    private fun activeAction(info: AppInstallInfo): RowAction? = when (info.packageName) {
        in reinstallingPackages -> RowAction.REINSTALL
        in clearingPackages -> RowAction.CLEAR
        in uninstallingPackages -> RowAction.UNINSTALL
        in preparingPushPackages -> RowAction.PUSH
        in pushingPackages -> RowAction.PUSH
        else -> null
    }

    private fun isActionEnabled(action: RowAction, info: AppInstallInfo): Boolean {
        if (loading || batchOperationActive || rebootCommandActive) return false
        val pushOnlyActive = preparingPushPackages.isNotEmpty() || pushingPackages.isNotEmpty()
        val nonPushActive = reinstallingPackages.isNotEmpty() || clearingPackages.isNotEmpty() || uninstallingPackages.isNotEmpty()
        if (hasActiveAction() && (action != RowAction.PUSH || nonPushActive || !pushOnlyActive)) return false
        val serial = currentSerial() ?: return false
        val observation = deviceObservations[serial]
        if (observation != null && (!observation.online || !observation.bootCompleted)) return false
        if (activeAction(info) != null) return false
        return when (action) {
            RowAction.REINSTALL -> info.apkFiles.isNotEmpty()
            RowAction.CLEAR -> info.isClearDataEnabled
            RowAction.UNINSTALL -> info.isUninstallable
            RowAction.PUSH -> info.apkFiles.isNotEmpty()
        }
    }

    private fun refreshActionRendering() {
        if (this::table.isInitialized) table.repaint()
        val active = hasActiveAction()
        deviceCombo.isEnabled = !loading && !active
        if (this::refreshBtn.isInitialized) refreshBtn.isEnabled = !loading && !active
        if (this::rebootRequiredBtn.isInitialized) rebootRequiredBtn.isEnabled = !active
        if (active) {
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
        batchOperationActive || rebootCommandActive || reinstallingPackages.isNotEmpty() || clearingPackages.isNotEmpty() || uninstallingPackages.isNotEmpty() ||
                preparingPushPackages.isNotEmpty() || pushingPackages.isNotEmpty()

    private fun appTooltip(info: AppInstallInfo): String {
        val module = info.moduleName
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
        RowAction.PUSH -> pushProgressByPackage[info.packageName]?.message ?: pushTooltip(info)
    }

    private fun reinstallTooltip(info: AppInstallInfo): String = when {
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
        ): Component = dataCell(tableModel.rows[row], tbl, value, row, col)

        private fun dataCell(r: TableRow, tbl: JTable, value: Any?, row: Int, col: Int): Component =
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
                else -> {
                    val c = textRenderer.getTableCellRendererComponent(tbl, value, false, false, row, col)
                    c.background = tbl.background
                    (c as? JLabel)?.border = JBUI.Borders.empty()
                    (c as? JLabel)?.horizontalAlignment = SwingConstants.CENTER
                    (c as? JLabel)?.foreground = statusColor(r.info.status)
                    c
                }
            }

        private fun appCell(rowData: TableRow, tbl: JTable): Component =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(5, 10, 5, 16)
                background = tbl.background
                isOpaque = true
                val info = rowData.info
                val primary = info.moduleName.ifEmpty { displayNameFromPackage(info.packageName) }
                add(Box.createVerticalGlue())
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
                add(Box.createVerticalGlue())
            }
    }

    private inner class StatusCellRenderer : JComponent(), TableCellRenderer {
        private var statusLine = ""
        private var pathLine: String? = null
        private var statusForeground = Color.GRAY
        private var pathForeground = Color.GRAY
        private var statusFont = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        private var pathFont = Font(Font.MONOSPACED, Font.PLAIN, 12)

        override fun getTableCellRendererComponent(
            tbl: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val info = tableModel.rows[row].info
            statusLine = statusText(info.status)
            pathLine = primaryActiveApkPath(info.activeApkPaths)
                ?.takeIf { info.status.isInstalled }
                ?.let(::compactApkPath)
            background = tbl.background
            statusForeground = statusColor(info.status)
            pathForeground = UIManager.getColor("Label.foreground")?.darker()
                ?: UIManager.getColor("Label.disabledForeground")
                ?: tbl.foreground.darker()
            val tableFont = tbl.font ?: UIManager.getFont("Table.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 13)
            statusFont = tableFont.deriveFont(Font.PLAIN, 12.5f)
            pathFont = Font(Font.MONOSPACED, Font.PLAIN, 12)
            return this
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.color = background
                g.fillRect(0, 0, width, height)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val maxTextWidth = (width - 8).coerceAtLeast(0)
                val statusMetrics = g.getFontMetrics(statusFont)
                val path = pathLine
                val pathMetrics = if (path != null) g.getFontMetrics(pathFont) else null
                val totalTextHeight = statusMetrics.height + if (pathMetrics != null) 1 + pathMetrics.height else 0
                var baseline = ((height - totalTextHeight) / 2).coerceAtLeast(0) + statusMetrics.ascent

                drawCenteredLine(g, statusLine, statusFont, statusForeground, baseline, maxTextWidth)
                if (path != null && pathMetrics != null) {
                    baseline += statusMetrics.descent + 1 + pathMetrics.ascent
                    drawCenteredLine(g, path, pathFont, pathForeground, baseline, maxTextWidth)
                }
            } finally {
                g.dispose()
            }
        }

        private fun drawCenteredLine(
            g: Graphics2D,
            text: String,
            lineFont: Font,
            color: Color,
            baseline: Int,
            maxWidth: Int,
        ) {
            g.font = lineFont
            val metrics = g.fontMetrics
            val fitted = fitText(text, metrics, maxWidth)
            g.color = color
            g.drawString(fitted, ((width - metrics.stringWidth(fitted)) / 2).coerceAtLeast(4), baseline)
        }

        private fun fitText(text: String, metrics: FontMetrics, maxWidth: Int): String {
            if (maxWidth <= 0) return ""
            if (metrics.stringWidth(text) <= maxWidth) return text
            val ellipsis = "..."
            if (metrics.stringWidth(ellipsis) >= maxWidth) return ""
            var leftLength = text.length / 2
            var rightLength = text.length - leftLength
            while (leftLength > 0 || rightLength > 0) {
                val candidate = text.take(leftLength) + ellipsis + text.takeLast(rightLength)
                if (metrics.stringWidth(candidate) <= maxWidth) return candidate
                if (leftLength >= rightLength && leftLength > 0) leftLength-- else if (rightLength > 0) rightLength--
            }
            return ellipsis
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
            btn.isVisible = true
            val isActive = activeAction(r.info) == action
            val activeIcon = if (action == RowAction.PUSH && r.info.packageName in pushingPackages) {
                CircularProgressIcon(pushProgressByPackage[r.info.packageName])
            } else {
                spinnerIcon
            }
            btn.icon = if (isActive) activeIcon else action.enabledIcon
            btn.disabledIcon = if (isActive) activeIcon else action.disabledIcon
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
            val r = tableModel.rows.getOrNull(row) ?: return false
            return isActionEnabled(action, r.info)
        }

        override fun getCellEditorValue(): Any = ""

        override fun getTableCellEditorComponent(tbl: JTable, value: Any?, isSelected: Boolean, row: Int, col: Int): Component {
            val r = tableModel.rows.getOrNull(row)
            val isActive = r?.info?.let { activeAction(it) } == action
            val packageName = r?.info?.packageName
            val activeIcon = if (action == RowAction.PUSH && packageName != null && packageName in pushingPackages) {
                CircularProgressIcon(pushProgressByPackage[packageName])
            } else {
                spinnerIcon
            }
            btn.icon = if (isActive) activeIcon else action.enabledIcon
            btn.disabledIcon = if (isActive) activeIcon else action.disabledIcon
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

private class CircularProgressIcon(
    private val progress: SystemPushProgress?,
    private val size: Int = ACTION_BUTTON_SIZE - 14,
) : Icon {
    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val inset = 3
            val diameter = size - inset * 2
            val normalColor = UIManager.getColor("ProgressBar.foreground") ?: Color(0x5D, 0x8D, 0xFF)
            val foreground = when (progress?.stage) {
                SystemPushStage.COMPLETED -> Color(0x59, 0xA8, 0x69)
                SystemPushStage.FAILED -> Color(0xDB, 0x58, 0x60)
                else -> normalColor
            }
            val background = UIManager.getColor("ProgressBar.trackColor")
                ?: UIManager.getColor("Label.disabledForeground")
                ?: Color(0x68, 0x6B, 0x70)

            g.color = Color(background.red, background.green, background.blue, 60)
            g.drawOval(x + inset, y + inset, diameter, diameter)
            g.color = foreground
            when (progress?.stage) {
                SystemPushStage.COMPLETED -> {
                    g.drawOval(x + inset, y + inset, diameter, diameter)
                    g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawLine(x + 7, y + 12, x + 10, y + 15)
                    g.drawLine(x + 10, y + 15, x + 17, y + 8)
                }
                SystemPushStage.FAILED -> {
                    g.drawOval(x + inset, y + inset, diameter, diameter)
                    g.font = component?.font?.deriveFont(Font.BOLD, 12f) ?: Font(Font.SANS_SERIF, Font.BOLD, 12)
                    val metrics = g.fontMetrics
                    g.drawString("!", x + (size - metrics.stringWidth("!")) / 2, y + (size - metrics.height) / 2 + metrics.ascent)
                }
                else -> paintProgress(g, component, x, y, inset, diameter, foreground)
            }
        } finally {
            g.dispose()
        }
    }

    private fun paintProgress(
        g: Graphics2D,
        component: Component?,
        x: Int,
        y: Int,
        inset: Int,
        diameter: Int,
        foreground: Color,
    ) {
        g.color = foreground
        val percent = progress?.percent
        if (percent == null) {
                val start = -((System.currentTimeMillis() / 5L) % 360L).toInt()
                g.drawArc(x + inset, y + inset, diameter, diameter, start, -105)
            return
        }
        val value = percent.coerceIn(0, 100)
        g.drawArc(x + inset, y + inset, diameter, diameter, 90, -(value * 360 / 100))
        val text = value.toString()
        g.font = component?.font?.deriveFont(Font.BOLD, 7.5f) ?: Font(Font.SANS_SERIF, Font.BOLD, 8)
        val metrics = g.fontMetrics
        g.drawString(
            text,
            x + (size - metrics.stringWidth(text)) / 2,
            y + (size - metrics.height) / 2 + metrics.ascent,
        )
    }
}

private data class DeviceObservation(
    val online: Boolean,
    val bootCompleted: Boolean,
    val bootId: String?,
)

private data class PackageMutationResult(
    val command: AdbService.CommandResult,
    val status: InstallStatus,
    val activePaths: List<String>,
    val currentUser: String,
)

private data class PushOperationOutcome(
    val execution: DeviceOperationCoordinator.PushExecution,
    val bootId: String?,
    val status: InstallStatus,
    val activePaths: List<String>,
)

private data class PushSuccessPrompt(
    val request: SystemPushRequest,
    val result: SystemPushResult,
)

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

}
