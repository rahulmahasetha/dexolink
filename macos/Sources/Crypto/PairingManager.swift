import Foundation
import CryptoKit

public enum PairingState {
    case idle
    case waitingForPin(pin: String)
    case verified
    case failed
}

public protocol PairingManagerDelegate: AnyObject {
    func pairingManager(_ manager: PairingManager, stateDidChange state: PairingState)
}

public class PairingManager {
    public weak var delegate: PairingManagerDelegate?
    public private(set) var state: PairingState = .idle {
        didSet {
            delegate?.pairingManager(self, stateDidChange: state)
        }
    }
    
    private var pendingPeerKey: String?
    private var pendingPeerId: String?
    private var pendingPeerName: String?
    
    public init() {}
    
    public func initiatePairing(peerId: String, peerName: String, peerKeyBase64: String) {
        self.pendingPeerId = peerId
        self.pendingPeerName = peerName
        self.pendingPeerKey = peerKeyBase64
        
        let localKey = CryptoService.shared.publicKeyBase64
        let pin = generatePin(key1: localKey, key2: peerKeyBase64)
        
        state = .waitingForPin(pin: pin)
    }
    
    public func confirmPairing() {
        guard let peerId = pendingPeerId,
              let peerName = pendingPeerName,
              let peerKey = pendingPeerKey else {
            state = .failed
            return
        }
        
        let device = TrustedDevice(
            deviceId: peerId,
            deviceName: peerName,
            publicKeyBase64: peerKey,
            pairedAt: Date()
        )
        
        TrustStore.shared.addTrustedDevice(device)
        state = .verified
        
        resetPending()
    }
    
    public func rejectPairing() {
        state = .failed
        resetPending()
    }
    
    private func resetPending() {
        pendingPeerKey = nil
        pendingPeerId = nil
        pendingPeerName = nil
    }
    
    /// Generates a 6-digit PIN by hashing both public keys.
    /// Since both sides do this with the same keys (sorted), they will generate the same PIN.
    private func generatePin(key1: String, key2: String) -> String {
        let keys = [key1, key2].sorted().joined(separator: ":")
        let hash = SHA256.hash(data: Data(keys.utf8))
        
        // Extract 4 bytes to form an integer
        let hashBytes = Array(hash)
        let value = UInt32(hashBytes[0]) << 24 |
                    UInt32(hashBytes[1]) << 16 |
                    UInt32(hashBytes[2]) << 8 |
                    UInt32(hashBytes[3])
        
        // Modulo 1,000,000 to get a 6-digit PIN
        let pin = String(format: "%06d", value % 1000000)
        return pin
    }
}
