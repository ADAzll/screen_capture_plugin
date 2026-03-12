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
import android.media.MediaMetadataRetriever
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
 * - 文件路径：从 MediaStore 查询最新截图/录屏，需 READ_EXTERNAL_STORAGE / READ_MEDIA_* 权限
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
            var logMsg = "录屏事件: $ev, 时间: $timeStr (timestamp=$ts)"
            val dur = (event["duration"] as? Number)?.toLong()
            val startTime = (event["startTime"] as? Number)?.toLong()
            if (dur != null && dur > 0 && startTime != null) {
                val startStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(startTime))
                logMsg += " 时长: ${dur / 1000.0}s 推算开始: $startStr"
            }
            Log.d(TAG, logMsg)
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
                fetchLatestScreenshotPath { path ->
                    if (path != null) {
                        sendEvent(mapOf(
                            "type" to "screenshot",
                            "event" to "taken",
                            "timestamp" to timestamp,
                            "path" to path
                        ))
                    }
                }
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
                // 先立即发送事件（模拟器 MediaStore 可能有延迟）
                val timestamp = now
                sendEvent(mapOf(
                    "type" to "screenshot",
                    "event" to "taken",
                    "timestamp" to timestamp
                ))
                // 延迟查询路径，等待 MediaStore 更新（模拟器尤其需要）
                handler.postDelayed({
                    fetchLatestScreenshotPath { path ->
                        if (path != null) {
                            sendEvent(mapOf(
                                "type" to "screenshot",
                                "event" to "taken",
                                "timestamp" to timestamp,
                                "path" to path
                            ))
                        }
                    }
                }, 800)
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
                    "timestamp" to timestamp
                ))
                if (!isCaptured) {
                    fetchLatestScreenRecordingPath { path ->
                        if (path != null) {
                            Thread {
                                val durationMs = getVideoDurationMs(path)
                                val event = mutableMapOf<String, Any>(
                                    "type" to "screen_recording",
                                    "event" to "end",
                                    "isCaptured" to false,
                                    "timestamp" to timestamp,
                                    "path" to path
                                )
                                if (durationMs != null && durationMs > 0) {
                                    event["duration"] = durationMs
                                    event["startTime"] = timestamp - durationMs
                                }
                                handler.post { sendEvent(event) }
                            }.start()
                        }
                    }
                }
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
            fetchLatestScreenRecordingPath { path ->
                val event = mutableMapOf<String, Any>(
                    "type" to "screen_recording",
                    "event" to "end",
                    "isCaptured" to false,
                    "timestamp" to timestamp
                )
                if (path != null) {
                    event["path"] = path
                    // 后台获取视频时长，推算录屏开始时间（API < 35 无法直接检测 start）
                    Thread {
                        val durationMs = getVideoDurationMs(path)
                        if (durationMs != null && durationMs > 0) {
                            event["duration"] = durationMs
                            event["startTime"] = timestamp - durationMs
                        }
                        handler.post { sendEvent(event) }
                    }.start()
                } else {
                    sendEvent(event)
                }
            }
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

    // region 获取文件路径

    /** 从 MediaStore 查询最新截屏路径，仅取最近 10 秒内且名称含 screenshot 的图片 */
    private fun fetchLatestScreenshotPath(callback: (String?) -> Unit) {
        Thread {
            try {
                val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.RELATIVE_PATH
                    )
                } else {
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DISPLAY_NAME
                    )
                }
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val nowSec = System.currentTimeMillis() / 1000
                contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                        val pathIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                        val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        val relPathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1
                        val path = if (pathIdx >= 0) cursor.getString(pathIdx) else null
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                        val relPath = if (relPathIdx >= 0) cursor.getString(relPathIdx) ?: "" else ""
                        val dateAdded = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                        // 只取最近 10 秒内的图片
                        if (nowSec - dateAdded <= 10) {
                            val isScreenshot = path?.lowercase()?.contains("screenshot") == true ||
                                name.lowercase().contains("screenshot") ||
                                relPath.lowercase().contains("screenshot") ||
                                name.lowercase().contains("capture") ||
                                name.lowercase().contains("截屏") ||
                                // 模拟器可能用不同命名，最近 3 秒内的新图也视为截屏
                                (nowSec - dateAdded <= 3)
                            if (isScreenshot && idIdx >= 0) {
                                val id = cursor.getLong(idIdx)
                                // Android 10+ Scoped Storage：返回 content URI，resolveImagePath 会复制到临时文件供 Image 使用
                                val resultPath = when {
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> android.content.ContentUris.withAppendedId(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                                    ).toString()
                                    !path.isNullOrEmpty() -> path
                                    else -> android.content.ContentUris.withAppendedId(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                                    ).toString()
                                }
                                if (resultPath.isNotEmpty()) {
                                    handler.post { callback(resultPath) }
                                    return@use
                                }
                            }
                        }
                    }
                }
                handler.post { callback(null) }
            } catch (e: Exception) {
                handler.post { callback(null) }
            }
        }.start()
    }

    /** 从视频文件读取时长（毫秒），用于推算录屏开始时间 */
    private fun getVideoDurationMs(pathOrUri: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (pathOrUri.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(pathOrUri))
            } else {
                retriever.setDataSource(pathOrUri)
            }
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            dur?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** 从 MediaStore 查询最新录屏路径，按 DATE_ADDED 降序取第一条 */
    private fun fetchLatestScreenRecordingPath(callback: (String?) -> Unit) {
        Thread {
            try {
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DATE_ADDED
                )
                val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                        val pathIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                        val path = if (pathIdx >= 0) cursor.getString(pathIdx) else null
                        val resultPath = when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && idIdx >= 0 -> {
                                val id = cursor.getLong(idIdx)
                                android.content.ContentUris.withAppendedId(
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                                ).toString()
                            }
                            !path.isNullOrEmpty() -> path
                            else -> null
                        }
                        if (resultPath != null) {
                            handler.post { callback(resultPath) }
                            return@use
                        }
                    }
                }
                handler.post { callback(null) }
            } catch (e: Exception) {
                handler.post { callback(null) }
            }
        }.start()
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
                    "timestamp" to timestamp
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
