import Foundation
import CoreGraphics
import AppKit
import AVFoundation
#if canImport(ScreenCaptureKit)
import ScreenCaptureKit
#endif
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

    // Screen capture cache
    private var latestScreenFrame: CapturedFrameData?

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
    public func hasScreenCaptureAccess() -> Bool {
        if #available(macOS 10.15, *) {
            return CGPreflightScreenCaptureAccess()
        }
        return true
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

    public struct CapturedFrameData {
        public let base64: String
        public let width: Int
        public let height: Int

        public init(base64: String, width: Int, height: Int) {
            self.base64 = base64
            self.width = width
            self.height = height
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
            // Generate optimized JPEG frame with dynamic proportional dimensions
            let frame = self.captureCurrentFrame(for: type)
            if !frame.base64.isEmpty {
                self.onFrameGenerated?(streamId, seq, frame.base64)
                let msg = ProtocolMessage.streamFrame(
                    streamId: streamId,
                    sequence: seq,
                    format: "jpeg",
                    width: frame.width,
                    height: frame.height,
                    data: frame.base64
                )
                self.connectionManager?.send(message: msg)
            }
        }
        streamTimers[streamId] = timer

        // On macOS 14+, run high-performance ScreenCaptureKit loop for full desktop & window capture
        if type == .screen || type == .desktopMode {
            if #available(macOS 14.0, *) {
                Task { [weak self] in
                    while let self = self, self.activeStreams[streamId] != nil {
                        if self.hasScreenCaptureAccess() {
                            if let scFrame = await self.captureScreenWithScreenCaptureKit() {
                                self.latestScreenFrame = scFrame
                            }
                        } else {
                            self.latestScreenFrame = nil
                        }
                        try? await Task.sleep(nanoseconds: 80_000_000) // ~12 FPS
                    }
                }
            }
        }
    }

    @available(macOS 14.0, *)
    private func captureScreenWithScreenCaptureKit() async -> CapturedFrameData? {
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content.displays.first else {
                return nil
            }
            let targetSize = ScreenDisplayHelper.calculateProportionalTargetSize(sourceWidth: display.width, sourceHeight: display.height, maxEdge: 1280)
            let filter = SCContentFilter(display: display, excludingWindows: [])
            let config = SCStreamConfiguration()
            config.width = targetSize.width
            config.height = targetSize.height
            config.showsCursor = true
            config.scalesToFit = true

            let cgImage = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: config)
            let bitmapRep = NSBitmapImageRep(cgImage: cgImage)
            if let jpegData = bitmapRep.representation(using: .jpeg, properties: [NSBitmapImageRep.PropertyKey.compressionFactor: NSNumber(value: 0.65)]) {
                return CapturedFrameData(base64: jpegData.base64EncodedString(), width: targetSize.width, height: targetSize.height)
            }
        } catch {
            // Handled via fallback
        }
        return nil
    }

    public func captureCurrentFrame(for type: StreamType) -> CapturedFrameData {
        switch type {
        case .screen, .desktopMode:
            if !hasScreenCaptureAccess() {
                _ = checkScreenCapturePermission()
                return generatePermissionBanner(
                    title: "Screen Recording Permission Required",
                    subtitle: "Please grant DexoLink Screen Recording permission in macOS System Settings.",
                    instruction: "Open System Settings -> Privacy & Security -> Screen Recording and enable DexoLink."
                )
            }

            if let cached = latestScreenFrame, !cached.base64.isEmpty {
                return cached
            }

            let displayId = CGMainDisplayID()
            if let imageRef = CGDisplayCreateImage(displayId) {
                let origW = imageRef.width
                let origH = imageRef.height
                let targetSize = ScreenDisplayHelper.calculateProportionalTargetSize(sourceWidth: origW, sourceHeight: origH, maxEdge: 1280)

                let finalImage: CGImage
                if origW > targetSize.width || origH > targetSize.height {
                    if let colorSpace = imageRef.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB),
                       let context = CGContext(
                            data: nil,
                            width: targetSize.width,
                            height: targetSize.height,
                            bitsPerComponent: 8,
                            bytesPerRow: targetSize.width * 4,
                            space: colorSpace,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                       ) {
                        context.interpolationQuality = .high
                        context.draw(imageRef, in: CGRect(x: 0, y: 0, width: targetSize.width, height: targetSize.height))
                        finalImage = context.makeImage() ?? imageRef
                    } else {
                        finalImage = imageRef
                    }
                } else {
                    finalImage = imageRef
                }

                let bitmapRep = NSBitmapImageRep(cgImage: finalImage)
                if let jpegData = bitmapRep.representation(using: .jpeg, properties: [NSBitmapImageRep.PropertyKey.compressionFactor: NSNumber(value: 0.6)]) {
                    return CapturedFrameData(base64: jpegData.base64EncodedString(), width: targetSize.width, height: targetSize.height)
                }
            }
            return generateFallbackBanner(title: "DexoLink Screen Sharing", subtitle: "Mirroring macOS Display")

        case .camera:
            if let liveFrame = latestCameraFrameBase64, !liveFrame.isEmpty {
                return CapturedFrameData(base64: liveFrame, width: 640, height: 480)
            }
            return generateFallbackBanner(title: "DexoLink Camera Feed", subtitle: "Live Camera Stream Active")
        }
    }

    private func generatePermissionBanner(title: String, subtitle: String, instruction: String) -> CapturedFrameData {
        let size = NSSize(width: 960, height: 540)
        let image = NSImage(size: size)
        image.lockFocus()

        // Sleek dark backdrop
        NSColor(calibratedRed: 0.06, green: 0.09, blue: 0.16, alpha: 1.0).setFill()
        NSRect(origin: .zero, size: size).fill()

        // Border frame
        let borderPath = NSBezierPath(roundedRect: NSRect(x: 20, y: 20, width: size.width - 40, height: size.height - 40), xRadius: 16, yRadius: 16)
        NSColor(calibratedRed: 0.2, green: 0.4, blue: 0.8, alpha: 0.6).setStroke()
        borderPath.lineWidth = 2
        borderPath.stroke()

        let pStyle = NSMutableParagraphStyle()
        pStyle.alignment = .center

        let headerAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.boldSystemFont(ofSize: 13),
            .foregroundColor: NSColor(calibratedRed: 0.3, green: 0.6, blue: 1.0, alpha: 1.0),
            .paragraphStyle: pStyle
        ]
        let titleAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.boldSystemFont(ofSize: 22),
            .foregroundColor: NSColor.white,
            .paragraphStyle: pStyle
        ]
        let subAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 14),
            .foregroundColor: NSColor(calibratedRed: 0.7, green: 0.75, blue: 0.85, alpha: 1.0),
            .paragraphStyle: pStyle
        ]
        let instAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 12),
            .foregroundColor: NSColor(calibratedRed: 0.45, green: 0.55, blue: 0.7, alpha: 1.0),
            .paragraphStyle: pStyle
        ]

        ("DEXOLINK DISPLAY MIRRORING" as NSString).draw(in: NSRect(x: 30, y: 350, width: size.width - 60, height: 25), withAttributes: headerAttrs)
        (title as NSString).draw(in: NSRect(x: 30, y: 300, width: size.width - 60, height: 35), withAttributes: titleAttrs)
        (subtitle as NSString).draw(in: NSRect(x: 40, y: 250, width: size.width - 80, height: 40), withAttributes: subAttrs)
        (instruction as NSString).draw(in: NSRect(x: 40, y: 190, width: size.width - 80, height: 40), withAttributes: instAttrs)

        image.unlockFocus()

        if let tiff = image.tiffRepresentation,
           let rep = NSBitmapImageRep(data: tiff),
           let jpegData = rep.representation(using: .jpeg, properties: [NSBitmapImageRep.PropertyKey.compressionFactor: NSNumber(value: 0.65)]) {
            return CapturedFrameData(base64: jpegData.base64EncodedString(), width: Int(size.width), height: Int(size.height))
        }
        return CapturedFrameData(base64: "", width: Int(size.width), height: Int(size.height))
    }

    private func generateFallbackBanner(title: String, subtitle: String) -> CapturedFrameData {
        let size = NSSize(width: 960, height: 540)
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

        let titleRect = NSRect(x: 20, y: 280, width: size.width - 40, height: 40)
        let subRect = NSRect(x: 20, y: 240, width: size.width - 40, height: 30)

        (title as NSString).draw(in: titleRect, withAttributes: titleAttrs)
        (subtitle as NSString).draw(in: subRect, withAttributes: subAttrs)

        image.unlockFocus()

        if let tiff = image.tiffRepresentation,
           let rep = NSBitmapImageRep(data: tiff),
           let jpegData = rep.representation(using: .jpeg, properties: [NSBitmapImageRep.PropertyKey.compressionFactor: NSNumber(value: 0.6)]) {
            return CapturedFrameData(base64: jpegData.base64EncodedString(), width: Int(size.width), height: Int(size.height))
        }
        return CapturedFrameData(base64: "", width: Int(size.width), height: Int(size.height))
    }

    public func stopStream(streamId: String) {
        streamTimers[streamId]?.invalidate()
        streamTimers.removeValue(forKey: streamId)
        activeStreams.removeValue(forKey: streamId)
        if activeStreams.values.filter({ $0 == .screen || $0 == .desktopMode }).isEmpty {
            latestScreenFrame = nil
        }
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
