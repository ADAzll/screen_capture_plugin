package cn.shj.svi.screen_capture_plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import io.flutter.plugin.common.EventChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/**
 * Android 截屏、录屏事件监听，通过 EventChannel 推送事件到 Flutter
 *
 * 实现方式：
 * - 截屏：API 34+ 使用 DETECT_SCREEN_CAPTURE；API < 34 使用 ContentObserver 监听 MediaStore
 * - 录屏：API 35+ 使用 WindowManager.addScreenRecordingCallback（可检测 start/end）；
 *         API < 35 使用 ContentObserver + FileObserver 监听新视频（仅能检测 end）
 * - 路径：不返回，path 固定为 ""
 */
class ScreenCaptureEventStreamHandler(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private var activity: Activity?
) : EventChannel.StreamHandler {

    private var eventSink: EventChannel.EventSink? = null
    private var screenshotObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private var fileObservers: MutableList<FileObserver> = mutableListOf()
    private var screenCaptureCallback: Any? = null
    private var screenRecordingCallback: Any? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lastScreenshotTime = AtomicLong(0)
    private val lastVideoTime = AtomicLong(0)
    private val recordingLock = Object()

    /** 设置/更新 Activity，用于 API 34/35 的截屏/录屏回调 */
    fun setActivity(activity: Activity?) {
        val hadActivity = this.activity != null
        this.activity = activity
        // 若正在监听且 Activity 状态变化，重新设置检测
        if (eventSink != null && hadActivity != (activity != null)) {
            removeScreenshotDetection()
            removeScreenRecordingDetection()
            setupScreenshotDetection()
            setupScreenRecordingDetection()
        }
    }

    /** Activity onStart：Android 15 需在此时注册录屏回调 */
    fun onActivityStart() {
        if (eventSink == null) return
        if (Build.VERSION.SDK_INT >= 35 && activity != null) {
            try {
                setupScreenRecordingDetectionApi35()
            } catch (_: Exception) {
                if (videoObserver == null) setupScreenRecordingDetectionContentObserver()
            }
        }
    }

    /** Activity onStop：Android 15 需在此时注销录屏回调 */
    fun onActivityStop() {
        if (Build.VERSION.SDK_INT >= 35) {
            removeScreenRecordingCallbackOnly()
        }
    }

    /** Flutter 订阅事件流时，开始监听截屏、录屏 */
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        setupScreenshotDetection()
        setupScreenRecordingDetection()
    }

    /** Flutter 取消订阅时，移除所有监听 */
    override fun onCancel(arguments: Any?) {
        eventSink = null
        removeScreenshotDetection()
        removeScreenRecordingDetection()
    }

    /** 向 Flutter 发送事件（主线程） */
    private fun sendEvent(event: Map<String, Any>) {
        if (event["type"] == "screen_recording") {
            val ev = event["event"] as? String ?: ""
            val ts = (event["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(ts))
            Log.d(TAG, "录屏事件: $ev, 时间: $timeStr (timestamp=$ts)")
        }
        handler.post {
            eventSink?.success(event)
        }
    }

    // region 截屏检测

    /** 根据 API 选择截屏检测方式：API 34+ 用系统回调，否则用 ContentObserver */
    private fun setupScreenshotDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && activity != null) {
            setupScreenshotDetectionApi34()
        } else {
            setupScreenshotDetectionContentObserver()
        }
    }

    /** API 34+：Activity.registerScreenCaptureCallback，精确检测截屏 */
    @SuppressLint("ObsoleteSdkInt")
    private fun setupScreenshotDetectionApi34() {
        val act = activity ?: run {
            setupScreenshotDetectionContentObserver()
            return
        }
        try {
            val callback = Activity.ScreenCaptureCallback {
                val timestamp = System.currentTimeMillis()
                sendEvent(mapOf(
                    "type" to "screenshot",
                    "event" to "taken",
                    "timestamp" to timestamp
                ))
            }
            screenCaptureCallback = callback
            act.registerScreenCaptureCallback(act.mainExecutor, callback)
        } catch (e: Exception) {
            setupScreenshotDetectionContentObserver()
        }
    }

    /** API < 34：ContentObserver 监听 MediaStore.Images，截屏保存时触发 */
    private fun setupScreenshotDetectionContentObserver() {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        screenshotObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val now = System.currentTimeMillis()
                // 1.5 秒防抖，避免一次截屏触发多次
                if (now - lastScreenshotTime.get() < 1500) return
                lastScreenshotTime.set(now)
                sendEvent(mapOf(
                    "type" to "screenshot",
                    "event" to "taken",
                    "timestamp" to now
                ))
            }
        }
        contentResolver.registerContentObserver(uri, true, screenshotObserver!!)
    }

    /** 移除截屏监听 */
    private fun removeScreenshotDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val act = activity
            val callback = screenCaptureCallback
            if (act != null && callback != null) {
                try {
                    act.unregisterScreenCaptureCallback(callback as Activity.ScreenCaptureCallback)
                } catch (_: Exception) {}
            }
            screenCaptureCallback = null
        } else {
            screenshotObserver?.let {
                contentResolver.unregisterContentObserver(it)
            }
            screenshotObserver = null
        }
    }

    // endregion

    // region 录屏检测

    /** 根据 API 选择录屏检测：API 35+ 用系统回调，否则用 ContentObserver + FileObserver */
    private fun setupScreenRecordingDetection() {
        if (Build.VERSION.SDK_INT >= 35 && activity != null) {
            Log.d(TAG, "录屏检测: API 35+ 模式，可检测 start 和 end")
            onActivityStart()
        } else {
            Log.d(TAG, "录屏检测: API ${Build.VERSION.SDK_INT} 使用 ContentObserver+FileObserver，仅能检测 end（录屏结束）")
            setupScreenRecordingDetectionContentObserver()
        }
    }

    /** API 35+：WindowManager.addScreenRecordingCallback，可检测 start 和 end */
    @SuppressLint("ObsoleteSdkInt")
    private fun setupScreenRecordingDetectionApi35() {
        val act = activity ?: return
        removeScreenRecordingCallbackOnly()
        try {
            val callback = Consumer<Int> { state ->
                val isCaptured = state == 1 // SCREEN_RECORDING_STATE_VISIBLE
                val timestamp = System.currentTimeMillis()
                sendEvent(mapOf(
                    "type" to "screen_recording",
                    "event" to if (isCaptured) "start" else "end",
                    "isCaptured" to isCaptured,
                    "timestamp" to timestamp,
                    "path" to ""
                ))
            }
            screenRecordingCallback = callback
            val initialState = act.windowManager.addScreenRecordingCallback(act.mainExecutor, callback)
            callback.accept(initialState)
        } catch (_: Exception) {}
    }

    /**
     * 录屏结束：ContentObserver/FileObserver 检测到新视频时调用，通知 Flutter
     * 使用同步块 + 5 秒防抖，避免 ContentObserver 与 FileObserver 重复触发
     */
    private fun onScreenRecordingStateChanged() {
        val now = System.currentTimeMillis()
        // 5 秒防抖，避免 ContentObserver + 多个 FileObserver 重复触发
        val shouldSend = synchronized(recordingLock) {
            if (now - lastVideoTime.get() < 5000) false
            else {
                lastVideoTime.set(now)
                true
            }
        }
        if (!shouldSend) return
        val timestamp = now
        handler.postDelayed({
            sendEvent(mapOf(
                "type" to "screen_recording",
                "event" to "end",
                "isCaptured" to false,
                "timestamp" to timestamp,
                "path" to ""
            ))
        }, 1500)
    }

    /** 录屏 ContentObserver：监听 MediaStore.Video，录屏保存时 onChange 被触发 */
    private inner class ScreenRecordingContentObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            onScreenRecordingStateChanged()
        }
    }

    /** API < 35：ContentObserver 监听 MediaStore.Video + FileObserver 监听目录 */
    private fun setupScreenRecordingDetectionContentObserver() {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        videoObserver = ScreenRecordingContentObserver(handler)
        contentResolver.registerContentObserver(uri, true, videoObserver!!)
        setupScreenRecordingDetectionFileObserver()
    }

    /**
     * FileObserver：监听指定目录下文件变化，录屏结束时新视频会写入这些目录
     * 需 READ_EXTERNAL_STORAGE 权限
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun setupScreenRecordingDetectionFileObserver() {
        val dirs = mutableListOf<File>()
        try {
            @Suppress("DEPRECATION")
            val externalStorage = Environment.getExternalStorageDirectory()
            if (externalStorage != null && externalStorage.exists()) {
                dirs.add(File(externalStorage, Environment.DIRECTORY_DCIM))
                dirs.add(File(externalStorage, Environment.DIRECTORY_MOVIES))
                dirs.add(File(externalStorage, "DCIM/ScreenRecorder"))
                dirs.add(File(externalStorage, "Movies/Screen recordings"))
            }
            @Suppress("DEPRECATION")
            val publicDcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            if (publicDcim.exists()) dirs.add(publicDcim)
            @Suppress("DEPRECATION")
            val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (publicMovies.exists()) dirs.add(publicMovies)
        } catch (_: Exception) {}
        val videoExtensions = setOf(".mp4", ".mkv", ".webm", ".3gp")
        val mask = FileObserver.CLOSE_WRITE or FileObserver.CREATE
        for (dir in dirs.distinct()) {
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                val path = dir.absolutePath
                val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(dir, mask) {
                        override fun onEvent(event: Int, p: String?) {
                            if (p != null && videoExtensions.any { p.lowercase().endsWith(it) }) {
                                onScreenRecordingStateChanged()
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(path, mask) {
                        override fun onEvent(event: Int, p: String?) {
                            if (p != null && videoExtensions.any { p.lowercase().endsWith(it) }) {
                                onScreenRecordingStateChanged()
                            }
                        }
                    }
                }
                observer.startWatching()
                fileObservers.add(observer)
            } catch (_: Exception) {}
        }
    }

    /** 仅移除 API 35 的录屏回调，不移除 ContentObserver/FileObserver */
    private fun removeScreenRecordingCallbackOnly() {
        if (Build.VERSION.SDK_INT >= 35) {
            val act = activity
            val callback = screenRecordingCallback
            if (act != null && callback != null) {
                try {
                    act.windowManager.removeScreenRecordingCallback(callback as Consumer<Int>)
                } catch (_: Exception) {}
            }
            screenRecordingCallback = null
        }
    }

    /** 移除所有录屏监听（回调、ContentObserver、FileObserver） */
    private fun removeScreenRecordingDetection() {
        removeScreenRecordingCallbackOnly()
        videoObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        videoObserver = null
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
    }

    // endregion

    /** 模拟器调试：手动发送测试事件（screenshot / screen_recording） */
    fun sendTestEvent(type: String) {
        sendTestEvent(type, eventSink)
    }

    companion object {
        private const val TAG = "ScreenCapturePlugin"

        private fun sendTestEvent(type: String, eventSink: EventChannel.EventSink?) {
            val timestamp = System.currentTimeMillis()
            val event = if (type == "screen_recording") {
                mapOf(
                    "type" to "screen_recording",
                    "event" to "start",
                    "isCaptured" to true,
                    "timestamp" to timestamp,
                    "path" to ""
                )
            } else {
                mapOf(
                    "type" to "screenshot",
                    "event" to "taken",
                    "timestamp" to timestamp
                )
            }
            eventSink?.success(event)
        }
    }
}
