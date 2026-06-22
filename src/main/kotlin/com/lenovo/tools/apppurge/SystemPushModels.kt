package com.lenovo.tools.apppurge

import java.io.File

data class SystemApkTarget(
    val packageName: String,
    val moduleName: String,
    val detectedPath: String?,
    val targetPath: String,
    val hasDataOverlay: Boolean,
)

data class SystemPushRequest(
    val serial: String,
    val packageName: String,
    val localApk: File,
    val targetPath: String,
    val removeDataOverlay: Boolean,
    val clearData: Boolean,
    val adb: String,
)

data class SystemPushResult(
    val success: Boolean,
    val step: String,
    val output: String,
    val dataOverlayRemoved: Boolean = false,
    val dataCleared: Boolean = false,
)

enum class SystemPushStage {
    PREPARING,
    UPLOADING,
    VERIFYING,
    APPLYING,
    CLEANING,
    COMPLETED,
    FAILED,
}

data class SystemPushProgress(
    val stage: SystemPushStage,
    val percent: Int? = null,
    val message: String,
)

data class RemountResult(
    val success: Boolean,
    val needsReboot: Boolean,
    val output: String,
)
