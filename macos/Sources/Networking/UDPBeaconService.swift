import Foundation
import Darwin

public final class UDPBeaconService {
    public static let shared = UDPBeaconService()
    public static let beaconPort: UInt16 = 52841

    private var socketFD: Int32 = -1
    private var readSource: DispatchSourceRead?
    private var timerSource: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "com.macdroid.udpbeacon", qos: .utility)

    public var onBeaconReceived: ((_ deviceId: String, _ deviceName: String, _ deviceType: String, _ host: String, _ port: Int) -> Void)?

    private var currentDeviceId: String = ""
    private var currentDeviceName: String = ""
    private var currentDeviceType: String = "mac"
    private var currentPort: Int = 52840
    private var isRunning: Bool = false
    private var isBroadcastingPaused: Bool = false
    private var burstCount: Int = 0
    private let maxBurstCount: Int = 4

    public init() {}

    public func start(deviceId: String, deviceName: String, deviceType: String = "mac", port: Int = 52840) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.currentDeviceId = deviceId
            self.currentDeviceName = deviceName
            self.currentDeviceType = deviceType
            self.currentPort = port

            if self.isRunning {
                // Update parameters and broadcast immediately if not paused
                if !self.isBroadcastingPaused {
                    self.sendBeacon()
                }
                return
            }

            self.setupSocketAndListener()
            self.isRunning = true
            self.isBroadcastingPaused = false
            self.burstCount = 0
            self.startAdaptiveBeaconTimer()
            self.sendBeacon()
            print("UDP Beacon Service started on port \(UDPBeaconService.beaconPort) (adaptive power-saving enabled)")
        }
    }

    public func pauseBroadcasting() {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard !self.isBroadcastingPaused else { return }
            self.isBroadcastingPaused = true
            self.timerSource?.cancel()
            self.timerSource = nil
            print("[UDP Beacon] Broadcasting paused to conserve battery")
        }
    }

    public func resumeBroadcasting(activeBurst: Bool = true) {
        queue.async { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.isBroadcastingPaused = false
            self.burstCount = activeBurst ? 0 : self.maxBurstCount
            self.startAdaptiveBeaconTimer()
            self.sendBeacon()
            print("[UDP Beacon] Broadcasting resumed (activeBurst: \(activeBurst))")
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard self.isRunning else { return }
            self.isRunning = false
            self.isBroadcastingPaused = false

            self.timerSource?.cancel()
            self.timerSource = nil

            if let source = self.readSource {
                source.cancel()
                self.readSource = nil
            } else if self.socketFD >= 0 {
                Darwin.close(self.socketFD)
                self.socketFD = -1
            }

            print("UDP Beacon Service stopped")
        }
    }

    private func setupSocketAndListener() {
        if socketFD >= 0 {
            Darwin.close(socketFD)
            socketFD = -1
        }

        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else {
            print("Failed to create UDP socket: \(errno)")
            return
        }

        var opt: Int32 = 1
        Darwin.setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, socklen_t(MemoryLayout<Int32>.size))
        Darwin.setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &opt, socklen_t(MemoryLayout<Int32>.size))
        Darwin.setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &opt, socklen_t(MemoryLayout<Int32>.size))

        var bindAddr = sockaddr_in()
        bindAddr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        bindAddr.sin_family = sa_family_t(AF_INET)
        bindAddr.sin_port = CFSwapInt16HostToBig(UDPBeaconService.beaconPort)
        bindAddr.sin_addr.s_addr = INADDR_ANY.bigEndian

        let bindResult = withUnsafePointer(to: &bindAddr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        if bindResult < 0 {
            print("Failed to bind UDP beacon socket to port \(UDPBeaconService.beaconPort): \(errno)")
            Darwin.close(fd)
            return
        }

        self.socketFD = fd

        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in
            guard let self = self else { return }
            var buffer = [UInt8](repeating: 0, count: 4096)
            var senderAddr = sockaddr_in()
            var senderAddrLen = socklen_t(MemoryLayout<sockaddr_in>.size)

            let bytesRead = withUnsafeMutablePointer(to: &senderAddr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    Darwin.recvfrom(fd, &buffer, buffer.count, 0, sa, &senderAddrLen)
                }
            }

            guard bytesRead > 0 else { return }

            var senderIP = ""
            var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            if inet_ntop(AF_INET, &senderAddr.sin_addr, &ipBuf, socklen_t(INET_ADDRSTRLEN)) != nil {
                senderIP = String(cString: ipBuf)
            }

            let data = Data(buffer[0..<bytesRead])
            self.handleIncomingPacket(data, fallbackIP: senderIP)
        }

        source.setCancelHandler { [weak self] in
            if let sfd = self?.socketFD, sfd >= 0 {
                Darwin.close(sfd)
                self?.socketFD = -1
            }
        }

        self.readSource = source
        source.resume()
    }

    private func startAdaptiveBeaconTimer() {
        timerSource?.cancel()
        guard !isBroadcastingPaused, isRunning else { return }

        let timer = DispatchSource.makeTimerSource(queue: queue)
        let isBursting = burstCount < maxBurstCount
        let interval: TimeInterval = isBursting ? 1.5 : 30.0
        // Optimized: 2s leeway in idle mode (was 500ms) for CPU coalescing
        let leeway: DispatchTimeInterval = isBursting ? .milliseconds(500) : .seconds(2)
        timer.schedule(deadline: .now() + interval, repeating: interval, leeway: leeway)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.burstCount += 1
            self.sendBeacon()
            if self.burstCount == self.maxBurstCount {
                // Transition to low-power idle discovery interval (30s)
                self.startAdaptiveBeaconTimer()
            }
        }
        self.timerSource = timer
        timer.resume()
    }

    private func sendBeacon() {
        guard socketFD >= 0, isRunning, !isBroadcastingPaused else { return }

        let localIP = DiscoveryService.getLocalIP() ?? ""
        let payloadDict: [String: Any] = [
            "dexolink": 1,
            "deviceId": currentDeviceId,
            "deviceName": currentDeviceName,
            "deviceType": currentDeviceType,
            "ip": localIP,
            "port": currentPort
        ]

        guard let jsonData = try? JSONSerialization.data(withJSONObject: payloadDict, options: []) else { return }

        // 1. Broadcast to 255.255.255.255
        var bcastAddr = sockaddr_in()
        bcastAddr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        bcastAddr.sin_family = sa_family_t(AF_INET)
        bcastAddr.sin_port = CFSwapInt16HostToBig(UDPBeaconService.beaconPort)
        bcastAddr.sin_addr.s_addr = inet_addr("255.255.255.255")

        jsonData.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.baseAddress else { return }
            withUnsafePointer(to: &bcastAddr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    _ = Darwin.sendto(socketFD, base, jsonData.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }

        // 2. Broadcast to specific interface subnet broadcast addresses
        for bcastIP in UDPBeaconService.getSubnetBroadcastAddresses() {
            var subAddr = sockaddr_in()
            subAddr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            subAddr.sin_family = sa_family_t(AF_INET)
            subAddr.sin_port = CFSwapInt16HostToBig(UDPBeaconService.beaconPort)
            subAddr.sin_addr.s_addr = inet_addr(bcastIP)

            jsonData.withUnsafeBytes { rawBuf in
                guard let base = rawBuf.baseAddress else { return }
                withUnsafePointer(to: &subAddr) { ptr in
                    ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        _ = Darwin.sendto(socketFD, base, jsonData.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }
    }

    private func handleIncomingPacket(_ data: Data, fallbackIP: String) {
        guard let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
              let _ = json["dexolink"],
              let devId = json["deviceId"] as? String,
              let devName = json["deviceName"] as? String else {
            return
        }

        // Ignore self
        if devId == currentDeviceId || devName == currentDeviceName {
            return
        }

        let devType = (json["deviceType"] as? String) ?? "android"
        let ipFromPacket = (json["ip"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let resolvedIP: String
        if !ipFromPacket.isEmpty && ipFromPacket != "0.0.0.0" && ipFromPacket != "127.0.0.1" {
            resolvedIP = ipFromPacket
        } else if !fallbackIP.isEmpty && fallbackIP != "127.0.0.1" {
            resolvedIP = fallbackIP
        } else {
            return
        }

        let port = (json["port"] as? Int) ?? 52840

        DispatchQueue.main.async { [weak self] in
            self?.onBeaconReceived?(devId, devName, devType, resolvedIP, port)
        }
    }

    public static func getSubnetBroadcastAddresses() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return result }
        defer { freeifaddrs(ifaddr) }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            guard let addr = interface.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(interface.ifa_flags)
            let isUp = (flags & IFF_UP) == IFF_UP
            let isLoopback = (flags & IFF_LOOPBACK) == IFF_LOOPBACK
            let isBroadcast = (flags & IFF_BROADCAST) == IFF_BROADCAST
            if isUp && !isLoopback && isBroadcast, let dst = interface.ifa_dstaddr {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(dst, socklen_t(dst.pointee.sa_len), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: hostname)
                    if !ip.isEmpty && ip != "127.0.0.1" && !result.contains(ip) {
                        result.append(ip)
                    }
                }
            }
        }
        return result
    }
}
