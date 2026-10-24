package com.v2ray.ang.core

import com.easytier.plugin.EasyTierPlugin
import com.easytier.plugin.EasyTierRoutePlan
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EasyTierRoutingRulesTest {

    private fun rule(tag: String) = V2rayConfig.RoutingBean.RulesBean(outboundTag = tag)

    @Test
    fun `direct LAN rule is inserted before easytier after dns-out`() {
        val plan = EasyTierRoutePlan(
            localLanCidrs = listOf("192.168.1.0/24"),
            easyTierCidrs = listOf("10.144.144.0/24")
        )
        val rules = mutableListOf(rule("dns-out"), rule("proxy"))

        val additions = EasyTierRoutingRules.buildMissing(rules, plan)
        assertEquals(
            listOf(AppConfig.TAG_DIRECT, EasyTierPlugin.OUTBOUND_TAG),
            additions.map { it.outboundTag }
        )

        EasyTierRoutingRules.insertAfterDnsRules(rules, additions)
        assertEquals(
            listOf("dns-out", AppConfig.TAG_DIRECT, EasyTierPlugin.OUTBOUND_TAG, "proxy"),
            rules.map { it.outboundTag }
        )
    }

    @Test
    fun `rules are inserted at the front when there is no dns-out rule`() {
        val plan = EasyTierRoutePlan(
            localLanCidrs = listOf("192.168.1.0/24"),
            easyTierCidrs = listOf("10.144.144.0/24")
        )
        val rules = mutableListOf(rule("proxy"))

        EasyTierRoutingRules.insertAfterDnsRules(
            rules,
            EasyTierRoutingRules.buildMissing(rules, plan)
        )

        assertEquals(
            listOf(AppConfig.TAG_DIRECT, EasyTierPlugin.OUTBOUND_TAG, "proxy"),
            rules.map { it.outboundTag }
        )
    }

    @Test
    fun `existing rules are not duplicated`() {
        val plan = EasyTierRoutePlan(
            localLanCidrs = listOf("192.168.1.0/24"),
            easyTierCidrs = listOf("10.144.144.0/24")
        )
        val rules = mutableListOf(
            V2rayConfig.RoutingBean.RulesBean(
                type = "field",
                ip = arrayListOf("192.168.1.0/24"),
                outboundTag = AppConfig.TAG_DIRECT
            ),
            V2rayConfig.RoutingBean.RulesBean(
                type = "field",
                ip = arrayListOf("10.144.144.0/24"),
                outboundTag = EasyTierPlugin.OUTBOUND_TAG
            )
        )

        assertTrue(EasyTierRoutingRules.buildMissing(rules, plan).isEmpty())
    }

    @Test
    fun `local LAN disabled only adds the easytier rule`() {
        val plan = EasyTierRoutePlan(
            localLanCidrs = emptyList(),
            easyTierCidrs = listOf("10.144.144.0/24")
        )
        val additions = EasyTierRoutingRules.buildMissing(emptyList(), plan)

        assertEquals(listOf(EasyTierPlugin.OUTBOUND_TAG), additions.map { it.outboundTag })
    }

    @Test
    fun `bypass LAN mode skips the direct LAN rule`() {
        val plan = EasyTierRoutePlan(
            localLanCidrs = listOf("192.168.1.0/24"),
            easyTierCidrs = listOf("10.144.144.0/24")
        )
        val additions = EasyTierRoutingRules.buildMissing(
            emptyList(),
            plan,
            includeLocalLanDirect = false
        )

        assertEquals(listOf(EasyTierPlugin.OUTBOUND_TAG), additions.map { it.outboundTag })
    }

    @Test
    fun `rules are inserted after an explicit port 53 DNS rule`() {
        val plan = EasyTierRoutePlan(
            localLanCidrs = listOf("192.168.1.0/24"),
            easyTierCidrs = listOf("10.144.144.0/24")
        )
        val rules = mutableListOf(
            V2rayConfig.RoutingBean.RulesBean(port = "53", outboundTag = "dns-proxy"),
            rule("proxy")
        )

        EasyTierRoutingRules.insertAfterDnsRules(
            rules,
            EasyTierRoutingRules.buildMissing(rules, plan)
        )

        assertEquals(
            listOf(
                "dns-proxy",
                AppConfig.TAG_DIRECT,
                EasyTierPlugin.OUTBOUND_TAG,
                "proxy"
            ),
            rules.map { it.outboundTag }
        )
    }
}