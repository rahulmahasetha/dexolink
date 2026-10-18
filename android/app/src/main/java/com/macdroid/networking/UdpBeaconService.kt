package com.macdroid.networking

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

class UdpBeaconService private constructor(private val context: Context) {
    companion object {
        private const val TAG = "UdpBeaconService"
        const val BEACON_PORT = 52841

        @Volatile
        private var instance: UdpBeaconService? = null

        fun getInstance(context: Context): UdpBeaconService {
            return instance ?: synchronized(this) {
                instance ?: UdpBeaconService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null
    private var beaconJob: Job? = null
    private var socket: DatagramSocket? = null

    private var currentDeviceId: String = ""
    private var currentDeviceName: String = ""
    private var currentPort: Int = 52840

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var isBroadcastingPaused: Boolean = false
    private var burstCount = 0
    private val maxBurstCount = 4

    var onBeaconReceived: ((deviceId: String, deviceName: String, deviceType: String, host: String, port: Int) -> Unit)? = null

    fun start(deviceId: String, deviceName: String, port: Int = 52840) {
        currentDeviceId = deviceId
        currentDeviceName = deviceName
        currentPort = port

        if (isRunning) {
            if (!isBroadcastingPaused) {
                sendBeacon()
            }
            return
        }

        isRunning = true
        isBroadcastingPaused = false
        burstCount = 0
        Log.d(TAG, "Starting UDP Beacon Service on port $BEACON_PORT (power-saving enabled)")

        setupSocketAndListen()
        startAdaptiveBeaconLoop()
    }

    fun pauseBroadcasting() {
        isBroadcastingPaused = true
        beaconJob?.cancel()
        beaconJob = null
        Log.d(TAG, "UDP Beacon broadcasting paused (power-saving mode)")
    }

    fun resumeBroadcasting(activeBurst: Boolean = true) {
        if (!isRunning) return
        isBroadcastingPaused = false
        burstCount = if (activeBurst) 0 else maxBurstCount
        startAdaptiveBeaconLoop()
        Log.d(TAG, "UDP Beacon broadcasting resumed (activeBurst: $activeBurst)")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        isBroadcastingPaused = false
        Log.d(TAG, "Stopping UDP Beacon Service")

        listenJob?.cancel()
        listenJob = null
        beaconJob?.cancel()
        beaconJob = null

        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
    }

    private fun setupSocketAndListen() {
        listenJob?.cancel()
        listenJob = scope.launch {
            try {
                val ds = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(BEACON_PORT))
                }
                socket = ds
                Log.d(TAG, "UDP socket bound to $BEACON_PORT successfully")

                val buffer = ByteArray(4096)
                while (isActive && isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    ds.receive(packet)

                    val senderIp = packet.address?.hostAddress ?: ""
                    val jsonStr = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleIncomingPacket(jsonStr, senderIp)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "UDP listener encountered error: ${e.message}")
                }
            }
        }
    }

    private fun startAdaptiveBeaconLoop() {
        beaconJob?.cancel()
        if (isBroadcastingPaused || !isRunning) return

        beaconJob = scope.launch {
            while (isActive && isRunning && !isBroadcastingPaused) {
                sendBeacon()
                burstCount++
                val delayMs = if (burstCount < maxBurstCount) 1500L else 30000L
                delay(delayMs)
            }
        }
    }

    private fun sendBeacon() {
        if (isBroadcastingPaused) return
        val ds = socket ?: return
        if (ds.isClosed) return

        try {
            val localIp = getLocalIpAddress() ?: ""
            val json = JSONObject().apply {
                put("dexolink", 1)
                put("deviceId", currentDeviceId)
                put("deviceName", currentDeviceName)
                put("deviceType", "android")
                put("ip", localIp)
                put("port", currentPort)
            }
            val payload = json.toString().toByteArray(Charsets.UTF_8)

            // 1. Broadcast to 255.255.255.255
            try {
                val p = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), BEACON_PORT)
                ds.send(p)
            } catch (e: Exception) {
                // Ignore network unreachable on 255.255.255.255
            }

            // 2. Broadcast to specific interface subnet broadcast addresses
            for (bcast in getSubnetBroadcastAddresses()) {
                try {
                    val p = DatagramPacket(payload, payload.size, bcast, BEACON_PORT)
                    ds.send(p)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending beacon: ${e.message}")
        }
    }

    private fun handleIncomingPacket(jsonStr: String, fallbackIp: String) {
        try {
            val json = JSONObject(jsonStr)
            if (!json.has("dexolink")) return

            val devId = json.optString("deviceId", "")
            val devName = json.optString("deviceName", "")
            if (devId.isEmpty() || devName.isEmpty()) return

            // Filter out self
            if (devId == currentDeviceId || devName == currentDeviceName) return

            val devType = json.optString("deviceType", "mac")
            val ipFromPacket = json.optString("ip", "").trim()
            val resolvedIp = when {
                ipFromPacket.isNotEmpty() && ipFromPacket != "0.0.0.0" && ipFromPacket != "127.0.0.1" -> ipFromPacket
                fallbackIp.isNotEmpty() && fallbackIp != "127.0.0.1" -> fallbackIp
                else -> return
            }

            val port = json.optInt("port", 52840)

            onBeaconReceived?.invoke(devId, devName, devType, resolvedIp, port)
        } catch (e: Exception) {
            // Non-JSON or malformed packet, ignore
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
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

    private fun getSubnetBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                for (ia in intf.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null && !bcast.isLoopbackAddress) {
                        result.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return result
    }
}
