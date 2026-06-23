package com.lenovo.tools.apppurge

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Coordinates per-device mutations while allowing prepared system APK pushes to run concurrently. */
object DeviceOperationCoordinator {

    data class Submission<T>(
        val future: CompletableFuture<T>,
        val queued: Boolean,
    )

    data class DeviceWorkSnapshot(
        val mutationActive: Boolean,
        val mutationQueued: Int,
        val preparing: Boolean,
        val pendingPushes: Int,
        val activePushes: Int,
    ) {
        val hasMutationWork: Boolean
            get() = mutationActive || mutationQueued > 0

        val hasPushWork: Boolean
            get() = preparing || pendingPushes > 0 || activePushes > 0

        val hasAnyWork: Boolean
            get() = hasMutationWork || hasPushWork

        val canRebootSafely: Boolean
            get() = !hasAnyWork
    }

    data class PushExecution(
        val preparation: RemountResult,
        val result: SystemPushResult? = null,
    )

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private class DeviceContext(serial: String) {
        private val mutationThreadNumber = AtomicInteger()
        val lock = Object()
        val mutationExecutor = ThreadPoolExecutor(
            1,
            1,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            ThreadFactory { runnable ->
                Thread(runnable, "AppPurge-$serial-Mutation-${mutationThreadNumber.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        ).apply { allowCoreThreadTimeOut(true) }

        @Volatile
        var preparedBootId: String? = null

        var preparationFuture: CompletableFuture<RemountResult>? = null
        var pendingSystemPushes: Int = 0
        var activeSystemPushes: Int = 0
    }

    private val pushThreadNumber = AtomicInteger()
    private val pushExecutor = ThreadPoolExecutor(
        0,
        Int.MAX_VALUE,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "AppPurge-SystemPush-${pushThreadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
    )

    private val contexts = ConcurrentHashMap<String, DeviceContext>()

    fun <T> submitMutation(serial: String, operation: () -> T): Submission<T> {
        val context = contexts.computeIfAbsent(serial, ::DeviceContext)
        return synchronized(context.lock) {
            enqueueMutation(context, queued = hasDeviceWorkLocked(context), operation)
        }
    }

    fun <T> submitExclusiveIfIdle(serial: String, operation: () -> T): Submission<T>? {
        val context = contexts.computeIfAbsent(serial, ::DeviceContext)
        return synchronized(context.lock) {
            if (hasDeviceWorkLocked(context)) return@synchronized null
            enqueueMutation(context, queued = false, operation)
        }
    }

    private fun <T> enqueueMutation(context: DeviceContext, queued: Boolean, operation: () -> T): Submission<T> {
        val future = CompletableFuture<T>()
        context.mutationExecutor.execute {
            try {
                future.complete(operation())
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return Submission(future, queued)
    }

    fun <T> submitSystemPush(
        request: SystemPushRequest,
        onProgress: (SystemPushProgress) -> Unit = {},
        postProcess: (PushExecution) -> T,
    ): Submission<T> {
        val context = contexts.computeIfAbsent(request.serial, ::DeviceContext)
        synchronized(context.lock) {
            context.pendingSystemPushes++
        }
        val future = CompletableFuture<T>()
        pushExecutor.execute {
            try {
                onProgress(SystemPushProgress(SystemPushStage.PREPARING, message = "Preparing device"))
                val preparation = ensureRootRemount(context, request, onProgress)
                val execution = if (!preparation.success) {
                    PushExecution(preparation)
                } else {
                    executePreparedPush(context, request, preparation, onProgress)
                }
                val result = postProcess(execution)
                synchronized(context.lock) {
                    context.pendingSystemPushes--
                    context.lock.notifyAll()
                }
                future.complete(result)
            } catch (error: Throwable) {
                synchronized(context.lock) {
                    context.pendingSystemPushes--
                    context.lock.notifyAll()
                }
                future.completeExceptionally(error)
            }
        }
        return Submission(future, queued = false)
    }

    fun invalidateDevice(serial: String) {
        contexts[serial]?.let { context ->
            synchronized(context.lock) {
                context.preparedBootId = null
            }
        }
    }

    fun snapshot(serial: String): DeviceWorkSnapshot {
        val context = contexts[serial] ?: return DeviceWorkSnapshot(
            mutationActive = false,
            mutationQueued = 0,
            preparing = false,
            pendingPushes = 0,
            activePushes = 0,
        )
        return synchronized(context.lock) { snapshotLocked(context) }
    }

    private fun hasDeviceWorkLocked(context: DeviceContext): Boolean =
        snapshotLocked(context).hasAnyWork

    private fun snapshotLocked(context: DeviceContext): DeviceWorkSnapshot =
        DeviceWorkSnapshot(
            mutationActive = context.mutationExecutor.activeCount > 0,
            mutationQueued = context.mutationExecutor.queue.size,
            preparing = context.preparationFuture != null,
            pendingPushes = context.pendingSystemPushes,
            activePushes = context.activeSystemPushes,
        )

    private fun ensureRootRemount(
        context: DeviceContext,
        request: SystemPushRequest,
        onProgress: (SystemPushProgress) -> Unit,
    ): RemountResult {
        val existing = synchronized(context.lock) { context.preparationFuture }
        if (existing != null) {
            onProgress(SystemPushProgress(SystemPushStage.PREPARING, message = "Waiting for device preparation"))
            return awaitPreparation(existing)
        }

        val future = CompletableFuture<RemountResult>()
        val shouldStart = synchronized(context.lock) {
            val current = context.preparationFuture
            if (current != null) {
                onProgress(SystemPushProgress(SystemPushStage.PREPARING, message = "Waiting for device preparation"))
                return@synchronized current to false
            }
            context.preparationFuture = future
            future to true
        }

        if (shouldStart.second) {
            enqueueMutation(context, queued = false) {
                prepareDeviceForSystemPush(context, request)
            }.future.whenComplete { result, error ->
                synchronized(context.lock) {
                    if (context.preparationFuture === future) context.preparationFuture = null
                    context.lock.notifyAll()
                }
                if (error != null) {
                    future.complete(
                        RemountResult(
                            success = false,
                            needsReboot = false,
                            output = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                } else {
                    future.complete(result)
                }
            }
        }
        return awaitPreparation(shouldStart.first)
    }

    private fun prepareDeviceForSystemPush(context: DeviceContext, request: SystemPushRequest): RemountResult {
        reusablePreparation(context, request)?.let { return it }
        waitForNoActiveSystemPushes(context)
        reusablePreparation(context, request)?.let { return it }

        val remount = AdbService.prepareRootRemount(request.serial, request.adb)
        if (!remount.success) {
            synchronized(context.lock) { context.preparedBootId = null }
            return remount
        }

        val writable = AdbService.isSystemTargetWritable(request.serial, request.targetPath, request.adb)
        if (!writable.success) {
            synchronized(context.lock) { context.preparedBootId = null }
            return RemountResult(
                success = false,
                needsReboot = remount.needsReboot,
                output = listOf(remount.output, writable.output).filter(String::isNotBlank).joinToString("\n"),
            )
        }

        synchronized(context.lock) {
            context.preparedBootId = AdbService.getBootId(request.serial, request.adb)
        }
        return remount
    }

    private fun reusablePreparation(context: DeviceContext, request: SystemPushRequest): RemountResult? {
        val currentBootId = AdbService.getBootId(request.serial, request.adb)
        val cachedBootId = synchronized(context.lock) { context.preparedBootId }
        val canTrustCache = currentBootId != null && currentBootId == cachedBootId
        if (!canTrustCache && !AdbService.isAdbRoot(request.serial, request.adb)) return null

        val writable = AdbService.isSystemTargetWritable(request.serial, request.targetPath, request.adb)
        if (!writable.success) {
            if (canTrustCache) synchronized(context.lock) { context.preparedBootId = null }
            return null
        }
        if (currentBootId != null) synchronized(context.lock) { context.preparedBootId = currentBootId }
        return RemountResult(success = true, needsReboot = false, output = "Reusing prepared root/remount state.")
    }

    private fun executePreparedPush(
        context: DeviceContext,
        request: SystemPushRequest,
        preparation: RemountResult,
        onProgress: (SystemPushProgress) -> Unit,
    ): PushExecution {
        var activePreparation = preparation
        var result = runActivePush(context) {
            AdbService.pushSystemApk(request, onProgress)
        }
        if (!result.success) {
            synchronized(context.lock) { context.preparedBootId = null }
            val readOnlyBeforeApply = result.step in setOf("creating target directory", "pushing APK") &&
                    result.output.contains("read-only file system", ignoreCase = true)
            if (readOnlyBeforeApply) {
                onProgress(SystemPushProgress(SystemPushStage.PREPARING, message = "Remount expired; preparing device again"))
                activePreparation = ensureRootRemount(context, request, onProgress)
                if (activePreparation.success) {
                    result = runActivePush(context) {
                        AdbService.pushSystemApk(request, onProgress)
                    }
                }
            }
        }
        if (!result.success) synchronized(context.lock) { context.preparedBootId = null }
        return PushExecution(activePreparation, result)
    }

    private fun <T> runActivePush(context: DeviceContext, operation: () -> T): T {
        synchronized(context.lock) {
            context.activeSystemPushes++
        }
        try {
            return operation()
        } finally {
            synchronized(context.lock) {
                context.activeSystemPushes--
                context.lock.notifyAll()
            }
        }
    }

    private fun waitForNoActiveSystemPushes(context: DeviceContext) {
        synchronized(context.lock) {
            while (context.activeSystemPushes > 0) context.lock.wait(500L)
        }
    }

    private fun awaitPreparation(future: CompletableFuture<RemountResult>): RemountResult =
        try {
            future.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            RemountResult(success = false, needsReboot = false, output = "Device preparation was cancelled.")
        } catch (error: ExecutionException) {
            RemountResult(
                success = false,
                needsReboot = false,
                output = error.cause?.message ?: error.message ?: error.javaClass.simpleName,
            )
        }
}
