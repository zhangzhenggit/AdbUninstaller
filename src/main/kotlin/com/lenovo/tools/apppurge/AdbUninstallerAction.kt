package com.lenovo.tools.apppurge

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader

class AdbUninstallerAction : AnAction(
    "AppPurge",
    "Scan app modules and uninstall APKs from connected device via ADB",
    IconLoader.getIcon("/icons/apppurge.svg", AdbUninstallerAction::class.java),
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath

        ProgressManager.getInstance().run(object : Task.Modal(project, "AppPurge: Scanning…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Scanning app modules…"
                val appInfos = AppModuleScanner.scan(project)

                indicator.text = "Connecting to ADB…"
                val adb = AdbService.adbPath(basePath)
                val serials = AdbService.getConnectedDevices(basePath)
                // serial → "ModelName (serial)" display name
                val deviceNames: Map<String, String> = serials.associateWith { serial ->
                    AdbService.getDeviceName(serial, adb)
                }

                ApplicationManager.getApplication().invokeLater {
                    if (appInfos.isEmpty() && serials.isEmpty()) {
                        Messages.showInfoMessage(
                            project,
                            "No app modules found and no ADB device connected.",
                            "AppPurge",
                        )
                        return@invokeLater
                    }
                    UninstallDialog(project, appInfos, deviceNames, basePath).show()
                }
            }
        })
    }
}
