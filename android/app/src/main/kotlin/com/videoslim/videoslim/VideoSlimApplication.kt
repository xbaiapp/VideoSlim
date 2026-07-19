package com.videoslim.videoslim

import android.app.Application

/** Application entry point used by the integrated M2 manifest. Cleanup is always best-effort. */
class VideoSlimApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val appLogStore = AppLogStore(this)
        val recoveryLogger: (String) -> Unit = { message ->
            runCatching { appLogStore.append("F19 recovery $message") }
        }
        try {
            val recoveryStore = TaskRecoveryStore(this, recoveryLogger)
            OrphanCleanup(this, recoveryStore, recoveryLogger).reconcile()
        } catch (error: Throwable) {
            recoveryLogger("startup reconciliation failed ${error.stackTraceToString()}")
        }
    }
}
