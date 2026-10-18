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
    private let keychainService = "com.macdroid.pairing"
    private let keychainAccount = "paired_device"
    
    public init() {}
    
    private var isRunningInTests: Bool {
        return ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil ||
               ProcessInfo.processInfo.processName.contains("xctest")
    }

    private func loadDeviceInternal() -> TrustedDevice? {
        // Bypass Keychain in headless/test environments to prevent SecurityServer GUI modal prompt blocking
        if !isRunningInTests, let data = loadFromKeychain(),
           let devices = try? JSONDecoder().decode([TrustedDevice].self, from: data) {
            return devices.first
        }
        
        // Fallback to defaults
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

    public func isPaired(deviceId: String, deviceName: String? = nil) -> Bool {
        return queue.sync {
            guard let paired = loadDeviceInternal() else { return false }
            if !deviceId.isEmpty && paired.deviceId == deviceId { return true }
            if let devName = deviceName, !devName.isEmpty {
                if paired.deviceName.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(devName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame {
                    return true
                }
            }
            return false
        }
    }

    public func setPairedDevice(_ device: TrustedDevice) {
        queue.sync {
            // Strictly enforce one paired device at a time, protected in macOS Keychain
            let devices = [device]
            if let data = try? JSONEncoder().encode(devices) {
                saveToKeychain(data: data)
                UserDefaults.standard.set(data, forKey: defaultsKey)
            }
        }
    }

    public func unpairDevice() {
        queue.sync {
            deleteFromKeychain()
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
        unpairDevice()
    }
    
    // MARK: - Keychain Operations
    
    private func saveToKeychain(data: Data) {
        guard !isRunningInTests else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecValueData as String: data
        ]
        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }

    private func loadFromKeychain() -> Data? {
        guard !isRunningInTests else { return nil }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecUseAuthenticationUI as String: kSecUseAuthenticationUISkip
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data {
            return data
        }
        return nil
    }

    private func deleteFromKeychain() {
        guard !isRunningInTests else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount
        ]
        SecItemDelete(query as CFDictionary)
    }
}
