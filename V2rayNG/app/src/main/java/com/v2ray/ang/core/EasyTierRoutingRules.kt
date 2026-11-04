package com.v2ray.ang.core

import com.easytier.plugin.EasyTierPlugin
import com.easytier.plugin.EasyTierRoutePlan
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig

/**
 * Pure helpers for inserting EasyTier routing rules into an Xray config.
 *
 * The direct LAN rule must come before the EasyTier rule, and both must come
 * after any DNS rule.  Extracted from [CoreConfigManager] so the order can be
 * covered by a JVM test.
 *
 * In bypass-LAN mode the VpnService route table normally excludes private
 * subnets, so the direct LAN rule is unnecessary.  The exception is a
 * mesh/extra route that overlaps the connected LAN: the route is kept in the
 * tunnel table so it survives a handover, and the direct rule keeps that LAN
 * traffic out of the proxy while the device is still on the LAN.
 */
internal object EasyTierRoutingRules {

    const val DNS_OUT_TAG = "dns-out"

    /**
     * Whether the direct LAN rule is required for [plan].
     *
     * The VPN captures LAN traffic in full-tunnel mode, and in bypass-LAN
     * mode when an EasyTier/extra route overlaps the connected LAN (the route
     * is kept in the tunnel table so it survives a handover).  Proxy-only and
     * root modes have no VpnService interface, so they never need this rule.
     */
    fun needsDirectLanRule(
        vpnMode: Boolean,
        bypassLan: Boolean,
        plan: EasyTierRoutePlan
    ): Boolean {
        if (!vpnMode) return false
        if (plan.localLanCidrs.isEmpty()) return false
        return !bypassLan || plan.vpnRoutesOverlapLocalLan
    }

    /**
     * Build the rules that are not already present.
     *
     * @param includeLocalLanDirect whether the direct LAN rule is needed.
     * @param includeEasyTierRule whether to add the EasyTier rule.  False when
     *   this process has no verified EasyTier listener; a direct LAN rule may
     *   still be required for the routes the VpnService captured.
     * @return the direct LAN rule followed by the EasyTier rule, or an empty
     *   list when equivalent rules already exist.
     */
    fun buildMissing(
        rules: List<V2rayConfig.RoutingBean.RulesBean>,
        plan: EasyTierRoutePlan,
        includeLocalLanDirect: Boolean = true,
        includeEasyTierRule: Boolean = true
    ): List<V2rayConfig.RoutingBean.RulesBean> {
        val additions = mutableListOf<V2rayConfig.RoutingBean.RulesBean>()

        val directCidrs = if (includeLocalLanDirect) plan.localLanCidrs else emptyList()
        val directExists = directCidrs.isEmpty() || rules.any { rule ->
            rule.outboundTag == AppConfig.TAG_DIRECT &&
                rule.ip?.toSet() == directCidrs.toSet()
        }
        if (!directExists) {
            additions.add(
                V2rayConfig.RoutingBean.RulesBean(
                    type = "field",
                    ip = ArrayList(directCidrs),
                    outboundTag = AppConfig.TAG_DIRECT
                )
            )
        }

        if (includeEasyTierRule) {
            val easyTierExists = plan.easyTierCidrs.isEmpty() ||
                rules.any { it.outboundTag == EasyTierPlugin.OUTBOUND_TAG }
            if (!easyTierExists) {
                additions.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        type = "field",
                        ip = ArrayList(plan.easyTierCidrs),
                        outboundTag = EasyTierPlugin.OUTBOUND_TAG
                    )
                )
            }
        }

        return additions
    }

    /**
     * Insert [additions] after the last DNS rule, preserving order.
     *
     * A DNS rule is either tagged `dns-out` or handles port 53 explicitly.
     * If no DNS rule exists, the additions are inserted at the front.
     */
    fun insertAfterDnsRules(
        rules: MutableList<V2rayConfig.RoutingBean.RulesBean>,
        additions: List<V2rayConfig.RoutingBean.RulesBean>
    ) {
        if (additions.isEmpty()) return
        val index = rules.indexOfLast { rule ->
            rule.outboundTag == DNS_OUT_TAG || handlesDnsPort(rule.port)
        } + 1
        rules.addAll(index, additions)
    }

    private fun handlesDnsPort(port: String?): Boolean {
        if (port.isNullOrBlank()) return false
        return port.split(',', ' ', '/')
            .map { it.trim() }
            .any { it == "53" || it == "53-53" }
    }
}