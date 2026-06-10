package com.lenovo.tools.apppurge

import com.android.tools.idea.model.AndroidModel
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet
import org.w3c.dom.Document
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object AppModuleScanner {

    fun scan(project: Project): List<AppInstallInfo> {
        if (project.isDisposed) return emptyList()
        return ReadAction.compute<List<AppInstallInfo>, RuntimeException> {
            scanModules(project)
        }
    }

    private fun scanModules(project: Project): List<AppInstallInfo> {
        return ModuleManager.getInstance(project).modules
            .filter { !it.isDisposed && !isTestModule(it) && isAppModule(it) }
            .mapNotNull { module ->
                val pkg = resolvePackageName(module)
                if (isValidPackage(pkg)) AppInstallInfo(
                    module = module,
                    moduleName = displayName(module),
                    packageName = pkg!!,
                    systemPathName = systemPathName(module),
                ) else null
            }
            .distinctBy { it.packageName }
    }

    private fun isTestModule(module: Module): Boolean {
        val lower = module.name.lowercase()
        return lower.endsWith(".androidtest") || lower.endsWith(".unittest") ||
                lower.endsWith(".test") || lower.contains("androidtest") || lower.contains("unittest")
    }

    private fun isAppModule(module: Module): Boolean {
        val facet = AndroidFacet.getInstance(module) ?: return false
        if (facet.configuration.isLibraryProject) return false
        return hasApplicationPluginInBuildFile(module)
    }

    private fun hasApplicationPluginInBuildFile(module: Module): Boolean {
        val moduleDir = File(module.moduleFilePath).parentFile ?: return true
        val buildFile = listOf(
            File(moduleDir, "build.gradle.kts"),
            File(moduleDir, "build.gradle"),
        ).firstOrNull { it.exists() } ?: return true
        val text = runCatching { buildFile.readText() }.getOrDefault("")
        return text.contains("com.android.application")
    }

    private fun resolvePackageName(module: Module): String? {
        val facet = AndroidFacet.getInstance(module)
        if (facet != null) {
            val appId = AndroidModel.get(facet)?.applicationId
            if (isValidPackage(appId)) return appId
        }
        val pkg = findManifest(module)?.let { parseManifestPackage(it) }
        if (isValidPackage(pkg)) return pkg
        return parseBuildGradleApplicationId(module)
    }

    private fun isValidPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg.startsWith("uninitialized")) return false
        if (pkg.startsWith("com.example.")) return false  // placeholder packages
        if (!pkg.contains('.')) return false
        return true
    }

    private fun findManifest(module: Module): File? {
        val dir = File(module.moduleFilePath).parentFile ?: return null
        return listOf(
            File(dir, "src/main/AndroidManifest.xml"),
            File(dir, "AndroidManifest.xml"),
        ).firstOrNull { it.exists() }
    }

    private fun parseManifestPackage(manifest: File): String? = runCatching {
        val doc: Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        doc.documentElement.getAttribute("package").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun parseBuildGradleApplicationId(module: Module): String? = runCatching {
        val dir = File(module.moduleFilePath).parentFile ?: return null
        val buildFile = listOf(
            File(dir, "build.gradle.kts"),
            File(dir, "build.gradle"),
        ).firstOrNull { it.exists() } ?: return null
        Regex("""applicationId\s*[=:]\s*["']([^"']+)["']""").find(buildFile.readText())?.groupValues?.get(1)
    }.getOrNull()

    private fun displayName(module: Module) = module.name.removeSuffix(".main")

    private fun systemPathName(module: Module): String {
        val moduleDirName = File(module.moduleFilePath).parentFile?.name
        if (!moduleDirName.isNullOrBlank()) return moduleDirName
        return displayName(module).substringAfterLast('.')
    }
}
