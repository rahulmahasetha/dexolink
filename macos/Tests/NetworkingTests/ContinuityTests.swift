import XCTest
@testable import Networking
@testable import Core

final class ContinuityTests: XCTestCase {
    var codec: ProtocolCodec!

    override func setUp() {
        super.setUp()
        codec = ProtocolCodec()
    }

    func testContinuityActivityProperties() {
        let activity = ContinuityActivity(
            activityId: "act-123",
            activityType: "pdf",
            title: "DexoLink Manual.pdf",
            subtitle: "Page 4",
            url: "https://example.com/manual.pdf",
            contentPayload: nil,
            position: 4,
            progress: 0.25,
            timestamp: 1700000000000,
            sourceDevice: "MacBook Pro"
        )

        XCTAssertEqual(activity.activityId, "act-123")
        XCTAssertEqual(activity.activityType, "pdf")
        XCTAssertEqual(activity.position, 4)
        XCTAssertEqual(activity.progress, 0.25)
        XCTAssertTrue(activity.displaySubtitle.contains("Page 4"))
    }

    func testEncodeDecodeContinuityActivityMessage() throws {
        let msg = ProtocolMessage.continuityActivity(
            activityId: "act-456",
            activityType: "webpage",
            title: "Apple Developer",
            subtitle: "developer.apple.com",
            url: "https://developer.apple.com/macos",
            contentPayload: nil,
            position: 0,
            progress: 0.5,
            timestamp: 1700000000000,
            sourceDevice: "Pixel 8"
        )

        let data = try codec.encode(message: msg)
        XCTAssertGreaterThan(data.count, 4)

        let decoded = try codec.decodePayload(from: data)
        XCTAssertNotNil(decoded)
        let decodedMsg = try codec.decodeMessage(from: decoded!.payload)
        XCTAssertEqual(decodedMsg, msg)
    }

    func testEncodeDecodeContinuityAckAndDismiss() throws {
        let ackMsg = ProtocolMessage.continuityAck(activityId: "act-789", accepted: true)
        let ackData = try codec.encode(message: ackMsg)
        let decodedAckPayload = try codec.decodePayload(from: ackData)
        XCTAssertEqual(try codec.decodeMessage(from: decodedAckPayload!.payload), ackMsg)

        let dismissMsg = ProtocolMessage.continuityDismiss(activityId: "act-789")
        let dismissData = try codec.encode(message: dismissMsg)
        let decodedDismissPayload = try codec.decodePayload(from: dismissData)
        XCTAssertEqual(try codec.decodeMessage(from: decodedDismissPayload!.payload), dismissMsg)

        let stateMsg = ProtocolMessage.continuityState(enabled: true)
        let stateData = try codec.encode(message: stateMsg)
        let decodedStatePayload = try codec.decodePayload(from: stateData)
        XCTAssertEqual(try codec.decodeMessage(from: decodedStatePayload!.payload), stateMsg)
    }

    func testAdaptersMatchActivityTypes() {
        let webAdapter = WebContinuityAdapter()
        let pdfAdapter = PdfContinuityAdapter()
        let presAdapter = PresentationContinuityAdapter()
        let docAdapter = DocumentContinuityAdapter()

        let webActivity = ContinuityActivity(activityType: "webpage", title: "Web", url: "https://example.com")
        XCTAssertTrue(webAdapter.canHandle(activity: webActivity))
        XCTAssertFalse(pdfAdapter.canHandle(activity: webActivity))

        let pdfActivity = ContinuityActivity(activityType: "pdf", title: "Doc.pdf", url: "https://example.com/file.pdf", position: 5)
        XCTAssertTrue(pdfAdapter.canHandle(activity: pdfActivity))
        XCTAssertFalse(webAdapter.canHandle(activity: pdfActivity))

        let presActivity = ContinuityActivity(activityType: "presentation", title: "Slides", position: 3)
        XCTAssertTrue(presAdapter.canHandle(activity: presActivity))

        let docActivity = ContinuityActivity(activityType: "document", title: "Spec", position: 1)
        XCTAssertTrue(docAdapter.canHandle(activity: docActivity))
    }
}
