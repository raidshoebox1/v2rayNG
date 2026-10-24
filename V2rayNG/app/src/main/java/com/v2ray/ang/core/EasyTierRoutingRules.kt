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
 * In bypass-LAN mode the VpnService route table already excludes private
 * subnets, so the direct LAN rule is unnecessary.  Only full-tunnel mode needs
 * it, and adding it there after DNS prevents a LAN resolver from being sent
 * direct before the user's DNS rule runs.
 */
internal object EasyTierRoutingRules {

    const val DNS_OUT_TAG = "dns-out"

    /**
     * Build the rules that are not already present.
     *
     * @param includeLocalLanDirect whether the direct LAN rule is needed.
     *   It is only needed when the VPN captures the local LAN (full tunnel).
     * @return the direct LAN rule followed by the EasyTier rule, or an empty
     *   list when equivalent rules already exist.
     */
    fun buildMissing(
        rules: List<V2rayConfig.RoutingBean.RulesBean>,
        plan: EasyTierRoutePlan,
        includeLocalLanDirect: Boolean = true
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