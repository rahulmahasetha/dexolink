import Foundation
import AppKit
import Networking
import CryptoKit

public enum ClipboardDirection {
    case disabled
    case sendOnly
    case receiveOnly
    case bidirectional
}

public struct ClipboardHistoryItem: Identifiable, Codable, Equatable {
    public let id: String
    public let text: String
    public let timestamp: Date
    public let isOutgoing: Bool

    public init(id: String = UUID().uuidString, text: String, timestamp: Date = Date(), isOutgoing: Bool) {
        self.id = id
        self.text = text
        self.timestamp = timestamp
        self.isOutgoing = isOutgoing
    }
}

public protocol ClipboardManagerDelegate: AnyObject {
    func clipboardManager(_ manager: ClipboardManager, didReceiveText text: String)
    func clipboardManager(_ manager: ClipboardManager, didSendText text: String)
    func clipboardManagerDidUpdateHistory(_ manager: ClipboardManager)
}

public extension ClipboardManagerDelegate {
    func clipboardManager(_ manager: ClipboardManager, didSendText text: String) {}
    func clipboardManagerDidUpdateHistory(_ manager: ClipboardManager) {}
}

public class ClipboardManager {
    public static let shared = ClipboardManager()
    public weak var delegate: ClipboardManagerDelegate?
    
    private let pasteboard = NSPasteboard.general
    private var lastChangeCount: Int
    private var lastSentHash: String = ""
    private var lastReceivedHash: String = ""
    private var timer: Timer?
    private let historyKey = "com.dexolink.clipboard_history"
    public private(set) var history: [ClipboardHistoryItem] = []
    
    public var direction: ClipboardDirection = .disabled {
        didSet {
            if direction == .sendOnly || direction == .bidirectional {
                startMonitoring()
            } else {
                stopMonitoring()
            }
        }
    }
    
    private var sequenceNumber: Int = 0
    private var connectionManager: ConnectionManager?
    
    private var appActivationObserver: NSObjectProtocol?
    private var idleCheckCount: Int = 0
    
    public init() {
        self.lastChangeCount = pasteboard.changeCount
        loadHistory()
    }
    
    private func loadHistory() {
        guard let data = UserDefaults.standard.data(forKey: historyKey),
              let list = try? JSONDecoder().decode([ClipboardHistoryItem].self, from: data) else {
            return
        }
        self.history = list
    }

    private func persistHistory() {
        if let data = try? JSONEncoder().encode(Array(history.prefix(50))) {
            UserDefaults.standard.set(data, forKey: historyKey)
        }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.clipboardManagerDidUpdateHistory(self)
        }
    }

    public func addToHistory(text: String, isOutgoing: Bool) {
        if let first = history.first, first.text == text {
            return
        }
        let item = ClipboardHistoryItem(text: text, isOutgoing: isOutgoing)
        history.insert(item, at: 0)
        if history.count > 50 {
            history = Array(history.prefix(50))
        }
        persistHistory()
    }

    // MARK: - History Deletion (Safe: does not delete disk files or disconnect)
    public func deleteItem(id: String) {
        history.removeAll(where: { $0.id == id })
        persistHistory()
        print("🗑️ Removed clipboard item \(id) from history")
    }

    public func deleteItems(ids: Set<String>) {
        history.removeAll(where: { ids.contains($0.id) })
        persistHistory()
        print("🗑️ Removed \(ids.count) clipboard items from history")
    }

    public func clearHistory() {
        history.removeAll()
        persistHistory()
        print("🗑️ Cleared all clipboard history")
    }
    
    public func setConnectionManager(_ manager: ConnectionManager?) {
        self.connectionManager = manager
    }
    
    private func startMonitoring() {
        self.lastChangeCount = pasteboard.changeCount
        
        // 1. Event-driven observer: check clipboard immediately whenever user switches applications
        if appActivationObserver == nil {
            appActivationObserver = NSWorkspace.shared.notificationCenter.addObserver(
                forName: NSWorkspace.didActivateApplicationNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.idleCheckCount = 0
                self?.scheduleAdaptiveTimer(fast: true)
                self?.checkForChanges()
            }
        }

        // 2. Schedule adaptive timer
        scheduleAdaptiveTimer(fast: true)
    }
    
    private func stopMonitoring() {
        timer?.invalidate()
        timer = nil
        if let observer = appActivationObserver {
            NSWorkspace.shared.notificationCenter.removeObserver(observer)
            appActivationObserver = nil
        }
    }

    private func scheduleAdaptiveTimer(fast: Bool) {
        timer?.invalidate()
        let interval: TimeInterval = fast ? 1.0 : 3.0
        let tolerance: TimeInterval = fast ? 0.3 : 0.8

        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.direction == .sendOnly || self.direction == .bidirectional else { return }
            let t = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                let hadChange = self.checkForChanges()
                if hadChange {
                    self.idleCheckCount = 0
                    if !fast {
                        self.scheduleAdaptiveTimer(fast: true)
                    }
                } else {
                    self.idleCheckCount += 1
                    if fast && self.idleCheckCount > 10 {
                        // Throttle to 3s when idle to conserve battery
                        self.scheduleAdaptiveTimer(fast: false)
                    }
                }
            }
            t.tolerance = tolerance
            self.timer = t
        }
    }
    
    @discardableResult
    private func checkForChanges() -> Bool {
        guard direction == .sendOnly || direction == .bidirectional else { return false }
        guard connectionManager?.state.isConnected == true else { return false }
        
        let currentCount = pasteboard.changeCount
        if currentCount != lastChangeCount {
            lastChangeCount = currentCount
            
            if let text = pasteboard.string(forType: .string), !text.isEmpty {
                // Reject extremely large clipboard texts (> 1MB)
                let textData = Data(text.utf8)
                if textData.count > 1024 * 1024 {
                    return false
                }
                
                let hash = computeHash(text)
                
                // Duplicate detection / Loop prevention
                if hash != lastSentHash && hash != lastReceivedHash {
                    lastSentHash = hash
                    sendClipboardText(text)
                    return true
                }
            }
        }
        return false
    }
    
    private func sendClipboardText(_ text: String) {
        sequenceNumber += 1
        print("📤 [macOS Clipboard Detect] Local pasteboard copied: \"\(text.prefix(35))...\" (\(text.count) chars, seq: \(sequenceNumber))")
        print("📤 [macOS Clipboard Send] Transmitting to Android (mode: \(direction))...")
        addToHistory(text: text, isOutgoing: true)
        let message = ProtocolMessage.clipboardPush(sequenceNumber: sequenceNumber, content: text)
        connectionManager?.send(message: message)
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.clipboardManager(self, didSendText: text)
        }
    }
    
    public func handleMessage(_ message: ProtocolMessage) {
        switch message {
        case let .clipboardPush(seq, content):
            print("📥 [macOS Clipboard Receive] Received from Android (seq: \(seq), chars: \(content.count), current mode: \(direction))")
            guard direction == .receiveOnly || direction == .bidirectional else {
                print("🚫 [macOS Clipboard Sync] Dropped push: direction is \(direction), reverse sync prohibited")
                let ack = ProtocolMessage.clipboardAck(sequenceNumber: seq)
                self.connectionManager?.send(message: ack)
                return
            }
            
            let hash = computeHash(content)
            
            // Loop prevention: only set pasteboard if it's new
            if hash != lastReceivedHash && hash != lastSentHash {
                lastReceivedHash = hash
                addToHistory(text: content, isOutgoing: false)
                
                DispatchQueue.main.async {
                    self.pasteboard.clearContents()
                    let success = self.pasteboard.setString(content, forType: .string)
                    self.lastChangeCount = self.pasteboard.changeCount
                    
                    if success {
                        print("✅ [macOS Clipboard Applied] Successfully wrote text to NSPasteboard: \"\(content.prefix(35))...\"")
                    } else {
                        print("❌ [macOS Clipboard Error] NSPasteboard.setString returned false")
                    }
                    
                    self.delegate?.clipboardManager(self, didReceiveText: content)
                    
                    // Send ACK
                    let ack = ProtocolMessage.clipboardAck(sequenceNumber: seq)
                    self.connectionManager?.send(message: ack)
                }
            } else {
                print("🔁 [macOS Clipboard Loop Prevention] Ignored duplicate/echo text (hash: \(hash.prefix(8)))")
                // Acknowledge receipt even if duplicate to avoid peer retries
                let ack = ProtocolMessage.clipboardAck(sequenceNumber: seq)
                self.connectionManager?.send(message: ack)
            }
            
        case let .clipboardAck(seq):
            print("📬 [macOS Clipboard ACK] Push acknowledged by peer (seq: \(seq))")
            
        default:
            break
        }
    }
    
    private func computeHash(_ text: String) -> String {
        let hash = SHA256.hash(data: Data(text.utf8))
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}
