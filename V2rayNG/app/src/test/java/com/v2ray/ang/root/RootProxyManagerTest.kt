package com.v2ray.ang.root

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootProxyManagerTest {

    @Test
    fun `IPv6 LAN forwarding disables jumbo and TSO`() {
        val config = RootProxyManager.buildHevConfig(null, null, 10808, true, 1500, true)

        assertTrue(config.contains("  offload:\n    jumbo: false\n"))
        assertTrue(config.contains("  multi-queue: true\n"))
        assertTrue(config.contains("    tso: false\n"))
        assertFalse(config.contains("uso:"))
    }

    @Test
    fun `local root keeps default offloads and configured MTU`() {
        val config = RootProxyManager.buildHevConfig(null, null, 10808, true, 8500, false)

        assertFalse(config.contains("offload:"))
        assertTrue(config.contains("  mtu: 8500\n"))
        assertTrue(config.contains("  ipv6:"))
    }

    @Test
    fun `LAN offload guard preserves IPv4 only and quoted authentication`() {
        val config = RootProxyManager.buildHevConfig("user'name", "pa'ss", 10809, false, 1500, true)

        assertTrue(config.contains("  offload:\n    jumbo: false\n"))
        assertFalse(config.contains("tso:"))
        assertFalse(config.contains("  ipv6:"))
        assertTrue(config.contains("  port: 10809\n"))
        assertTrue(config.contains("  username: 'user''name'\n"))
        assertTrue(config.contains("  password: 'pa''ss'\n"))
    }

    @Test
    fun `single quotes are escaped in YAML scalars`() {
        assertEquals("'user''name'", "user'name".toSingleQuotedYamlScalar())
        assertEquals("'pa''''ss'", "pa''ss".toSingleQuotedYamlScalar())
    }

    @Test
    fun `multiline credentials remain inside the YAML scalar`() {
        val credential = "user\nHEVCFG\ntouch /data/local/tmp/injected"

        assertEquals("'user\nHEVCFG\ntouch /data/local/tmp/injected'", credential.toSingleQuotedYamlScalar())
    }

    @Test
    fun `teardown removes every routing priority root mode adds`() {
        val script = RootProxyManager.buildTeardown(File("build/tmp/root-teardown-test"), corePid = 4242)

        // 5024 is the EasyTier mesh-CIDR gate added per CIDR by the LAN-share setup.
        // Leaving it behind kept steering those destinations into the table this
        // teardown flushes, so they black-holed until a reboot.
        assertTrue(script.contains("ip rule del pref 5024 2>/dev/null || true"))
        for (pref in listOf(5000, 5010, 5020, 5025, 5026, 5027, 5030, 5040, 5050, 6000)) {
            assertTrue(script.contains("ip rule del pref $pref 2>/dev/null || true"))
        }
    }

    @Test
    fun `EasyTier mesh marks precede the LAN bypass return`() {
        val script = RootProxyManager.buildMangleMarking(
            "iptables", 10100, false, false, emptyList(), false,
            easyTierCidrs = listOf("10.144.0.0/24")
        )

        val meshMark = script.indexOf("-d 10.144.0.0/24 -j MARK")
        val lanReturn = script.indexOf("-d 10.0.0.0/8 -j RETURN")
        assertTrue(meshMark >= 0, "mesh CIDR is not marked")
        assertTrue(lanReturn >= 0, "LAN bypass RETURN disappeared")
        assertTrue(meshMark < lanReturn, "mesh mark is unreachable behind the LAN bypass RETURN")
    }

    @Test
    fun `tether DNS bypasses the hijack only without LAN sharing`() {
        val tether = "-m owner --uid-owner 1052 -j RETURN"
        val off = RootProxyManager.buildMangleMarking("iptables", 10100, false, false, emptyList(), false)
        val on = RootProxyManager.buildMangleMarking("iptables", 10100, false, false, emptyList(), true)

        assertTrue(off.indexOf(tether) in 0 until off.indexOf("--dport 53"))
        assertFalse(on.contains(tether))
    }
}
