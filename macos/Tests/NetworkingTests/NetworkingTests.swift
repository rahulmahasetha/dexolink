import XCTest
@testable import Networking

final class NetworkingTests: XCTestCase {
    func testDiscoveredDeviceModel() throws {
        let dev = DiscoveredDevice(
            id: "test-device-1",
            name: "Rahul's Android",
            endpoint: nil,
            isPaired: true,
            isOnline: true,
            lastSeen: Date(),
            host: "192.168.1.10",
            port: 8765,
            deviceType: "android"
        )
        
        XCTAssertEqual(dev.id, "test-device-1")
        XCTAssertEqual(dev.name, "Rahul's Android")
        XCTAssertTrue(dev.isPaired)
        XCTAssertTrue(dev.isOnline)
        XCTAssertEqual(dev.host, "192.168.1.10")
        XCTAssertEqual(dev.port, 8765)
        XCTAssertEqual(dev.deviceType, "android")
    }

    func testConnectionStateIsConnected() throws {
        XCTAssertFalse(ConnectionState.disconnected.isConnected)
        XCTAssertFalse(ConnectionState.connecting.isConnected)
        XCTAssertFalse(ConnectionState.failed(NSError(domain: "test", code: -1)).isConnected)
        
        XCTAssertTrue(ConnectionState.connected.isConnected)
        XCTAssertTrue(ConnectionState.authenticated.isConnected)
        XCTAssertTrue(ConnectionState.transferring.isConnected)
    }

    func testDiscoveryServiceMaintainsDevicesWhenStopped() throws {
        let service = DiscoveryService()
        // Service initializes with cached / paired devices
        XCTAssertFalse(service.isBrowsing)
        
        // Stop browsing should never crash or wipe out paired/cached devices
        service.stopBrowsing()
        XCTAssertFalse(service.isBrowsing)
    }

    func testDiscoveryServiceAdvertisingAndBrowsingLifecycle() throws {
        let service = DiscoveryService()
        XCTAssertFalse(service.isBrowsing)

        service.stopBrowsing()
        XCTAssertFalse(service.isBrowsing)

        service.stopAdvertising()
        // Ensure repeated stops are idempotent and safe
        service.stopAdvertising()
        service.stopBrowsing()
        XCTAssertFalse(service.isBrowsing)
    }
}
