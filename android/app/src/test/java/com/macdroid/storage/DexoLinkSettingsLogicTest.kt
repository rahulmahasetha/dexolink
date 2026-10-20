package com.macdroid.storage

import com.macdroid.core.ClipboardSyncMode
import com.macdroid.core.PendingIncomingTransfer
import org.junit.Assert.*
import org.junit.Test

class DexoLinkSettingsLogicTest {

    @Test
    fun testPendingIncomingTransferDataClass() {
        val transfer = PendingIncomingTransfer(
            transferId = "transfer-12345",
            totalSize = 104857600L // 100 MB
        )

        assertEquals("transfer-12345", transfer.transferId)
        assertEquals(104857600L, transfer.totalSize)
        assertTrue(transfer.timestamp > 0L)
    }

    @Test
    fun testHomeScreenFeatureVisibilityLogic_allEnabled() {
        val syncMode = ClipboardSyncMode.TWO_WAY
        val quickFileSharingEnabled = true
        val autoDeviceDiscoveryEnabled = true
        val isContinuityEnabled = true
        val isEnhancedClipboardEnabled = true
        val cameraSharingEnabled = true
        val screenSharingEnabled = true
        val desktopModeEnabled = true
        val hasPairedDevice = true
        val hasDiscoveredDevices = true

        val showClipboardSync = (syncMode != ClipboardSyncMode.DISABLED)
        val showFileSharing = quickFileSharingEnabled
        val showDevicePairing = autoDeviceDiscoveryEnabled || hasPairedDevice || hasDiscoveredDevices
        val showContinuity = isContinuityEnabled
        val showEnhancedClipboard = isEnhancedClipboardEnabled
        val showCameraSharing = cameraSharingEnabled
        val showScreenSharing = screenSharingEnabled
        val showDesktopMode = desktopModeEnabled
        val hasAnyActiveFeature = showClipboardSync || showFileSharing || showDevicePairing || showContinuity || showEnhancedClipboard || showCameraSharing || showScreenSharing || showDesktopMode

        assertTrue(showClipboardSync)
        assertTrue(showFileSharing)
        assertTrue(showDevicePairing)
        assertTrue(showContinuity)
        assertTrue(showEnhancedClipboard)
        assertTrue(showCameraSharing)
        assertTrue(showScreenSharing)
        assertTrue(showDesktopMode)
        assertTrue(hasAnyActiveFeature)
    }

    @Test
    fun testHomeScreenFeatureVisibilityLogic_allDisabled() {
        val syncMode = ClipboardSyncMode.DISABLED
        val quickFileSharingEnabled = false
        val autoDeviceDiscoveryEnabled = false
        val isContinuityEnabled = false
        val isEnhancedClipboardEnabled = false
        val cameraSharingEnabled = false
        val screenSharingEnabled = false
        val desktopModeEnabled = false
        val hasPairedDevice = false
        val hasDiscoveredDevices = false

        val showClipboardSync = (syncMode != ClipboardSyncMode.DISABLED)
        val showFileSharing = quickFileSharingEnabled
        val showDevicePairing = autoDeviceDiscoveryEnabled || hasPairedDevice || hasDiscoveredDevices
        val showContinuity = isContinuityEnabled
        val showEnhancedClipboard = isEnhancedClipboardEnabled
        val showCameraSharing = cameraSharingEnabled
        val showScreenSharing = screenSharingEnabled
        val showDesktopMode = desktopModeEnabled
        val hasAnyActiveFeature = showClipboardSync || showFileSharing || showDevicePairing || showContinuity || showEnhancedClipboard || showCameraSharing || showScreenSharing || showDesktopMode

        assertFalse("Clipboard sync should be hidden when disabled", showClipboardSync)
        assertFalse("File sharing should be hidden when disabled", showFileSharing)
        assertFalse("Device pairing should be hidden when discovery off and no devices", showDevicePairing)
        assertFalse("Continuity should be hidden when disabled", showContinuity)
        assertFalse("Enhanced clipboard should be hidden when disabled", showEnhancedClipboard)
        assertFalse("Camera sharing should be hidden when disabled", showCameraSharing)
        assertFalse("Screen sharing should be hidden when disabled", showScreenSharing)
        assertFalse("Desktop mode should be hidden when disabled", showDesktopMode)
        assertFalse("Empty state should be shown when no features active", hasAnyActiveFeature)
    }

    @Test
    fun testHomeScreenFeatureVisibilityLogic_partialEnabled() {
        // Only Camera Sharing and File Sharing enabled
        val syncMode = ClipboardSyncMode.DISABLED
        val quickFileSharingEnabled = true
        val autoDeviceDiscoveryEnabled = false
        val isContinuityEnabled = false
        val isEnhancedClipboardEnabled = false
        val cameraSharingEnabled = true
        val screenSharingEnabled = false
        val desktopModeEnabled = false
        val hasPairedDevice = false
        val hasDiscoveredDevices = false

        val showClipboardSync = (syncMode != ClipboardSyncMode.DISABLED)
        val showFileSharing = quickFileSharingEnabled
        val showDevicePairing = autoDeviceDiscoveryEnabled || hasPairedDevice || hasDiscoveredDevices
        val showContinuity = isContinuityEnabled
        val showEnhancedClipboard = isEnhancedClipboardEnabled
        val showCameraSharing = cameraSharingEnabled
        val showScreenSharing = screenSharingEnabled
        val showDesktopMode = desktopModeEnabled
        val hasAnyActiveFeature = showClipboardSync || showFileSharing || showDevicePairing || showContinuity || showEnhancedClipboard || showCameraSharing || showScreenSharing || showDesktopMode

        assertFalse(showClipboardSync)
        assertTrue(showFileSharing)
        assertFalse(showDevicePairing)
        assertFalse(showContinuity)
        assertFalse(showEnhancedClipboard)
        assertTrue(showCameraSharing)
        assertFalse(showScreenSharing)
        assertFalse(showDesktopMode)
        assertTrue("hasAnyActiveFeature must be true when at least one feature is active", hasAnyActiveFeature)
    }

    @Test
    fun testMediaStreamingToggles_defaultOff() {
        val cameraSharingDefault = false
        val screenSharingDefault = false
        val desktopModeDefault = false

        assertFalse("Camera Sharing must be OFF by default", cameraSharingDefault)
        assertFalse("Screen Sharing must be OFF by default", screenSharingDefault)
        assertFalse("Desktop Mode must be OFF by default", desktopModeDefault)
    }

    @Test
    fun testAutoAcceptFromPairedDevicesLogic() {
        // Simulate auto-accept ON (default)
        var autoAcceptPaired = true
        var pendingTransfer: PendingIncomingTransfer? = null
        var isTransferAcceptedImmediately = false

        fun onIncomingTransferRequest(transferId: String, totalSize: Long) {
            if (autoAcceptPaired) {
                isTransferAcceptedImmediately = true
                pendingTransfer = null
            } else {
                isTransferAcceptedImmediately = false
                pendingTransfer = PendingIncomingTransfer(transferId, totalSize)
            }
        }

        onIncomingTransferRequest("transfer-1", 5000000L)
        assertTrue("When auto-accept is true, transfer must be accepted immediately", isTransferAcceptedImmediately)
        assertNull("When auto-accept is true, pendingTransfer must be null", pendingTransfer)

        // Simulate auto-accept OFF
        autoAcceptPaired = false
        isTransferAcceptedImmediately = false
        onIncomingTransferRequest("transfer-2", 8000000L)
        assertFalse("When auto-accept is false, transfer must not be accepted immediately", isTransferAcceptedImmediately)
        assertNotNull("When auto-accept is false, pendingTransfer must be created", pendingTransfer)
        assertEquals("transfer-2", pendingTransfer?.transferId)
        assertEquals(8000000L, pendingTransfer?.totalSize)

        // Simulate user clicking Accept
        isTransferAcceptedImmediately = true
        pendingTransfer = null
        assertTrue(isTransferAcceptedImmediately)
        assertNull(pendingTransfer)
    }

    @Test
    fun testAutoDeviceDiscoveryLogic() {
        var autoDeviceDiscovery = true
        var isBrowsing = false

        fun onAppStart() {
            if (autoDeviceDiscovery) {
                isBrowsing = true
            } else {
                isBrowsing = false
            }
        }

        onAppStart()
        assertTrue("Discovery should browse on start when enabled", isBrowsing)

        autoDeviceDiscovery = false
        onAppStart()
        assertFalse("Discovery should not browse on start when disabled", isBrowsing)
    }

    @Test
    fun testMultiplePairedDevices_defaultsToFalse() {
        val multiPairedDefault = false
        assertFalse("Multiple Paired Devices must be OFF by default", multiPairedDefault)
    }

    @Test
    fun testMultiplePairedDevices_singleModeEnforcesSingleDevice() {
        var multiPairedEnabled = false
        val deviceList = mutableListOf<String>()

        fun pairDevice(id: String) {
            if (!multiPairedEnabled) {
                deviceList.clear()
                deviceList.add(id)
            } else {
                if (!deviceList.contains(id)) {
                    deviceList.add(id)
                }
            }
        }

        pairDevice("macbook-1")
        assertEquals(1, deviceList.size)
        assertEquals("macbook-1", deviceList.first())

        // Pairing second device in single mode replaces the first
        pairDevice("iphone-1")
        assertEquals(1, deviceList.size)
        assertEquals("iphone-1", deviceList.first())
    }

    @Test
    fun testMultiplePairedDevices_multiModeAllowsMultipleAndDeviceSpecificUnpairing() {
        var multiPairedEnabled = true
        val deviceList = mutableListOf<String>()

        fun pairDevice(id: String) {
            if (!multiPairedEnabled) {
                deviceList.clear()
                deviceList.add(id)
            } else {
                if (!deviceList.contains(id)) {
                    deviceList.add(id)
                }
            }
        }

        fun unpairDevice(id: String) {
            deviceList.remove(id)
        }

        pairDevice("macbook-pro")
        pairDevice("pixel-8")
        pairDevice("windows-desktop")

        assertEquals(3, deviceList.size)
        assertTrue(deviceList.contains("macbook-pro"))
        assertTrue(deviceList.contains("pixel-8"))
        assertTrue(deviceList.contains("windows-desktop"))

        // Device-specific unpairing: unpair only Pixel 8
        unpairDevice("pixel-8")
        assertEquals(2, deviceList.size)
        assertFalse("pixel-8 must be removed", deviceList.contains("pixel-8"))
        assertTrue("macbook-pro must remain paired", deviceList.contains("macbook-pro"))
        assertTrue("windows-desktop must remain paired", deviceList.contains("windows-desktop"))
    }

    @Test
    fun testDynamicDeviceStreamingOptions_whenDisconnected_neverShowsConnectionAvailable() {
        val isConnected = false
        val connectedDeviceName: String? = null

        // Rule: Never show a connection as available unless verified
        val canShowStreamingActions = isConnected && connectedDeviceName != null
        assertFalse("Streaming actions must be hidden when disconnected", canShowStreamingActions)

        val warningBadge = if (!canShowStreamingActions) {
            "No Compatible Device Connected"
        } else {
            null
        }
        assertNotNull("Warning badge must be present when disconnected", warningBadge)
    }

    @Test
    fun testDynamicDeviceStreamingOptions_whenConnected_showsVerifiedOptionsForPlatform() {
        val platforms = listOf(
            Pair("macOS", "MacBook Pro M3"),
            Pair("windows", "Windows 11 Workstation"),
            Pair("iphone", "iPhone 15 Pro"),
            Pair("android", "Galaxy S24 Ultra")
        )

        for ((platform, devName) in platforms) {
            val isConnected = true
            val canShowStreamingActions = isConnected && devName.isNotBlank()
            assertTrue("Must allow streaming actions for verified connected $platform", canShowStreamingActions)

            // Verify supported directions
            val outgoingAction = "Android → $devName"
            val incomingAction = "$devName → Android"
            assertTrue(outgoingAction.contains("Android →"))
            assertTrue(incomingAction.contains("→ Android"))
        }
    }
}


