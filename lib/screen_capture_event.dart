/// 截屏/录屏事件
///
/// 由 iOS/Android 原生通过 EventChannel 推送。
/// [path] 为获取到的文件路径，需相册权限，可能为 null。
class ScreenCaptureEvent {
  const ScreenCaptureEvent({
    required this.type,
    required this.event,
    this.isCaptured,
    required this.timestamp,
    this.path,
    this.duration,
    this.startTime,
  });

  /// 事件类型：`screenshot` 截屏，`screen_recording` 录屏
  final String type;

  /// 事件动作：截屏为 `taken`，录屏为 `start` 或 `end`
  final String event;

  /// 录屏时表示当前是否正在录屏（仅 screen_recording 类型有）
  final bool? isCaptured;

  /// 时间戳（毫秒），录屏 end 时为检测到结束的时间
  final int timestamp;

  /// 文件路径（截屏图片或录屏视频），需相册权限，获取失败时为 null
  final String? path;

  /// 录屏视频时长（毫秒），仅 end 事件且能读取到元数据时有值
  final int? duration;

  /// 推算的录屏开始时间（毫秒），= timestamp - duration，仅 end 事件且 duration 有效时有值
  final int? startTime;

  /// 是否为截屏事件
  bool get isScreenshot => type == 'screenshot';

  /// 是否为录屏事件
  bool get isScreenRecording => type == 'screen_recording';

  factory ScreenCaptureEvent.fromMap(Map<Object?, Object?> map) {
    return ScreenCaptureEvent(
      type: map['type'] as String? ?? '',
      event: map['event'] as String? ?? '',
      isCaptured: map['isCaptured'] as bool?,
      timestamp: (map['timestamp'] as num?)?.toInt() ?? 0,
      path: map['path'] as String?,
      duration: (map['duration'] as num?)?.toInt(),
      startTime: (map['startTime'] as num?)?.toInt(),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'type': type,
      'event': event,
      if (isCaptured != null) 'isCaptured': isCaptured,
      'timestamp': timestamp,
      if (path != null) 'path': path,
      if (duration != null) 'duration': duration,
      if (startTime != null) 'startTime': startTime,
    };
  }

  @override
  String toString() =>
      'ScreenCaptureEvent(type: $type, event: $event, isCaptured: $isCaptured, timestamp: $timestamp, path: $path, duration: $duration, startTime: $startTime)';
}
