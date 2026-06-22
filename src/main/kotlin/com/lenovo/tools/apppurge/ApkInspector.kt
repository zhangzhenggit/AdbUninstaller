package com.lenovo.tools.apppurge

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File
import java.util.Properties

internal data class ApkInspectionResult(
    val packageName: String? = null,
    val error: String? = null,
) {
    val success: Boolean
        get() = packageName != null
}

internal object ApkInspector {

    fun inspectPackageName(apk: File, projectBasePath: String?, adbPath: String): ApkInspectionResult {
        if (!apk.isFile || !apk.canRead()) {
            return ApkInspectionResult(error = "APK is missing or unreadable.")
        }

        val sdkRoots = sdkRoots(projectBasePath, adbPath)
        val errors = mutableListOf<String>()

        findAapt2(sdkRoots)?.let { aapt2 ->
            val result = executeHidden(listOf(aapt2.absolutePath, "dump", "badging", apk.absolutePath))
            if (Thread.currentThread().isInterrupted) return ApkInspectionResult(error = "APK validation was cancelled.")
            val packageName = Regex("""(?m)^package:\s+name='([^']+)'""")
                .find(result.output)
                ?.groupValues
                ?.getOrNull(1)
            if (result.completed && !packageName.isNullOrBlank()) return ApkInspectionResult(packageName = packageName)
            errors += result.output.trim().ifBlank { "aapt2 could not read the APK manifest." }
        }

        findApkAnalyzer(sdkRoots)?.let { analyzer ->
            val command = if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("cmd.exe", "/d", "/c", analyzer.absolutePath, "manifest", "application-id", apk.absolutePath)
            } else {
                listOf(analyzer.absolutePath, "manifest", "application-id", apk.absolutePath)
            }
            val result = executeHidden(command)
            if (Thread.currentThread().isInterrupted) return ApkInspectionResult(error = "APK validation was cancelled.")
            val packageName = result.output.lineSequence()
                .map(String::trim)
                .firstOrNull { it.matches(PACKAGE_NAME_REGEX) }
            if (result.completed && packageName != null) return ApkInspectionResult(packageName = packageName)
            errors += result.output.trim().ifBlank { "apkanalyzer could not read the APK manifest." }
        }

        val error = if (errors.isEmpty()) {
            "Android SDK aapt2/apkanalyzer was not found. Check sdk.dir or ANDROID_SDK_ROOT."
        } else {
            errors.joinToString("\n")
        }
        return ApkInspectionResult(error = error)
    }

    private fun sdkRoots(projectBasePath: String?, adbPath: String): List<File> {
        val configuredSdk = projectBasePath?.let { basePath ->
            runCatching {
                val properties = Properties()
                File(basePath, "local.properties").inputStream().use(properties::load)
                properties.getProperty("sdk.dir")?.replace("\\\\", "\\")
            }.getOrNull()
        }
        return sequenceOf(
            configuredSdk,
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            File(adbPath).takeIf(File::isFile)?.parentFile?.parentFile?.absolutePath,
        ).filterNotNull()
            .map(::File)
            .filter(File::isDirectory)
            .distinctBy { it.absolutePath.lowercase() }
            .toList()
    }

    private fun findAapt2(sdkRoots: List<File>): File? = sdkRoots.asSequence()
        .flatMap { sdk ->
            File(sdk, "build-tools").listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.sortedByDescending(File::getName)
                ?: emptySequence()
        }
        .map { File(it, if (isWindows()) "aapt2.exe" else "aapt2") }
        .firstOrNull(File::isFile)

    private fun findApkAnalyzer(sdkRoots: List<File>): File? {
        val fileName = if (isWindows()) "apkanalyzer.bat" else "apkanalyzer"
        return sdkRoots.asSequence().flatMap { sdk ->
            sequenceOf(
                File(sdk, "cmdline-tools/latest/bin/$fileName"),
                File(sdk, "tools/bin/$fileName"),
            ) + (File(sdk, "cmdline-tools").listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.sortedByDescending(File::getName)
                ?.map { File(it, "bin/$fileName") }
                ?: emptySequence())
        }.firstOrNull(File::isFile)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private fun executeHidden(command: List<String>): AdbService.ExecResult = runCatching {
        val handler = CapturingProcessHandler(GeneralCommandLine(command))
        val output = handler.runProcess(30_000, true)
        val text = listOf(output.stdout.trimEnd(), output.stderr.trimEnd())
            .filter(String::isNotBlank)
            .joinToString("\n")
        AdbService.ExecResult(
            exitCode = output.exitCode.takeUnless { output.isTimeout || output.isCancelled },
            output = text,
            timedOut = output.isTimeout,
        )
    }.getOrElse { error ->
        AdbService.ExecResult(null, error.message ?: error.javaClass.simpleName, timedOut = false)
    }

    private val PACKAGE_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}
