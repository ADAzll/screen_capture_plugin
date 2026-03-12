import 'dart:async';

import 'screen_capture_event.dart';
import 'screen_capture_plugin_platform_interface.dart';

export 'screen_capture_event.dart';

class ScreenCapturePlugin {
  /// 获取平台版本
  Future<String?> getPlatformVersion() {
    return ScreenCapturePluginPlatform.instance.getPlatformVersion();
  }

  /// 请求相册权限（用于获取截屏/录屏文件路径）
  ///
  /// 建议在监听事件前调用，以便用户授权后能获取到 path。
  Future<bool> requestPhotoLibraryPermission() {
    return ScreenCapturePluginPlatform.instance.requestPhotoLibraryPermission();
  }

  /// 监听截屏、录屏事件
  ///
  /// iOS 支持；模拟器截屏需用 Device → Trigger Screenshot（⌘S）；
  /// 录屏检测需在真机上测试。
  /// 事件中的 [path] 需相册权限，可先调用 [requestPhotoLibraryPermission]。
  Stream<ScreenCaptureEvent> captureEvents() {
    return ScreenCapturePluginPlatform.instance.captureEvents();
  }

  /// 解析图片路径为可用于 Image.file 的路径
  Future<String?> resolveImagePath(String path) {
    return ScreenCapturePluginPlatform.instance.resolveImagePath(path);
  }

  /// 模拟器调试：手动触发测试事件
  ///
  /// [type] 为 `screenshot` 或 `screen_recording`。
  Future<void> sendTestEvent(String type) {
    return ScreenCapturePluginPlatform.instance.sendTestEvent(type);
  }
}
