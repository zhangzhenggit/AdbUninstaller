package com.lenovo.tools.apppurge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Computable
import java.io.File

object ApkFinder {
    fun findApks(module: Module): List<File> {
        val (contentRoots, moduleDir) = ApplicationManager.getApplication().runReadAction(Computable {
            val cr = ModuleRootManager.getInstance(module).contentRoots.map { it.path }
            val md = File(module.moduleFilePath).parentFile?.path
            Pair(cr, md)
        })

        // Walk up from each content root to find the directory containing build.gradle,
        // which is the true module root (content roots may point to src/main/ or similar subdirs).
        val resolvedRoots = contentRoots
            .mapNotNull { resolveModuleDir(it) }
            .distinctBy { it.absolutePath }

        // Include the .iml parent dir as-is (fallback, usually .idea/modules/...).
        val allRoots = (resolvedRoots.map { it.absolutePath } + listOfNotNull(moduleDir))
            .distinct()
            .map { File(it) }

        val primaryApks = scanApks(allRoots.map { File(it, "build/outputs/apk") })
        if (primaryApks.isNotEmpty()) return primaryApks

        return scanApks(allRoots.map { File(it, "build/intermediates/apk") })
    }

    // Walk up from startPath until a build.gradle(.kts) is found — that directory is the module root.
    private fun resolveModuleDir(startPath: String): File? {
        var dir = File(startPath)
        repeat(4) {
            if (File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return null
        }
        return File(startPath)
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
