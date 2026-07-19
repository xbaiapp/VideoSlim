package com.videoslim.videoslim

import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class MainActivity : FlutterFragmentActivity() {
    private var logStore: AppLogStore? = null
    private var logChannel: LogChannel? = null
    private var pickerChannel: VideoPickerChannel? = null
    private var engineChannel: EngineChannel? = null
    private var mediaActionsChannel: MediaActionsChannel? = null
    private var pendingLegacyPermission: ((Boolean) -> Unit)? = null
    private var pendingNotificationPermission: ((Boolean) -> Unit)? = null
    private var pendingDeleteConsent: ((Boolean) -> Unit)? = null
    private val nativeLogSequence = AtomicLong()

    private val legacyWritePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = pendingLegacyPermission
            pendingLegacyPermission = null
            callback?.invoke(granted)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = pendingNotificationPermission
            pendingNotificationPermission = null
            callback?.invoke(granted)
        }

    private val deleteConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val callback = pendingDeleteConsent
            pendingDeleteConsent = null
            callback?.invoke(result.resultCode == Activity.RESULT_OK)
        }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        val store = AppLogStore(this)
        logStore = store
        logChannel = LogChannel(this, messenger, store)
        pickerChannel = VideoPickerChannel(this, messenger, ::appendNativeLog)
        val metadataReader = VideoMetadataReader(this)
        val transcodeEngine =
            TranscodeEngine(
                context = this,
                metadataReader = metadataReader,
                mediaStoreSaver = MediaStoreSaver(this),
                logger = ::appendNativeLog,
            )
        engineChannel =
            EngineChannel(
                messenger = messenger,
                metadataReader = metadataReader,
                transcodeEngine = transcodeEngine,
                requestLegacyWritePermission = ::requestLegacyWritePermission,
                logger = ::appendNativeLog,
            )
        mediaActionsChannel =
            MediaActionsChannel(
                activity = this,
                channel = MethodChannel(messenger, "videoslim/media_actions"),
                deleteConsentLauncher = DeleteConsentLauncher(::launchDeleteConsent),
                log = { level, event, details ->
                    appendNativeLog("$level $event ${JSONObject(details).toString()}")
                },
            )
        appendNativeLog("Flutter engine channels configured")
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        mediaActionsChannel?.dispose()
        mediaActionsChannel = null
        pendingDeleteConsent?.invoke(false)
        pendingDeleteConsent = null
        pendingNotificationPermission?.invoke(false)
        pendingNotificationPermission = null
        pendingLegacyPermission?.invoke(false)
        pendingLegacyPermission = null
        engineChannel?.dispose()
        engineChannel = null
        pickerChannel?.dispose()
        pickerChannel = null
        logChannel?.dispose()
        logChannel = null
        appendNativeLog("Flutter engine channels disposed")
        logStore = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun launchDeleteConsent(
        intentSender: IntentSender,
        onDecision: (Boolean) -> Unit,
    ) {
        check(pendingDeleteConsent == null) { "已有系统删除确认正在进行" }
        pendingDeleteConsent = onDecision
        try {
            deleteConsentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        } catch (error: Throwable) {
            pendingDeleteConsent = null
            throw error
        }
    }

    private fun requestLegacyWritePermission(callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            callback(true)
            return
        }
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            callback(true)
            return
        }
        if (pendingLegacyPermission != null) {
            callback(false)
            return
        }
        pendingLegacyPermission = callback
        legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    internal fun requestNotificationPermission(callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback(true)
            return
        }
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            callback(true)
            return
        }
        if (pendingNotificationPermission != null) {
            callback(false)
            return
        }
        pendingNotificationPermission = callback
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun appendNativeLog(message: String) {
        val store = logStore ?: return
        runCatching {
            val eventId = "native-${nativeLogSequence.incrementAndGet()}"
            store.append(
                "${Instant.now()} [INFO] [native] [event:$eventId] $message",
            )
        }
    }
}
