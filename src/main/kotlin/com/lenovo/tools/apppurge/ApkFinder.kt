package com.lenovo.tools.apppurge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Computable
import java.io.File

object ApkFinder {
    fun findApks(module: Module, expectedPackageName: String): List<File> {
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
        if (primaryApks.isNotEmpty()) return filterByPackageName(primaryApks, expectedPackageName)

        return filterByPackageName(scanApks(allRoots.map { File(it, "build/intermediates/apk") }), expectedPackageName)
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

    private fun filterByPackageName(apks: List<File>, expectedPackageName: String): List<File> {
        if (expectedPackageName.isBlank() || apks.size <= 1) return apks
        val matched = apks.filter { apkPackageNameFromMetadata(it) == expectedPackageName }
        return matched.ifEmpty { apks }
    }

    private fun apkPackageNameFromMetadata(apk: File): String? {
        val metadata = File(apk.parentFile ?: return null, "output-metadata.json")
        if (!metadata.isFile) return null
        val text = runCatching { metadata.readText() }.getOrNull() ?: return null
        if (!Regex(""""outputFile"\s*:\s*"${Regex.escape(apk.name)}"""").containsMatchIn(text)) return null
        return Regex(""""applicationId"\s*:\s*"([^"]+)"""")
            .find(text)
            ?.groupValues
            ?.get(1)
    }
}
