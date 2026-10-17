import Foundation
import CryptoKit
import Security

public class CryptoService {
    public static let shared = CryptoService()
    
    private let keyTag = "com.macdroid.identity.privatekey"
    private var privateKey: Curve25519.Signing.PrivateKey
    
    public init() {
        if let storedKey = Self.loadKey(tag: keyTag) {
            self.privateKey = storedKey
        } else {
            let newKey = Curve25519.Signing.PrivateKey()
            self.privateKey = newKey
            Self.saveKey(newKey, tag: keyTag)
        }
    }
    
    public var publicKey: Curve25519.Signing.PublicKey {
        return privateKey.publicKey
    }
    
    public var deviceId: String {
        let pubKeyData = publicKey.rawRepresentation
        let hash = SHA256.hash(data: pubKeyData)
        return hash.prefix(8).map { String(format: "%02x", $0) }.joined() // First 16 hex chars
    }
    
    public var publicKeyBase64: String {
        return publicKey.rawRepresentation.base64EncodedString()
    }
    
    // MARK: - Keychain Operations
    
    private static func saveKey(_ key: Curve25519.Signing.PrivateKey, tag: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag.data(using: .utf8)!,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecValueData as String: key.rawRepresentation
        ]
        
        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }
    
    private static func loadKey(tag: String) -> Curve25519.Signing.PrivateKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag.data(using: .utf8)!,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnData as String: true
        ]
        
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        
        guard status == errSecSuccess,
              let data = item as? Data,
              let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: data) else {
            return nil
        }
        return key
    }
    
    // Note: Creating actual self-signed certificates and integrating with SecIdentity 
    // requires using Security.framework APIs or swift-crypto, which is complex.
    // For local Network.framework TLS, we can use pre-shared keys (TLS-PSK) 
    // or rely on application-level encryption for the first pass, 
    // since macOS doesn't easily vend self-signed SecIdentity without saving to Keychain.
    // We will use standard Network.framework TLS with a generic SecIdentity 
    // or fall back to application-layer verification using Ed25519.
}
