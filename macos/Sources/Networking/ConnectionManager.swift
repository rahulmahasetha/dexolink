import Foundation
import Network
import Security
import Utilities

public enum ConnectionState {
    case disconnected
    case connecting
    case connected
    case authenticated
    case transferring
    case failed(Error)

    public var isConnected: Bool {
        switch self {
        case .connected, .authenticated, .transferring:
            return true
        default:
            return false
        }
    }
}

public protocol ConnectionManagerDelegate: AnyObject {
    func connectionManager(_ manager: ConnectionManager, didChangeState state: ConnectionState)
    func connectionManager(_ manager: ConnectionManager, didReceiveMessage message: ProtocolMessage)
}

public class ConnectionManager {
    private var connection: NWConnection?
    private let codec = ProtocolCodec()
    private let queue = DispatchQueue(label: "com.macdroid.connection")
    
    public weak var delegate: ConnectionManagerDelegate?
    public private(set) var state: ConnectionState = .disconnected {
        didSet {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.delegate?.connectionManager(self, didChangeState: self.state)
            }
        }
    }
    
    public private(set) var isIncoming: Bool = false
    private var buffer = Data()
    
    public init() {}
    
    public func connect(to endpoint: NWEndpoint) {
        queue.async { [weak self] in
            guard let self = self else { return }
            
            // Clean up any existing connection without firing cancelled state update to active manager
            if let old = self.connection {
                old.stateUpdateHandler = nil
                old.cancel()
                self.connection = nil
            }
            self.buffer.removeAll()
            
            self.isIncoming = false
            self.state = .connecting
            
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.enableKeepalive = true
            tcpOptions.keepaliveIdle = 15
            tcpOptions.noDelay = true
            
            let parameters = NWParameters(tls: nil, tcp: tcpOptions)
            parameters.includePeerToPeer = true
            
            let connection = NWConnection(to: endpoint, using: parameters)
            self.connection = connection
            
            connection.stateUpdateHandler = { [weak self, weak connection] newState in
                guard let self = self, let conn = connection, self.connection === conn else {
                    return
                }
                self.handleStateUpdate(newState)
            }
            
            connection.start(queue: self.queue)
        }
    }
    
    public func accept(connection: NWConnection) {
        queue.async { [weak self, connection] in
            guard let self = self else { return }
            
            if let old = self.connection {
                old.stateUpdateHandler = nil
                old.cancel()
                self.connection = nil
            }
            self.buffer.removeAll()
            
            self.connection = connection
            self.isIncoming = true
            self.state = .connecting
            
            connection.stateUpdateHandler = { [weak self, weak connection] newState in
                guard let self = self, let conn = connection, self.connection === conn else {
                    return
                }
                self.handleStateUpdate(newState)
            }
            
            connection.start(queue: self.queue)
        }
    }
    
    public func disconnect() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.connection?.stateUpdateHandler = nil
            self.connection?.cancel()
            self.connection = nil
            self.buffer.removeAll()
            self.state = .disconnected
        }
    }
    
    public func send(message: ProtocolMessage) {
        queue.async { [weak self] in
            guard let self = self, let conn = self.connection else { return }
            do {
                let data = try self.codec.encode(message: message)
                conn.send(content: data, completion: .contentProcessed({ [weak self, weak conn] error in
                    guard let self = self, let c = conn, self.connection === c else { return }
                    if let error = error {
                        print("NWConnection send error: \(error.localizedDescription)")
                        self.state = .failed(error)
                    }
                }))
            } catch {
                print("Failed to encode message: \(error.localizedDescription)")
                self.state = .failed(error)
            }
        }
    }
    
    private func handleStateUpdate(_ state: NWConnection.State) {
        switch state {
        case .ready:
            print("NWConnection is ready")
            self.state = .connected
            receiveLoop()
        case .failed(let error):
            print("NWConnection failed: \(error.localizedDescription)")
            self.state = .failed(error)
        case .cancelled:
            print("NWConnection cancelled")
            self.state = .disconnected
        case .setup, .waiting, .preparing:
            break
        @unknown default:
            break
        }
    }
    
    private func receiveLoop() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self, weak connection] content, _, isComplete, error in
            guard let self = self, let conn = connection, self.connection === conn else {
                return
            }
            
            if let data = content, !data.isEmpty {
                self.buffer.append(data)
                self.processBuffer()
            }
            
            if let error = error {
                print("NWConnection receive error: \(error.localizedDescription)")
                self.state = .failed(error)
                return
            }
            
            if isComplete {
                print("NWConnection completed/closed by peer")
                self.state = .disconnected
                return
            }
            
            self.receiveLoop()
        }
    }
    
    private func processBuffer() {
        while let result = try? codec.decodePayload(from: buffer) {
            buffer.removeFirst(result.consumedBytes)
            
            if let message = try? codec.decodeMessage(from: result.payload) {
                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    self.delegate?.connectionManager(self, didReceiveMessage: message)
                }
            }
        }
    }
}
