package com.easytier.plugin

/**
 * Decides how often the VPN process publishes its status snapshot.
 *
 * The snapshot is only consumed by the EasyTier settings screen.  Writing it
 * every three seconds while nobody is looking costs a JNI round trip, a JSON
 * parse and a file write each time, so the interval follows the observer.
 *
 * When the power-saving switch is off the historical 3s cadence is kept, so
 * turning the switch off fully restores the old behaviour.
 */
object StatusWriterPolicy {

    /** Cadence while somebody is looking at the status. */
    const val ACTIVE_INTERVAL_MS = EasyTierPlugin.STATUS_WRITER_INTERVAL_MS

    /** Screen on, but the settings screen is not in front. */
    const val IDLE_SCREEN_ON_INTERVAL_MS = 30_000L

    /** Screen off: nobody can read the snapshot. */
    const val SCREEN_OFF_INTERVAL_MS = 300_000L

    /**
     * A "watch" marker older than this is ignored, so a settings screen that
     * was killed without onPause cannot pin the writer at the fast cadence.
     */
    const val WATCH_TTL_MS = 5 * 60_000L

    /** How long a snapshot may be served after it was written. */
    fun snapshotTtlMs(powerSaving: Boolean): Long =
        if (powerSaving) SCREEN_OFF_INTERVAL_MS + 60_000L else EasyTierPlugin.STATUS_SNAPSHOT_TTL_MS

    fun intervalMs(powerSaving: Boolean, screenOn: Boolean, watching: Boolean): Long {
        if (!powerSaving) return ACTIVE_INTERVAL_MS
        return when {
            watching -> ACTIVE_INTERVAL_MS
            screenOn -> IDLE_SCREEN_ON_INTERVAL_MS
            else -> SCREEN_OFF_INTERVAL_MS
        }
    }
}