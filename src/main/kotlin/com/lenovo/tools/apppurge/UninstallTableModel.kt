package com.lenovo.tools.apppurge

import java.util.Locale
import javax.swing.table.AbstractTableModel

data class TableRow(val info: AppInstallInfo, var selected: Boolean = false)

class UninstallTableModel : AbstractTableModel() {

    internal val rows: MutableList<TableRow> = mutableListOf()
    private var projectItems: List<AppInstallInfo> = emptyList()

    val selectedItems: List<AppInstallInfo>
        get() = rows.filter { it.selected && it.info.isUninstallable }
            .map { it.info }

    val visibleItems: List<AppInstallInfo>
        get() = rows.map { it.info }

    val selectedCount: Int
        get() = rows.count { it.selected && it.info.isUninstallable }

    val installableVisibleCount: Int
        get() = rows.count { it.info.isUninstallable }

    val allVisibleSelected: Boolean
        get() = installableVisibleCount > 0 && selectedCount == installableVisibleCount

    fun resetItems(projectItems: List<AppInstallInfo>) {
        this.projectItems = projectItems
        rebuildRows()
    }

    private fun rebuildRows() {
        rows.clear()

        projectItems
            .sortedWith(compareBy<AppInstallInfo> { it.moduleName.lowercase(Locale.ROOT) }
                .thenBy { it.packageName.lowercase(Locale.ROOT) })
            .forEach { rows.add(TableRow(it)) }

        fireTableDataChanged()
    }

    fun updateRow(
        packageName: String,
        newStatus: InstallStatus,
        activeApkPaths: List<String>? = null,
    ) {
        val idx = rows.indexOfFirst { it.info.packageName == packageName }
        val info = rows.firstOrNull { it.info.packageName == packageName }?.info
            ?: projectItems.firstOrNull { it.packageName == packageName }
            ?: return
        info.let {
            it.status = newStatus
            if (activeApkPaths != null) it.activeApkPaths = activeApkPaths
        }
        if (idx >= 0) {
            rows[idx].selected = false
            fireTableRowsUpdated(idx, idx)
        } else {
            rebuildRows()
        }
    }

    fun notifyRowChanged(packageName: String) {
        val idx = rows.indexOfFirst { it.info.packageName == packageName }
        if (idx >= 0) fireTableRowsUpdated(idx, idx)
    }

    fun setSelectAll(select: Boolean) {
        rows.forEachIndexed { i, row ->
            if (row.info.isUninstallable) {
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
        return when (col) {
            COL_CHECK -> rows[row].info.isUninstallable
            COL_REINSTALL, COL_CLEAR, COL_UNINSTALL, COL_PUSH -> true
            else -> false
        }
    }

    override fun getValueAt(row: Int, col: Int): Any {
        val r = rows[row]
        return when (col) {
            COL_CHECK -> r.selected
            COL_APP -> r.info.moduleName.ifEmpty { r.info.packageName }
            COL_STATUS -> statusText(r.info)
            COL_REINSTALL, COL_CLEAR, COL_UNINSTALL, COL_PUSH -> ""
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        if (col == COL_CHECK) {
            rows[row].selected = value as Boolean
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

        private fun statusText(info: AppInstallInfo) = when (info.status) {
            InstallStatus.USER_APP -> "Installed"
            InstallStatus.UPDATED_SYSTEM_APP -> "Installed"
            InstallStatus.SYSTEM_APP -> "Installed (system)"
            InstallStatus.NOT_INSTALLED -> "Not installed"
            InstallStatus.UNKNOWN -> "Querying…"
        }
    }
}
