package com.lenovo.tools.apppurge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Computable
import java.io.File

object ApkFinder {
    fun findApks(module: Module): List<File> {
        val rootPath = ApplicationManager.getApplication().runReadAction(Computable {
            ModuleRootManager.getInstance(module).contentRoots.firstOrNull()?.path
        }) ?: return emptyList()

        val apkDir = File(rootPath, "build/outputs/apk")
        if (!apkDir.exists()) return emptyList()

        return apkDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
            .filter { !it.name.contains("androidTest", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .toList()
    }
}
