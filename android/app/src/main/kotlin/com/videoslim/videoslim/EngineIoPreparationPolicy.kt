package com.videoslim.videoslim

/** Orders worker-only destination validation ahead of every media preparation operation. */
internal object EngineIoPreparationPolicy {
    fun prepare(
        validateDestination: () -> Unit,
        prepareMedia: () -> Unit,
    ) {
        validateDestination()
        prepareMedia()
    }
}
