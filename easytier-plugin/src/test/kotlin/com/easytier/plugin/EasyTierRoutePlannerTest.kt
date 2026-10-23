package com.easytier.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EasyTierRoutePlannerTest {

    @Test
    fun `virtual ip defaults to a 24 bit subnet`() {
        val plan = EasyTierRoutePlanner.plan(
            localLanCidrs = emptyList(),
            vpnClientCidr = null,
            meshCidrs = emptyList(),
            virtualIp = "10.144.144.5",
            extraCidrs = emptyList()
        )

        assertEquals(listOf("10.144.144.0/24"), plan.easyTierCidrs)
    }

    @Test
    fun `all configured mesh addresses map to the same virtual subnet`() {
        for (address in listOf("10.144.144.1", "10.144.144.3", "10.144.144.4", "10.144.144.5")) {
            val plan = EasyTierRoutePlanner.plan(
                localLanCidrs = listOf("192.168.31.0/24"),
                vpnClientCidr = "10.10.14.1/30",
                meshCidrs = emptyList(),
                virtualIp = address,
                extraCidrs = emptyList()
            )
            assertEquals(listOf("10.144.144.0/24"), plan.easyTierCidrs, "virtual IP $address")
            assertEquals(listOf("192.168.31.0/24"), plan.localLanCidrs, "virtual IP $address")
        }
    }

    @Test
    fun `no broad private ranges are generated for a normal home LAN`() {
        val plan = EasyTierRoutePlanner.plan(
            localLanCidrs = listOf("192.168.1.23/24"),
            vpnClientCidr = "10.10.14.1/30",
            meshCidrs = emptyList(),
            virtualIp = "10.144.144.1",
            extraCidrs = emptyList()
        )

        assertEquals(listOf("192.168.1.0/24"), plan.localLanCidrs)
        assertEquals(listOf("10.144.144.0/24"), plan.easyTierCidrs)
        assertFalse(plan.easyTierCidrs.contains("10.0.0.0/8"))
        assertFalse(plan.easyTierCidrs.contains("172.16.0.0/12"))
        assertFalse(plan.easyTierCidrs.contains("192.168.0.0/16"))
    }

    @Test
    fun `a broad remote subnet is split around the local LAN`() {
        val plan = EasyTierRoutePlanner.plan(
            localLanCidrs = listOf("192.168.1.0/24"),
            vpnClientCidr = null,
            meshCidrs = listOf("192.168.0.0/16"),
            virtualIp = null,
            extraCidrs = emptyList()
        )

        assertFalse(plan.easyTierCidrs.contains("192.168.1.0/24"))
        assertFalse(plan.easyTierCidrs.contains("192.168.0.0/16"))
        assertTrue(plan.easyTierCidrs.contains("192.168.0.0/24"))
        assertTrue(plan.easyTierCidrs.contains("192.168.2.0/23"))
        assertTrue(plan.conflicts.isNotEmpty())
    }

    @Test
    fun `ipv6 mesh blocks are kept`() {
        val plan = EasyTierRoutePlanner.plan(
            localLanCidrs = emptyList(),
            vpnClientCidr = null,
            meshCidrs = listOf("fc00::1/64"),
            virtualIp = null,
            extraCidrs = emptyList()
        )

        assertEquals(listOf("fc00::/64"), plan.easyTierCidrs)
    }

    @Test
    fun `public candidates are rejected`() {
        val plan = EasyTierRoutePlanner.plan(
            localLanCidrs = emptyList(),
            vpnClientCidr = null,
            meshCidrs = listOf("8.8.8.0/24", "0.0.0.0/0"),
            virtualIp = null,
            extraCidrs = emptyList()
        )

        assertTrue(plan.easyTierCidrs.isEmpty())
        assertEquals(2, plan.conflicts.size)
    }

    @Test
    fun `extra routes are routed through easytier`() {
        val plan = EasyTierRoutePlanner.plan(
            localLanCidrs = listOf("192.168.1.0/24"),
            vpnClientCidr = null,
            meshCidrs = emptyList(),
            virtualIp = "10.144.144.1",
            extraCidrs = listOf("192.168.50.0/24")
        )

        assertTrue(plan.easyTierCidrs.contains("192.168.50.0/24"))
        assertEquals(listOf("192.168.1.0/24"), plan.localLanCidrs)
    }

    @Test
    fun `disabling local lan bypass keeps the old behaviour`() {
        val plan = EasyTierRoutePlanner.plan(
            localLanCidrs = listOf("192.168.1.0/24"),
            vpnClientCidr = null,
            meshCidrs = listOf("192.168.0.0/16"),
            virtualIp = null,
            extraCidrs = emptyList(),
            bypassLocalLan = false
        )

        assertTrue(plan.easyTierCidrs.contains("192.168.0.0/16"))
        assertTrue(plan.localLanCidrs.isEmpty())
    }

    @Test
    fun `mesh json parser extracts local peer and proxy CIDRs`() {
        val json = """
            {
              "map": {
                "v2rayng_plugin": {
                  "running": true,
                  "my_node_info": {
                    "virtual_ipv4": {
                      "address": { "addr": 177246209 },
                      "network_length": 24
                    }
                  },
                  "routes": [
                    {
                      "peer_id": 1,
                      "ipv4_addr": {
                        "address": { "addr": 177246210 },
                        "network_length": 24
                      },
                      "proxy_cidrs": ["192.168.50.0/24"]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val cidrs = EasyTierPlugin.parseMeshCidrs(json)

        assertTrue(cidrs.contains("10.144.144.0/24"))
        assertTrue(cidrs.contains("192.168.50.0/24"))
    }

    @Test
    fun `safe mesh cidr validation`() {
        assertTrue(EasyTierRoutePlanner.isSafeMeshCidr("10.0.0.0/8"))
        assertTrue(EasyTierRoutePlanner.isSafeMeshCidr("192.168.1.0/24"))
        assertTrue(EasyTierRoutePlanner.isSafeMeshCidr("fc00::/7"))
        assertTrue(EasyTierRoutePlanner.isSafeMeshCidr("fe80::/10"))
        assertFalse(EasyTierRoutePlanner.isSafeMeshCidr("0.0.0.0/0"))
        assertFalse(EasyTierRoutePlanner.isSafeMeshCidr("8.8.8.0/24"))
        assertFalse(EasyTierRoutePlanner.isSafeMeshCidr("2001:db8::/32"))
    }

    @Test
    fun `parse list normalizes separators`() {
        assertEquals(
            listOf("10.144.144.0/24", "192.168.50.0/24"),
            EasyTierRoutePlanner.parseList("10.144.144.1/24,\n192.168.50.5/24")
        )
    }
}