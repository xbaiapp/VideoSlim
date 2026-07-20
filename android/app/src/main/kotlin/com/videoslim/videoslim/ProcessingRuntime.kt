package com.videoslim.videoslim

import android.content.Context
import android.content.Intent
import java.io.Serializable
import java.util.UUID

internal object SerializableArguments {
    fun copy(arguments: Any?): HashMap<String, Any?> {
        val root = arguments as? Map<*, *>
            ?: throw IllegalArgumentException("processing arguments must be a map")
        require(root.size <= MAX_ENTRIES) { "too many processing arguments" }
        return copyMap(root, 0)
    }

    private fun copyMap(
        source: Map<*, *>,
        depth: Int,
    ): HashMap<String, Any?> {
        require(depth <= MAX_DEPTH) { "processing arguments are too deeply nested" }
        require(source.size <= MAX_ENTRIES) { "too many processing arguments" }
        val destination = HashMap<String, Any?>(source.size)
        source.forEach { (rawKey, rawValue) ->
            val key = rawKey as? String
                ?: throw IllegalArgumentException("processing argument keys must be strings")
            destination[key] = copyValue(rawValue, depth + 1)
        }
        return destination
    }

    private fun copyValue(
        value: Any?,
        depth: Int,
    ): Any? =
        when (value) {
            null, is String, is Boolean, is Int, is Long, is Double -> value
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Float -> value.toDouble()
            is Map<*, *> -> copyMap(value, depth)
            else -> throw IllegalArgumentException("unsupported processing argument value")
        }

    private const val MAX_DEPTH = 4
    private const val MAX_ENTRIES = 32
}

internal object ProcessingRuntime {
    val registry = ProcessingRegistry()

    fun launch(
        context: Context,
        arguments: Any?,
        request: ProcessRequest,
    ): String {
        val copiedArguments = SerializableArguments.copy(arguments)
        val taskId = UUID.randomUUID().toString()
        registry.reserve(
            taskId = taskId,
            sourceUri = request.sourceUri,
            outputFileName = request.outputFileName,
            outputLocationLabel = request.outputLocationLabel,
            videoDecoderMode = request.videoDecoderMode.wireName,
            startedAtEpochMs = System.currentTimeMillis(),
        )
        val intent =
            Intent(context, ProcessingService::class.java)
                .setAction(ProcessingService.ACTION_START)
                .putExtra(ProcessingService.EXTRA_TASK_ID, taskId)
                .putExtra(ProcessingService.EXTRA_ARGUMENTS, copiedArguments as Serializable)
        try {
            context.startForegroundService(intent)
        } catch (error: Throwable) {
            registry.releaseLaunchFailure(taskId)
            throw error
        }
        return taskId
    }

    fun cancel(
        context: Context,
        taskId: String,
    ) {
        require(taskId.isNotBlank())
        context.startService(
            Intent(context, ProcessingService::class.java)
                .setAction(ProcessingService.ACTION_CANCEL)
                .putExtra(ProcessingService.EXTRA_TASK_ID, taskId),
        )
    }
}
