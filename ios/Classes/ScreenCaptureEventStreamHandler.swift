import Flutter
import Photos
import UIKit
import AVFoundation

/// iOS 截屏、录屏事件监听，通过 EventChannel 发送到 Flutter
///
/// 模拟器测试说明（Apple 限制，模拟器与真机行为不同）：
/// - 截屏：必须使用「Device → Trigger Screenshot」（⌘S），「File → Save screenshot」不会触发
/// - 录屏：模拟器「File → Record Screen」不会触发 capturedDidChangeNotification
///   （宿主级录屏，模拟的 iOS 无法感知）。录屏检测需在真机上测试。
///
/// 获取文件路径需在 Info.plist 中添加 NSPhotoLibraryUsageDescription。
public class ScreenCaptureEventStreamHandler: NSObject, FlutterStreamHandler {
    private var eventSink: FlutterEventSink?
    private var isObserving = false
    /// 录屏 end 防抖：避免重复发送
    private var lastRecordingEndTime: Date?
    /// 待发送的录屏结束任务，收到 isCaptured=true 时取消（长按/点按灵动岛会快速恢复 true）
    private var pendingRecordingEndTask: DispatchWorkItem?

    /// 用于 MethodChannel 触发模拟器测试（仅 debug）
    static weak var shared: ScreenCaptureEventStreamHandler?

    /// 请求相册读写权限，用于获取截屏/录屏文件路径
    static func requestPhotoLibraryPermission(completion: @escaping (Bool) -> Void) {
        let status: PHAuthorizationStatus
        if #available(iOS 14, *) {
            status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        } else {
            status = PHPhotoLibrary.authorizationStatus()
        }
        let isGranted: Bool
        if #available(iOS 14, *) {
            isGranted = (status == .authorized || status == .limited)
        } else {
            isGranted = (status == .authorized)
        }
        if isGranted {
            DispatchQueue.main.async { completion(true) }
            return
        }
        if status == .denied || status == .restricted {
            DispatchQueue.main.async { completion(false) }
            return
        }
        if #available(iOS 14, *) {
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { newStatus in
                let granted = (newStatus == .authorized || newStatus == .limited)
                DispatchQueue.main.async { completion(granted) }
            }
        } else {
            PHPhotoLibrary.requestAuthorization { newStatus in
                DispatchQueue.main.async { completion(newStatus == .authorized) }
            }
        }
    }

    /// 模拟器调试：手动发送测试事件，type 为 "screenshot" 或 "screen_recording"
    static func sendTestEvent(type: String) {
        let event: [String: Any]
        if type == "screen_recording" {
            event = [
                "type": "screen_recording",
                "event": "start",
                "isCaptured": true,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000)
            ]
        } else {
            event = [
                "type": "screenshot",
                "event": "taken",
                "timestamp": Int(Date().timeIntervalSince1970 * 1000)
            ]
        }
        shared?.sendEvent(event)
    }

    /// 向 Flutter 发送事件（主线程）
    private func sendEvent(_ event: [String: Any]) {
        DispatchQueue.main.async { [weak self] in
            self?.eventSink?(event)
        }
    }

    /// 截屏通知（真机：物理按键；模拟器：Device → Trigger Screenshot）
    private func setupScreenshotObserver() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onScreenshot),
            name: UIApplication.userDidTakeScreenshotNotification,
            object: nil
        )
    }

    /// 录屏开始/结束通知（也包含 AirPlay、投屏等）
    private func setupScreenRecordingObserver() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onScreenCaptureChanged),
            name: UIScreen.capturedDidChangeNotification,
            object: nil
        )
    }

    /// 移除截屏、录屏通知监听
    private func removeObservers() {
        NotificationCenter.default.removeObserver(
            self,
            name: UIApplication.userDidTakeScreenshotNotification,
            object: nil
        )
        NotificationCenter.default.removeObserver(
            self,
            name: UIScreen.capturedDidChangeNotification,
            object: nil
        )
        isObserving = false
    }

    /// 截屏通知回调：等待获取路径后再发送，避免取到旧图
    @objc private func onScreenshot() {
        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        // 异步获取截屏路径后再发送（避免取到旧图），只发一次带 path 的事件
        fetchLatestScreenshotPath { [weak self] path in
            var event: [String: Any] = [
                "type": "screenshot",
                "event": "taken",
                "timestamp": timestamp
            ]
            if let path = path { event["path"] = path }
            self?.sendEvent(event)
        }
    }

    /// 录屏开始/结束通知回调：start 立即发送；end 仅延迟 0.2s，若期间收到 true 则取消
    /// 长按/点按灵动岛会在 ~0.1s 内恢复 true，0.2s 足以过滤且几乎无感知延迟
    @objc private func onScreenCaptureChanged() {
        let isCaptured = UIScreen.main.isCaptured
        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        if isCaptured {
            let hadPending = pendingRecordingEndTask != nil
            pendingRecordingEndTask?.cancel()
            pendingRecordingEndTask = nil
            lastRecordingEndTime = nil
            if !hadPending {
                sendEvent([
                    "type": "screen_recording",
                    "event": "start",
                    "isCaptured": true,
                    "timestamp": timestamp
                ])
            }
        } else {
            let now = Date()
            if let last = lastRecordingEndTime, now.timeIntervalSince(last) < 0.5 {
                return
            }
            lastRecordingEndTime = now
            let captureTimestamp = timestamp
            pendingRecordingEndTask?.cancel()
            let task = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                self.pendingRecordingEndTask = nil
                guard UIScreen.main.isCaptured == false else { return }
                self.fetchLatestScreenRecordingPath { path in
                    var event: [String: Any] = [
                        "type": "screen_recording",
                        "event": "end",
                        "isCaptured": false,
                        "timestamp": captureTimestamp
                    ]
                    if let path = path { event["path"] = path }
                    self.sendEvent(event)
                }
            }
            pendingRecordingEndTask = task
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: task)
        }
    }

    // MARK: - 获取文件路径

    /// 从相册获取最新截屏路径，仅取最近 8 秒内图片，避免取到旧图
    /// - Parameter completion: 主线程回调，path 为 nil 表示获取失败（无权限或未找到）
    private func fetchLatestScreenshotPath(completion: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let status: PHAuthorizationStatus
            if #available(iOS 14, *) {
                status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            } else {
                status = PHPhotoLibrary.authorizationStatus()
            }
            let hasAccess: Bool
            if #available(iOS 14, *) {
                hasAccess = (status == .authorized || status == .limited)
            } else {
                hasAccess = (status == .authorized)
            }
            if !hasAccess {
                DispatchQueue.main.async { completion(nil) }
                return
            }

            // 等待系统保存截屏到相册（1.2s，0.5s 易取到旧图）
            Thread.sleep(forTimeInterval: 1.2)

            let fetchOptions = PHFetchOptions()
            fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            fetchOptions.fetchLimit = 1
            // 只取最近 8 秒内的图片，避免取到旧截屏
            fetchOptions.predicate = NSPredicate(format: "creationDate >= %@", Date().addingTimeInterval(-8) as NSDate)

            var assets = PHAsset.fetchAssets(with: .image, options: fetchOptions)
            if assets.firstObject == nil {
                // 首次未找到则再等 0.6s 重试（系统保存较慢时）
                Thread.sleep(forTimeInterval: 0.6)
                fetchOptions.predicate = NSPredicate(format: "creationDate >= %@", Date().addingTimeInterval(-10) as NSDate)
                assets = PHAsset.fetchAssets(with: .image, options: fetchOptions)
            }
            guard let asset = assets.firstObject else {
                DispatchQueue.main.async { completion(nil) }
                return
            }

            let options = PHImageRequestOptions()
            options.isSynchronous = false
            options.deliveryMode = .highQualityFormat
            options.isNetworkAccessAllowed = true

            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                guard let data = data else {
                    DispatchQueue.main.async { completion(nil) }
                    return
                }
                let tempDir = FileManager.default.temporaryDirectory
                let fileName = "screenshot_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
                let fileURL = tempDir.appendingPathComponent(fileName)
                do {
                    try data.write(to: fileURL)
                    DispatchQueue.main.async { completion(fileURL.path) }
                } catch {
                    DispatchQueue.main.async { completion(nil) }
                }
            }
        }
    }

    /// 从相册获取最新录屏路径，仅取最近 10 秒内视频
    private func fetchLatestScreenRecordingPath(completion: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let status: PHAuthorizationStatus
            if #available(iOS 14, *) {
                status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            } else {
                status = PHPhotoLibrary.authorizationStatus()
            }
            let hasAccess: Bool
            if #available(iOS 14, *) {
                hasAccess = (status == .authorized || status == .limited)
            } else {
                hasAccess = (status == .authorized)
            }
            if !hasAccess {
                DispatchQueue.main.async { completion(nil) }
                return
            }

            Thread.sleep(forTimeInterval: 1.5)

            let fetchOptions = PHFetchOptions()
            fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            fetchOptions.fetchLimit = 1
            // 只取最近 10 秒内的视频，避免取到旧录屏
            fetchOptions.predicate = NSPredicate(format: "creationDate >= %@", Date().addingTimeInterval(-10) as NSDate)

            let assets = PHAsset.fetchAssets(with: .video, options: fetchOptions)
            guard let asset = assets.firstObject else {
                DispatchQueue.main.async { completion(nil) }
                return
            }

            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = true

            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { avAsset, _, _ in
                if let urlAsset = avAsset as? AVURLAsset {
                    DispatchQueue.main.async { completion(urlAsset.url.path) }
                } else {
                    DispatchQueue.main.async { completion(nil) }
                }
            }
        }
    }

    // MARK: - FlutterStreamHandler

    /// Flutter 订阅事件流时，开始监听截屏、录屏
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        Self.shared = self
        if !isObserving {
            setupScreenshotObserver()
            setupScreenRecordingObserver()
            isObserving = true
        }
        return nil
    }

    /// Flutter 取消订阅时，移除所有监听
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        pendingRecordingEndTask?.cancel()
        pendingRecordingEndTask = nil
        eventSink = nil
        Self.shared = nil
        removeObservers()
        return nil
    }
}
