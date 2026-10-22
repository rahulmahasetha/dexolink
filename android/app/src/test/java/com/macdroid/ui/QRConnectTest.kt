package com.macdroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QRConnectTest {

    @Test
    fun testQRPayloadFormat() {
        val host = "192.168.1.50"
        val port = 52840
        val deviceName = "Rahul's Samsung"
        val encodedName = java.net.URLEncoder.encode(deviceName, "UTF-8")
        val payload = "dexolink://connect?host=$host&port=$port&name=$encodedName"

        assertTrue(payload.startsWith("dexolink://connect"))
        assertTrue(payload.contains("host=192.168.1.50"))
        assertTrue(payload.contains("port=52840"))
        assertTrue(payload.contains("name=Rahul%27s+Samsung") || payload.contains("name=Rahul"))
    }

    @Test
    fun testMobileConnectedDeviceModel() {
        val dev1 = MobileConnectedDevice(
            id = "mac_1",
            name = "Rahul's MacBook",
            status = "Connected",
            isDirectIp = false
        )
        val dev2 = MobileConnectedDevice(
            id = "win_1",
            name = "Windows PC",
            status = "Connected by IP",
            isDirectIp = true
        )

        assertEquals("Rahul's MacBook", dev1.name)
        assertEquals("Connected", dev1.status)
        assertEquals(false, dev1.isDirectIp)

        assertEquals("Windows PC", dev2.name)
        assertEquals("Connected by IP", dev2.status)
        assertEquals(true, dev2.isDirectIp)
    }

    @Test
    fun testNavTabEnum() {
        val tabs = NavTab.values()
        assertTrue(tabs.contains(NavTab.HOME))
        assertTrue(tabs.contains(NavTab.FILES))
        assertTrue(tabs.contains(NavTab.TRANSFERS))
        assertTrue(tabs.contains(NavTab.SETTINGS))
        assertTrue(tabs.contains(NavTab.QR_CONNECT))
        assertEquals(5, tabs.size)
    }
}
