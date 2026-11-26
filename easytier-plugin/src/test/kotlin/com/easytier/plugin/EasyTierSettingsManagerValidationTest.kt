package com.easytier.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Peer-URI and log-level checks that [EasyTierSettingsManager.importFromJson] applies to
 * untrusted backup content.
 *
 * They live in the settings manager rather than only in the settings screen so a
 * hand-edited backup cannot add a peer that breaks TOML serialization or fails the whole
 * instance start, and cannot store a log level the native logger would map to debug.
 */
class EasyTierSettingsManagerValidationTest {

    @Test
    fun `accepts the schemes EasyTier understands and plain host-port`() {
        val valid = listOf(
            "tcp://1.2.3.4:11010",
            "udp://example.com:11010",
            "ws://host:80",
            "wss://host:443",
            "wg://host:11011",
            "ring://host:11012",
            "1.2.3.4:11010",
            "peer.example.com:11010"
        )
        valid.forEach { assertTrue(EasyTierSettingsManager.isValidPeerUri(it), it) }
    }

    @Test
    fun `rejects whitespace, empty values and unsupported schemes`() {
        val invalid = listOf(
            "",
            "   ",
            "http://host:80",
            "tcp://",
            "tcp:///path-only",
            "host:11010 extra"
        )
        invalid.forEach { assertFalse(EasyTierSettingsManager.isValidPeerUri(it), it) }
    }

    @Test
    fun `log levels are normalized and unknown values are rejected`() {
        assertEquals("warn", EasyTierSettingsManager.normalizeLogLevel(" WARN "))
        assertEquals("off", EasyTierSettingsManager.normalizeLogLevel("off"))
        assertNull(EasyTierSettingsManager.normalizeLogLevel("verbose"))
        assertNull(EasyTierSettingsManager.normalizeLogLevel(null))
    }
}
