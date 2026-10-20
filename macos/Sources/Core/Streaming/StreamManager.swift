import Foundation
import CoreGraphics
import AppKit
import AVFoundation
import Networking
import Utilities

public enum StreamType: String {
    case camera = "camera"
    case screen = "screen"
    case desktopMode = "desktop_mode"
}

public class StreamManager: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    public static let shared = StreamManager()

    public var isCameraSharingEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isCameraSharingEnabled, forKey: "dexolink_camera_sharing_enabled")
            if !isCameraSharingEnabled {
                stopStreamType(.camera)
                stopCameraSession()
            }
        }
    }

    public var isScreenSharingEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isScreenSharingEnabled, forKey: "dexolink_screen_sharing_enabled")
            if !isScreenSharingEnabled {
                stopStreamType(.screen)
            }
        }
    }

    public var isDesktopModeEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isDesktopModeEnabled, forKey: "dexolink_desktop_mode_enabled")
            if !isDesktopModeEnabled {
                stopStreamType(.desktopMode)
            }
        }
    }

    private var activeStreams: [String: StreamType] = [:]
    private var streamTimers: [String: Timer] = [:]
    private weak var connectionManager: ConnectionManager?

    // Camera capture session components
    private var captureSession: AVCaptureSession?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var latestCameraFrameBase64: String?
    private let cameraQueue = DispatchQueue(label: "com.dexolink.camera.capture", qos: .userInteractive)
    private let ciContext = CIContext()

    public var onFrameGenerated: ((String, Int64, String) -> Void)?
    public var onStatusChanged: ((String) -> Void)?

    public override init() {
        self.isCameraSharingEnabled = UserDefaults.standard.bool(forKey: "dexolink_camera_sharing_enabled")
        self.isScreenSharingEnabled = UserDefaults.standard.bool(forKey: "dexolink_screen_sharing_enabled")
        self.isDesktopModeEnabled = UserDefaults.standard.bool(forKey: "dexolink_desktop_mode_enabled")
        super.init()
    }

    public func setConnectionManager(_ cm: ConnectionManager) {
        self.connectionManager = cm
    }

    // MARK: - Permission Handlers
    @discardableResult
    public func checkCameraPermission(completion: ((Bool) -> Void)? = nil) -> Bool {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            completion?(true)
            return true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    completion?(granted)
                }
            }
            return false
        case .denied, .restricted:
            DispatchQueue.main.async {
                if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera") {
                    NSWorkspace.shared.open(url)
                }
                completion?(false)
            }
            return false
        @unknown default:
            completion?(false)
            return false
        }
    }

    @discardableResult
    public func checkScreenCapturePermission() -> Bool {
        if #available(macOS 10.15, *) {
            if CGPreflightScreenCaptureAccess() {
                return true
            } else {
                CGRequestScreenCaptureAccess()
                let granted = CGPreflightScreenCaptureAccess()
                if !granted {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
                        NSWorkspace.shared.open(url)
                    }
                }
                return granted
            }
        }
        return true
    }

    @discardableResult
    public func checkAccessibilityPermission() -> Bool {
        let options: NSDictionary = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true]
        let isTrusted = AXIsProcessTrustedWithOptions(options)
        if !isTrusted {
            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
                NSWorkspace.shared.open(url)
            }
        }
        return isTrusted
    }

    // MARK: - Camera Hardware Session
    private func startCameraSessionIfNeeded() {
        guard captureSession == nil else {
            if !(captureSession?.isRunning ?? false) {
                cameraQueue.async { [weak self] in
                    self?.captureSession?.startRunning()
                }
            }
            return
        }

        cameraQueue.async { [weak self] in
            guard let self = self else { return }
            let session = AVCaptureSession()
            session.sessionPreset = .vga640x480

            guard let device = AVCaptureDevice.default(for: .video) ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .unspecified),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else {
                return
            }
            session.addInput(input)

            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)]
            output.setSampleBufferDelegate(self, queue: self.cameraQueue)

            if session.canAddOutput(output) {
                session.addOutput(output)
            }

            session.startRunning()
            self.captureSession = session
            self.videoOutput = output
        }
    }

    private func stopCameraSession() {
        cameraQueue.async { [weak self] in
            self?.captureSession?.stopRunning()
            self?.captureSession = nil
            self?.videoOutput = nil
            self?.latestCameraFrameBase64 = nil
        }
    }

    public func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ciImage = CIImage(cvPixelBuffer: imageBuffer)
        if let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) {
            let bitmap = NSBitmapImageRep(cgImage: cgImage)
            if let jpegData = bitmap.representation(using: .jpeg, properties: [NSBitmapImageRep.PropertyKey.compressionFactor: NSNumber(value: 0.6)]) {
                self.latestCameraFrameBase64 = jpegData.base64EncodedString()
            }
        }
    }

    // MARK: - Remote Inbound Streams (e.g. Android -> Mac)
    public var onRemoteFrameReceived: ((_ streamId: String, _ image: NSImage, _ width: Int, _ height: Int) -> Void)?
    public var onRemoteCameraFrameReceived: ((_ streamId: String, _ image: NSImage, _ width: Int, _ height: Int) -> Void)?
    public var onRemoteScreenFrameReceived: ((_ streamId: String, _ image: NSImage, _ width: Int, _ height: Int) -> Void)?
    public var onRemoteStreamStarted: ((_ streamId: String, _ streamType: String) -> Void)?
    public var onRemoteStreamStopped: ((_ streamId: String) -> Void)?

    public func registerActiveStream(streamId: String, type: StreamType) {
        activeStreams[streamId] = type
    }

    public func getStreamType(streamId: String) -> StreamType? {
        return activeStreams[streamId]
    }

    public func handleIncomingStreamFrame(streamId: String, sequence: Int64, format: String, width: Int, height: Int, data: String) {
        guard let frameData = Data(base64Encoded: data, options: .ignoreUnknownCharacters),
              let image = NSImage(data: frameData) else {
            return
        }

        let type = activeStreams[streamId]

        DispatchQueue.main.async { [weak self] in
            self?.onRemoteFrameReceived?(streamId, image, width, height)
            if type == .screen || type == .desktopMode {
                self?.onRemoteScreenFrameReceived?(streamId, image, width, height)
            } else if type == .camera {
                self?.onRemoteCameraFrameReceived?(streamId, image, width, height)
            } else {
                // If stream type was not pre-registered, notify both handlers
                self?.onRemoteCameraFrameReceived?(streamId, image, width, height)
                self?.onRemoteScreenFrameReceived?(streamId, image, width, height)
            }
        }
    }

    // MARK: - Stream Lifecycle
    public func handleIncomingStreamRequest(streamId: String, streamType: String, options: [String: String]?) -> ProtocolMessage {
        guard let type = StreamType(rawValue: streamType) else {
            return .streamResponse(streamId: streamId, streamType: streamType, accepted: false, reason: "Unsupported stream type")
        }

        let isRemoteSendingFeed = options?["direction"] == "outgoing" || options?["sender"] == "android"

        switch type {
        case .camera:
            guard isCameraSharingEnabled else {
                return .streamResponse(streamId: streamId, streamType: streamType, accepted: false, reason: "Camera Sharing is turned OFF in Settings")
            }
            if isRemoteSendingFeed {
                activeStreams[streamId] = type
                DispatchQueue.main.async { [weak self] in
                    self?.onRemoteStreamStarted?(streamId, streamType)
                }
                return .streamResponse(streamId: streamId, streamType: streamType, accepted: true, reason: nil)
            }
            checkCameraPermission()
            startCameraSessionIfNeeded()
        case .screen:
            guard isScreenSharingEnabled else {
                return .streamResponse(streamId: streamId, streamType: streamType, accepted: false, reason: "Screen Sharing is turned OFF in Settings")
            }
            if isRemoteSendingFeed {
                activeStreams[streamId] = type
                DispatchQueue.main.async { [weak self] in
                    self?.onRemoteStreamStarted?(streamId, streamType)
                }
                return .streamResponse(streamId: streamId, streamType: streamType, accepted: true, reason: nil)
            }
            checkScreenCapturePermission()
        case .desktopMode:
            guard isDesktopModeEnabled else {
                return .streamResponse(streamId: streamId, streamType: streamType, accepted: false, reason: "Desktop Mode (beta) is turned OFF in Settings")
            }
            checkAccessibilityPermission()
        }

        activeStreams[streamId] = type
        startStreamingLoop(streamId: streamId, type: type)
        return .streamResponse(streamId: streamId, streamType: streamType, accepted: true, reason: nil)
    }

    public func handleDesktopInput(streamId: String, eventType: String, x: Double, y: Double, button: Int, key: String?) {
        guard isDesktopModeEnabled else { return }

        // Security check: Check Accessibility permissions for input simulation and prompt if needed
        let isTrusted = checkAccessibilityPermission()
        guard isTrusted else {
            print("[DexoLink Desktop Mode] Remote input ignored: macOS Accessibility permissions not granted.")
            return
        }

        let mainDisplayBounds = CGDisplayBounds(CGMainDisplayID())
        let targetX = mainDisplayBounds.origin.x + (mainDisplayBounds.width * CGFloat(x))
        let targetY = mainDisplayBounds.origin.y + (mainDisplayBounds.height * CGFloat(y))
        let point = CGPoint(x: targetX, y: targetY)

        switch eventType {
        case "mouse_move":
            let event = CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: point, mouseButton: .left)
            event?.post(tap: .cghidEventTap)
        case "mouse_down":
            let mouseType: CGEventType = button == 1 ? .rightMouseDown : .leftMouseDown
            let mouseButton: CGMouseButton = button == 1 ? .right : .left
            let event = CGEvent(mouseEventSource: nil, mouseType: mouseType, mouseCursorPosition: point, mouseButton: mouseButton)
            event?.post(tap: .cghidEventTap)
        case "mouse_up":
            let mouseType: CGEventType = button == 1 ? .rightMouseUp : .leftMouseUp
            let mouseButton: CGMouseButton = button == 1 ? .right : .left
            let event = CGEvent(mouseEventSource: nil, mouseType: mouseType, mouseCursorPosition: point, mouseButton: mouseButton)
            event?.post(tap: .cghidEventTap)
        default:
            break
        }
    }

    private func startStreamingLoop(streamId: String, type: StreamType) {
        var seq: Int64 = 0
        let timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] t in
            guard let self = self, self.activeStreams[streamId] != nil else {
                t.invalidate()
                return
            }

            seq += 1
            // Generate optimized JPEG frame
            let frameBase64 = self.captureCurrentFrame(for: type)
            if !frameBase64.isEmpty {
                self.onFrameGenerated?(streamId, seq, frameBase64)
                let msg = ProtocolMessage.streamFrame(streamId: streamId, sequence: seq, format: "jpeg", width: 640, height: 480, data: frameBase64)
                self.connectionManager?.send(message: msg)
            }
        }
        streamTimers[streamId] = timer
    }

    private func captureCurrentFrame(for type: StreamType) -> String {
        switch type {
        case .screen, .desktopMode:
            if let imageRef = CGDisplayCreateImage(CGMainDisplayID()) {
                let bitmapRep = NSBitmapImageRep(cgImage: imageRef)
                if let jpegData = bitmapRep.representation(using: .jpeg, properties: [NSBitmapImageRep.PropertyKey.compressionFactor: NSNumber(value: 0.6)]) {
                    return jpegData.base64EncodedString()
                }
            }
            return generateFallbackBanner(title: "DexoLink Screen Sharing", subtitle: "Mirroring macOS Display")
        case .camera:
            if let liveFrame = latestCameraFrameBase64, !liveFrame.isEmpty {
                return liveFrame
            }
            return generateFallbackBanner(title: "DexoLink Camera Feed", subtitle: "Live Camera Stream Active")
        }
    }

    private func generateFallbackBanner(title: String, subtitle: String) -> String {
        let size = NSSize(width: 640, height: 480)
        let image = NSImage(size: size)
        image.lockFocus()

        NSColor(calibratedRed: 0.08, green: 0.11, blue: 0.18, alpha: 1.0).setFill()
        NSRect(origin: .zero, size: size).fill()

        let pStyle = NSMutableParagraphStyle()
        pStyle.alignment = .center

        let titleAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.boldSystemFont(ofSize: 24),
            .foregroundColor: NSColor.white,
            .paragraphStyle: pStyle
        ]
        let subAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 14),
            .foregroundColor: NSColor(calibratedRed: 0.6, green: 0.7, blue: 0.85, alpha: 1.0),
            .paragraphStyle: pStyle
        ]

        let titleRect = NSRect(x: 20, y: 250, width: size.width - 40, height: 40)
        let subRect = NSRect(x: 20, y: 210, width: size.width - 40, height: 30)

        (title as NSString).draw(in: titleRect, withAttributes: titleAttrs)
        (subtitle as NSString).draw(in: subRect, withAttributes: subAttrs)

        image.unlockFocus()

        if let tiff = image.tiffRepresentation,
           let rep = NSBitmapImageRep(data: tiff),
           let jpegData = rep.representation(using: .jpeg, properties: [NSBitmapImageRep.PropertyKey.compressionFactor: NSNumber(value: 0.6)]) {
            return jpegData.base64EncodedString()
        }
        return ""
    }

    public func stopStream(streamId: String) {
        streamTimers[streamId]?.invalidate()
        streamTimers.removeValue(forKey: streamId)
        activeStreams.removeValue(forKey: streamId)
        if activeStreams.values.filter({ $0 == .camera }).isEmpty {
            stopCameraSession()
        }
        DispatchQueue.main.async { [weak self] in
            self?.onRemoteStreamStopped?(streamId)
        }
    }

    public func stopStreamType(_ type: StreamType) {
        for (id, streamType) in activeStreams where streamType == type {
            stopStream(streamId: id)
        }
    }

    public func stopAllStreams() {
        for (_, timer) in streamTimers {
            timer.invalidate()
        }
        streamTimers.removeAll()
        activeStreams.removeAll()
        stopCameraSession()
    }
}
