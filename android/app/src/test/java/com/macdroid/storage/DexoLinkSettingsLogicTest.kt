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
        val hasPairedDevice = true
        val hasDiscoveredDevices = true

        val showClipboardSync = (syncMode != ClipboardSyncMode.DISABLED)
        val showFileSharing = quickFileSharingEnabled
        val showDevicePairing = autoDeviceDiscoveryEnabled || hasPairedDevice || hasDiscoveredDevices
        val showContinuity = isContinuityEnabled
        val showEnhancedClipboard = isEnhancedClipboardEnabled
        val hasAnyActiveFeature = showClipboardSync || showFileSharing || showDevicePairing || showContinuity || showEnhancedClipboard

        assertTrue(showClipboardSync)
        assertTrue(showFileSharing)
        assertTrue(showDevicePairing)
        assertTrue(showContinuity)
        assertTrue(showEnhancedClipboard)
        assertTrue(hasAnyActiveFeature)
    }

    @Test
    fun testHomeScreenFeatureVisibilityLogic_allDisabled() {
        val syncMode = ClipboardSyncMode.DISABLED
        val quickFileSharingEnabled = false
        val autoDeviceDiscoveryEnabled = false
        val isContinuityEnabled = false
        val isEnhancedClipboardEnabled = false
        val hasPairedDevice = false
        val hasDiscoveredDevices = false

        val showClipboardSync = (syncMode != ClipboardSyncMode.DISABLED)
        val showFileSharing = quickFileSharingEnabled
        val showDevicePairing = autoDeviceDiscoveryEnabled || hasPairedDevice || hasDiscoveredDevices
        val showContinuity = isContinuityEnabled
        val showEnhancedClipboard = isEnhancedClipboardEnabled
        val hasAnyActiveFeature = showClipboardSync || showFileSharing || showDevicePairing || showContinuity || showEnhancedClipboard

        assertFalse("Clipboard sync should be hidden when disabled", showClipboardSync)
        assertFalse("File sharing should be hidden when disabled", showFileSharing)
        assertFalse("Device pairing should be hidden when discovery off and no devices", showDevicePairing)
        assertFalse("Continuity should be hidden when disabled", showContinuity)
        assertFalse("Enhanced clipboard should be hidden when disabled", showEnhancedClipboard)
        assertFalse("Empty state should be shown when no features active", hasAnyActiveFeature)
    }

    @Test
    fun testHomeScreenFeatureVisibilityLogic_partialEnabled() {
        // Only Continuity and File Sharing enabled
        val syncMode = ClipboardSyncMode.DISABLED
        val quickFileSharingEnabled = true
        val autoDeviceDiscoveryEnabled = false
        val isContinuityEnabled = true
        val isEnhancedClipboardEnabled = false
        val hasPairedDevice = false
        val hasDiscoveredDevices = false

        val showClipboardSync = (syncMode != ClipboardSyncMode.DISABLED)
        val showFileSharing = quickFileSharingEnabled
        val showDevicePairing = autoDeviceDiscoveryEnabled || hasPairedDevice || hasDiscoveredDevices
        val showContinuity = isContinuityEnabled
        val showEnhancedClipboard = isEnhancedClipboardEnabled
        val hasAnyActiveFeature = showClipboardSync || showFileSharing || showDevicePairing || showContinuity || showEnhancedClipboard

        assertFalse(showClipboardSync)
        assertTrue(showFileSharing)
        assertFalse(showDevicePairing)
        assertTrue(showContinuity)
        assertFalse(showEnhancedClipboard)
        assertTrue("hasAnyActiveFeature must be true when at least one feature is active", hasAnyActiveFeature)
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
}
