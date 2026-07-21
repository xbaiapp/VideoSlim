package com.videoslim.videoslim

import android.app.Application
import android.util.Log

/** Application entry point used by the integrated M2 manifest. */
class VideoSlimApplication : Application() {
    internal lateinit var logDispatcher: AppLogDispatcher
        private set
    internal lateinit var mediaIoDispatcher: AppMediaIoDispatcher
        private set

    override fun onCreate() {
        super.onCreate()
        logDispatcher = AppLogDispatcher(AppLogStore(this))
        mediaIoDispatcher = AppMediaIoDispatcher()
        val recoveryLogger: (String) -> Unit = { message -> logNative("F19 recovery $message") }
        ProcessingRuntime.startReconciliationOnce {
            try {
                val recoveryStore = TaskRecoveryStore(this, recoveryLogger)
                OrphanCleanup(this, recoveryStore, recoveryLogger).reconcile()
            } catch (error: Throwable) {
                runCatching {
                    recoveryLogger("startup reconciliation failed ${error.stackTraceToString()}")
                }
                throw error
            }
        }
    }

    /**
     * Protected native logging adapter shared by every lifecycle owner.
     *
     * A finite dispatcher can reject under protected-only saturation, and storage can fail after
     * admission. Keep those failures explicit without recursing into the file logger.
     */
    internal fun logNative(message: String) {
        logDispatcher.native(message) { outcome ->
            outcome.exceptionOrNull()?.let { error ->
                logNativeFailure(message, error)
            }
        }
    }

    internal fun logNativeFailure(
        message: String,
        error: Throwable,
    ) {
        Log.e(LOG_TAG, "Native file log unavailable: $message", error)
    }

    internal fun logProgress(
        taskId: String,
        message: String,
    ) {
        logDispatcher.progress(taskId, message)
    }

    override fun onTerminate() {
        if (::mediaIoDispatcher.isInitialized) {
            // Activity channels only borrow this process worker. Emulator/test teardown requests
            // a graceful drain without blocking the main thread.
            mediaIoDispatcher.shutdown()
        }
        if (::logDispatcher.isInitialized) {
            logDispatcher.shutdown { outcome ->
                outcome.exceptionOrNull()?.let { error ->
                    Log.e(LOG_TAG, "Native log dispatcher shutdown failed", error)
                }
            }
        }
        super.onTerminate()
    }

    private companion object {
        const val LOG_TAG = "VideoSlimLog"
    }
}
