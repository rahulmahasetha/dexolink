import XCTest
@testable import Networking

final class ProtocolCodecTests: XCTestCase {
    var codec: ProtocolCodec!
    
    override func setUp() {
        super.setUp()
        codec = ProtocolCodec()
    }
    
    func testEncodeDecodeHello() throws {
        let msg = ProtocolMessage.hello(version: 1, deviceId: "abcd", deviceName: "Test Mac")
        let data = try codec.encode(message: msg)
        
        // Ensure data is at least 4 bytes length + some JSON
        XCTAssertGreaterThan(data.count, 4)
        
        let decodedResult = try codec.decodePayload(from: data)
        XCTAssertNotNil(decodedResult)
        
        if let result = decodedResult {
            XCTAssertEqual(result.consumedBytes, data.count)
            let decodedMsg = try codec.decodeMessage(from: result.payload)
            XCTAssertEqual(decodedMsg, msg)
        }
    }
    
    func testDecodeInsufficientData() throws {
        let msg = ProtocolMessage.ping
        let data = try codec.encode(message: msg)
        
        // Give only 3 bytes (incomplete length prefix)
        XCTAssertNil(try codec.decodePayload(from: data.prefix(3)))
        
        // Give length prefix but incomplete payload
        XCTAssertNil(try codec.decodePayload(from: data.prefix(data.count - 1)))
    }
    
    func testDecodeStream() throws {
        let msg1 = ProtocolMessage.ping
        let msg2 = ProtocolMessage.pong
        
        let data1 = try codec.encode(message: msg1)
        let data2 = try codec.encode(message: msg2)
        
        var streamBuffer = Data()
        streamBuffer.append(data1)
        streamBuffer.append(data2)
        
        // First message
        let result1 = try codec.decodePayload(from: streamBuffer)
        XCTAssertNotNil(result1)
        XCTAssertEqual(try codec.decodeMessage(from: result1!.payload), msg1)
        
        // Remove consumed bytes
        streamBuffer.removeFirst(result1!.consumedBytes)
        
        // Second message
        let result2 = try codec.decodePayload(from: streamBuffer)
        XCTAssertNotNil(result2)
        XCTAssertEqual(try codec.decodeMessage(from: result2!.payload), msg2)
    }

    func testEncodeDecodeStreamMessages() throws {
        let req = ProtocolMessage.streamRequest(streamId: "s-101", streamType: "camera", options: ["fps": "30"])
        let reqData = try codec.encode(message: req)
        let decReq = try codec.decodeMessage(from: codec.decodePayload(from: reqData)!.payload)
        XCTAssertEqual(decReq, req)

        let resp = ProtocolMessage.streamResponse(streamId: "s-101", streamType: "camera", accepted: true, reason: nil)
        let respData = try codec.encode(message: resp)
        let decResp = try codec.decodeMessage(from: codec.decodePayload(from: respData)!.payload)
        XCTAssertEqual(decResp, resp)

        let frame = ProtocolMessage.streamFrame(streamId: "s-101", sequence: 42, format: "jpeg", width: 640, height: 480, data: "base64Data")
        let frameData = try codec.encode(message: frame)
        let decFrame = try codec.decodeMessage(from: codec.decodePayload(from: frameData)!.payload)
        XCTAssertEqual(decFrame, frame)

        let stop = ProtocolMessage.streamStop(streamId: "s-101", reason: "user_cancelled")
        let stopData = try codec.encode(message: stop)
        let decStop = try codec.decodeMessage(from: codec.decodePayload(from: stopData)!.payload)
        XCTAssertEqual(decStop, stop)

        let input = ProtocolMessage.desktopInputEvent(streamId: "s-desk", eventType: "mouse_move", x: 0.5, y: 0.75, button: 0, key: nil)
        let inputData = try codec.encode(message: input)
        let decInput = try codec.decodeMessage(from: codec.decodePayload(from: inputData)!.payload)
        XCTAssertEqual(decInput, input)
    }
}
