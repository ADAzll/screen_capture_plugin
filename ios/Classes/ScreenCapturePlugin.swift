import Flutter
import UIKit

/// iOS 截屏、录屏事件监听插件
///
/// 负责：MethodChannel 方法调用、EventChannel 事件流、权限请求
public class ScreenCapturePlugin: NSObject, FlutterPlugin {
  /// 插件注册：创建 MethodChannel、EventChannel，绑定 StreamHandler
  public static func register(with registrar: FlutterPluginRegistrar) {
    let methodChannel = FlutterMethodChannel(name: "screen_capture_plugin", binaryMessenger: registrar.messenger())
    let instance = ScreenCapturePlugin()
    registrar.addMethodCallDelegate(instance, channel: methodChannel)

    let eventChannel = FlutterEventChannel(name: "screen_capture_plugin/events", binaryMessenger: registrar.messenger())
    eventChannel.setStreamHandler(ScreenCaptureEventStreamHandler())
  }

  /// 处理 Flutter 侧 MethodChannel 调用
  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "getPlatformVersion":
      result("iOS " + UIDevice.current.systemVersion)
    case "requestPhotoLibraryPermission":
      // 相册权限由宿主应用请求，此处仅返回状态
      ScreenCaptureEventStreamHandler.requestPhotoLibraryPermission { granted in
        result(granted)
      }
    case "resolveImagePath":
      // iOS 直接返回 path，Flutter 侧用 Image.file 等加载
      if let args = call.arguments as? [String: Any], let path = args["path"] as? String, !path.isEmpty {
        result(path)
      } else {
        result(nil)
      }
    case "sendTestEvent":
      // 模拟器调试：手动发送截屏/录屏测试事件
      if let args = call.arguments as? [String: Any], let type = args["type"] as? String {
        ScreenCaptureEventStreamHandler.sendTestEvent(type: type)
        result(nil)
      } else {
        result(FlutterError(code: "INVALID_ARGS", message: "type is required", details: nil))
      }
    default:
      result(FlutterMethodNotImplemented) // 未实现的方法
    }
  }
}
