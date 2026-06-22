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
    val module: Module,
    var moduleName: String,
    val packageName: String,
    val systemPathName: String = moduleName,
    var status: InstallStatus = InstallStatus.UNKNOWN,
    var apkFiles: List<File> = emptyList(),
    var activeApkPaths: List<String> = emptyList(),
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

}
