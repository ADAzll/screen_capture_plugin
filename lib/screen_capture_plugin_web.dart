// In order to *not* need this ignore, consider extracting the "web" version
// of your plugin as a separate package, instead of inlining it in the same
// package as the core of your plugin.
// ignore: avoid_web_libraries_in_flutter

import 'dart:async';

import 'package:flutter_web_plugins/flutter_web_plugins.dart';
import 'package:web/web.dart' as web;

import 'screen_capture_event.dart';
import 'screen_capture_plugin_platform_interface.dart';

/// A web implementation of the ScreenCapturePluginPlatform of the ScreenCapturePlugin plugin.
class ScreenCapturePluginWeb extends ScreenCapturePluginPlatform {
  /// Constructs a ScreenCapturePluginWeb
  ScreenCapturePluginWeb();

  static void registerWith(Registrar registrar) {
    ScreenCapturePluginPlatform.instance = ScreenCapturePluginWeb();
  }

  /// Returns a [String] containing the version of the platform.
  @override
  Future<String?> getPlatformVersion() async {
    final version = web.window.navigator.userAgent;
    return version;
  }

  @override
  Future<String?> resolveImagePath(String path) async => null;

  @override
  Future<bool> requestPhotoLibraryPermission() async => false;

  @override
  Stream<ScreenCaptureEvent> captureEvents() => Stream.empty();

  @override
  Future<void> sendTestEvent(String type) async {}
}
