package com.videoslim.videoslim

internal data class OutputLocationChangeRejection(
    val code: String,
    val message: String,
)

/** Pure guard shared by custom-folder replacement and reset-to-default mutations. */
internal class OutputLocationChangeGuard(
    private val hasActiveRunningTask: () -> Boolean,
) {
    fun replaceCustomFolder(action: () -> Unit): OutputLocationChangeRejection? =
        runIfAllowed(action)

    fun resetToDefault(action: () -> Unit): OutputLocationChangeRejection? =
        runIfAllowed(action)

    private fun runIfAllowed(action: () -> Unit): OutputLocationChangeRejection? {
        if (hasActiveRunningTask()) return ACTIVE_TASK_REJECTION
        action()
        return null
    }

    private companion object {
        val ACTIVE_TASK_REJECTION =
            OutputLocationChangeRejection(
                code = OutputLocationStore.ERROR_UNKNOWN,
                message = "视频处理期间不能更改保存位置",
            )
    }
}
