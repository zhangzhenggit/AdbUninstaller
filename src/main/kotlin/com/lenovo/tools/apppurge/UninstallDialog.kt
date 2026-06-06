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
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

private const val PLUGIN_VERSION = "1.2.36"
private const val TOGGLE_DEFAULT = "Show all user-installed on device"
private const val CARD_TOGGLE = "toggle"
private const val CARD_LOADING = "loading"
private const val ACTION_BUTTON_SIZE = 38
private const val ACTION_BUTTON_GAP = 18
private const val DATA_ROW_HEIGHT = 54
private const val DIVIDER_ROW_HEIGHT = 6
private const val DIVIDER_LINE_INSET = 18

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
    private var actionSpinnerTimer: Timer? = null
    private var pressedAction: RowActionTarget? = null
    private val nameResolveRunId = AtomicInteger(0)
    private val uninstallBtn = JButton("Uninstall Selected").apply {
        foreground = Color(0xD3, 0x56, 0x5C)
    }
    private val cancelNameResolveBtn = JButton("Stop").apply {
        isVisible = false
        margin = Insets(0, 8, 0, 8)
        addActionListener { cancelNameResolving("Name resolving stopped") }
    }

    init {
        title = "AppPurge — APK Manager"
        init()
        if (serials.isNotEmpty()) loadInstallStatus(serials[0], showAll = false)
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
                // Draw pressed button highlight AFTER super (covers JBTable hover overlay)
                val pressed = pressedAction ?: return
                val rowData = tableModel.rows.getOrNull(pressed.row) as? TableRow.Data ?: return
                val actions = actionsFor(rowData.info)
                val idx = actions.indexOf(pressed.action)
                if (idx < 0) return
                val cell = getCellRect(pressed.row, UninstallTableModel.COL_ACTION, true)
                val totalWidth = actionGroupWidth(actions)
                val fullWidth = actionGroupWidth(RowAction.entries)
                val bx = cell.x + ((cell.width - fullWidth) / 2).coerceAtLeast(0) + fullWidth - totalWidth + idx * (ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP)
                val by = cell.y + (cell.height - ACTION_BUTTON_SIZE) / 2
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = actionPressedBackground(background)
                g2.fillRoundRect(bx, by, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE, 6, 6)
                val icon = pressed.action.enabledIcon
                icon.paintIcon(this, g2, bx + (ACTION_BUTTON_SIZE - icon.iconWidth) / 2, by + (ACTION_BUTTON_SIZE - icon.iconHeight) / 2)
                g2.dispose()
            }

            override fun getToolTipText(e: MouseEvent): String? {
                val row = rowAtPoint(e.point)
                val col = columnAtPoint(e.point)
                val data = tableModel.rows.getOrNull(row) as? TableRow.Data ?: return null
                return if (col == UninstallTableModel.COL_ACTION) {
                    actionAt(row, e.point.x)?.let { actionTooltip(it, data.info) }
                } else {
                    appTooltip(data.info)
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
            columnModel.getColumn(UninstallTableModel.COL_APP).preferredWidth = 360
            columnModel.getColumn(UninstallTableModel.COL_STATUS).preferredWidth = 150
            columnModel.getColumn(UninstallTableModel.COL_ACTION).apply { maxWidth = 320; minWidth = 320 }
            val renderer = UniversalRenderer()
            for (i in 0 until columnCount) columnModel.getColumn(i).cellRenderer = renderer
            tableHeader.defaultRenderer = CenterHeaderRenderer(tableHeader.defaultRenderer)

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    clearSelection()
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    val tableRow = tableModel.rows.getOrNull(row) as? TableRow.Data ?: return
                    when (col) {
                        UninstallTableModel.COL_CHECK -> return
                        UninstallTableModel.COL_APP -> copyPackageName(tableRow.info)
                        UninstallTableModel.COL_ACTION -> {
                            val action = actionAt(row, e.point.x) ?: return
                            setPressedAction(RowActionTarget(row, tableRow.info.packageName, action))
                        }
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    val pressed = pressedAction
                    setPressedAction(null)
                    if (pressed == null || actionTargetAt(e) != pressed) return
                    val info = (tableModel.rows.getOrNull(pressed.row) as? TableRow.Data)?.info ?: return
                    val serial = currentSerial() ?: return
                    when (pressed.action) {
                        RowAction.REINSTALL -> chooseApkAndReinstall(serial, info)
                        RowAction.CLEAR -> onClearData(serial, info)
                        RowAction.UNINSTALL -> onUninstallOne(serial, info)
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    setPressedAction(null)
                }
            })
        }

        val spinnerLabel = JLabel("Fetching device apps…", AnimatedIcon.Default(), SwingConstants.LEFT).apply {
            border = JBUI.Borders.empty(0, 2)
        }
        toggleWrapper.add(showAllToggle, CARD_TOGGLE)
        toggleWrapper.add(spinnerLabel, CARD_LOADING)

        val devicePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            border = JBUI.Borders.empty(10, 10, 8, 10)
            add(JLabel("Device:"))
            add(deviceCombo.apply {
                preferredSize = Dimension(290, 32)
                addActionListener { reload() }
            })
            add(JButton("Refresh").apply {
                preferredSize = Dimension(96, 32)
                addActionListener { reload(rescanProject = true) }
            })
            add(toggleWrapper)
            showAllToggle.addActionListener { reload() }
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

    private fun updateRowHeights() {
        tableModel.rows.forEachIndexed { i, row ->
            table.setRowHeight(i, if (row is TableRow.Divider) DIVIDER_ROW_HEIGHT else DATA_ROW_HEIGHT)
        }
    }

    private fun loadInstallStatus(serial: String, showAll: Boolean, rescanProject: Boolean = false) {
        cancelNameResolving()
        setLoading(true)
        setStatus(if (rescanProject) "Scanning project modules…" else if (showAll) "Fetching all user apps…" else "Querying project modules…")

        Thread {
            try {
                val projectInfos = if (rescanProject) AppModuleScanner.scan(project) else projectAppInfos
                projectAppInfos = projectInfos

                val installedPkgs = AdbService.getAllInstalledPackages(serial, adbPath)
                val systemPkgs = AdbService.getAllSystemPackages(serial, adbPath)

                projectInfos.forEach { info ->
                    info.status = when {
                        info.packageName in installedPkgs -> InstallStatus.INSTALLED
                        info.packageName in systemPkgs -> InstallStatus.SYSTEM_ONLY
                        else -> InstallStatus.NOT_INSTALLED
                    }
                }
                projectInfos.filter { it.status == InstallStatus.INSTALLED }.forEach { info ->
                    info.installTimeMs = AdbService.getInstallTime(serial, info.packageName, adbPath)
                }

                // Scan build APKs for project modules
                projectInfos.forEach { info ->
                    if (info.module != null) info.apkFiles = ApkFinder.findApks(info.module)
                }

                if (showAll) {
                    val projectPkgs = projectInfos.map { it.packageName }.toSet()
                    val userPkgs = (installedPkgs - systemPkgs - projectPkgs).sorted()
                    val deviceItems = userPkgs.map { pkg ->
                        AppInstallInfo(null, displayNameFromPackage(pkg), pkg, InstallStatus.INSTALLED)
                    }
                    val runId = nameResolveRunId.incrementAndGet()
                    SwingUtilities.invokeAndWait {
                        tableModel.resetItems(projectInfos, deviceItems)
                        updateRowHeights()
                        setLoading(false)
                        setNameResolving(true)
                        setStatus("Project Apps: ${projectInfos.size} · Installed Apps: ${userPkgs.size} · resolving names 0 / ${userPkgs.size}")
                    }

                    userPkgs.forEachIndexed { index, pkg ->
                        if (runId != nameResolveRunId.get()) return@forEachIndexed
                        val label = AdbService.getApplicationLabel(serial, pkg, adbPath)
                        if (runId != nameResolveRunId.get()) return@forEachIndexed
                        val item = AppInstallInfo(null, label, pkg, InstallStatus.INSTALLED)
                        SwingUtilities.invokeAndWait {
                            if (runId == nameResolveRunId.get()) {
                                tableModel.updateDeviceLabel(item.packageName, item.moduleName)
                                setStatus("Project Apps: ${projectInfos.size} · Installed Apps: ${userPkgs.size} · resolving names ${index + 1} / ${userPkgs.size}")
                            }
                        }
                    }

                    SwingUtilities.invokeLater {
                        if (runId == nameResolveRunId.get()) {
                            setNameResolving(false)
                            setStatus("Project Apps: ${projectInfos.size} · Installed Apps: ${userPkgs.size}")
                        }
                    }
                } else {
                    SwingUtilities.invokeLater {
                        tableModel.resetItems(projectInfos)
                        updateRowHeights()
                        setLoading(false)
                        setStatus("")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setNameResolving(false)
                    setStatus("Error: ${e.message ?: e.javaClass.simpleName}")
                    setLoading(false)
                }
            }
        }.also { it.isDaemon = true; it.name = "AppPurge-ADB" }.start()
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
        Thread {
            var successCnt = 0
            selected.forEach { info ->
                val ok = AdbService.uninstall(serial, info.packageName, adbPath)
                if (ok) successCnt++
                SwingUtilities.invokeLater {
                    if (ok && !info.isFromProject) {
                        tableModel.removeDeviceItem(info.packageName)
                        updateRowHeights()
                    } else {
                        tableModel.updateRow(info.packageName, if (ok) InstallStatus.NOT_INSTALLED else info.status)
                    }
                }
            }
            SwingUtilities.invokeLater {
                uninstallBtn.isEnabled = true
                setStatus("Uninstalled $successCnt / ${selected.size}")
            }
        }.also { it.isDaemon = true }.start()
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
        Thread {
            val result = AdbService.clearAppData(serial, info.packageName, adbPath)
            SwingUtilities.invokeLater {
                clearingPackages.remove(info.packageName)
                refreshActionRendering()
                if (result.success) {
                    setStatus("")
                } else {
                    setStatus("Clear data failed: ${info.packageName}")
                    Messages.showErrorDialog(commandFailureMessage("Failed to clear app data.", result.output), "AppPurge Clear Data Failed")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun onUninstallOne(serial: String, info: AppInstallInfo) {
        if (!isActionEnabled(RowAction.UNINSTALL, info)) return
        if (Messages.showOkCancelDialog(
                "Uninstall ${info.packageName}?",
                "AppPurge", "Uninstall", "Cancel", Messages.getQuestionIcon(),
            ) != Messages.OK) return
        if (!uninstallingPackages.add(info.packageName)) return
        refreshActionRendering()
        Thread {
            val result = AdbService.uninstallPackage(serial, info.packageName, adbPath)
            SwingUtilities.invokeLater {
                uninstallingPackages.remove(info.packageName)
                refreshActionRendering()
                if (result.success) {
                    if (info.isFromProject) {
                        tableModel.updateRow(info.packageName, InstallStatus.NOT_INSTALLED)
                    } else {
                        tableModel.removeDeviceItem(info.packageName)
                        updateRowHeights()
                    }
                    setStatus("")
                } else {
                    setStatus("Uninstall failed: ${info.packageName}")
                    Messages.showErrorDialog(commandFailureMessage("Failed to uninstall app.", result.output), "AppPurge Uninstall Failed")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun onReinstall(serial: String, info: AppInstallInfo, apk: File) {
        if (!reinstallingPackages.add(info.packageName)) return
        refreshActionRendering()
        Thread {
            val result = AdbService.installApk(serial, apk.absolutePath, adbPath)
            val installed = if (result.success) {
                info.packageName in AdbService.getAllInstalledPackages(serial, adbPath)
            } else {
                false
            }
            val installTimeMs = if (installed) {
                AdbService.getInstallTime(serial, info.packageName, adbPath)
            } else {
                0L
            }
            SwingUtilities.invokeLater {
                reinstallingPackages.remove(info.packageName)
                refreshActionRendering()
                if (installed) {
                    tableModel.updateRow(info.packageName, InstallStatus.INSTALLED, installTimeMs)
                    setStatus("")
                } else {
                    setStatus("Install failed: ${info.packageName}")
                    Messages.showErrorDialog(
                        commandFailureMessage("Failed to install APK with adb install -r -t.", result.output),
                        "AppPurge Install Failed",
                    )
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
        updateSummary()
    }

    private fun copyPackageName(info: AppInstallInfo) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(info.packageName), null)
        setStatus("Copied package: ${info.packageName}")
    }

    private fun updateSummary() {
        if (!this::tableModel.isInitialized) return
        val visible = tableModel.visibleItems
        val installed = visible.count { it.status == InstallStatus.INSTALLED }
        val selected = tableModel.selectedCount
        summaryLabel.text = buildString {
            if (selected > 0) append("$selected selected · ")
            append("$installed / ${visible.size} installed")
        }
        summaryLabel.foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        uninstallBtn.text = if (selected > 0) "Uninstall Selected ($selected)" else "Uninstall Selected"
        uninstallBtn.isEnabled = !loading && selected > 0
    }

    private fun dividerLineEndX(): Int {
        if (!this::table.isInitialized) return 0
        val actionColumnRect = table.getCellRect(0, UninstallTableModel.COL_ACTION, true)
        val totalWidth = actionGroupWidth(RowAction.entries)
        val startX = actionColumnRect.x + ((actionColumnRect.width - totalWidth) / 2).coerceAtLeast(0)
        return (startX + totalWidth).coerceAtMost(table.width - DIVIDER_LINE_INSET)
    }

    private fun actionAt(row: Int, x: Int): RowAction? {
        val info = (tableModel.rows.getOrNull(row) as? TableRow.Data)?.info ?: return null
        val cell = table.getCellRect(row, UninstallTableModel.COL_ACTION, true)
        val localX = x - cell.x
        val actions = actionsFor(info)
        val totalWidth = actionGroupWidth(actions)
        val fullWidth = actionGroupWidth(RowAction.entries)
        val fullStartX = ((cell.width - fullWidth) / 2).coerceAtLeast(0)
        val startX = fullStartX + fullWidth - totalWidth
        if (localX < startX || localX >= startX + totalWidth) return null
        val offset = localX - startX
        val stride = ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP
        val idx = offset / stride
        if (offset % stride >= ACTION_BUTTON_SIZE) return null
        if (idx !in actions.indices) return null
        val action = actions[idx]
        return action.takeIf { isActionEnabled(it, info) }
    }

    private fun actionTargetAt(e: MouseEvent): RowActionTarget? {
        if (!this::table.isInitialized) return null
        val row = table.rowAtPoint(e.point)
        if (row < 0 || table.columnAtPoint(e.point) != UninstallTableModel.COL_ACTION) return null
        val info = (tableModel.rows.getOrNull(row) as? TableRow.Data)?.info ?: return null
        val action = actionAt(row, e.point.x) ?: return null
        return RowActionTarget(row, info.packageName, action)
    }

    private fun setPressedAction(target: RowActionTarget?) {
        if (pressedAction == target) return
        pressedAction = target
        if (this::table.isInitialized) table.repaint()
    }

    private fun actionsFor(info: AppInstallInfo): List<RowAction> =
        if (info.isFromProject) RowAction.entries else listOf(RowAction.CLEAR, RowAction.UNINSTALL)

    private fun actionGroupWidth(actions: List<RowAction>): Int =
        actions.size * ACTION_BUTTON_SIZE + (actions.size - 1).coerceAtLeast(0) * ACTION_BUTTON_GAP

    private fun activeAction(info: AppInstallInfo): RowAction? = when (info.packageName) {
        in reinstallingPackages -> RowAction.REINSTALL
        in clearingPackages -> RowAction.CLEAR
        in uninstallingPackages -> RowAction.UNINSTALL
        else -> null
    }

    private fun isActionEnabled(action: RowAction, info: AppInstallInfo): Boolean {
        if (currentSerial() == null) return false
        if (activeAction(info) != null) return false
        return when (action) {
            RowAction.REINSTALL -> info.isFromProject && info.apkFiles.isNotEmpty()
            RowAction.CLEAR -> info.status == InstallStatus.INSTALLED
            RowAction.UNINSTALL -> info.isUninstallable
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
        reinstallingPackages.isNotEmpty() || clearingPackages.isNotEmpty() || uninstallingPackages.isNotEmpty()

    private fun appTooltip(info: AppInstallInfo): String {
        val module = info.moduleName.ifEmpty { "Device-only app" }
        val installTime = if (info.installTimeMs > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(info.installTimeMs))
        } else {
            "Unknown"
        }
        val apkText = if (info.apkFiles.isEmpty()) {
            "No APK found"
        } else {
            info.apkFiles.joinToString("<br>") { htmlEscape(apkLabel(it)) }
        }
        return """
            <html>
            <b>${htmlEscape(module)}</b><br>
            Package: ${htmlEscape(info.packageName)}<br>
            Status: ${htmlEscape(statusText(info.status))}<br>
            Install time: ${htmlEscape(installTime)}<br>
            APK: $apkText
            </html>
        """.trimIndent()
    }

    private fun statusText(status: InstallStatus): String = when (status) {
        InstallStatus.INSTALLED -> "Installed"
        InstallStatus.NOT_INSTALLED -> "Not installed"
        InstallStatus.SYSTEM_ONLY -> "System (cannot uninstall)"
        InstallStatus.UNKNOWN -> "Querying..."
    }

    private fun actionTooltip(action: RowAction, info: AppInstallInfo): String = when (action) {
        RowAction.REINSTALL -> reinstallTooltip(info)
        RowAction.CLEAR -> "Clear app data"
        RowAction.UNINSTALL -> "Uninstall"
    }

    private fun reinstallTooltip(info: AppInstallInfo): String = when {
        !info.isFromProject -> "Device-only app, cannot reinstall"
        info.apkFiles.isEmpty() -> "No APK found — build first"
        else -> {
            val actionText = if (info.status == InstallStatus.INSTALLED) "Reinstall" else "Install"
            """
                <html>
                ${htmlEscape(actionText)}<br>
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

    private fun commandFailureMessage(summary: String, output: String): String {
        val details = output.takeIf(String::isNotBlank) ?: "ADB returned no output."
        return "$summary\n\n$details"
    }

    // "app-debug.apk  [debug]  06-03 14:22"
    private fun apkLabel(f: File): String {
        val folder = f.parentFile?.name ?: ""
        val time = SimpleDateFormat("MM-dd HH:mm").format(Date(f.lastModified()))
        return "${f.name}  [$folder]  $time"
    }

    // ── Renderer ──────────────────────────────────────────────────────────────

    private inner class UniversalRenderer : TableCellRenderer {
        private val textRenderer = DefaultTableCellRenderer()
        private val spinnerIcon = AnimatedIcon.Default()
        private val checkbox = JCheckBox().apply {
            isOpaque = true
            horizontalAlignment = SwingConstants.CENTER
            isFocusable = false
            isRequestFocusEnabled = false
            model.isRollover = false
        }
        private val actionPanel = JPanel(GridBagLayout()).apply { isOpaque = true }
        private val actionButtonByType = RowAction.entries.associateWith { action ->
            JButton().apply {
                icon = action.enabledIcon
                disabledIcon = action.disabledIcon
                preferredSize = Dimension(ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)
                minimumSize = preferredSize
                maximumSize = preferredSize
                isFocusable = false
                isFocusPainted = false
                isBorderPainted = false
                isContentAreaFilled = false
                isOpaque = false
                margin = Insets(0, 0, 0, 0)
                text = ""
            }
        }

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component = when (val r = tableModel.rows[row]) {
            is TableRow.Divider -> dividerCell(tbl, col)
            is TableRow.Data -> dataCell(r, tbl, value, isSelected, hasFocus, row, col)
        }

        private fun dividerCell(tbl: JTable, col: Int): Component =
            JPanel().apply {
                background = tbl.background
                isOpaque = true
            }

        private fun dataCell(
            r: TableRow.Data, tbl: JTable, value: Any?,
            isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component = when (col) {
            UninstallTableModel.COL_CHECK -> checkbox.apply {
                this.isSelected = r.selected
                isEnabled = r.info.isUninstallable
                background = rowBackground(tbl, row, r)
                model.isRollover = false
                model.isArmed = false
                model.isPressed = false
            }
            UninstallTableModel.COL_ACTION -> {
                actionPanel.apply {
                    removeAll()
                    background = rowBackground(tbl, row, r)
                    val activeAction = activeAction(r.info)
                    add(Box.createHorizontalStrut(actionGroupWidth(RowAction.entries) - actionGroupWidth(actionsFor(r.info))), GridBagConstraints().apply {
                        gridx = 0
                    })
                    actionsFor(r.info).forEachIndexed { index, action ->
                        val enabled = isActionEnabled(action, r.info)
                        val button = actionButtonByType.getValue(action).apply {
                            isEnabled = enabled
                            icon = if (activeAction == action) spinnerIcon else action.enabledIcon
                            disabledIcon = if (activeAction == action) spinnerIcon else action.disabledIcon
                            toolTipText = if (activeAction == action) action.loadingTooltip else actionTooltip(action, r.info)
                        }
                        add(button, GridBagConstraints().apply {
                            gridx = index + 1
                            insets = Insets(0, if (index == 0) 0 else ACTION_BUTTON_GAP, 0, 0)
                        })
                    }
                }
            }
            UninstallTableModel.COL_APP -> appCell(r, tbl, row)
            else -> {
                val c = textRenderer.getTableCellRendererComponent(tbl, value, false, false, row, col)
                c.background = rowBackground(tbl, row, r)
                (c as? JLabel)?.border = if (col == UninstallTableModel.COL_STATUS) {
                    JBUI.Borders.empty()
                } else {
                    JBUI.Borders.emptyLeft(16)
                }
                (c as? JLabel)?.horizontalAlignment = if (col == UninstallTableModel.COL_STATUS) {
                    SwingConstants.CENTER
                } else {
                    SwingConstants.LEFT
                }
                (c as? JLabel)?.foreground = when (r.info.status) {
                    InstallStatus.INSTALLED -> Color(0x82, 0xCC, 0x8D)
                    else -> UIManager.getColor("Label.disabledForeground") ?: Color(0x9A, 0x9D, 0xA4)
                }
                c
            }
        }

        private fun rowBackground(tbl: JTable, row: Int, data: TableRow.Data): Color = tbl.background

        private fun appCell(rowData: TableRow.Data, tbl: JTable, row: Int): Component =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(5, 10, 5, 16)
                background = rowBackground(tbl, row, rowData)
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

    private class CenterHeaderRenderer(private val delegate: TableCellRenderer) : TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val c = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            (c as? JLabel)?.horizontalAlignment = SwingConstants.CENTER
            return c
        }
    }
}

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

private fun actionPressedBackground(base: Color): Color =
    blendColors(Color(0x5D, 0x8D, 0xFF), base, 0.34f)

private data class RowActionTarget(
    val row: Int,
    val packageName: String,
    val action: RowAction,
) {
    fun matches(info: AppInstallInfo, action: RowAction): Boolean =
        packageName == info.packageName && this.action == action
}

private enum class RowAction {
    REINSTALL,
    CLEAR,
    UNINSTALL;

    val enabledIcon: Icon
        get() = IconLoader.getIcon("/icons/${svgName}.svg", RowAction::class.java)

    val disabledIcon: Icon
        get() = IconLoader.getIcon("/icons/${svgName}_disabled.svg", RowAction::class.java)

    private val svgName get() = when (this) {
        REINSTALL -> "action_reinstall"
        UNINSTALL -> "action_uninstall"
        CLEAR     -> "action_cleardata"
    }

    val loadingTooltip: String
        get() = when (this) {
            REINSTALL -> "Installing..."
            CLEAR -> "Clearing data..."
            UNINSTALL -> "Uninstalling..."
        }
}
