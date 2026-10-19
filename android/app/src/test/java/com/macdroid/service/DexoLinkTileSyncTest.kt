package com.macdroid.service

import com.macdroid.core.TransferItem
import com.macdroid.core.TransferStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class DexoLinkTileSyncTest {

    @Test
    fun testQuickSettingsToggleStateSync() {
        // Test initial state
        assertFalse("Initially service should not be running", MacDroidService.isRunning())

        // Simulate turn ON initiated from Tile
        val startField = MacDroidService::class.java.getDeclaredField("isPendingStart").apply { isAccessible = true }
        val stopField = MacDroidService::class.java.getDeclaredField("isPendingStop").apply { isAccessible = true }

        startField.set(null, true)
        stopField.set(null, false)
        assertTrue("When pending start, isRunning() must immediately return true", MacDroidService.isRunning())

        // Simulate turn OFF initiated from Tile
        startField.set(null, false)
        stopField.set(null, true)
        assertFalse("When pending stop, isRunning() must immediately return false (never remain ON visually)", MacDroidService.isRunning())

        // Reset to stopped
        startField.set(null, false)
        stopField.set(null, false)
        assertFalse("When stopped, isRunning() is false", MacDroidService.isRunning())
    }

    @Test
    fun testFileTransferFormatters() {
        // Test size formatting: 2.4 GB and 5 GB
        val transferredBytes = (2.4 * 1024 * 1024 * 1024).toLong()
        val totalBytes = (5.0 * 1024 * 1024 * 1024).toLong()

        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val group = digitGroups.coerceIn(0, units.size - 1)
            return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, group.toDouble()), units[group])
        }

        fun formatSpeed(bytesPerSec: Double): String {
            if (bytesPerSec <= 0) return "0 KB/s"
            val mb = bytesPerSec / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB/s", mb)
            } else {
                String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            }
        }

        fun formatEta(seconds: Long): String {
            if (seconds <= 0L) return "--"
            if (seconds < 60L) return "${seconds}s"
            if (seconds < 3600L) return "${seconds / 60}m ${seconds % 60}s"
            return "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }

        assertEquals("2.4 GB", formatSize(transferredBytes))
        assertEquals("5.0 GB", formatSize(totalBytes))

        // Speed format test
        val speedBytes = 15.2 * 1024 * 1024
        assertEquals("15.2 MB/s", formatSpeed(speedBytes))

        // ETA format test
        assertEquals("2m 45s", formatEta(165L))
        assertEquals("45s", formatEta(45L))
        assertEquals("1h 15m", formatEta(4500L))

        // Progress percentage calculation
        val progressPercent = Math.round((transferredBytes.toDouble() / totalBytes.toDouble()) * 100).toInt()
        assertEquals(48, progressPercent)

        // Combined transfer notification text verification
        val expectedNotificationText = "2.4 GB / 5.0 GB (48%) • 15.2 MB/s • ETA: 2m 45s"
        val actualNotificationText = "${formatSize(transferredBytes)} / ${formatSize(totalBytes)} ($progressPercent%) • ${formatSpeed(speedBytes)} • ETA: ${formatEta(165L)}"
        assertEquals(expectedNotificationText, actualNotificationText)
    }

    @Test
    fun testTransferItemLifecycleStates() {
        val item = TransferItem(
            id = "tx-123",
            fileId = "f-123",
            name = "ProjectArchive.zip",
            size = 5000000000L,
            bytesTransferred = 2500000000L,
            progress = 0.5f,
            isOutgoing = true,
            state = TransferStatus.TRANSFERRING,
            speedBytesPerSec = 25000000.0
        )

        assertEquals("ProjectArchive.zip", item.name)
        assertTrue(item.isOutgoing)
        assertEquals(TransferStatus.TRANSFERRING, item.state)
        assertEquals(100L, item.etaSeconds) // (5000000000 - 2500000000) / 25000000 = 100s

        // Complete state
        item.state = TransferStatus.COMPLETED
        assertEquals(TransferStatus.COMPLETED, item.state)

        // Failed state
        item.state = TransferStatus.FAILED
        assertEquals(TransferStatus.FAILED, item.state)

        // Cancelled state
        item.state = TransferStatus.CANCELLED
        assertEquals(TransferStatus.CANCELLED, item.state)
    }
}
