package com.easytier.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusWriterPolicyTest {

    @Test
    fun `power saving off keeps the historical cadence`() {
        assertEquals(
            StatusWriterPolicy.ACTIVE_INTERVAL_MS,
            StatusWriterPolicy.intervalMs(powerSaving = false, screenOn = true, watching = false)
        )
        assertEquals(
            StatusWriterPolicy.ACTIVE_INTERVAL_MS,
            StatusWriterPolicy.intervalMs(powerSaving = false, screenOn = false, watching = false)
        )
    }

    @Test
    fun `watching the settings screen uses the fast cadence`() {
        assertEquals(
            StatusWriterPolicy.ACTIVE_INTERVAL_MS,
            StatusWriterPolicy.intervalMs(powerSaving = true, screenOn = true, watching = true)
        )
    }

    @Test
    fun `screen on without an observer is slower`() {
        assertEquals(
            StatusWriterPolicy.IDLE_SCREEN_ON_INTERVAL_MS,
            StatusWriterPolicy.intervalMs(powerSaving = true, screenOn = true, watching = false)
        )
    }

    @Test
    fun `screen off is the slowest cadence`() {
        assertEquals(
            StatusWriterPolicy.SCREEN_OFF_INTERVAL_MS,
            StatusWriterPolicy.intervalMs(powerSaving = true, screenOn = false, watching = false)
        )
        assertTrue(
            StatusWriterPolicy.SCREEN_OFF_INTERVAL_MS > StatusWriterPolicy.IDLE_SCREEN_ON_INTERVAL_MS
        )
    }

    @Test
    fun `snapshot ttl covers the slowest interval`() {
        assertTrue(StatusWriterPolicy.snapshotTtlMs(true) > StatusWriterPolicy.SCREEN_OFF_INTERVAL_MS)
        assertEquals(30_000L, StatusWriterPolicy.snapshotTtlMs(false))
    }
}
