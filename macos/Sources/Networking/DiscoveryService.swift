import Foundation
import Network
import Crypto

public struct DiscoveredDevice: Identifiable, Equatable {
    public let id: String // deviceId
    public var name: String
    public var serviceName: String?
    public var endpoint: NWEndpoint?
    public var isPaired: Bool
    public var isOnline: Bool
    public var lastSeen: Date
    public var host: String?
    public var port: Int?
    public var deviceType: String // "android", "iphone", "ipad", "mac", "unknown"

    public init(
        id: String,
        name: String,
        serviceName: String? = nil,
        endpoint: NWEndpoint?,
        isPaired: Bool,
        isOnline: Bool = true,
        lastSeen: Date = Date(),
        host: String? = nil,
        port: Int? = nil,
        deviceType: String = "unknown"
    ) {
        self.id = id
        self.name = name
        self.serviceName = serviceName
        self.endpoint = endpoint
        self.isPaired = isPaired
        self.isOnline = isOnline
        self.lastSeen = lastSeen
        self.host = host
        self.port = port
        self.deviceType = deviceType
    }
}

public protocol DiscoveryServiceDelegate: AnyObject {
    func discoveryService(_ service: DiscoveryService, didDiscover devices: [DiscoveredDevice])
    func discoveryService(_ service: DiscoveryService, didReceiveIncomingConnection connection: NWConnection)
}

public extension DiscoveryServiceDelegate {
    func discoveryService(_ service: DiscoveryService, didReceiveIncomingConnection connection: NWConnection) {}
}

public struct CachedDiscoveredDevice: Codable, Equatable {
    public let id: String
    public let name: String
    public let lastSeen: Date
    public let serviceName: String?
    public let host: String?
    public let port: Int?
    public let deviceType: String
}

public class DiscoveryService {
    public let serviceType = "_macdroid._tcp"
    public let serviceDomain = "local."
    
    private var browser: NWBrowser?
    private var listener: NWListener?
    
    private let queue = DispatchQueue(label: "com.macdroid.discovery")
    private let cacheKey = "com.dexolink.discovered_devices_cache"
    
    public weak var delegate: DiscoveryServiceDelegate?
    public private(set) var discoveredDevices: [DiscoveredDevice] = []
    public private(set) var isBrowsing: Bool = false
    public private(set) var listeningPort: UInt16 = 52840
    
    public init() {
        loadCachedDevices()
        ensurePairedDeviceIncluded()
    }
    
    // MARK: - Browser
    
    public func startBrowsing() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.startBrowsingInternal()
        }
    }
    
    private func startBrowsingInternal() {
        if isBrowsing && browser != nil {
            return
        }
        
        isBrowsing = true
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
        queue.async { [weak self] in
            guard let self = self else { return }
            self.browser?.cancel()
            self.browser = nil
            self.isBrowsing = false
            
            // Mark online devices as offline, but do NOT remove them!
            for i in 0..<self.discoveredDevices.count {
                self.discoveredDevices[i].isOnline = false
            }
            self.persistCachedDevices()
            
            DispatchQueue.main.async {
                self.updateDelegate()
            }
        }
    }
    
    public func refreshBrowsing() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.browser?.cancel()
            self.browser = nil
            self.isBrowsing = false
            self.startBrowsingInternal()
        }
    }
    
    public func refreshDeviceAvailability() {
        queue.async { [weak self] in
            guard let self = self else { return }
            let now = Date()
            var changed = false
            
            for i in 0..<self.discoveredDevices.count {
                // If a device hasn't been refreshed in over 3 minutes, mark offline
                if self.discoveredDevices[i].isOnline && now.timeIntervalSince(self.discoveredDevices[i].lastSeen) > 180 {
                    self.discoveredDevices[i].isOnline = false
                    changed = true
                }
            }
            
            if changed {
                self.persistCachedDevices()
                DispatchQueue.main.async {
                    self.updateDelegate()
                }
            }
        }
    }
    
    private func handleBrowserResults(_ results: Set<NWBrowser.Result>) {
        var seenIds = Set<String>()
        var seenNames = Set<String>()
        
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
                
                seenIds.insert(deviceId)
                seenNames.insert(deviceName)
                
                let lower = deviceName.lowercased()
                let devType: String
                if lower.contains("iphone") {
                    devType = "iphone"
                } else if lower.contains("ipad") {
                    devType = "ipad"
                } else if lower.contains("mac") {
                    devType = "mac"
                } else {
                    devType = "android"
                }
                
                let isPaired = TrustStore.shared.isTrusted(deviceId: deviceId) ||
                               TrustStore.shared.getTrustedDevices().contains(where: { $0.deviceName == deviceName })
                
                if let index = self.discoveredDevices.firstIndex(where: { $0.id == deviceId || $0.name == deviceName || ($0.serviceName != nil && $0.serviceName == name) }) {
                    var existing = self.discoveredDevices[index]
                    existing.name = deviceName
                    existing.serviceName = name
                    existing.endpoint = result.endpoint
                    existing.isPaired = isPaired
                    existing.isOnline = true
                    existing.lastSeen = Date()
                    existing.deviceType = devType
                    self.discoveredDevices[index] = existing
                } else {
                    let newDevice = DiscoveredDevice(
                        id: deviceId,
                        name: deviceName,
                        serviceName: name,
                        endpoint: result.endpoint,
                        isPaired: isPaired,
                        isOnline: true,
                        lastSeen: Date(),
                        deviceType: devType
                    )
                    self.discoveredDevices.append(newDevice)
                }
            }
        }
        
        // Mark devices not present in results as offline (do NOT remove them!)
        for i in 0..<self.discoveredDevices.count {
            let dev = self.discoveredDevices[i]
            if !seenIds.contains(dev.id) && !seenNames.contains(dev.name) {
                self.discoveredDevices[i].isOnline = false
            }
        }
        
        self.ensurePairedDeviceIncluded()
        self.persistCachedDevices()
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.updateDelegate()
        }
    }
    
    private func updateDelegate() {
        delegate?.discoveryService(self, didDiscover: discoveredDevices)
    }
    
    // MARK: - Caching & Persistence
    
    private func loadCachedDevices() {
        guard let data = UserDefaults.standard.data(forKey: cacheKey),
              let cached = try? JSONDecoder().decode([CachedDiscoveredDevice].self, from: data) else {
            return
        }
        
        for c in cached {
            let isPaired = TrustStore.shared.isTrusted(deviceId: c.id) ||
                           TrustStore.shared.getTrustedDevices().contains(where: { $0.deviceName == c.name })
            let endpoint: NWEndpoint?
            if let host = c.host, let port = c.port {
                endpoint = .hostPort(host: .init(host), port: .init(integerLiteral: UInt16(port)))
            } else if let sName = c.serviceName {
                endpoint = .service(name: sName, type: serviceType, domain: serviceDomain, interface: nil)
            } else {
                endpoint = .service(name: c.name, type: serviceType, domain: serviceDomain, interface: nil)
            }
            
            let dev = DiscoveredDevice(
                id: c.id,
                name: c.name,
                serviceName: c.serviceName,
                endpoint: endpoint,
                isPaired: isPaired,
                isOnline: false,
                lastSeen: c.lastSeen,
                host: c.host,
                port: c.port,
                deviceType: c.deviceType
            )
            if !discoveredDevices.contains(where: { $0.id == dev.id || $0.name == dev.name }) {
                discoveredDevices.append(dev)
            }
        }
    }
    
    private func persistCachedDevices() {
        let cached = discoveredDevices.map { dev -> CachedDiscoveredDevice in
            var sName: String? = nil
            var h: String? = dev.host
            var p: Int? = dev.port
            if let ep = dev.endpoint {
                if case .service(let name, _, _, _) = ep {
                    sName = name
                } else if case .hostPort(let host, let port) = ep {
                    if case .ipv4(let ip) = host {
                        h = "\(ip)"
                    }
                    p = Int(port.rawValue)
                }
            }
            return CachedDiscoveredDevice(
                id: dev.id,
                name: dev.name,
                lastSeen: dev.lastSeen,
                serviceName: sName,
                host: h,
                port: p,
                deviceType: dev.deviceType
            )
        }
        if let data = try? JSONEncoder().encode(cached) {
            UserDefaults.standard.set(data, forKey: cacheKey)
        }
    }
    
    private func ensurePairedDeviceIncluded() {
        if let paired = TrustStore.shared.getPairedDevice() {
            if let idx = discoveredDevices.firstIndex(where: { $0.id == paired.deviceId || $0.name == paired.deviceName }) {
                discoveredDevices[idx].isPaired = true
            } else {
                let pairedDev = DiscoveredDevice(
                    id: paired.deviceId,
                    name: paired.deviceName,
                    endpoint: .service(name: paired.deviceName, type: serviceType, domain: serviceDomain, interface: nil),
                    isPaired: true,
                    isOnline: false,
                    lastSeen: paired.pairedAt,
                    deviceType: paired.deviceName.lowercased().contains("iphone") ? "iphone" : "android"
                )
                discoveredDevices.insert(pairedDev, at: 0)
            }
        }
    }
    
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
