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

    fun isDeviceOnline(serial: String, adb: String): Boolean {
        val result = execResult(listOf(adb, "-s", serial, "get-state"), timeoutSec = 5)
        return result.completed && result.output.trim() == "device"
    }

    fun isBootCompleted(serial: String, adb: String): Boolean =
        shellResult(serial, "getprop sys.boot_completed", adb, timeoutSec = 5).let {
            it.completed && it.output.trim() == "1"
        }

    data class PackageSnapshot(
        val currentUser: String,
        val installedPackages: Set<String>,
        val systemPackages: Set<String>,
    ) {
        val isEmpty: Boolean
            get() = installedPackages.isEmpty() && systemPackages.isEmpty()
    }

    fun getPackageSnapshot(serial: String, adb: String): PackageSnapshot {
        val currentUser = getCurrentUser(serial, adb)
        return PackageSnapshot(
            currentUser = currentUser,
            installedPackages = getInstalledPackagesForUser(serial, adb, currentUser),
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

    fun getActivePackagePaths(serial: String, packageName: String, adb: String): List<String> =
        getPackagePaths(serial, packageName, adb)

    // Returns ALL installed package names in one shot
    fun getAllInstalledPackages(serial: String, adb: String): Set<String> =
        parsePackageList(shell(serial, "pm list packages", adb))

    // Returns system package names in one shot
    fun getAllSystemPackages(serial: String, adb: String): Set<String> =
        parsePackageList(shell(serial, "pm list packages -s", adb))

    fun uninstall(serial: String, packageName: String, adb: String): Boolean {
        return uninstallPackage(serial, packageName, adb).success
    }

    data class CommandResult(val success: Boolean, val output: String)

    fun uninstallPackage(serial: String, packageName: String, adb: String): CommandResult {
        if (!isSafePackageName(packageName)) return CommandResult(false, "Invalid package name: $packageName")
        val result = execResult(listOf(adb, "-s", serial, "uninstall", packageName))
        val output = result.output.trim()
        return CommandResult(result.completed && output.contains("Success", ignoreCase = true), output)
    }

    fun installApk(serial: String, apkPath: String, adb: String): CommandResult {
        val result = execResult(listOf(adb, "-s", serial, "install", "-r", "-t", apkPath), timeoutSec = 60)
        val output = result.output.trim()
        return CommandResult(result.completed && output.contains("Success", ignoreCase = true), output)
    }

    fun getSystemApkTarget(serial: String, packageName: String, moduleName: String, adb: String): SystemApkTarget {
        require(isSafePackageName(packageName)) { "Invalid package name: $packageName" }
        val paths = getPackagePaths(serial, packageName, adb)
        val hiddenSystemPath = getHiddenSystemPackagePath(serial, packageName, adb)
        val pmSystemPath = paths.firstOrNull(::isSystemPartitionPath)
        val detected = hiddenSystemPath ?: pmSystemPath
        val sanitizedModule = sanitizePathSegment(moduleName.ifBlank { packageName.substringAfterLast('.') })
        val targetPath = when {
            detected == null -> "/system/app/$sanitizedModule/$sanitizedModule.apk"
            detected.endsWith(".apk", ignoreCase = true) -> detected
            else -> "${systemAreaFromPath(detected)}/$sanitizedModule/$sanitizedModule.apk"
        }
        return SystemApkTarget(
            packageName = packageName,
            moduleName = sanitizedModule,
            detectedPath = detected,
            targetPath = targetPath,
            hasDataOverlay = paths.any(::isDataAppPath),
        )
    }

    fun getBootId(serial: String, adb: String): String? =
        shellResult(serial, "cat /proc/sys/kernel/random/boot_id", adb, timeoutSec = 5).output
            .trim()
            .takeIf { it.matches(Regex("[0-9a-fA-F-]{16,}")) }

    fun isAdbRoot(serial: String, adb: String): Boolean {
        val uid = shellResult(serial, "id -u", adb, timeoutSec = 10)
        return uid.completed && uid.output.trim() == "0"
    }

    fun isSystemTargetWritable(serial: String, targetPath: String, adb: String): CommandResult {
        if (!isSystemPartitionPath(targetPath)) {
            return CommandResult(false, "Target is not under a writable system partition: $targetPath")
        }
        val root = systemPartitionRootFromPath(targetPath)
            ?: return CommandResult(false, "Unable to resolve system partition for target: $targetPath")
        val tempPath = "$root/.apppurge_write_check_${System.currentTimeMillis()}_${System.nanoTime()}"
        val quotedTemp = shellQuote(tempPath)
        val result = shellResult(
            serial,
            "tmp=$quotedTemp; echo apppurge > \"\$tmp\" && rm -f \"\$tmp\"",
            adb,
            timeoutSec = 10,
        )
        val output = result.output.trim()
        return CommandResult(
            result.completed,
            output.ifBlank {
                if (result.timedOut) "Writable check timed out for $root." else "Writable check failed for $root."
            },
        )
    }

    fun prepareRootRemount(serial: String, adb: String): RemountResult {
        val root = execResult(listOf(adb, "-s", serial, "root"), timeoutSec = 20)
        val rootOutput = root.output.trim()
        val rootLower = rootOutput.lowercase()
        if (!root.completed || listOf("cannot run as root", "production builds", "not allowed").any(rootLower::contains)) {
            return RemountResult(false, false, rootOutput.ifBlank { "adb root failed." })
        }

        val waitForDevice = execResult(listOf(adb, "-s", serial, "wait-for-device"), timeoutSec = 30)
        if (!waitForDevice.completed) {
            return RemountResult(false, false, listOf(rootOutput, waitForDevice.output.trim(), "Device did not reconnect after adb root.")
                .filter(String::isNotBlank).joinToString("\n"))
        }
        val uid = shellResult(serial, "id -u", adb, timeoutSec = 10)
        if (!uid.completed || uid.output.trim() != "0") {
            return RemountResult(false, false, listOf(rootOutput, uid.output.trim(), "adbd is not running as root.")
                .filter(String::isNotBlank).joinToString("\n"))
        }

        val remount = execResult(listOf(adb, "-s", serial, "remount"), timeoutSec = 45)
        val remountOutput = remount.output.trim()
        val output = listOf(rootOutput, remountOutput).filter(String::isNotBlank).joinToString("\n")
        val lower = output.lowercase()
        val failed = listOf("failed", "failure", "permission denied", "not permitted", "read-only file system")
            .any(lower::contains)
        val success = remount.completed && !failed &&
                (remountOutput.isBlank() || lower.contains("remount succeeded") || lower.contains("remounted"))
        val needsReboot = listOf(
            "reboot your device",
            "reboot the device",
            "verity",
            "read-only file system",
            "remount failed",
        ).any { lower.contains(it) }
        return RemountResult(success = success, needsReboot = needsReboot, output = output)
    }

    fun pushSystemApk(
        request: SystemPushRequest,
        onProgress: (SystemPushProgress) -> Unit = {},
    ): SystemPushResult {
        val targetDir = request.targetPath.substringBeforeLast('/', "")
        val targetSegments = request.targetPath.split('/').drop(1)
        if (targetDir.isBlank() || !request.targetPath.endsWith(".apk", ignoreCase = true) ||
            !isSystemPartitionPath(request.targetPath) || targetSegments.any { it.isBlank() || it == "." || it == ".." } ||
            request.targetPath.any { it.isISOControl() }) {
            return SystemPushResult(false, "validating target path", "Target must be a full APK path under a system partition.")
        }
        if (!isSafePackageName(request.packageName)) {
            return SystemPushResult(false, "validating package name", "Invalid package name: ${request.packageName}")
        }
        if (!request.localApk.isFile || !request.localApk.canRead() || request.localApk.length() <= 0L) {
            return SystemPushResult(false, "validating local APK", "Local APK is missing, unreadable, or empty: ${request.localApk.absolutePath}")
        }
        val tmpPath = "${request.targetPath}.apppurge.tmp"

        fun shellStep(step: String, command: String, timeoutSec: Long = 20): Pair<Boolean, String> {
            val result = shellResult(request.serial, command, request.adb, timeoutSec)
            val output = result.output.trim()
            return result.completed to output.ifBlank {
                if (result.timedOut) "$step timed out after $timeoutSec seconds." else "$step completed"
            }
        }

        fun report(stage: SystemPushStage, percent: Int? = null, message: String) {
            runCatching { onProgress(SystemPushProgress(stage, percent, message)) }
        }

        fun remoteFileSize(path: String, attempts: Int = 3): Long? {
            val quoted = shellQuote(path)
            repeat(attempts) { attempt ->
                val result = shellResult(
                    request.serial,
                    "test -f $quoted && (stat -c %s $quoted 2>/dev/null || wc -c < $quoted)",
                    request.adb,
                    timeoutSec = 15,
                )
                val size = result.output.lineSequence()
                    .map(String::trim)
                    .lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?.toLongOrNull()
                if (result.completed && size != null) return size
                if (attempt < attempts - 1) Thread.sleep(300)
            }
            return null
        }

        fun cleanupTemp() {
            shellResult(request.serial, "rm -f ${shellQuote(tmpPath)}", request.adb, timeoutSec = 10)
        }

        fun pushWithProgress(localSize: Long, timeoutSec: Long): ExecResult = runCatching {
            val process = ProcessBuilder(
                request.adb,
                "-s",
                request.serial,
                "push",
                request.localApk.absolutePath,
                tmpPath,
            ).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                try { process.inputStream.bufferedReader().use { output.append(it.readText()) } } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                name = "AppPurge-PushOutput"
                start()
            }
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            var lastPercent = -1
            var timedOut = false
            try {
                while (!process.waitFor(350, TimeUnit.MILLISECONDS)) {
                    if (System.nanoTime() >= deadlineNanos) {
                        timedOut = true
                        process.destroyForcibly()
                        break
                    }
                    val uploadedSize = remoteFileSize(tmpPath, attempts = 1) ?: continue
                    val percent = ((uploadedSize * 100L) / localSize).toInt().coerceIn(0, 99)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        report(SystemPushStage.UPLOADING, percent, "Uploading APK: $percent%")
                    }
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                reader.join(1000)
                return ExecResult(null, output.toString().ifBlank { "Push cancelled." }, timedOut = false)
            } catch (error: Throwable) {
                process.destroyForcibly()
                reader.join(1000)
                return ExecResult(null, output.toString().ifBlank { error.message ?: error.javaClass.simpleName }, timedOut = false)
            }
            if (timedOut) process.waitFor(2, TimeUnit.SECONDS)
            reader.join(2000)
            val exitCode = if (!timedOut && !process.isAlive) process.exitValue() else null
            ExecResult(exitCode, output.toString(), timedOut)
        }.getOrElse { error ->
            ExecResult(null, error.message ?: error.javaClass.simpleName, timedOut = false)
        }

        report(SystemPushStage.PREPARING, message = "Creating target directory")
        val mkdir = shellStep("creating target directory", "mkdir -p ${shellQuote(targetDir)} && test -d ${shellQuote(targetDir)}")
        if (!mkdir.first) return SystemPushResult(false, "creating target directory", mkdir.second)

        val localSize = request.localApk.length()
        val pushTimeoutSec = (localSize / (1024L * 1024L) + 60L).coerceIn(90L, 600L)
        report(SystemPushStage.UPLOADING, 0, "Uploading APK: 0%")
        val push = pushWithProgress(localSize, pushTimeoutSec)
        val pushOutput = push.output.trim()
        if (!push.completed) {
            cleanupTemp()
            return SystemPushResult(false, "pushing APK", pushOutput.ifBlank { "adb push failed with no output." })
        }
        val reportedSize = Regex("""\((\d+) bytes in [^)]+\)""")
            .find(pushOutput)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        if (reportedSize != null && reportedSize != localSize) {
            cleanupTemp()
            return SystemPushResult(false, "verifying uploaded APK", "ADB reported $reportedSize bytes, local APK is $localSize bytes.")
        }
        report(SystemPushStage.UPLOADING, 100, "Uploading APK: 100%")
        report(SystemPushStage.VERIFYING, 100, "Verifying uploaded APK")
        val temporarySize = remoteFileSize(tmpPath)
        if (temporarySize != null && temporarySize != localSize) {
            cleanupTemp()
            return SystemPushResult(false, "verifying uploaded APK", "Size mismatch: local=$localSize bytes, device=$temporarySize.")
        }

        report(SystemPushStage.APPLYING, 100, "Applying APK and permissions")
        val apply = shellStep(
            "applying APK",
            listOf(
                "mv -f ${shellQuote(tmpPath)} ${shellQuote(request.targetPath)}",
                "chmod 644 ${shellQuote(request.targetPath)}",
                "chown 0:0 ${shellQuote(request.targetPath)}",
                "(restorecon ${shellQuote(request.targetPath)} 2>/dev/null || true)",
                "sync",
                "test -s ${shellQuote(request.targetPath)}",
            ).joinToString(" && "),
            timeoutSec = 30,
        )
        if (!apply.first) {
            cleanupTemp()
            return SystemPushResult(false, "applying APK", apply.second)
        }
        val installedSize = remoteFileSize(request.targetPath)
        if (installedSize != null && installedSize != localSize) {
            return SystemPushResult(false, "verifying installed APK", "Size mismatch: local=$localSize bytes, device=$installedSize.")
        }

        val outputs = mutableListOf(pushOutput, apply.second)

        var overlayRemoved = false
        report(SystemPushStage.CLEANING, 100, "Finishing package cleanup")
        if (request.removeDataOverlay) {
            val uninstall = uninstallPackage(request.serial, request.packageName, request.adb)
            if (uninstall.success) {
                overlayRemoved = true
            } else {
                // Fallback: per-user uninstall handles UPDATED_SYSTEM_APP and some protected packages
                val currentUser = getCurrentUser(request.serial, request.adb)
                val pmOut = shell(request.serial, "pm uninstall --user $currentUser ${shellQuote(request.packageName)}", request.adb).trim()
                if (pmOut.contains("Success", ignoreCase = true)) {
                    overlayRemoved = true
                } else {
                    val stateAfter = getPackageState(request.serial, request.packageName, request.adb)
                    overlayRemoved = stateAfter == InstallStatus.SYSTEM_APP || stateAfter == InstallStatus.NOT_INSTALLED
                    if (!overlayRemoved) outputs += "Overlay removal warning: ${pmOut.ifBlank { uninstall.output.ifBlank { "Unable to confirm overlay removal." } }}"
                }
            }
        }

        var dataCleared = false
        if (request.clearData) {
            val clear = clearAppData(request.serial, request.packageName, request.adb)
            dataCleared = clear.success
            if (!dataCleared) outputs += "Clear data warning: ${clear.output.ifBlank { "pm clear returned no output." }}"
        }

        report(SystemPushStage.COMPLETED, 100, "Push completed")

        return SystemPushResult(
            success = true,
            step = "completed",
            output = outputs.filter(String::isNotBlank).joinToString("\n"),
            dataOverlayRemoved = overlayRemoved,
            dataCleared = dataCleared,
        )
    }

    fun clearAppData(serial: String, packageName: String, adb: String): CommandResult {
        if (!isSafePackageName(packageName)) return CommandResult(false, "Invalid package name: $packageName")
        val result = shellResult(serial, "pm clear ${shellQuote(packageName)}", adb)
        val output = result.output.trim()
        return CommandResult(result.completed && output.contains("Success", ignoreCase = true), output)
    }

    fun getCurrentUser(serial: String, adb: String): String {
        val cmdUser = shell(serial, "cmd activity get-current-user", adb).trim()
        if (cmdUser.isNotBlank() && cmdUser.all(Char::isDigit)) return cmdUser
        val amUser = shell(serial, "am get-current-user", adb).trim()
        if (amUser.isNotBlank() && amUser.all(Char::isDigit)) return amUser
        return "0"
    }

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
        if (!isSafePackageName(packageName)) emptyList() else shell(serial, "pm path ${shellQuote(packageName)}", adb)
            .lineSequence()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun isDataAppPath(path: String): Boolean =
        path.startsWith("/data/app/")

    private fun isSystemPartitionPath(path: String): Boolean =
        listOf("/system/", "/system_ext/", "/product/", "/vendor/", "/odm/").any { path.startsWith(it) }

    private fun systemPartitionRootFromPath(path: String): String? =
        listOf("/system_ext", "/system", "/product", "/vendor", "/odm")
            .firstOrNull { path == it || path.startsWith("$it/") }

    private fun getHiddenSystemPackagePath(serial: String, packageName: String, adb: String): String? {
        if (!isSafePackageName(packageName)) return null
        val output = shell(serial, "dumpsys package ${shellQuote(packageName)}", adb)
        val lines = output.lines()
        val hiddenStart = lines.indexOfFirst {
            val t = it.trim()
            t == "Hidden system packages:" || t == "Disabled System packages:"
        }
        if (hiddenStart < 0) return null

        var inTarget = false
        for (i in hiddenStart + 1 until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("Package [")) {
                if (inTarget) break
                inTarget = trimmed.startsWith("Package [$packageName]")
                continue
            }
            if (!inTarget) continue
            val path = when {
                trimmed.startsWith("codePath=") -> trimmed.substringAfter("codePath=").trim()
                trimmed.startsWith("resourcePath=") -> trimmed.substringAfter("resourcePath=").trim()
                else -> null
            }
            if (path != null && isSystemPartitionPath(path)) return path
        }
        return null
    }

    fun sanitizePathSegment(value: String): String =
        value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .ifBlank { "app" }

    private fun systemAreaFromPath(path: String): String {
        val normalized = path.trimEnd('/')
        val markers = listOf(
            "/system/priv-app/",
            "/system/app/",
            "/system_ext/priv-app/",
            "/system_ext/app/",
            "/product/priv-app/",
            "/product/app/",
            "/vendor/priv-app/",
            "/vendor/app/",
            "/odm/priv-app/",
            "/odm/app/",
        )
        return markers.firstOrNull { normalized.startsWith(it) }?.trimEnd('/')
            ?: normalized.substringBeforeLast('/', "/system/app")
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private fun parsePackageList(output: String): Set<String> =
        output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun shell(serial: String, command: String, adb: String): String =
        shellResult(serial, command, adb).output

    private fun shellResult(serial: String, command: String, adb: String, timeoutSec: Long = 15): ExecResult =
        execResult(listOf(adb, "-s", serial, "shell", command), timeoutSec)

    // Reads stdout on a daemon thread so we never block forever on hung processes.
    fun exec(cmd: List<String>, timeoutSec: Long = 15): String =
        execResult(cmd, timeoutSec).output

    fun execResult(cmd: List<String>, timeoutSec: Long = 15): ExecResult = runCatching {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = Thread {
            try { process.inputStream.bufferedReader().use { sb.append(it.readText()) } } catch (_: Exception) {}
        }
        reader.isDaemon = true
        reader.start()
        val finished = try {
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            reader.join(1000)
            Thread.currentThread().interrupt()
            return ExecResult(exitCode = null, output = sb.toString().ifBlank { "Command cancelled." }, timedOut = false)
        }
        val exitCode = if (finished) process.exitValue() else null
        if (!finished) {
            process.destroyForcibly()
            reader.join(2000)
        } else {
            reader.join(timeoutSec * 500)
        }
        ExecResult(exitCode = exitCode, output = sb.toString(), timedOut = !finished)
    }.getOrElse { e ->
        ExecResult(exitCode = null, output = e.message ?: e.javaClass.simpleName, timedOut = false)
    }

    data class ExecResult(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean,
    ) {
        val completed: Boolean
            get() = !timedOut && exitCode == 0
    }

    private fun isSafePackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
}
