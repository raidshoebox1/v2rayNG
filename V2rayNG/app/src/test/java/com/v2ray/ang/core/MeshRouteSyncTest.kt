package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeshRouteSyncTest {

    @Test
    fun `a new cidr triggers a config reload`() {
        assertTrue(
            MeshRouteSync.shouldReload(
                applied = setOf("10.144.144.0/24"),
                current = setOf("10.144.144.0/24", "192.168.50.0/24"),
                reloads = 0,
                coreRunning = true,
                sessionActive = true
            )
        )
    }

    @Test
    fun `the same cidrs do not trigger a reload`() {
        assertFalse(
            MeshRouteSync.shouldReload(
                applied = setOf("10.144.144.0/24"),
                current = setOf("10.144.144.0/24"),
                reloads = 0,
                coreRunning = true,
                sessionActive = true
            )
        )
    }

    @Test
    fun `a shrinking cidr set does not trigger a reload`() {
        assertFalse(
            MeshRouteSync.shouldReload(
                applied = setOf("10.144.144.0/24", "192.168.50.0/24"),
                current = setOf("10.144.144.0/24"),
                reloads = 0,
                coreRunning = true,
                sessionActive = true
            )
        )
    }

    @Test
    fun `a stopped core does not reload`() {
        assertFalse(
            MeshRouteSync.shouldReload(
                applied = emptySet(),
                current = setOf("10.144.144.0/24"),
                reloads = 0,
                coreRunning = false,
                sessionActive = true
            )
        )
    }

    @Test
    fun `an inactive easytier session does not reload`() {
        assertFalse(
            MeshRouteSync.shouldReload(
                applied = emptySet(),
                current = setOf("10.144.144.0/24"),
                reloads = 0,
                coreRunning = true,
                sessionActive = false
            )
        )
    }

    @Test
    fun `the reload budget is bounded`() {
        assertFalse(
            MeshRouteSync.shouldReload(
                applied = setOf("10.144.144.0/24"),
                current = setOf("10.144.144.0/24", "192.168.50.0/24"),
                reloads = MeshRouteSync.MAX_CONFIG_RELOADS,
                coreRunning = true,
                sessionActive = true
            )
        )
    }

    @Test
    fun `added cidrs are reported without duplicates`() {
        assertEquals(
            setOf("192.168.50.0/24"),
            MeshRouteSync.addedCidrs(
                applied = setOf("10.144.144.0/24"),
                current = setOf("10.144.144.0/24", "192.168.50.0/24")
            )
        )
    }
}
