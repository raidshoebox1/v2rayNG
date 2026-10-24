package com.easytier.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastKnownRoutesTest {

    private val now = 1_700_000_000_000L
    private val ttl = 24 * 60 * 60 * 1000L

    @Test
    fun `decodes a fresh file written by this version`() {
        val raw = LastKnownRoutes.encode(
            listOf("192.168.50.0/24", "10.144.144.0/24"),
            now
        )

        assertEquals(
            listOf("10.144.144.0/24", "192.168.50.0/24"),
            LastKnownRoutes.decode(raw, now, now + 1_000, ttl)
        )
    }

    @Test
    fun `drops expired entries`() {
        val raw = LastKnownRoutes.encode(listOf("192.168.50.0/24"), now - ttl - 1)

        assertTrue(LastKnownRoutes.decode(raw, now, now, ttl).isEmpty())
    }

    @Test
    fun `reads the legacy format using the file modification time`() {
        val legacy = """{"cidrs":["192.168.50.0/24"]}"""

        assertEquals(
            listOf("192.168.50.0/24"),
            LastKnownRoutes.decode(legacy, now - 1_000, now, ttl)
        )
    }

    @Test
    fun `expires a legacy file that is older than the ttl`() {
        val legacy = """{"cidrs":["192.168.50.0/24"]}"""

        assertTrue(LastKnownRoutes.decode(legacy, now - ttl - 1, now, ttl).isEmpty())
    }

    @Test
    fun `rejects invalid input`() {
        assertTrue(LastKnownRoutes.decode("{", now, now, ttl).isEmpty())
        assertTrue(LastKnownRoutes.decode(null, now, now, ttl).isEmpty())
        assertTrue(LastKnownRoutes.decode("", now, now, ttl).isEmpty())
    }

    @Test
    fun `filters unsafe and duplicate entries`() {
        val raw = """{"updatedAt":$now,"cidrs":["8.8.8.0/24","10.0.0.0/8","10.0.0.0/8"]}"""

        assertEquals(listOf("10.0.0.0/8"), LastKnownRoutes.decode(raw, now, now, ttl))
    }

    @Test
    fun `encode sorts and de-duplicates`() {
        val raw = LastKnownRoutes.encode(
            listOf("192.168.2.0/24", "192.168.1.0/24", "192.168.1.0/24"),
            now
        )

        assertEquals(
            listOf("192.168.1.0/24", "192.168.2.0/24"),
            LastKnownRoutes.decode(raw, now, now, ttl)
        )
    }
}