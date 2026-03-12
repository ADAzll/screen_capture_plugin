import 'dart:async';

import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'screen_capture_event.dart';
import 'screen_capture_plugin_method_channel.dart';

abstract class ScreenCapturePluginPlatform extends PlatformInterface {
  /// Constructs a ScreenCapturePluginPlatform.
  ScreenCapturePluginPlatform() : super(token: _token);

  static final Object _token = Object();

  static ScreenCapturePluginPlatform _instance =
      MethodChannelScreenCapturePlugin();

  /// The default instance of [ScreenCapturePluginPlatform] to use.
  ///
  /// Defaults to [MethodChannelScreenCapturePlugin].
  static ScreenCapturePluginPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [ScreenCapturePluginPlatform] when
  /// they register themselves.
  static set instance(ScreenCapturePluginPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  /// 请求相册权限（用于获取截屏/录屏文件路径）
  Future<bool> requestPhotoLibraryPermission() {
    throw UnimplementedError(
        'requestPhotoLibraryPermission() has not been implemented.');
  }

  /// 截屏、录屏事件流
  ///
  /// iOS 支持；Android/Web 暂不支持，返回空流。
  Stream<ScreenCaptureEvent> captureEvents() {
    throw UnimplementedError('captureEvents() has not been implemented.');
  }

  /// 解析图片路径为可用于 Image.file 的路径
  /// Android content:// URI 会复制到临时文件后返回
  Future<String?> resolveImagePath(String path) {
    throw UnimplementedError('resolveImagePath() has not been implemented.');
  }

  /// 模拟器调试：手动触发测试事件
  ///
  /// [type] 为 `screenshot` 或 `screen_recording`。
  /// 仅 iOS 模拟器调试用，真机/其他平台调用无效果。
  Future<void> sendTestEvent(String type) {
    throw UnimplementedError('sendTestEvent() has not been implemented.');
  }
}
