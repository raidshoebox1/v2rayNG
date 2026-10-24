package com.easytier.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EasyTierConfigTest {

    @Test
    fun `socks5 listener binds to loopback only`() {
        val toml = EasyTierConfig(networkName = "mesh").toToml()

        assertTrue(toml.contains("socks5_proxy = \"socks5://127.0.0.1:10852\""))
    }

    @Test
    fun `empty secret is omitted from the config`() {
        val toml = EasyTierConfig(networkName = "mesh", networkSecret = "").toToml()

        assertFalse(toml.contains("network_secret"))
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        val toml = EasyTierConfig(networkName = "a\"b\\c").toToml()

        assertTrue(toml.contains("network_name = \"a\\\"b\\\\c\""))
    }

    @Test
    fun `del and control characters are stripped`() {
        val toml = EasyTierConfig(networkName = "a\u007Fb\u0001c").toToml()

        assertTrue(toml.contains("network_name = \"abc\""))
    }

    @Test
    fun `peers are emitted as an array of tables`() {
        val toml = EasyTierConfig(
            networkName = "mesh",
            peers = listOf("tcp://peer.example:11010")
        ).toToml()

        assertTrue(toml.contains("[[peer]]"))
        assertTrue(toml.contains("uri = \"tcp://peer.example:11010\""))
    }

    @Test
    fun `power saving writes the flag set`() {
        val toml = EasyTierConfig(networkName = "mesh", powerSaving = true).toToml()

        assertTrue(toml.contains("lazy_p2p = true"))
        assertTrue(toml.contains("disable_upnp = true"))
        assertTrue(toml.contains("mobile_power_saving = true"))
    }

    @Test
    fun `disabling power saving writes explicit false flags`() {
        val toml = EasyTierConfig(networkName = "mesh", powerSaving = false).toToml()

        assertTrue(toml.contains("lazy_p2p = false"))
        assertTrue(toml.contains("disable_upnp = false"))
        assertTrue(toml.contains("mobile_power_saving = false"))
    }

    @Test
    fun `power saving is on by default`() {
        val toml = EasyTierConfig(networkName = "mesh").toToml()

        assertTrue(toml.contains("mobile_power_saving = true"))
    }

    @Test
    fun `no_tun is always written for v2rayng coexistence`() {
        val toml = EasyTierConfig(networkName = "mesh", noTun = true).toToml()

        assertTrue(toml.contains("[flags]"))
        assertTrue(toml.contains("no_tun = true"))
    }
}