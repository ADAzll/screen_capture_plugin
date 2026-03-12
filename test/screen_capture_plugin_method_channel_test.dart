import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:screen_capture_plugin/screen_capture_plugin_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  MethodChannelScreenCapturePlugin platform =
      MethodChannelScreenCapturePlugin();
  const MethodChannel channel = MethodChannel('screen_capture_plugin');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      channel,
      (MethodCall methodCall) async {
        if (methodCall.method == 'getPlatformVersion') return '42';
        if (methodCall.method == 'requestPhotoLibraryPermission') return true;
        if (methodCall.method == 'resolveImagePath')
          return (methodCall.arguments as Map)['path'];
        if (methodCall.method == 'sendTestEvent') return null;
        return null;
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('getPlatformVersion', () async {
    expect(await platform.getPlatformVersion(), '42');
  });

  test('sendTestEvent', () async {
    await platform.sendTestEvent('screenshot');
    await platform.sendTestEvent('screen_recording');
  });

  test('resolveImagePath', () async {
    expect(await platform.resolveImagePath('/path/to/image.jpg'),
        '/path/to/image.jpg');
  });
}
