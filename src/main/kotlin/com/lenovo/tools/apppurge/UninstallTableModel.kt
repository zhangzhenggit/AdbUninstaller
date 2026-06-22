package com.lenovo.tools.apppurge

import javax.swing.table.AbstractTableModel

sealed class TableRow {
    object Divider : TableRow()
    data class Data(val info: AppInstallInfo, var selected: Boolean = false) : TableRow()
}

class UninstallTableModel : AbstractTableModel() {

    internal val rows: MutableList<TableRow> = mutableListOf()
    private var projectItems: List<AppInstallInfo> = emptyList()
    private var deviceItems: List<AppInstallInfo> = emptyList()

    val selectedItems: List<AppInstallInfo>
        get() = rows.filterIsInstance<TableRow.Data>()
            .filter { it.selected && it.info.isUninstallable }
            .map { it.info }

    val visibleItems: List<AppInstallInfo>
        get() = rows.filterIsInstance<TableRow.Data>().map { it.info }

    val selectedCount: Int
        get() = rows.filterIsInstance<TableRow.Data>().count { it.selected && it.info.isUninstallable }

    val installableVisibleCount: Int
        get() = rows.filterIsInstance<TableRow.Data>().count { it.info.isUninstallable }

    val allVisibleSelected: Boolean
        get() = installableVisibleCount > 0 && selectedCount == installableVisibleCount

    fun resetItems(projectItems: List<AppInstallInfo>, deviceItems: List<AppInstallInfo> = emptyList()) {
        this.projectItems = projectItems
        this.deviceItems = deviceItems
        rebuildRows()
    }

    fun addDeviceItem(item: AppInstallInfo) {
        if (deviceItems.any { it.packageName == item.packageName }) return
        deviceItems = deviceItems + item
        rebuildRows()
    }

    fun removeDeviceItem(packageName: String) {
        if (deviceItems.none { it.packageName == packageName }) return
        deviceItems = deviceItems.filterNot { it.packageName == packageName }
        rebuildRows()
    }

    fun updateDeviceLabel(packageName: String, label: String) {
        if (label.isBlank()) return
        val info = deviceItems.firstOrNull { it.packageName == packageName } ?: return
        if (info.moduleName == label) return
        info.moduleName = label
        val idx = rows.indexOfFirst { it is TableRow.Data && it.info.packageName == packageName }
        if (idx >= 0) fireTableCellUpdated(idx, COL_APP)
    }

    fun updateActiveApkPaths(packageName: String, paths: List<String>) {
        val info = rows.filterIsInstance<TableRow.Data>().firstOrNull { it.info.packageName == packageName }?.info
            ?: (projectItems + deviceItems).firstOrNull { it.packageName == packageName }
            ?: return
        if (info.activeApkPaths == paths) return
        info.activeApkPaths = paths
        val idx = rows.indexOfFirst { it is TableRow.Data && it.info.packageName == packageName }
        if (idx >= 0) fireTableCellUpdated(idx, COL_STATUS)
    }

    private fun rebuildRows() {
        rows.clear()

        projectItems
            .sortedWith(compareBy<AppInstallInfo> { statusOrder(it) }.thenByDescending { it.installTimeMs }.thenBy { it.packageName })
            .forEach { rows.add(TableRow.Data(it)) }

        if (deviceItems.isNotEmpty()) rows.add(TableRow.Divider)

        deviceItems.sortedByDescending { it.installTimeMs }
            .forEach { rows.add(TableRow.Data(it)) }

        fireTableDataChanged()
    }

    fun updateRow(
        packageName: String,
        newStatus: InstallStatus,
        installTimeMs: Long = 0L,
        activeApkPaths: List<String>? = null,
    ) {
        val idx = rows.indexOfFirst { it is TableRow.Data && it.info.packageName == packageName }
        val info = rows.filterIsInstance<TableRow.Data>().firstOrNull { it.info.packageName == packageName }?.info
            ?: (projectItems + deviceItems).firstOrNull { it.packageName == packageName }
            ?: return
        info.let {
            it.status = newStatus
            if (installTimeMs > 0L) it.installTimeMs = installTimeMs
            if (activeApkPaths != null) it.activeApkPaths = activeApkPaths
        }
        if (idx >= 0) {
            (rows[idx] as TableRow.Data).selected = false
            fireTableRowsUpdated(idx, idx)
        } else {
            rebuildRows()
        }
    }

    fun notifyRowChanged(packageName: String) {
        val idx = rows.indexOfFirst { it is TableRow.Data && it.info.packageName == packageName }
        if (idx >= 0) fireTableRowsUpdated(idx, idx)
    }

    fun setSelectAll(select: Boolean) {
        rows.forEachIndexed { i, row ->
            if (row is TableRow.Data && row.info.isUninstallable) {
                row.selected = select
                fireTableCellUpdated(i, COL_CHECK)
            }
        }
    }

    fun toggleSelectAllVisible() {
        setSelectAll(!allVisibleSelected)
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = COLUMNS.size
    override fun getColumnName(col: Int): String = COLUMNS[col]
    override fun getColumnClass(col: Int): Class<*> =
        if (col == COL_CHECK) Boolean::class.javaObjectType else String::class.java

    override fun isCellEditable(row: Int, col: Int): Boolean {
        val r = rows[row]
        return when (col) {
            COL_CHECK -> r is TableRow.Data && r.info.isUninstallable
            COL_REINSTALL, COL_CLEAR, COL_UNINSTALL, COL_PUSH -> r is TableRow.Data
            else -> false
        }
    }

    override fun getValueAt(row: Int, col: Int): Any = when (val r = rows[row]) {
        is TableRow.Divider -> if (col == COL_CHECK) false else ""
        is TableRow.Data -> when (col) {
            COL_CHECK -> r.selected
            COL_APP -> r.info.moduleName.ifEmpty { r.info.packageName }
            COL_STATUS -> statusText(r.info)
            COL_REINSTALL, COL_CLEAR, COL_UNINSTALL, COL_PUSH -> ""
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        if (col == COL_CHECK && rows[row] is TableRow.Data) {
            (rows[row] as TableRow.Data).selected = value as Boolean
            fireTableCellUpdated(row, col)
        }
    }

    companion object {
        const val COL_CHECK = 0
        const val COL_APP = 1
        const val COL_STATUS = 2
        const val COL_REINSTALL = 3
        const val COL_CLEAR = 4
        const val COL_UNINSTALL = 5
        const val COL_PUSH = 6
        val COLUMNS = arrayOf("", "App", "Status", "", "", "", "")

        private fun statusOrder(info: AppInstallInfo) = when (info.status) {
            InstallStatus.USER_APP -> 0
            InstallStatus.UPDATED_SYSTEM_APP -> 1
            InstallStatus.SYSTEM_APP -> 2
            InstallStatus.NOT_INSTALLED -> 3
            InstallStatus.UNKNOWN -> 4
        }

        private fun statusText(info: AppInstallInfo) = when (info.status) {
            InstallStatus.USER_APP -> "Installed"
            InstallStatus.UPDATED_SYSTEM_APP -> "Installed"
            InstallStatus.SYSTEM_APP -> "Installed (system)"
            InstallStatus.NOT_INSTALLED -> "Not installed"
            InstallStatus.UNKNOWN -> "Querying…"
        }
    }
}
