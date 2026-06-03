package com.lenovo.tools.apppurge

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

object AdbService {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val adbExe = if (isWindows) "adb.exe" else "adb"

    fun adbPath(projectBasePath: String? = null): String {
        // 1. ANDROID_HOME / ANDROID_SDK_ROOT
        for (key in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            val home = System.getenv(key) ?: continue
            val f = File(home, "platform-tools/$adbExe")
            if (f.exists()) return f.absolutePath
        }
        // 2. local.properties sdk.dir in the project
        if (projectBasePath != null) {
            runCatching {
                val props = Properties()
                File(projectBasePath, "local.properties").inputStream().use(props::load)
                val sdkDir = props.getProperty("sdk.dir")?.replace("\\\\", "\\")
                if (sdkDir != null) {
                    val f = File(sdkDir, "platform-tools/$adbExe")
                    if (f.exists()) return f.absolutePath
                }
            }
        }
        // 3. Default SDK location on Windows
        if (isWindows) {
            val f = File(System.getProperty("user.home"), "AppData/Local/Android/Sdk/platform-tools/$adbExe")
            if (f.exists()) return f.absolutePath
        }
        return "adb"
    }

    fun getConnectedDevices(projectBasePath: String? = null): List<String> {
        val output = exec(listOf(adbPath(projectBasePath), "devices"))
        return output.lines()
            .drop(1)
            .filter { it.contains("\t") && it.trimEnd().endsWith("device") }
            .map { it.substringBefore("\t").trim() }
            .filter { it.isNotBlank() }
    }

    fun getDeviceName(serial: String, adb: String): String {
        val model = exec(listOf(adb, "-s", serial, "shell", "getprop ro.product.model")).trim()
        return if (model.isNotBlank()) "$model ($serial)" else serial
    }

    // Returns ALL installed package names in one shot
    fun getAllInstalledPackages(serial: String, adb: String): Set<String> =
        parsePackageList(shell(serial, "pm list packages", adb))

    // Returns system package names in one shot
    fun getAllSystemPackages(serial: String, adb: String): Set<String> =
        parsePackageList(shell(serial, "pm list packages -s", adb))

    fun getInstallTime(serial: String, packageName: String, adb: String): Long {
        val output = shell(serial, "dumpsys package $packageName", adb)
        val value = output.lines()
            .firstOrNull { it.trimStart().startsWith("lastUpdateTime=") }
            ?.substringAfter("lastUpdateTime=")?.trim() ?: return 0L
        return runCatching {
            if (value.contains("-")) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value)?.time ?: 0L
            else value.toLong()
        }.getOrDefault(0L)
    }

    fun uninstall(serial: String, packageName: String, adb: String): Boolean {
        return uninstallPackage(serial, packageName, adb).success
    }

    data class CommandResult(val success: Boolean, val output: String)

    fun uninstallPackage(serial: String, packageName: String, adb: String): CommandResult {
        val output = exec(listOf(adb, "-s", serial, "uninstall", packageName)).trim()
        return CommandResult(output.contains("Success", ignoreCase = true), output)
    }

    fun installApk(serial: String, apkPath: String, adb: String): CommandResult {
        val output = exec(listOf(adb, "-s", serial, "install", "-r", apkPath), timeoutSec = 60)
        return CommandResult(output.contains("Success", ignoreCase = true), output.trim())
    }

    fun clearAppData(serial: String, packageName: String, adb: String): CommandResult {
        val output = shell(serial, "pm clear $packageName", adb).trim()
        return CommandResult(output.contains("Success", ignoreCase = true), output)
    }

    fun getAllUserPackages(serial: String, adb: String): List<String> {
        val output = shell(serial, "pm list packages -3", adb)
        return output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
    }

    private fun parsePackageList(output: String): Set<String> =
        output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun shell(serial: String, command: String, adb: String): String =
        exec(listOf(adb, "-s", serial, "shell", command))

    // Reads stdout on a daemon thread so we never block forever on hung processes.
    fun exec(cmd: List<String>, timeoutSec: Long = 15): String = runCatching {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = Thread {
            try { process.inputStream.bufferedReader().use { sb.append(it.readText()) } } catch (_: Exception) {}
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(2000)
        sb.toString()
    }.getOrDefault("")
}
