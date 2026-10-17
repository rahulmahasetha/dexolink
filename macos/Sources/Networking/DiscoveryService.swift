import Foundation
import Network
import Crypto

public struct DiscoveredDevice: Identifiable, Equatable {
    public let id: String // deviceId
    public let name: String
    public let endpoint: NWEndpoint
    public var isPaired: Bool
}

public protocol DiscoveryServiceDelegate: AnyObject {
    func discoveryService(_ service: DiscoveryService, didDiscover devices: [DiscoveredDevice])
    func discoveryService(_ service: DiscoveryService, didReceiveIncomingConnection connection: NWConnection)
}

public extension DiscoveryServiceDelegate {
    func discoveryService(_ service: DiscoveryService, didReceiveIncomingConnection connection: NWConnection) {}
}

public class DiscoveryService {
    private let serviceType = "_macdroid._tcp"
    private let serviceDomain = "local."
    
    private var browser: NWBrowser?
    private var listener: NWListener?
    
    private let queue = DispatchQueue(label: "com.macdroid.discovery")
    
    public weak var delegate: DiscoveryServiceDelegate?
    public private(set) var discoveredDevices: [DiscoveredDevice] = []
    
    public init() {}
    
    // MARK: - Browser
    
    public func startBrowsing() {
        let parameters = NWParameters()
        parameters.includePeerToPeer = true
        
        browser = NWBrowser(for: .bonjour(type: serviceType, domain: serviceDomain), using: parameters)
        
        browser?.browseResultsChangedHandler = { [weak self] results, changes in
            guard let self = self else { return }
            self.handleBrowserResults(results)
        }
        
        browser?.start(queue: queue)
    }
    
    public func stopBrowsing() {
        browser?.cancel()
        browser = nil
        discoveredDevices.removeAll()
        updateDelegate()
    }
    
    private func handleBrowserResults(_ results: Set<NWBrowser.Result>) {
        var newDevices: [DiscoveredDevice] = []
        
        for result in results {
            if case .service(let name, _, _, _) = result.endpoint {
                var deviceId = String(name.hashValue)
                var deviceName = name
                
                if case .bonjour(let record) = result.metadata {
                    if let id = record.dictionary["deviceId"] {
                        deviceId = id
                    }
                    if let devName = record.dictionary["deviceName"] {
                        deviceName = devName
                    }
                }
                
                let isPaired = TrustStore.shared.isTrusted(deviceId: deviceId) ||
                               TrustStore.shared.getTrustedDevices().contains(where: { $0.deviceName == deviceName })
                let device = DiscoveredDevice(id: deviceId, name: deviceName, endpoint: result.endpoint, isPaired: isPaired)
                newDevices.append(device)
            }
        }
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.discoveredDevices = newDevices
            self.updateDelegate()
        }
    }
    
    private func updateDelegate() {
        delegate?.discoveryService(self, didDiscover: discoveredDevices)
    }
    
    public private(set) var listeningPort: UInt16 = 52840
    
    // MARK: - Advertiser
    
    public func startAdvertising(port: NWEndpoint.Port = 52840) {
        do {
            let tcpOptions = NWProtocolTCP.Options()
            let parameters = NWParameters(tls: nil, tcp: tcpOptions)
            parameters.includePeerToPeer = true
            
            listener = try NWListener(using: parameters, on: port)
            let txtRecord = NWTXTRecord([
                "deviceId": CryptoService.shared.deviceId,
                "deviceName": Host.current().localizedName ?? "MacBook"
            ])
            listener?.service = NWListener.Service(type: serviceType, domain: serviceDomain, txtRecord: txtRecord)
            
            listener?.stateUpdateHandler = { [weak self] state in
                if case .ready = state, let rawPort = self?.listener?.port?.rawValue {
                    self?.listeningPort = rawPort
                    print("📡 MacDroid listening on port \(rawPort)")
                }
            }
            
            listener?.newConnectionHandler = { [weak self] connection in
                // Handle new incoming connection
                self?.handleNewConnection(connection)
            }
            
            listener?.start(queue: queue)
            
        } catch {
            print("Failed to start listener on port \(port): \(error), retrying with any port")
            if port != .any {
                startAdvertising(port: .any)
            }
        }
    }
    
    public func stopAdvertising() {
        listener?.cancel()
        listener = nil
    }
    
    private func handleNewConnection(_ connection: NWConnection) {
        print("Received incoming connection: \(connection.endpoint)")
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.discoveryService(self, didReceiveIncomingConnection: connection)
        }
    }
}
