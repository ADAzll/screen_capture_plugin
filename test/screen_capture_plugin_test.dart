import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:screen_capture_plugin/screen_capture_plugin.dart';
import 'package:screen_capture_plugin/screen_capture_plugin_platform_interface.dart';
import 'package:screen_capture_plugin/screen_capture_plugin_method_channel.dart';
import 'package:screen_capture_plugin/screen_capture_event.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockScreenCapturePluginPlatform
    with MockPlatformInterfaceMixin
    implements ScreenCapturePluginPlatform {
  @override
  Future<String?> getPlatformVersion() => Future.value('42');

  @override
  Future<String?> resolveImagePath(String path) async => null;

  @override
  Future<bool> requestPhotoLibraryPermission() async => false;

  @override
  Stream<ScreenCaptureEvent> captureEvents() => Stream.empty();

  @override
  Future<void> sendTestEvent(String type) async {}
}

void main() {
  final ScreenCapturePluginPlatform initialPlatform =
      ScreenCapturePluginPlatform.instance;

  test('$MethodChannelScreenCapturePlugin is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelScreenCapturePlugin>());
  });

  test('getPlatformVersion', () async {
    ScreenCapturePlugin screenCapturePlugin = ScreenCapturePlugin();
    MockScreenCapturePluginPlatform fakePlatform =
        MockScreenCapturePluginPlatform();
    ScreenCapturePluginPlatform.instance = fakePlatform;

    expect(await screenCapturePlugin.getPlatformVersion(), '42');
  });
}
