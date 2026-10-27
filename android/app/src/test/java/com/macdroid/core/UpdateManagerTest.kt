package com.macdroid.core

import org.junit.Assert.*
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testParseVersion_variousFormats() {
        assertEquals(Triple(4, 0, 2), UpdateManager.parseVersion("4.0.2"))
        assertEquals(Triple(4, 0, 2), UpdateManager.parseVersion("v4.0.2"))
        assertEquals(Triple(4, 0, 2), UpdateManager.parseVersion("V4.0.2"))
        assertEquals(Triple(0, 2, 0), UpdateManager.parseVersion("0.2"))
        assertEquals(Triple(0, 2, 0), UpdateManager.parseVersion("v0.2"))
        assertEquals(Triple(2, 0, 2), UpdateManager.parseVersion("2.0.2"))
        assertEquals(Triple(4, 0, 1), UpdateManager.parseVersion("4.0.1"))
        assertEquals(Triple(4, 0, 2), UpdateManager.parseVersion("4.0.2-beta1"))
    }

    @Test
    fun testIsNewerVersion_legacyV02ToV402() {
        // Direct test for upgrading from legacy v0.2 to v4.0.2
        assertTrue(UpdateManager.isNewerVersion("4.0.2", "0.2"))
        assertTrue(UpdateManager.isNewerVersion("v4.0.2", "v0.2"))
        assertTrue(UpdateManager.isNewerVersion("4.0.2", "0.2.0"))
    }

    @Test
    fun testIsNewerVersion_historicalVersions() {
        // v2.0.2 -> v4.0.2
        assertTrue(UpdateManager.isNewerVersion("4.0.2", "2.0.2"))
        // v3.0.0 -> v4.0.2
        assertTrue(UpdateManager.isNewerVersion("4.0.2", "3.0.0"))
        // v4.0.1 -> v4.0.2
        assertTrue(UpdateManager.isNewerVersion("4.0.2", "4.0.1"))
    }

    @Test
    fun testIsNewerVersion_sameOrOlder() {
        assertFalse(UpdateManager.isNewerVersion("4.0.2", "4.0.2"))
        assertFalse(UpdateManager.isNewerVersion("4.0.1", "4.0.2"))
        assertFalse(UpdateManager.isNewerVersion("0.2", "4.0.2"))
    }

    @Test
    fun testIsNewerVersion_futureVersions() {
        assertTrue(UpdateManager.isNewerVersion("4.1.0", "4.0.2"))
        assertTrue(UpdateManager.isNewerVersion("5.0.0", "4.0.2"))
    }
}
