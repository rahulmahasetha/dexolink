import Foundation
import Security
import CryptoKit

public struct TrustedDevice: Codable, Equatable {
    public let deviceId: String
    public let deviceName: String
    public let publicKeyBase64: String
    public let pairedAt: Date

    public init(deviceId: String, deviceName: String, publicKeyBase64: String, pairedAt: Date) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.publicKeyBase64 = publicKeyBase64
        self.pairedAt = pairedAt
    }
}

public class TrustStore {
    public static let shared = TrustStore()
    private let queue = DispatchQueue(label: "com.macdroid.truststore")
    
    // In a production app, we would store this securely in the Keychain.
    // For simplicity in this implementation, we store it in UserDefaults
    // because the public keys are not sensitive, they just identify the peer.
    private let defaultsKey = "com.macdroid.trustedDevices"
    
    public init() {}
    
    private func loadDevicesInternal() -> [TrustedDevice] {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey),
              let devices = try? JSONDecoder().decode([TrustedDevice].self, from: data) else {
            return []
        }
        return devices
    }

    public func getTrustedDevices() -> [TrustedDevice] {
        return queue.sync {
            return loadDevicesInternal()
        }
    }
    
    public func isTrusted(deviceId: String) -> Bool {
        return queue.sync {
            let devices = loadDevicesInternal()
            return devices.contains(where: { $0.deviceId == deviceId })
        }
    }
    
    public func addTrustedDevice(_ device: TrustedDevice) {
        queue.sync {
            var devices = loadDevicesInternal()
            devices.removeAll(where: { $0.deviceId == device.deviceId })
            devices.append(device)
            if let data = try? JSONEncoder().encode(devices) {
                UserDefaults.standard.set(data, forKey: defaultsKey)
            }
        }
    }
    
    public func removeTrustedDevice(deviceId: String) {
        queue.sync {
            var devices = loadDevicesInternal()
            devices.removeAll(where: { $0.deviceId == deviceId })
            if let data = try? JSONEncoder().encode(devices) {
                UserDefaults.standard.set(data, forKey: defaultsKey)
            }
        }
    }
}
