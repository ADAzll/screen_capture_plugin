import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'screen_capture_event.dart';
import 'screen_capture_plugin_platform_interface.dart';

/// An implementation of [ScreenCapturePluginPlatform] that uses method channels.
class MethodChannelScreenCapturePlugin extends ScreenCapturePluginPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('screen_capture_plugin');

  /// Event channel for screen capture events (screenshot, screen recording).
  @visibleForTesting
  final eventChannel = const EventChannel('screen_capture_plugin/events');

  @override
  Future<String?> getPlatformVersion() async {
    final version =
        await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  @override
  Future<String?> resolveImagePath(String path) async {
    return methodChannel
        .invokeMethod<String>('resolveImagePath', {'path': path});
  }

  @override
  Future<bool> requestPhotoLibraryPermission() async {
    final result =
        await methodChannel.invokeMethod<bool>('requestPhotoLibraryPermission');
    return result ?? false;
  }

  @override
  Stream<ScreenCaptureEvent> captureEvents() {
    return eventChannel.receiveBroadcastStream().map((event) {
      if (event is Map) {
        return ScreenCaptureEvent.fromMap(
          event.map((k, v) => MapEntry(k, v)),
        );
      }
      return ScreenCaptureEvent(
        type: 'unknown',
        event: 'unknown',
        timestamp: 0,
      );
    });
  }

  @override
  Future<void> sendTestEvent(String type) async {
    await methodChannel.invokeMethod<void>('sendTestEvent', {'type': type});
  }
}
