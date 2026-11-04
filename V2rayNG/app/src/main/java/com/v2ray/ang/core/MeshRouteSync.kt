package com.v2ray.ang.core

/**
 * Pure decisions for refreshing the Xray routing rules after EasyTier
 * discovers mesh CIDRs.
 *
 * The VpnService route table is frozen at `Builder.establish()`, but the Xray
 * rule can be rebuilt by restarting only the core.  After a network handover
 * EasyTier may take longer than the initial poll to converge, so the rule set
 * applied at start can be missing a remote LAN.  A bounded watcher reloads the
 * core when the live mesh grows.
 *
 * Rebuilding the core drops established connections, so the watcher is
 * deliberately capped and never reloads for a shrinking set (the last-known
 * routes remain the routing baseline until their TTL expires).
 */
internal object MeshRouteSync {

    /** Maximum config-only core reloads triggered by one topology change. */
    const val MAX_CONFIG_RELOADS = 3

    /** How long to watch for late mesh CIDRs after a start or handover. */
    const val WATCH_WINDOW_MS = 30_000L

    /** Poll interval while watching. */
    const val POLL_INTERVAL_MS = 3_000L

    /**
     * Whether the running core needs a config-only reload to pick up new
     * EasyTier CIDRs.
     *
     * @param applied CIDRs baked into the running Xray config.
     * @param current CIDRs the current planner would inject.
     * @param reloads config-only reloads already spent on this topology.
     * @param coreRunning whether the native core is running.
     * @param sessionActive whether this process owns a verified EasyTier
     *   listener (fail-closed; no rule may point at an unverified port).
     */
    fun shouldReload(
        applied: Set<String>,
        current: Set<String>,
        reloads: Int,
        coreRunning: Boolean,
        sessionActive: Boolean
    ): Boolean {
        if (!coreRunning || !sessionActive) return false
        if (reloads >= MAX_CONFIG_RELOADS) return false
        return addedCidrs(applied, current).isNotEmpty()
    }

    /** CIDRs in [current] that are not yet in [applied]. */
    fun addedCidrs(applied: Set<String>, current: Set<String>): Set<String> =
        current.filterTo(LinkedHashSet()) { it !in applied }
}
