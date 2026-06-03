package com.lenovo.tools.apppurge

import com.intellij.openapi.module.Module
import java.io.File

enum class InstallStatus {
    INSTALLED,
    NOT_INSTALLED,
    SYSTEM_ONLY,   // pre-installed, no user overlay
    UNKNOWN,       // not yet queried
}

data class AppInstallInfo(
    val module: Module?,          // null for device-only apps (show-all mode)
    var moduleName: String,
    val packageName: String,
    var status: InstallStatus = InstallStatus.UNKNOWN,
    var installTimeMs: Long = 0L,
    var apkFiles: List<File> = emptyList(),
) {
    val isUninstallable: Boolean
        get() = status == InstallStatus.INSTALLED

    val isFromProject: Boolean
        get() = module != null

    val installTimeDisplay: String
        get() = if (installTimeMs > 0L)
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(installTimeMs))
        else "-"
}
