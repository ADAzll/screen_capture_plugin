import 'dart:io';

import 'package:flutter/material.dart';

Widget buildScreenshotPreview(String path) {
  return ClipRRect(
    borderRadius: BorderRadius.circular(8),
    child: Image.file(
      File(path),
      fit: BoxFit.contain,
      height: 200,
      errorBuilder: (_, __, ___) => const Text('加载失败'),
    ),
  );
}
