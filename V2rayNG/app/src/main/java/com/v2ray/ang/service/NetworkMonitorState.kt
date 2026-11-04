package com.v2ray.ang.service

/**
 * Pure state machine for [NetworkMonitor].
 *
 * The platform can announce a new upstream before it tears down the old one
 * (make-before-break) or after a gap (break-before-make).  In both cases the
 * core must be reloaded, but a late `onLost` for the old network must not clear
 * the underlying networks that were just installed for the new one.
 *
 * Tokens are compared by identity; [Network] objects are stable per interface
 * for the lifetime of the process.  Keeping this class free of Android types
 * lets the transitions be covered by a JVM test.
 */
internal class NetworkMonitorState {

    private var current: Any? = null
    private var lastSeen: Any? = null

    /**
     * Record an available network.
     *
     * @return true when this is a change of upstream that requires a handover
     *   reload.  The first network after [reset] is not a handover; a network
     *   that becomes available again after [onLost] is.
     */
    @Synchronized
    fun onAvailable(token: Any): Boolean {
        val handover = if (current == null) {
            lastSeen != null
        } else {
            current !== token
        }
        current = token
        lastSeen = token
        return handover
    }

    /**
     * Record a lost network.
     *
     * @return true when the lost network was the current upstream and the
     *   caller must clear the underlying networks.
     */
    @Synchronized
    fun onLost(token: Any): Boolean {
        if (current !== token) return false
        current = null
        return true
    }

    /** Whether [token] is the current upstream. */
    @Synchronized
    fun isUpstream(token: Any): Boolean = current === token

    /** Forget all state, e.g. when the monitor is unregistered. */
    @Synchronized
    fun reset() {
        current = null
        lastSeen = null
    }
}
