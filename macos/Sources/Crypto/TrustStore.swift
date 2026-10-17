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
    private let defaultsKey = "com.macdroid.trustedDevices"
    
    public init() {}
    
    private func loadDeviceInternal() -> TrustedDevice? {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey),
              let devices = try? JSONDecoder().decode([TrustedDevice].self, from: data) else {
            return nil
        }
        return devices.first
    }

    public func getPairedDevice() -> TrustedDevice? {
        return queue.sync {
            return loadDeviceInternal()
        }
    }

    public func hasPairedDevice() -> Bool {
        return queue.sync {
            return loadDeviceInternal() != nil
        }
    }

    public func isPaired(deviceId: String) -> Bool {
        return queue.sync {
            guard let paired = loadDeviceInternal() else { return false }
            return paired.deviceId == deviceId
        }
    }

    public func setPairedDevice(_ device: TrustedDevice) {
        queue.sync {
            // Strictly enforce one paired device at a time
            let devices = [device]
            if let data = try? JSONEncoder().encode(devices) {
                UserDefaults.standard.set(data, forKey: defaultsKey)
            }
        }
    }

    public func unpairDevice() {
        queue.sync {
            UserDefaults.standard.removeObject(forKey: defaultsKey)
        }
    }

    // Backwards-compatible facade methods
    public func getTrustedDevices() -> [TrustedDevice] {
        return queue.sync {
            if let d = loadDeviceInternal() {
                return [d]
            }
            return []
        }
    }
    
    public func isTrusted(deviceId: String) -> Bool {
        return isPaired(deviceId: deviceId)
    }
    
    public func addTrustedDevice(_ device: TrustedDevice) {
        setPairedDevice(device)
    }
    
    public func removeTrustedDevice(deviceId: String) {
        queue.sync {
            if let paired = loadDeviceInternal(), paired.deviceId == deviceId {
                UserDefaults.standard.removeObject(forKey: defaultsKey)
            }
        }
    }
}
