package com.macdroid.networking

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.macdroid.security.CryptoService
import com.macdroid.storage.TrustStore

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val serviceInfo: NsdServiceInfo? = null,
    val deviceId: String? = null,
    val isTrusted: Boolean = false
)

interface DiscoveryListener {
    fun onDeviceFound(device: DiscoveredDevice)
    fun onDeviceLost(name: String)
    fun onError(message: String)
}

class DiscoveryService(private val context: Context) {
    companion object {
        private const val TAG = "DiscoveryService"
        private const val SERVICE_TYPE = "_macdroid._tcp."
        val DEFAULT_SERVICE_NAME = "MacDroid-${Build.MODEL}"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val trustStore = TrustStore.getInstance(context)
    private val cryptoService = CryptoService.getInstance(context)
    private val udpBeaconService = UdpBeaconService.getInstance(context)

    init {
        udpBeaconService.onBeaconReceived = { deviceId, deviceName, deviceType, host, port ->
            handleUdpBeacon(deviceId, deviceName, deviceType, host, port)
        }
    }

    private fun handleUdpBeacon(deviceId: String, deviceName: String, deviceType: String, host: String, port: Int) {
        val isTrusted = if (deviceId.isNotBlank()) {
            trustStore.isPairedWith(deviceId, deviceName)
        } else {
            trustStore.getTrustedDevices().any { it.deviceName == deviceName }
        }

        Log.d(TAG, "UDP Beacon discovered: $deviceName @ $host:$port (trusted: $isTrusted)")
        val device = DiscoveredDevice(
            name = deviceName,
            host = host,
            port = port,
            serviceInfo = null,
            deviceId = deviceId,
            isTrusted = isTrusted
        )
        discoveredDevices[deviceName] = device
        for (l in listeners) {
            l.onDeviceFound(device)
        }
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<DiscoveryListener>()
    var advertisedPort: Int = 0
        private set

    var isBrowsing: Boolean = false
        private set

    var isAdvertising: Boolean = false
        private set

    val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()

    fun addListener(listener: DiscoveryListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            // Replay currently known devices
            for (device in discoveredDevices.values) {
                listener.onDeviceFound(device)
            }
        }
    }

    fun removeListener(listener: DiscoveryListener) {
        listeners.remove(listener)
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("MacDroidMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
                Log.d(TAG, "MulticastLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release MulticastLock: ${e.message}")
        }
    }

    // --- Advertise this device on the network ---

    fun startAdvertising(port: Int) {
        if (port <= 0) return
        advertisedPort = port
        acquireMulticastLock()

        val connPrefs = context.getSharedPreferences("macdroid_connection_prefs", Context.MODE_PRIVATE)
        val currentDeviceName = connPrefs.getString("custom_device_name", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        udpBeaconService.start(cryptoService.deviceId, currentDeviceName, port)

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = DEFAULT_SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("deviceId", cryptoService.deviceId)
            setAttribute("deviceName", currentDeviceName)
            getLocalIpAddress()?.let { setAttribute("ip", it) }
            setAttribute("port", "$port")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                isAdvertising = true
                Log.d(TAG, "Service registered: ${info.serviceName} on port $port")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isAdvertising = false
                Log.e(TAG, "Registration failed: $errorCode")
                for (l in listeners) l.onError("Failed to advertise service: error $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                isAdvertising = false
                Log.d(TAG, "Service unregistered")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service: ${e.message}")
        }
    }

    // --- Browse for other MacDroid instances (macOS) ---

    fun startBrowsing() {
        val connPrefs = context.getSharedPreferences("macdroid_connection_prefs", Context.MODE_PRIVATE)
        val currentDeviceName = connPrefs.getString("custom_device_name", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        val portToUse = if (advertisedPort > 0) advertisedPort else 52840
        udpBeaconService.start(cryptoService.deviceId, currentDeviceName, portToUse)

        if (isBrowsing) return
        acquireMulticastLock()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isBrowsing = true
                Log.d(TAG, "Discovery started for: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                // Filter out self
                if (service.serviceName.startsWith("MacDroid-Android") ||
                    service.serviceName == DEFAULT_SERVICE_NAME) {
                    return
                }
                // Resolve service
                resolveService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                val name = service.serviceName
                discoveredDevices.remove(name)
                for (l in listeners) l.onDeviceLost(name)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isBrowsing = false
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isBrowsing = false
                Log.e(TAG, "Start discovery failed: $errorCode")
                for (l in listeners) l.onError("Discovery failed: error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery: ${e.message}")
        }
    }

    private fun resolveService(service: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val resolvedHost = serviceInfo.host?.hostAddress
                    val hostFromAttr = try { serviceInfo.attributes["ip"]?.let { String(it, Charsets.UTF_8) } } catch (e: Exception) { null }
                    val host = if (!resolvedHost.isNullOrBlank() && resolvedHost != "0.0.0.0") resolvedHost else hostFromAttr ?: return
                    val portFromAttr = try { serviceInfo.attributes["port"]?.let { String(it, Charsets.UTF_8).toIntOrNull() } } catch (e: Exception) { null }
                    val port = if (serviceInfo.port > 0) serviceInfo.port else portFromAttr ?: 52840
                    val rawDeviceId = try {
                        serviceInfo.attributes["deviceId"]?.let { String(it, Charsets.UTF_8) }
                    } catch (e: Exception) { null }

                    val isTrusted = if (!rawDeviceId.isNullOrBlank()) {
                        trustStore.isTrusted(rawDeviceId)
                    } else {
                        // Check if trusted devices contain any device or matching name
                        trustStore.getTrustedDevices().any { it.deviceName == serviceInfo.serviceName }
                    }

                    Log.d(TAG, "Resolved: ${serviceInfo.serviceName} -> $host:$port (trusted: $isTrusted)")

                    val device = DiscoveredDevice(
                        name = serviceInfo.serviceName,
                        host = host,
                        port = port,
                        serviceInfo = serviceInfo,
                        deviceId = rawDeviceId,
                        isTrusted = isTrusted
                    )
                    discoveredDevices[device.name] = device
                    for (l in listeners) l.onDeviceFound(device)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service: ${e.message}")
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    fun restartBrowsing() {
        stopBrowsing()
        startBrowsing()
    }

    fun stopBrowsing() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping browsing: ${e.message}")
        }
        discoveryListener = null
        isBrowsing = false
        discoveredDevices.clear()
        releaseMulticastLock()
        udpBeaconService.pauseBroadcasting()
    }

    fun stopAdvertising() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising: ${e.message}")
        }
        registrationListener = null
        isAdvertising = false
        releaseMulticastLock()
        if (!isBrowsing) {
            udpBeaconService.stop()
        }
    }

    fun pauseForActiveConnection() {
        udpBeaconService.pauseBroadcasting()
        releaseMulticastLock()
        Log.d(TAG, "Discovery & beacons paused for active connection (power-saving mode)")
    }

    fun resumeAfterDisconnect() {
        if (isBrowsing || isAdvertising) {
            acquireMulticastLock()
            udpBeaconService.resumeBroadcasting(activeBurst = true)
            Log.d(TAG, "Discovery & beacons resumed after disconnect")
        }
    }

    fun stopAll() {
        stopBrowsing()
        stopAdvertising()
        releaseMulticastLock()
        udpBeaconService.stop()
    }
}
