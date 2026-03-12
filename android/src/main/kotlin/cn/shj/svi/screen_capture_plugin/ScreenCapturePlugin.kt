package cn.shj.svi.screen_capture_plugin

import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Android 截屏、录屏事件监听插件
 *
 * 负责：MethodChannel 通信、EventChannel 事件流、Activity 生命周期绑定
 */
class ScreenCapturePlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

  private lateinit var methodChannel: MethodChannel
  private lateinit var eventChannel: EventChannel
  private var eventStreamHandler: ScreenCaptureEventStreamHandler? = null
  private var applicationContext: android.content.Context? = null
  private var lifecycleObserver: DefaultLifecycleObserver? = null
  private var currentActivity: android.app.Activity? = null

  /** 插件附着到 Flutter 引擎时，初始化 MethodChannel、EventChannel、StreamHandler */
  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "screen_capture_plugin")
    methodChannel.setMethodCallHandler(this)
    applicationContext = flutterPluginBinding.applicationContext

    val context = flutterPluginBinding.applicationContext
    eventStreamHandler = ScreenCaptureEventStreamHandler(context, context.contentResolver, null)
    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "screen_capture_plugin/events")
    eventChannel.setStreamHandler(eventStreamHandler)
  }

  /** 插件从引擎分离时，释放资源 */
  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
    eventStreamHandler = null
    applicationContext = null
  }

  /** Activity 附着：绑定 Activity、注册生命周期观察者，Android 15 录屏回调需 onStart/onStop */
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    currentActivity = binding.activity
    eventStreamHandler?.setActivity(binding.activity)
    val activity = binding.activity
    if (activity is LifecycleOwner) {
      lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
          eventStreamHandler?.onActivityStart()
        }

        override fun onStop(owner: LifecycleOwner) {
          eventStreamHandler?.onActivityStop()
        }
      }
      activity.lifecycle.addObserver(lifecycleObserver!!)
      if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        eventStreamHandler?.onActivityStart()
      }
    }
  }

  /** 配置变更导致 Activity 分离（如旋转屏幕） */
  override fun onDetachedFromActivityForConfigChanges() {
    lifecycleObserver?.let { obs ->
      (currentActivity as? LifecycleOwner)?.lifecycle?.removeObserver(obs)
    }
    lifecycleObserver = null
    currentActivity = null
    eventStreamHandler?.setActivity(null)
  }

  /** 配置变更后 Activity 重新附着 */
  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    currentActivity = binding.activity
    eventStreamHandler?.setActivity(binding.activity)
    val activity = binding.activity
    if (activity is LifecycleOwner) {
      if (lifecycleObserver == null) {
        lifecycleObserver = object : DefaultLifecycleObserver {
          override fun onStart(owner: LifecycleOwner) {
            eventStreamHandler?.onActivityStart()
          }

          override fun onStop(owner: LifecycleOwner) {
            eventStreamHandler?.onActivityStop()
          }
        }
        activity.lifecycle.addObserver(lifecycleObserver!!)
      }
      if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        eventStreamHandler?.onActivityStart()
      }
    }
  }

  /** Activity 完全分离（如返回桌面） */
  override fun onDetachedFromActivity() {
    lifecycleObserver?.let { obs ->
      (currentActivity as? LifecycleOwner)?.lifecycle?.removeObserver(obs)
    }
    lifecycleObserver = null
    currentActivity = null
    eventStreamHandler?.setActivity(null)
  }

  /** 处理 Flutter 侧 MethodChannel 调用 */
  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
      "resolveImagePath" -> {
        val path = call.argument<String>("path") ?: ""
        if (path.isEmpty()) {
          result.success(null)
          return
        }
        Thread {
          try {
            val ctx = applicationContext ?: return@Thread
            // content:// URI（Android 10+）需复制到临时文件供 Image.file 使用
            val resolved = if (path.startsWith("content://")) {
              try {
                val uri = Uri.parse(path)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                  val tempFile = java.io.File(ctx.cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
                  tempFile.outputStream().use { output -> input.copyTo(output) }
                  tempFile.absolutePath
                }
              } catch (e: Exception) {
                null
              }
            } else {
              // 直接文件路径（API < 29）
              path
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { result.success(resolved) }
          } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { result.success(null) }
          }
        }.start()
      }
      "requestPhotoLibraryPermission" -> {
        // 存储权限由宿主应用用 permission_handler 等请求
        result.success(true)
      }
      "sendTestEvent" -> {
        val type = call.argument<String>("type")
        if (type != null) {
          eventStreamHandler?.sendTestEvent(type)
          result.success(null)
        } else {
          result.error("INVALID_ARGS", "type is required", null)
        }
      }
      else -> result.notImplemented()
    }
  }
}
