package com.lenovo.tools.apppurge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Computable
import java.io.File

object ApkFinder {
    fun findApks(module: Module): List<File> {
        val rootPaths = ApplicationManager.getApplication().runReadAction(Computable {
            val contentRoots = ModuleRootManager.getInstance(module).contentRoots.map { it.path }
            val moduleDir = File(module.moduleFilePath).parentFile?.path
            (contentRoots + listOfNotNull(moduleDir)).distinct()
        })

        val primaryApks = scanApks(rootPaths.map { File(it, "build/outputs/apk") })
        if (primaryApks.isNotEmpty()) return primaryApks

        return scanApks(rootPaths.map { File(it, "build/intermediates/apk") })
    }

    private fun scanApks(apkDirs: List<File>): List<File> {
        return apkDirs.asSequence()
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
            .filter { !it.name.contains("androidTest", ignoreCase = true) }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
            .toList()
    }
}
