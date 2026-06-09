package com.lenovo.tools.apppurge

import com.intellij.openapi.module.Module
import java.io.File

enum class InstallStatus {
    USER_APP,              // user-installed app, uninstallable
    UPDATED_SYSTEM_APP,    // system app with a user-installed update, uninstall removes the update
    SYSTEM_APP,            // system app installed for the current user, data can be cleared
    NOT_INSTALLED,         // not installed for the current user
    UNKNOWN,               // not yet queried
}

data class AppInstallInfo(
    val module: Module?,          // null for device-only apps (show-all mode)
    var moduleName: String,
    val packageName: String,
    var status: InstallStatus = InstallStatus.UNKNOWN,
    var installTimeMs: Long = 0L,
    var apkFiles: List<File> = emptyList(),
) {
    val isInstalled: Boolean
        get() = status == InstallStatus.USER_APP ||
                status == InstallStatus.UPDATED_SYSTEM_APP ||
                status == InstallStatus.SYSTEM_APP

    val isUninstallable: Boolean
        get() = status == InstallStatus.USER_APP ||
                status == InstallStatus.UPDATED_SYSTEM_APP

    val isClearDataEnabled: Boolean
        get() = isInstalled

    val isFromProject: Boolean
        get() = module != null

    val installTimeDisplay: String
        get() = if (installTimeMs > 0L)
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(installTimeMs))
        else "-"
}
