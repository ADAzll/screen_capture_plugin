import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'screenshot_preview_mobile.dart'
    if (dart.library.html) 'screenshot_preview_web.dart' as preview;
import 'package:permission_handler/permission_handler.dart';
import 'package:screen_capture_plugin/screen_capture_plugin.dart';

void main() => runApp(const MyApp());

String _formatTime(int timestamp) {
  final dt = DateTime.fromMillisecondsSinceEpoch(timestamp);
  return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}:'
      '${dt.second.toString().padLeft(2, '0')}.${dt.millisecond.toString().padLeft(3, '0')}';
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _plugin = ScreenCapturePlugin();
  StreamSubscription<ScreenCaptureEvent>? _subscription;

  String? _screenshotTime;
  String? _screenshotPath;
  String? _recordingStartTime;
  String? _recordingEndTime;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    try {
      await _plugin.requestPhotoLibraryPermission();
    } on PlatformException {
      // 热重载后忽略
    }
    if (mounted) {
      final platformVersion = await _plugin.getPlatformVersion() ?? '';
      if (platformVersion.startsWith('Android')) {
        await Permission.photos.request();
        await Permission.storage.request();
        await Permission.videos.request();
      }
    }
    _listenCaptureEvents();
  }

  void _listenCaptureEvents() {
    _subscription = _plugin.captureEvents().listen((event) async {
      if (!mounted) return;
      if (event.isScreenshot) {
        final path = event.path;
        String? resolved;
        if (path != null) {
          resolved = await _plugin.resolveImagePath(path);
        }
        setState(() {
          _screenshotTime = _formatTime(event.timestamp);
          _screenshotPath = resolved ?? path;
          _recordingStartTime = null;
          _recordingEndTime = null;
        });
      } else if (event.isScreenRecording) {
        setState(() {
          if (event.event == 'start') {
            _recordingStartTime = _formatTime(event.timestamp);
            _recordingEndTime = null;
          } else {
            _recordingEndTime = _formatTime(event.timestamp);
          }
          _screenshotTime = null;
          _screenshotPath = null;
        });
      }
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Screen Capture Demo')),
        body: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildSection('截屏', [
                _buildRow('时间', _screenshotTime ?? '-'),
                _buildRow('路径', _screenshotPath ?? '-'),
                if (_screenshotPath != null) ...[
                  const SizedBox(height: 12),
                  preview.buildScreenshotPreview(_screenshotPath!),
                ],
              ]),
              const SizedBox(height: 24),
              _buildSection('录屏', [
                _buildRow('开始时间', _recordingStartTime ?? '-'),
                _buildRow('结束时间', _recordingEndTime ?? '-'),
              ]),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSection(String title, List<Widget> children) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        ...children,
      ],
    );
  }

  Widget _buildRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: 72, child: Text('$label:', style: TextStyle(color: Colors.grey[600]))),
          Expanded(child: Text(value, style: const TextStyle(fontFamily: 'monospace'))),
        ],
      ),
    );
  }
}
