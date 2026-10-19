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
}

