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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

private const val PLUGIN_VERSION = "1.1.4"
private const val TOGGLE_DEFAULT = "Show all user-installed on device"
private const val CARD_TOGGLE = "toggle"
private const val CARD_LOADING = "loading"
private const val ACTION_BUTTON_SIZE = 36
private const val ACTION_BUTTON_GAP = 8

class UninstallDialog(
    project: Project,
    private val projectAppInfos: List<AppInstallInfo>,
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
    private val statusLabel = JLabel("Ready")
    private val reinstallingPackages = mutableSetOf<String>()
    private val clearingPackages = mutableSetOf<String>()
    private val uninstallingPackages = mutableSetOf<String>()
    private val uninstallBtn = JButton("Uninstall Selected").apply {
        foreground = Color(0xD3, 0x56, 0x5C)
    }

    init {
        title = "AppPurge — Uninstall APKs"
        init()
        if (serials.isNotEmpty()) loadInstallStatus(serials[0], showAll = false)
    }

    override fun createCenterPanel(): JComponent {
        tableModel = UninstallTableModel()
        table = object : JBTable(tableModel) {
            // Single full-width line for Divider rows
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                g.color = UIManager.getColor("Separator.foreground") ?: Color.LIGHT_GRAY
                tableModel.rows.forEachIndexed { i, row ->
                    if (row is TableRow.Divider) {
                        val r = getCellRect(i, 0, true)
                        g.drawLine(0, r.y + r.height / 2, width, r.y + r.height / 2)
                    }
                }
            }

            override fun getToolTipText(e: MouseEvent): String? {
                val row = rowAtPoint(e.point)
                val col = columnAtPoint(e.point)
                val data = tableModel.rows.getOrNull(row) as? TableRow.Data ?: return null
                return if (col == UninstallTableModel.COL_ACTION) {
                    actionAt(row, e.point.x)?.tooltip(data.info)
                } else {
                    appTooltip(data.info)
                }
            }
        }.apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 68
            columnModel.getColumn(UninstallTableModel.COL_CHECK).apply { maxWidth = 54; minWidth = 54 }
            columnModel.getColumn(UninstallTableModel.COL_APP).preferredWidth = 400
            columnModel.getColumn(UninstallTableModel.COL_STATUS).preferredWidth = 300
            columnModel.getColumn(UninstallTableModel.COL_ACTION).apply { maxWidth = 192; minWidth = 192 }
            val renderer = UniversalRenderer()
            for (i in 0 until columnCount) columnModel.getColumn(i).cellRenderer = renderer

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (col != UninstallTableModel.COL_ACTION || row < 0) return
                    val tableRow = tableModel.rows.getOrNull(row) as? TableRow.Data ?: return
                    val info = tableRow.info
                    val serial = currentSerial() ?: return
                    when (actionAt(row, e.point.x)) {
                        RowAction.REINSTALL -> chooseApkAndReinstall(serial, info)
                        RowAction.CLEAR -> onClearData(serial, info)
                        RowAction.UNINSTALL -> onUninstallOne(serial, info)
                        null -> return
                    }
                }
            })
        }

        val spinnerLabel = JLabel("Fetching device apps…", AnimatedIcon.Default(), SwingConstants.LEFT).apply {
            border = JBUI.Borders.empty(0, 2)
        }
        toggleWrapper.add(showAllToggle, CARD_TOGGLE)
        toggleWrapper.add(spinnerLabel, CARD_LOADING)

        val devicePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Device:"))
            add(deviceCombo.apply {
                preferredSize = Dimension(260, 28)
                addActionListener { reload() }
            })
            add(JButton("Refresh").apply { addActionListener { reload() } })
            add(toggleWrapper)
            showAllToggle.addActionListener { reload() }
        }

        val tableScroll = JBScrollPane(table).apply { preferredSize = Dimension(820, 420) }

        val selectAllBtn = JButton("Select All").apply { addActionListener { tableModel.setSelectAll(true) } }
        val deselectAllBtn = JButton("Deselect All").apply { addActionListener { tableModel.setSelectAll(false) } }
        uninstallBtn.addActionListener { onBatchUninstall() }

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            border = JBUI.Borders.emptyTop(2)
            add(selectAllBtn); add(deselectAllBtn)
        }
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            border = JBUI.Borders.emptyTop(2)
            add(statusLabel); add(uninstallBtn)
        }
        val bottomPanel = JPanel(BorderLayout()).apply {
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
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

    private fun currentSerial(): String? {
        val idx = deviceCombo.selectedIndex
        return if (idx >= 0 && idx < serials.size) serials[idx] else null
    }

    private fun reload() {
        val serial = currentSerial() ?: return
        loadInstallStatus(serial, showAll = showAllToggle.isSelected)
    }

    private fun setLoading(loading: Boolean) {
        uninstallBtn.isEnabled = !loading
        deviceCombo.isEnabled = !loading
        showAllToggle.isEnabled = !loading
        (toggleWrapper.layout as CardLayout).show(toggleWrapper, if (loading) CARD_LOADING else CARD_TOGGLE)
    }

    private fun updateRowHeights() {
        tableModel.rows.forEachIndexed { i, row ->
            when (row) {
                is TableRow.Section -> table.setRowHeight(i, 34)
                else -> table.setRowHeight(i, 68)
            }
        }
    }

    private fun loadInstallStatus(serial: String, showAll: Boolean) {
        setLoading(true)
        setStatus(if (showAll) "Fetching all user apps…" else "Querying project modules…")

        Thread {
            try {
                val installedPkgs = AdbService.getAllInstalledPackages(serial, adbPath)
                val systemPkgs = AdbService.getAllSystemPackages(serial, adbPath)

                projectAppInfos.forEach { info ->
                    info.status = when {
                        info.packageName in installedPkgs -> InstallStatus.INSTALLED
                        info.packageName in systemPkgs -> InstallStatus.SYSTEM_ONLY
                        else -> InstallStatus.NOT_INSTALLED
                    }
                }
                projectAppInfos.filter { it.status == InstallStatus.INSTALLED }.forEach { info ->
                    info.installTimeMs = AdbService.getInstallTime(serial, info.packageName, adbPath)
                }

                // Scan build APKs for project modules
                projectAppInfos.forEach { info ->
                    if (info.module != null) info.apkFiles = ApkFinder.findApks(info.module)
                }

                val deviceItems: List<AppInstallInfo> = if (showAll) {
                    val userPkgs = installedPkgs - systemPkgs
                    val projectPkgs = projectAppInfos.map { it.packageName }.toSet()
                    (userPkgs - projectPkgs).map { pkg ->
                        AppInstallInfo(null, "", pkg, InstallStatus.INSTALLED)
                    }
                } else emptyList()

                val projectInstalledCnt = projectAppInfos.count { it.status == InstallStatus.INSTALLED }
                val statusMsg = if (showAll) {
                    "$projectInstalledCnt / ${projectAppInfos.size} project installed · ${deviceItems.size} device-only"
                } else {
                    "$projectInstalledCnt / ${projectAppInfos.size} installed"
                }

                SwingUtilities.invokeLater {
                    tableModel.resetItems(projectAppInfos, deviceItems)
                    updateRowHeights()
                    setStatus(statusMsg)
                    setLoading(false)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
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
                    tableModel.updateRow(info.packageName, if (ok) InstallStatus.NOT_INSTALLED else info.status)
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
        table.repaint()
        setStatus("Clearing data for ${info.packageName}…")
        Thread {
            val result = AdbService.clearAppData(serial, info.packageName, adbPath)
            SwingUtilities.invokeLater {
                clearingPackages.remove(info.packageName)
                table.repaint()
                if (result.success) {
                    setStatus("Cleared data: ${info.packageName}")
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
        table.repaint()
        setStatus("Uninstalling ${info.packageName}…")
        Thread {
            val result = AdbService.uninstallPackage(serial, info.packageName, adbPath)
            SwingUtilities.invokeLater {
                uninstallingPackages.remove(info.packageName)
                table.repaint()
                if (result.success) {
                    tableModel.updateRow(info.packageName, InstallStatus.NOT_INSTALLED)
                    setStatus("Uninstalled ${info.packageName}")
                } else {
                    setStatus("Uninstall failed: ${info.packageName}")
                    Messages.showErrorDialog(commandFailureMessage("Failed to uninstall app.", result.output), "AppPurge Uninstall Failed")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun onReinstall(serial: String, info: AppInstallInfo, apk: File) {
        if (!reinstallingPackages.add(info.packageName)) return
        table.repaint()
        setStatus("Installing ${info.packageName}…")
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
                table.repaint()
                if (installed) {
                    tableModel.updateRow(info.packageName, InstallStatus.INSTALLED, installTimeMs)
                    setStatus("Installed ${info.packageName}")
                } else {
                    setStatus("Install failed: ${info.packageName}")
                    Messages.showErrorDialog(
                        commandFailureMessage("Failed to install APK with adb install -r.", result.output),
                        "AppPurge Install Failed",
                    )
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun setStatus(text: String) { statusLabel.text = text }

    private fun actionAt(row: Int, x: Int): RowAction? {
        val info = (tableModel.rows.getOrNull(row) as? TableRow.Data)?.info ?: return null
        val cell = table.getCellRect(row, UninstallTableModel.COL_ACTION, true)
        val localX = x - cell.x
        val totalWidth = RowAction.entries.size * ACTION_BUTTON_SIZE + (RowAction.entries.size - 1) * ACTION_BUTTON_GAP
        val startX = ((cell.width - totalWidth) / 2).coerceAtLeast(0)
        if (localX < startX || localX >= startX + totalWidth) return null
        val offset = localX - startX
        val stride = ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP
        val idx = offset / stride
        if (offset % stride >= ACTION_BUTTON_SIZE) return null
        if (idx !in RowAction.entries.indices) return null
        val action = RowAction.entries[idx]
        return action.takeIf { isActionEnabled(it, info) }
    }

    private fun isActionEnabled(action: RowAction, info: AppInstallInfo): Boolean {
        if (currentSerial() == null) return false
        if (info.packageName in reinstallingPackages ||
            info.packageName in clearingPackages ||
            info.packageName in uninstallingPackages) return false
        return when (action) {
            RowAction.REINSTALL -> info.isFromProject
            RowAction.CLEAR -> info.status == InstallStatus.INSTALLED
            RowAction.UNINSTALL -> info.isUninstallable
        }
    }

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
        private val checkbox = JCheckBox().apply { isOpaque = true; horizontalAlignment = SwingConstants.CENTER }
        private val actionPanel = JPanel(FlowLayout(FlowLayout.CENTER, ACTION_BUTTON_GAP, 0)).apply { isOpaque = true }
        private val actionButtons = RowAction.entries.map { action ->
            JButton().apply {
                icon = action.enabledIcon
                disabledIcon = action.disabledIcon
                preferredSize = Dimension(ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)
                minimumSize = preferredSize
                maximumSize = preferredSize
                isFocusable = false
                isBorderPainted = false
                isContentAreaFilled = false
                margin = Insets(0, 0, 0, 0)
                text = ""
            }
        }

        private val sectionBg get() = UIManager.getColor("Table.stripeColor")
            ?: UIManager.getColor("Panel.background") ?: Color(245, 245, 245)
        private val sectionFg get() = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component = when (val r = tableModel.rows[row]) {
            is TableRow.Section -> sectionCell(r.title, col)
            is TableRow.Divider -> JPanel().apply { background = tbl.background; isOpaque = true }
            is TableRow.Data   -> dataCell(r, tbl, value, isSelected, hasFocus, row, col)
        }

        private fun sectionCell(title: String, col: Int): Component =
            JPanel(BorderLayout()).apply {
                background = sectionBg; isOpaque = true
                if (col == UninstallTableModel.COL_APP) {
                    add(JLabel("▾  $title").apply {
                        foreground = sectionFg
                        font = font.deriveFont(Font.BOLD, 13f)
                        border = JBUI.Borders.empty(0, 4)
                    }, BorderLayout.WEST)
                }
            }

        private fun dataCell(
            r: TableRow.Data, tbl: JTable, value: Any?,
            isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component = when (col) {
            UninstallTableModel.COL_CHECK -> checkbox.apply {
                this.isSelected = r.selected
                isEnabled = r.info.isUninstallable
                background = if (isSelected) tbl.selectionBackground else tbl.background
            }
            UninstallTableModel.COL_ACTION -> {
                actionPanel.apply {
                    removeAll()
                    background = if (isSelected) tbl.selectionBackground else tbl.background
                    RowAction.entries.forEachIndexed { index, action ->
                        val enabled = isActionEnabled(action, r.info)
                        actionButtons[index].apply {
                            isEnabled = enabled
                            toolTipText = action.tooltip(r.info)
                            background = actionPanel.background
                        }
                        add(actionButtons[index])
                    }
                }
            }
            UninstallTableModel.COL_APP -> appCell(r.info, tbl, isSelected)
            else -> {
                val c = textRenderer.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col)
                if (!isSelected) {
                    (c as? JLabel)?.foreground = when (r.info.status) {
                        InstallStatus.INSTALLED -> Color(0x82, 0xCC, 0x8D)
                        else -> UIManager.getColor("Label.disabledForeground") ?: Color(0x9A, 0x9D, 0xA4)
                    }
                }
                c
            }
        }

        private fun appCell(info: AppInstallInfo, tbl: JTable, isSelected: Boolean): Component =
            JPanel(GridLayout(2, 1, 0, 0)).apply {
                border = JBUI.Borders.empty(4, 0)
                background = if (isSelected) tbl.selectionBackground else tbl.background
                isOpaque = true
                val primary = info.moduleName.ifEmpty { info.packageName }
                add(JLabel(primary).apply {
                    foreground = tbl.foreground
                    font = font.deriveFont(Font.BOLD, 14f)
                })
                add(JLabel(if (info.moduleName.isEmpty()) "" else info.packageName).apply {
                    foreground = UIManager.getColor("Label.disabledForeground") ?: tbl.foreground.darker()
                    font = font.deriveFont(12f)
                })
            }
    }
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

    fun tooltip(info: AppInstallInfo): String = when (this) {
        REINSTALL -> when {
            !info.isFromProject -> "Device-only app, cannot reinstall"
            info.apkFiles.isEmpty() -> "No APK found — build first"
            info.status == InstallStatus.INSTALLED -> "Reinstall"
            else -> "Install"
        }
        CLEAR -> "Clear app data"
        UNINSTALL -> "Uninstall"
    }
}
