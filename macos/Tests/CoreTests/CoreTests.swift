import XCTest
import AppKit
@testable import Core
import Crypto

final class CoreTests: XCTestCase {

    func testSanitizePlainText_removesZeroWidthCharacters() {
        let dirty = "Hello\u{200B} \u{FEFF}World\u{200C}!\u{200D}"
        let clean = EnhancedClipboardHelper.sanitizePlainText(dirty)
        XCTAssertEqual(clean, "Hello World!")
    }

    func testSanitizePlainText_normalizesLineBreaks() {
        let dirty = "Line 1\r\nLine 2\rLine 3\nLine 4"
        let clean = EnhancedClipboardHelper.sanitizePlainText(dirty)
        XCTAssertEqual(clean, "Line 1\nLine 2\nLine 3\nLine 4")
    }

    func testContainsHtml() {
        XCTAssertTrue(EnhancedClipboardHelper.containsHtml("<p>Hello</p>"))
        XCTAssertTrue(EnhancedClipboardHelper.containsHtml("<b>Bold</b>"))
        XCTAssertFalse(EnhancedClipboardHelper.containsHtml("Plain text without tags"))
        XCTAssertFalse(EnhancedClipboardHelper.containsHtml("2 < 3 and 5 > 4"))
    }

    func testToHtmlRepresentation_wrapsParagraphs() {
        let plain = "First line\nSecond line"
        let html = EnhancedClipboardHelper.toHtmlRepresentation(plain)
        XCTAssertEqual(html, "<p>First line</p><p>Second line</p>")
    }

    func testToHtmlRepresentation_handlesBlankLines() {
        let plain = "First line\n\nSecond line"
        let html = EnhancedClipboardHelper.toHtmlRepresentation(plain)
        XCTAssertEqual(html, "<p>First line</p><p><br></p><p>Second line</p>")
    }

    func testToHtmlRepresentation_formatsLinks() {
        let url = "https://example.com/test?param=1"
        let html = EnhancedClipboardHelper.toHtmlRepresentation(url)
        XCTAssertEqual(html, "<a href=\"https://example.com/test?param=1\">https://example.com/test?param=1</a>")
    }

    func testEnhancedClipboard_defaultsToFalse() {
        UserDefaults.standard.removeObject(forKey: "com.dexolink.enhanced_clipboard_enabled")
        let manager = ClipboardManager.shared
        XCTAssertFalse(manager.isEnhancedClipboardEnabled)
    }

    func testApplyTextToPasteboard_whenEnhancedEnabled() {
        let manager = ClipboardManager.shared
        let original = manager.isEnhancedClipboardEnabled
        defer { manager.isEnhancedClipboardEnabled = original }

        manager.isEnhancedClipboardEnabled = true
        manager.applyTextToPasteboard("Test Line 1\r\nTest Line 2\u{200B}")

        let pb = NSPasteboard.general
        let stringContent = pb.string(forType: .string)
        let htmlContent = pb.string(forType: .html)

        XCTAssertEqual(stringContent, "Test Line 1\nTest Line 2")
        XCTAssertNotNil(htmlContent)
        XCTAssertTrue(htmlContent?.contains("<p>Test Line 1</p>") == true)
    }

    func testClipboardDirectionTransitions() {
        let manager = ClipboardManager.shared
        manager.direction = .disabled
        XCTAssertEqual(manager.direction, .disabled)

        manager.direction = .bidirectional
        XCTAssertEqual(manager.direction, .bidirectional)

        manager.direction = .disabled
        XCTAssertEqual(manager.direction, .disabled)
    }

    func testClipboardHistoryItemModel() {
        let item = ClipboardHistoryItem(text: "Sample Snippet", isOutgoing: true)
        XCTAssertEqual(item.text, "Sample Snippet")
        XCTAssertTrue(item.isOutgoing)
        XCTAssertFalse(item.id.isEmpty)
    }

    func testMultiplePairedDevices_defaultsToFalse() {
        let store = TrustStore.shared
        XCTAssertFalse(store.isMultiplePairedDevicesEnabled)
    }

    func testTrustStore_multiDeviceBehaviorAndDeviceSpecificUnpair() {
        let store = TrustStore.shared
        store.isMultiplePairedDevicesEnabled = true
        defer {
            store.unpairDevice()
            store.isMultiplePairedDevicesEnabled = false
        }

        let dev1 = TrustedDevice(deviceId: "mac-test-1", deviceName: "MacBook One", publicKeyBase64: "", pairedAt: Date())
        let dev2 = TrustedDevice(deviceId: "mac-test-2", deviceName: "Pixel 8", publicKeyBase64: "", pairedAt: Date())

        store.addTrustedDevice(dev1)
        store.addTrustedDevice(dev2)

        XCTAssertTrue(store.isPaired(deviceId: "mac-test-1"))
        XCTAssertTrue(store.isPaired(deviceId: "mac-test-2"))
        XCTAssertEqual(store.getTrustedDevices().count, 2)

        // Device-specific unpairing
        store.removeTrustedDevice(deviceId: "mac-test-2")
        XCTAssertTrue(store.isPaired(deviceId: "mac-test-1"))
        XCTAssertFalse(store.isPaired(deviceId: "mac-test-2"))
        XCTAssertEqual(store.getTrustedDevices().count, 1)
    }

    func testStreamingToggles_defaultToFalse() {
        UserDefaults.standard.removeObject(forKey: "dexolink_camera_sharing_enabled")
        UserDefaults.standard.removeObject(forKey: "dexolink_screen_sharing_enabled")
        UserDefaults.standard.removeObject(forKey: "dexolink_desktop_mode_enabled")

        let streamManager = StreamManager()
        XCTAssertFalse(streamManager.isCameraSharingEnabled, "Camera Sharing must be OFF by default")
        XCTAssertFalse(streamManager.isScreenSharingEnabled, "Screen Sharing must be OFF by default")
        XCTAssertFalse(streamManager.isDesktopModeEnabled, "Desktop Mode must be OFF by default")
    }

    func testStreamManager_rejectsWhenDisabledAndAcceptsWhenEnabled() {
        let streamManager = StreamManager()
        streamManager.isCameraSharingEnabled = false
        streamManager.isScreenSharingEnabled = false
        streamManager.isDesktopModeEnabled = false

        // 1. Should reject when disabled
        let respCam = streamManager.handleIncomingStreamRequest(streamId: "cam-1", streamType: "camera", options: nil)
        if case let .streamResponse(_, _, accepted, reason) = respCam {
            XCTAssertFalse(accepted)
            XCTAssertNotNil(reason)
        } else {
            XCTFail("Expected streamResponse")
        }

        let respScreen = streamManager.handleIncomingStreamRequest(streamId: "screen-1", streamType: "screen", options: nil)
        if case let .streamResponse(_, _, accepted, _) = respScreen {
            XCTAssertFalse(accepted)
        } else {
            XCTFail("Expected streamResponse")
        }

        let respDesk = streamManager.handleIncomingStreamRequest(streamId: "desk-1", streamType: "desktop_mode", options: nil)
        if case let .streamResponse(_, _, accepted, _) = respDesk {
            XCTAssertFalse(accepted)
        } else {
            XCTFail("Expected streamResponse")
        }

        // 2. Should accept when enabled
        streamManager.isCameraSharingEnabled = true
        let respCamEnabled = streamManager.handleIncomingStreamRequest(streamId: "cam-2", streamType: "camera", options: nil)
        if case let .streamResponse(_, _, accepted, _) = respCamEnabled {
            XCTAssertTrue(accepted)
        } else {
            XCTFail("Expected streamResponse")
        }

        // 3. Turning OFF stops active streams
        streamManager.isCameraSharingEnabled = false
        streamManager.stopAllStreams()
    }

    func testRemoteInboundCameraStream_acceptsAndDecodesFrames() {
        let streamManager = StreamManager()
        streamManager.isCameraSharingEnabled = true

        let startExp = self.expectation(description: "Remote stream started")
        var startedStreamId: String? = nil
        streamManager.onRemoteStreamStarted = { streamId, type in
            startedStreamId = streamId
            startExp.fulfill()
        }

        // Test remote request (Android streaming TO Mac)
        let resp = streamManager.handleIncomingStreamRequest(
            streamId: "remote-cam-1",
            streamType: "camera",
            options: ["direction": "outgoing", "sender": "android"]
        )

        if case let .streamResponse(streamId, streamType, accepted, _) = resp {
            XCTAssertEqual(streamId, "remote-cam-1")
            XCTAssertEqual(streamType, "camera")
            XCTAssertTrue(accepted)
        } else {
            XCTFail("Expected streamResponse")
        }

        waitForExpectations(timeout: 2.0)
        XCTAssertEqual(startedStreamId, "remote-cam-1")

        // Generate a valid 2x2 test JPEG
        let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: 2,
            pixelsHigh: 2,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .calibratedRGB,
            bytesPerRow: 8,
            bitsPerPixel: 32
        )!
        let jpegData = rep.representation(using: .jpeg, properties: [:])!
        let base64String = jpegData.base64EncodedString()

        let frameExp = self.expectation(description: "Remote frame received")
        streamManager.onRemoteFrameReceived = { streamId, image, width, height in
            XCTAssertEqual(streamId, "remote-cam-1")
            XCTAssertEqual(width, 640)
            XCTAssertEqual(height, 480)
            XCTAssertNotNil(image)
            frameExp.fulfill()
        }

        streamManager.handleIncomingStreamFrame(
            streamId: "remote-cam-1",
            sequence: 1,
            format: "jpeg",
            width: 640,
            height: 480,
            data: base64String
        )

        waitForExpectations(timeout: 2.0)

        // Stop stream
        let stopExp = self.expectation(description: "Remote stream stopped")
        streamManager.onRemoteStreamStopped = { streamId in
            if streamId == "remote-cam-1" {
                stopExp.fulfill()
            }
        }
        streamManager.stopStream(streamId: "remote-cam-1")
        waitForExpectations(timeout: 2.0)
    }

    func testRemoteInboundScreenStream_acceptsAndDecodesFrames() {
        let streamManager = StreamManager.shared
        streamManager.isScreenSharingEnabled = true

        let startedExp = self.expectation(description: "Remote screen stream started")
        var startedStreamId = ""
        streamManager.onRemoteStreamStarted = { streamId, streamType in
            if streamId == "remote-screen-1" && streamType == "screen" {
                startedStreamId = streamId
                startedExp.fulfill()
            }
        }

        // Test remote request (Android / Windows / iPhone mirroring screen TO Mac)
        let resp = streamManager.handleIncomingStreamRequest(
            streamId: "remote-screen-1",
            streamType: "screen",
            options: ["direction": "outgoing", "sender": "android"]
        )

        if case let .streamResponse(streamId, streamType, accepted, _) = resp {
            XCTAssertEqual(streamId, "remote-screen-1")
            XCTAssertEqual(streamType, "screen")
            XCTAssertTrue(accepted)
        } else {
            XCTFail("Expected streamResponse")
        }

        waitForExpectations(timeout: 2.0)
        XCTAssertEqual(startedStreamId, "remote-screen-1")

        // Generate a valid 4x4 test JPEG
        let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: 4,
            pixelsHigh: 4,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .calibratedRGB,
            bytesPerRow: 16,
            bitsPerPixel: 32
        )!
        let jpegData = rep.representation(using: .jpeg, properties: [:])!
        let base64String = jpegData.base64EncodedString()

        let screenFrameExp = self.expectation(description: "Remote screen frame received")
        streamManager.onRemoteScreenFrameReceived = { streamId, image, width, height in
            XCTAssertEqual(streamId, "remote-screen-1")
            XCTAssertEqual(width, 960)
            XCTAssertEqual(height, 540)
            XCTAssertNotNil(image)
            screenFrameExp.fulfill()
        }

        streamManager.handleIncomingStreamFrame(
            streamId: "remote-screen-1",
            sequence: 1,
            format: "jpeg",
            width: 960,
            height: 540,
            data: base64String
        )

        waitForExpectations(timeout: 2.0)

        // Stop stream
        let stopExp = self.expectation(description: "Remote screen stream stopped")
        streamManager.onRemoteStreamStopped = { streamId in
            if streamId == "remote-screen-1" {
                stopExp.fulfill()
            }
        }
        streamManager.stopStream(streamId: "remote-screen-1")
        waitForExpectations(timeout: 2.0)
    }
}

