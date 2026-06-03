package com.lenovo.tools.apppurge

import javax.swing.table.AbstractTableModel

sealed class TableRow {
    class Section(val key: SectionKey, val title: String) : TableRow()
    object Divider : TableRow()
    data class Data(val info: AppInstallInfo, var selected: Boolean = false) : TableRow()
}

enum class SectionKey {
    PROJECT,
    DEVICE,
}

class UninstallTableModel : AbstractTableModel() {

    internal val rows: MutableList<TableRow> = mutableListOf()
    private var projectItems: List<AppInstallInfo> = emptyList()
    private var deviceItems: List<AppInstallInfo> = emptyList()
    private val collapsedSections: MutableSet<SectionKey> = mutableSetOf()

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

    /**
     * Rebuilds table:
     *   Project Apps section  — installed (↓ time) then not-installed/system-only (↓ status order)
     *   Divider + Device Apps — only when deviceItems is non-empty (show-all mode)
     */
    fun resetItems(projectItems: List<AppInstallInfo>, deviceItems: List<AppInstallInfo> = emptyList()) {
        this.projectItems = projectItems
        this.deviceItems = deviceItems
        rebuildRows()
    }

    private fun rebuildRows() {
        rows.clear()

        rows.add(TableRow.Section(SectionKey.PROJECT, "Project Apps"))
        if (SectionKey.PROJECT !in collapsedSections) projectItems
            .sortedWith(compareBy<AppInstallInfo> { statusOrder(it) }.thenByDescending { it.installTimeMs }.thenBy { it.packageName })
            .forEach { rows.add(TableRow.Data(it)) }

        if (deviceItems.isNotEmpty()) {
            rows.add(TableRow.Section(SectionKey.DEVICE, "Other User-Installed Apps"))
            if (SectionKey.DEVICE !in collapsedSections) deviceItems.sortedByDescending { it.installTimeMs }
                .forEach { rows.add(TableRow.Data(it)) }
        }

        fireTableDataChanged()
    }

    fun updateRow(packageName: String, newStatus: InstallStatus, installTimeMs: Long = 0L) {
        val idx = rows.indexOfFirst { it is TableRow.Data && it.info.packageName == packageName }
        val info = rows.filterIsInstance<TableRow.Data>().firstOrNull { it.info.packageName == packageName }?.info
            ?: (projectItems + deviceItems).firstOrNull { it.packageName == packageName }
            ?: return
        info.let {
            it.status = newStatus
            if (installTimeMs > 0L) it.installTimeMs = installTimeMs
        }
        if (idx >= 0) {
            (rows[idx] as TableRow.Data).selected = false
            fireTableRowsUpdated(idx, idx)
        } else {
            rebuildRows()
        }
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

    fun toggleSectionAt(row: Int): Boolean {
        val section = rows.getOrNull(row) as? TableRow.Section ?: return false
        if (!collapsedSections.add(section.key)) collapsedSections.remove(section.key)
        rebuildRows()
        return true
    }

    fun isSectionCollapsed(key: SectionKey): Boolean = key in collapsedSections

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = COLUMNS.size
    override fun getColumnName(col: Int): String = COLUMNS[col]
    override fun getColumnClass(col: Int): Class<*> =
        if (col == COL_CHECK) Boolean::class.javaObjectType else String::class.java

    override fun isCellEditable(row: Int, col: Int): Boolean {
        val r = rows[row]
        return col == COL_CHECK && r is TableRow.Data && r.info.isUninstallable
    }

    override fun getValueAt(row: Int, col: Int): Any = when (val r = rows[row]) {
        is TableRow.Section -> when (col) { COL_CHECK -> false; COL_APP -> r.title; else -> "" }
        is TableRow.Divider -> if (col == COL_CHECK) false else ""
        is TableRow.Data -> when (col) {
            COL_CHECK -> r.selected
            COL_APP -> r.info.moduleName.ifEmpty { r.info.packageName }
            COL_STATUS -> statusText(r.info)
            COL_ACTION -> ""
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
        const val COL_ACTION = 3
        val COLUMNS = arrayOf("", "App", "Status", "Options")

        private fun statusOrder(info: AppInstallInfo) = when (info.status) {
            InstallStatus.INSTALLED -> 0
            InstallStatus.NOT_INSTALLED -> 1
            InstallStatus.SYSTEM_ONLY -> 2
            InstallStatus.UNKNOWN -> 3
        }

        private fun statusText(info: AppInstallInfo) = when (info.status) {
            InstallStatus.INSTALLED -> "Installed"
            InstallStatus.NOT_INSTALLED -> "Not installed"
            InstallStatus.SYSTEM_ONLY -> "System (cannot uninstall)"
            InstallStatus.UNKNOWN -> "Querying…"
        }
    }
}
