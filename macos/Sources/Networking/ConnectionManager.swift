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
        isIncoming = false
        state = .connecting
        
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.enableKeepalive = true
        tcpOptions.keepaliveIdle = 15
        tcpOptions.noDelay = true
        
        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        parameters.includePeerToPeer = true
        
        let connection = NWConnection(to: endpoint, using: parameters)
        self.connection = connection
        
        connection.stateUpdateHandler = { [weak self] newState in
            self?.handleStateUpdate(newState)
        }
        
        connection.start(queue: queue)
    }
    
    public func accept(connection: NWConnection) {
        self.connection = connection
        isIncoming = true
        state = .connecting
        
        connection.stateUpdateHandler = { [weak self] newState in
            self?.handleStateUpdate(newState)
        }
        
        connection.start(queue: queue)
    }
    
    public func disconnect() {
        connection?.cancel()
        connection = nil
        state = .disconnected
    }
    
    public func send(message: ProtocolMessage) {
        queue.async {
            do {
                let data = try self.codec.encode(message: message)
                self.connection?.send(content: data, completion: .contentProcessed({ error in
                    if let error = error {
                        self.state = .failed(error)
                    }
                }))
            } catch {
                self.state = .failed(error)
            }
        }
    }
    
    private func handleStateUpdate(_ state: NWConnection.State) {
        switch state {
        case .ready:
            self.state = .connected
            receiveLoop()
        case .failed(let error):
            self.state = .failed(error)
        case .cancelled:
            self.state = .disconnected
        case .setup, .waiting, .preparing:
            break
        @unknown default:
            break
        }
    }
    
    private func receiveLoop() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] content, _, isComplete, error in
            guard let self = self else { return }
            
            if let data = content, !data.isEmpty {
                self.buffer.append(data)
                self.processBuffer()
            }
            
            if let error = error {
                self.state = .failed(error)
                return
            }
            
            if isComplete {
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
