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

public class StreamManager: NSObject {
    public static let shared = StreamManager()

    public var isCameraSharingEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isCameraSharingEnabled, forKey: "dexolink_camera_sharing_enabled")
            if !isCameraSharingEnabled {
                stopStreamType(.camera)
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

    // MARK: - Stream Lifecycle
    public func handleIncomingStreamRequest(streamId: String, streamType: String, options: [String: String]?) -> ProtocolMessage {
        guard let type = StreamType(rawValue: streamType) else {
            return .streamResponse(streamId: streamId, streamType: streamType, accepted: false, reason: "Unsupported stream type")
        }

        switch type {
        case .camera:
            guard isCameraSharingEnabled else {
                return .streamResponse(streamId: streamId, streamType: streamType, accepted: false, reason: "Camera Sharing is turned OFF in Settings")
            }
        case .screen:
            guard isScreenSharingEnabled else {
                return .streamResponse(streamId: streamId, streamType: streamType, accepted: false, reason: "Screen Sharing is turned OFF in Settings")
            }
        case .desktopMode:
            guard isDesktopModeEnabled else {
                return .streamResponse(streamId: streamId, streamType: streamType, accepted: false, reason: "Desktop Mode (beta) is turned OFF in Settings")
            }
        }

        activeStreams[streamId] = type
        startStreamingLoop(streamId: streamId, type: type)
        return .streamResponse(streamId: streamId, streamType: streamType, accepted: true, reason: nil)
    }

    public func handleDesktopInput(streamId: String, eventType: String, x: Double, y: Double, button: Int, key: String?) {
        guard isDesktopModeEnabled else { return }

        // Security check: Check Accessibility permissions for input simulation
        let options: NSDictionary = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: false]
        let isTrusted = AXIsProcessTrustedWithOptions(options)
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
                try? self.connectionManager?.send(message: msg)
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
            return ""
        case .camera:
            // High-efficiency JPEG placeholder/sample frame
            return "data:image/jpeg;base64,camera_stream_active"
        }
    }

    public func stopStream(streamId: String) {
        streamTimers[streamId]?.invalidate()
        streamTimers.removeValue(forKey: streamId)
        activeStreams.removeValue(forKey: streamId)
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
    }
}
