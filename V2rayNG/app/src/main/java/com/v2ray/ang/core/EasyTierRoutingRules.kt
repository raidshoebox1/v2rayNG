package com.v2ray.ang.core

import com.easytier.plugin.EasyTierPlugin
import com.easytier.plugin.EasyTierRoutePlan
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig

/**
 * Pure helpers for inserting EasyTier routing rules into an Xray config.
 *
 * The direct LAN rule must come before the EasyTier rule, and both must come
 * after any `dns-out` rule.  Extracted from [CoreConfigManager] so the order
 * can be covered by a JVM test.
 */
internal object EasyTierRoutingRules {

    const val DNS_OUT_TAG = "dns-out"

    /**
     * Build the rules that are not already present.
     *
     * @return the direct LAN rule followed by the EasyTier rule, or an empty
     *   list when equivalent rules already exist.
     */
    fun buildMissing(
        rules: List<V2rayConfig.RoutingBean.RulesBean>,
        plan: EasyTierRoutePlan
    ): List<V2rayConfig.RoutingBean.RulesBean> {
        val additions = mutableListOf<V2rayConfig.RoutingBean.RulesBean>()

        val directExists = plan.localLanCidrs.isEmpty() || rules.any { rule ->
            rule.outboundTag == AppConfig.TAG_DIRECT &&
                rule.ip?.toSet() == plan.localLanCidrs.toSet()
        }
        if (!directExists) {
            additions.add(
                V2rayConfig.RoutingBean.RulesBean(
                    type = "field",
                    ip = ArrayList(plan.localLanCidrs),
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
     * Insert [additions] after the last `dns-out` rule, preserving order.
     * If no `dns-out` rule exists, the additions are inserted at the front.
     */
    fun insertAfterDnsOut(
        rules: MutableList<V2rayConfig.RoutingBean.RulesBean>,
        additions: List<V2rayConfig.RoutingBean.RulesBean>
    ) {
        if (additions.isEmpty()) return
        val index = rules.indexOfLast { it.outboundTag == DNS_OUT_TAG } + 1
        rules.addAll(index, additions)
    }
}