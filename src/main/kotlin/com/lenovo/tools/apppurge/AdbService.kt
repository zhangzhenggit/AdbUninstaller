package com.lenovo.tools.apppurge

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

object AdbService {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val adbExe = if (isWindows) "adb.exe" else "adb"
    private val labelCache = mutableMapOf<String, String>()

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

    data class PackageSnapshot(
        val currentUser: String,
        val installedPackages: Set<String>,
        val userPackages: Set<String>,
        val systemPackages: Set<String>,
    ) {
        val isEmpty: Boolean
            get() = installedPackages.isEmpty() && userPackages.isEmpty() && systemPackages.isEmpty()
    }

    fun getPackageSnapshot(serial: String, adb: String): PackageSnapshot {
        val currentUser = getCurrentUser(serial, adb)
        return PackageSnapshot(
            currentUser = currentUser,
            installedPackages = getInstalledPackagesForUser(serial, adb, currentUser),
            userPackages = getUserPackagesForUser(serial, adb, currentUser),
            systemPackages = getSystemPackagesForUser(serial, adb, currentUser),
        )
    }

    fun getInstalledPackagesForUser(serial: String, adb: String, user: String = getCurrentUser(serial, adb)): Set<String> =
        listPackagesForUser(serial, adb, user, emptyList())
            .ifEmpty { getAllInstalledPackages(serial, adb) }

    fun getSystemPackagesForUser(serial: String, adb: String, user: String = getCurrentUser(serial, adb)): Set<String> =
        listPackagesForUser(serial, adb, user, listOf("-s"))
            .ifEmpty { getAllSystemPackages(serial, adb) }

    fun getPackageState(serial: String, packageName: String, adb: String): InstallStatus {
        val currentUser = getCurrentUser(serial, adb)
        val installedPackages = getInstalledPackagesForUser(serial, adb, currentUser)
        val systemPackages = getSystemPackagesForUser(serial, adb, currentUser)
        return queryProjectPackageStatus(serial, packageName, installedPackages, systemPackages, adb)
    }

    fun queryProjectPackageStatus(
        serial: String,
        packageName: String,
        installedPackages: Set<String>,
        systemPackages: Set<String>,
        adb: String,
    ): InstallStatus {
        if (packageName !in installedPackages) return InstallStatus.NOT_INSTALLED
        if (packageName !in systemPackages) return InstallStatus.USER_APP

        val paths = getPackagePaths(serial, packageName, adb)
        return if (paths.any(::isDataAppPath)) InstallStatus.UPDATED_SYSTEM_APP else InstallStatus.SYSTEM_APP
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

    fun getApplicationLabel(serial: String, packageName: String, adb: String): String {
        val cacheKey = "$serial:$packageName"
        labelCache[cacheKey]?.let { return it }
        val output = shell(serial, "dumpsys package $packageName", adb)
        val label = output.lineSequence()
            .mapNotNull(::extractNonLocalizedLabel)
            .firstOrNull()
            ?: getApplicationLabelFromApk(serial, packageName, adb)
        labelCache[cacheKey] = label
        return label
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
        val output = exec(listOf(adb, "-s", serial, "install", "-r", "-t", apkPath), timeoutSec = 60)
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

    fun getCurrentUser(serial: String, adb: String): String {
        val cmdUser = shell(serial, "cmd activity get-current-user", adb).trim()
        if (cmdUser.isNotBlank() && cmdUser.all(Char::isDigit)) return cmdUser
        val amUser = shell(serial, "am get-current-user", adb).trim()
        if (amUser.isNotBlank() && amUser.all(Char::isDigit)) return amUser
        return "0"
    }

    private fun getUserPackagesForUser(serial: String, adb: String, user: String): Set<String> =
        listPackagesForUser(serial, adb, user, listOf("-3"))
            .ifEmpty { getAllUserPackages(serial, adb).toSet() }

    private fun listPackagesForUser(serial: String, adb: String, user: String, flags: List<String>): Set<String> {
        val cmdOutput = shell(serial, packageListCommand("cmd package list packages", user, flags), adb)
        val cmdPackages = parsePackageList(cmdOutput)
        if (cmdPackages.isNotEmpty() || cmdOutput.isBlank()) return cmdPackages

        val pmOutput = shell(serial, packageListCommand("pm list packages", user, flags), adb)
        val pmPackages = parsePackageList(pmOutput)
        if (pmPackages.isNotEmpty() || pmOutput.isBlank()) return pmPackages

        return emptySet()
    }

    private fun packageListCommand(base: String, user: String, flags: List<String>): String =
        listOf(base, flags.joinToString(" "), "--user $user")
            .filter { it.isNotBlank() }
            .joinToString(" ")

    private fun getPackagePaths(serial: String, packageName: String, adb: String): List<String> =
        shell(serial, "pm path $packageName", adb)
            .lineSequence()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun isDataAppPath(path: String): Boolean =
        path.startsWith("/data/app/")

    private fun parsePackageList(output: String): Set<String> =
        output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun extractNonLocalizedLabel(line: String): String? {
        if (!line.contains("nonLocalizedLabel=")) return null
        val raw = line.substringAfter("nonLocalizedLabel=")
            .substringBefore(" icon=")
            .substringBefore(" banner=")
            .substringBefore(" logo=")
            .trim()
            .trim('"', '\'')
        return raw.takeIf { it.isNotBlank() && it != "null" && it != "0x0" }
    }

    private fun getApplicationLabelFromApk(serial: String, packageName: String, adb: String): String {
        val aapt = findAapt(adb) ?: return ""
        val remoteApk = getPackageBaseApkPath(serial, packageName, adb) ?: return ""
        val localApk = File.createTempFile("apppurge-${packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")}-", ".apk")
        return try {
            val pullOutput = exec(listOf(adb, "-s", serial, "pull", remoteApk, localApk.absolutePath), timeoutSec = 45)
            if (!pullOutput.contains("pulled", ignoreCase = true) && localApk.length() == 0L) return ""
            val badging = exec(listOf(aapt.absolutePath, "dump", "badging", localApk.absolutePath), timeoutSec = 20)
            chooseLocalizedLabel(badging, getDeviceLocale(serial, adb))
        } finally {
            runCatching { localApk.delete() }
        }
    }

    private fun getPackageBaseApkPath(serial: String, packageName: String, adb: String): String? =
        shell(serial, "pm path $packageName", adb)
            .lineSequence()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .sortedWith(compareByDescending<String> { it.endsWith("/base.apk") || it.endsWith("base.apk") }.thenBy { it })
            .firstOrNull()

    private fun getDeviceLocale(serial: String, adb: String): String {
        val locale = shell(serial, "getprop persist.sys.locale", adb).trim()
            .ifBlank { shell(serial, "getprop ro.product.locale", adb).trim() }
        if (locale.isNotBlank()) return locale
        val language = shell(serial, "getprop persist.sys.language", adb).trim()
        val country = shell(serial, "getprop persist.sys.country", adb).trim()
        return listOf(language, country).filter { it.isNotBlank() }.joinToString("-")
    }

    private fun findAapt(adb: String): File? {
        val sdkCandidates = sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            File(adb).takeIf { it.exists() }?.parentFile?.parentFile?.absolutePath,
        ).filterNotNull()
            .map { File(it) }
            .filter { it.exists() }
            .distinctBy { it.absolutePath }

        val names = if (isWindows) listOf("aapt2.exe", "aapt.exe") else listOf("aapt2", "aapt")
        return sdkCandidates.flatMap { sdk ->
            val buildTools = File(sdk, "build-tools")
            if (!buildTools.exists()) emptySequence() else buildTools.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.flatMap { versionDir -> names.asSequence().map { File(versionDir, it) } }
                ?: emptySequence()
        }.firstOrNull { it.exists() && it.canExecute() }
    }

    private fun chooseLocalizedLabel(badging: String, locale: String): String {
        val labels = linkedMapOf<String, String>()
        val regex = Regex("""^application-label(?:-([^:]+))?:'(.+)'$""")
        badging.lineSequence().forEach { line ->
            val match = regex.find(line.trim()) ?: return@forEach
            labels[match.groupValues.getOrNull(1).orEmpty()] = match.groupValues[2]
        }
        if (labels.isEmpty()) return ""

        val normalized = locale.replace('_', '-')
        val parts = normalized.split('-').filter { it.isNotBlank() }
        val candidates = buildList {
            if (normalized.isNotBlank()) add(normalized)
            if (parts.size >= 2) add("${parts[0]}-${parts[1]}")
            if (parts.isNotEmpty()) add(parts[0])
            add("")
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            labels.entries.firstOrNull { it.key.equals(candidate, ignoreCase = true) }?.value
        } ?: labels[""] ?: labels.values.first()
    }

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
        if (!finished) {
            process.destroyForcibly()
            reader.join(2000)
        } else {
            reader.join(timeoutSec * 500)
        }
        sb.toString()
    }.getOrDefault("")
}
