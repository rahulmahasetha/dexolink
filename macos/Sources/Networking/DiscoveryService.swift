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
        setupUDPBeacon()
    }

    private func setupUDPBeacon() {
        UDPBeaconService.shared.onBeaconReceived = { [weak self] deviceId, deviceName, deviceType, host, port in
            self?.handleUDPBeacon(deviceId: deviceId, deviceName: deviceName, deviceType: deviceType, host: host, port: port)
        }
    }

    private func handleUDPBeacon(deviceId: String, deviceName: String, deviceType: String, host: String, port: Int) {
        queue.async { [weak self] in
            guard let self = self else { return }

            let isPaired = TrustStore.shared.isPaired(deviceId: deviceId, deviceName: deviceName)
            let endpoint = NWEndpoint.hostPort(host: .init(host), port: .init(integerLiteral: UInt16(port)))

            if let index = self.discoveredDevices.firstIndex(where: {
                $0.id == deviceId ||
                $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(deviceName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame ||
                ($0.host != nil && $0.host == host && $0.port == port)
            }) {
                var existing = self.discoveredDevices[index]
                existing.name = deviceName
                existing.endpoint = endpoint
                existing.isPaired = isPaired
                existing.isOnline = true
                existing.lastSeen = Date()
                existing.host = host
                existing.port = port
                existing.deviceType = deviceType
                self.discoveredDevices[index] = existing
            } else {
                let newDevice = DiscoveredDevice(
                    id: deviceId,
                    name: deviceName,
                    serviceName: nil,
                    endpoint: endpoint,
                    isPaired: isPaired,
                    isOnline: true,
                    lastSeen: Date(),
                    host: host,
                    port: port,
                    deviceType: deviceType
                )
                self.discoveredDevices.append(newDevice)
            }

            self.deduplicateDevices()
            self.ensurePairedDeviceIncluded()
            self.persistCachedDevices()

            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.updateDelegate()
            }
        }
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

        let myName = Host.current().localizedName ?? "MacBook"
        UDPBeaconService.shared.start(
            deviceId: CryptoService.shared.deviceId,
            deviceName: myName,
            deviceType: "mac",
            port: Int(self.listeningPort)
        )
    }
    
    public func stopBrowsing() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.browser?.cancel()
            self.browser = nil
            self.isBrowsing = false
            
            UDPBeaconService.shared.pauseBroadcasting()

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
                var host: String? = nil
                var port: Int? = nil
                
                if case .bonjour(let record) = result.metadata {
                    if let id = record.dictionary["deviceId"] {
                        deviceId = id
                    }
                    if let devName = record.dictionary["deviceName"] {
                        deviceName = devName
                    }
                    if let ip = record.dictionary["ip"], !ip.isEmpty {
                        host = ip
                    }
                    if let pStr = record.dictionary["port"], let p = Int(pStr), p > 0 {
                        port = p
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
                
                let isPaired = TrustStore.shared.isPaired(deviceId: deviceId, deviceName: deviceName)
                
                if let index = self.discoveredDevices.firstIndex(where: {
                    $0.id == deviceId ||
                    $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(deviceName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame ||
                    ($0.serviceName != nil && $0.serviceName == name) ||
                    (host != nil && $0.host != nil && $0.host == host && $0.port == port)
                }) {
                    var existing = self.discoveredDevices[index]
                    existing.name = deviceName
                    existing.serviceName = name
                    existing.endpoint = result.endpoint
                    existing.isPaired = isPaired
                    existing.isOnline = true
                    existing.lastSeen = Date()
                    existing.deviceType = devType
                    if let h = host { existing.host = h }
                    if let p = port { existing.port = p }
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
                        host: host,
                        port: port,
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
        
        self.deduplicateDevices()
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
            let isPaired = TrustStore.shared.isPaired(deviceId: c.id, deviceName: c.name)
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
            if !discoveredDevices.contains(where: {
                $0.id == dev.id ||
                $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(dev.name.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame ||
                ($0.host != nil && dev.host != nil && $0.host == dev.host && $0.port == dev.port)
            }) {
                discoveredDevices.append(dev)
            }
        }
        deduplicateDevices()
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
            if let idx = discoveredDevices.firstIndex(where: {
                $0.id == paired.deviceId ||
                $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(paired.deviceName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
            }) {
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
        deduplicateDevices()
    }

    public func deduplicateDevices() {
        var unique: [DiscoveredDevice] = []
        let paired = TrustStore.shared.getPairedDevice()

        for dev in discoveredDevices {
            let devMatchesPaired = (paired != nil) && (
                dev.id == paired!.deviceId ||
                dev.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(paired!.deviceName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
            )

            if let existingIdx = unique.firstIndex(where: { existing in
                let existingMatchesPaired = (paired != nil) && (
                    existing.id == paired!.deviceId ||
                    existing.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(paired!.deviceName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
                )
                return existing.id == dev.id ||
                existing.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(dev.name.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame ||
                (existing.host != nil && dev.host != nil && !existing.host!.isEmpty && existing.host == dev.host) ||
                (existingMatchesPaired && devMatchesPaired)
            }) {
                var existing = unique[existingIdx]
                if dev.isOnline { existing.isOnline = true }
                if dev.lastSeen > existing.lastSeen {
                    existing.lastSeen = dev.lastSeen
                    existing.name = dev.name
                }
                if dev.host != nil { existing.host = dev.host }
                if dev.port != nil { existing.port = dev.port }
                if dev.endpoint != nil { existing.endpoint = dev.endpoint }
                if dev.isPaired || devMatchesPaired { existing.isPaired = true }
                if let p = paired, (existing.isPaired || devMatchesPaired) {
                    existing.isPaired = true
                    existing.name = p.deviceName
                }
                unique[existingIdx] = existing
            } else {
                var newDev = dev
                if devMatchesPaired, let p = paired {
                    newDev.isPaired = true
                    newDev.name = p.deviceName
                }
                unique.append(newDev)
            }
        }

        if let paired = paired {
            var foundPaired = false
            for i in 0..<unique.count {
                let matches = unique[i].id == paired.deviceId ||
                              unique[i].name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(paired.deviceName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame ||
                              unique[i].isPaired
                if matches {
                    if !foundPaired {
                        unique[i].isPaired = true
                        unique[i].name = paired.deviceName
                        foundPaired = true
                    } else {
                        unique[i].isPaired = false
                    }
                }
            }
            if foundPaired {
                var first = true
                unique.removeAll { dev in
                    let matches = dev.id == paired.deviceId ||
                                  dev.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(paired.deviceName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame ||
                                  dev.isPaired
                    if matches {
                        if first {
                            first = false
                            return false
                        }
                        return true
                    }
                    return false
                }
            }
        }

        discoveredDevices = unique
    }

    public func removeDevice(id: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.discoveredDevices.removeAll(where: { $0.id == id })
            self.persistCachedDevices()
            DispatchQueue.main.async {
                self.updateDelegate()
            }
            print("Removed discovered device \(id)")
        }
    }
    
    // MARK: - Advertiser
    
    public func startAdvertising(port: NWEndpoint.Port = 52840) {
        do {
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.enableKeepalive = true
            tcpOptions.keepaliveIdle = 15
            tcpOptions.noDelay = true
            let parameters = NWParameters(tls: nil, tcp: tcpOptions)
            parameters.includePeerToPeer = true
            
            listener = try NWListener(using: parameters, on: port)
            var txtDict: [String: String] = [
                "deviceId": CryptoService.shared.deviceId,
                "deviceName": Host.current().localizedName ?? "MacBook"
            ]
            if let ip = DiscoveryService.getLocalIP() {
                txtDict["ip"] = ip
            }
            if port != .any {
                txtDict["port"] = "\(port.rawValue)"
            }
            let txtRecord = NWTXTRecord(txtDict)
            listener?.service = NWListener.Service(type: serviceType, domain: serviceDomain, txtRecord: txtRecord)
            
            listener?.stateUpdateHandler = { [weak self] state in
                if case .ready = state, let rawPort = self?.listener?.port?.rawValue {
                    self?.listeningPort = rawPort
                    print("MacDroid listening on port \(rawPort)")
                    let myName = Host.current().localizedName ?? "MacBook"
                    UDPBeaconService.shared.start(
                        deviceId: CryptoService.shared.deviceId,
                        deviceName: myName,
                        deviceType: "mac",
                        port: Int(rawPort)
                    )
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
    
    public static func getLocalIP() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family
            if addrFamily == UInt8(AF_INET) {
                let flags = Int32(interface.ifa_flags)
                let isUp = (flags & IFF_UP) == IFF_UP
                let isLoopback = (flags & IFF_LOOPBACK) == IFF_LOOPBACK
                if isUp && !isLoopback {
                    let name = String(cString: interface.ifa_name)
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                                   &hostname, socklen_t(hostname.count),
                                   nil, socklen_t(0), NI_NUMERICHOST) == 0 {
                        let ip = String(cString: hostname)
                        if !ip.isEmpty && ip != "127.0.0.1" {
                            if name.hasPrefix("en") {
                                return ip // Prefer active Wi-Fi or Ethernet
                            }
                            if address == nil {
                                address = ip
                            }
                        }
                    }
                }
            }
        }
        return address
    }
    
    public func stopAdvertising() {
        listener?.cancel()
        listener = nil
        if !isBrowsing {
            UDPBeaconService.shared.stop()
        }
    }
    
    private func handleNewConnection(_ connection: NWConnection) {
        print("Received incoming connection: \(connection.endpoint)")
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.discoveryService(self, didReceiveIncomingConnection: connection)
        }
    }
}
