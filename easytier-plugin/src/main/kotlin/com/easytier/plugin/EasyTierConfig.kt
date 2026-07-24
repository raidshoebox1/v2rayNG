package com.easytier.plugin

/**
 * EasyTier mesh network configuration.
 *
 * This data class holds all parameters needed to start an EasyTier instance
 * in no-tun + SOCKS5 mode.  It is serialised to TOML before being passed
 * to the EasyTier JNI layer.
 *
 * @property enabled        Master switch.  When `false`, the plugin is skipped entirely.
 * @property instanceName   Unique EasyTier instance name.
 * @property networkName    EasyTier network name (must match across peers).
 * @property networkSecret  Network secret key (optional but recommended).
 * @property virtualIp      Virtual IPv4 address (e.g. `"10.144.144.1"`).  `null` = auto-assign.
 * @property peers          List of peer URIs (e.g. `["tcp://public.easytier.top:11010"]`).
 * @property listeners      Listener URIs.  Defaults to SOCKS5 on the configured port.
 * @property socks5Port     Local SOCKS5 port that Xray-core will connect to.
 * @property noTun          If `true`, EasyTier runs without a TUN device (required for coexistence with v2rayNG VPN).
 * @property mtu            MTU override.  `null` = EasyTier default (1420 for most transports).
 * @property logLevel       EasyTier log level: `"error"`, `"warn"`, `"info"`, `"debug"`, `"trace"`.
 * @property powerSaving    If `true`, injects power-saving flags into the TOML `[flags]` section:
 *                          `lazy_p2p`, `disable_upnp`, `latency_first`, `multi_thread_count=1`,
 *                          and longer PeerConn heartbeat intervals.  Designed to reduce CPU
 *                          wakeups and background traffic on Android without sacrificing
 *                          TCP/UDP hole punching or encryption.
 */
data class EasyTierConfig(
    var enabled: Boolean = false,
    var instanceName: String = EasyTierPlugin.DEFAULT_INSTANCE_NAME,
    var hostname: String? = null,
    var networkName: String = "",
    var networkSecret: String = "",
    var virtualIp: String? = null,
    var peers: List<String> = emptyList(),
    var listeners: List<String> = emptyList(),
    var socks5Port: Int = EasyTierPlugin.DEFAULT_SOCKS5_PORT,
    var noTun: Boolean = true,
    var mtu: Int? = null,
    var logLevel: String = "warn",
    var powerSaving: Boolean = true,
) {

    /**
     * Convert to EasyTier TOML configuration string.
     *
     * The TOML format matches what `easytier-ffi` / `easytier-android-jni`
     * expects via `EasyTierJNI.parseConfig()` / `runNetworkInstance()`.
     *
     * Field names match the Rust `Config` struct in `easytier/src/common/config.rs`:
     * - `instance_name` (NOT `inst_name`)
     * - `[network_identity]` with `network_name` / `network_secret`
     * - `socks5_proxy` (NOT `listeners` with `socks5://`)
     * - `[[peer]]` array-of-tables (NOT `peers = [...]`)
     * - `[flags]` with `no_tun`, `mtu`
     * - `[console_logger]` with `level` (NOT top-level `log_level`)
     */
    fun toToml(): String {
        val sb = StringBuilder()

        // ─── TOP-LEVEL FIELDS (must come before any [section] in TOML) ───

        // instance_name (top-level)
        sb.appendLine("instance_name = \"${escapeTomlString(instanceName)}\"")

        // hostname (top-level, optional) — node name shown in mesh peer list.
        // Rust Config field: hostname: Option<String>
        // If empty/omitted, EasyTier falls back to the OS hostname (usually "localhost" on Android).
        // Max 32 chars; control chars stripped by EasyTier's get_hostname().
        if (!hostname.isNullOrBlank()) {
            sb.appendLine("hostname = \"${escapeTomlString(hostname)}\"")
        }

        // SOCKS5 proxy listener — top-level field.
        // Bind to 127.0.0.1 (loopback only) so the SOCKS5 port is NOT exposed to
        // other devices on the LAN. Xray-core connects in-process via 127.0.0.1.
        // Rust Config field: socks5_proxy: Option<url::Url>
        sb.appendLine("socks5_proxy = \"socks5://127.0.0.1:$socks5Port\"")

        // listeners (optional, for peer discovery — NOT for SOCKS5)
        // EasyTier listener schemes: tcp://, udp://, wg://, ws://, wss://
        if (listeners.isNotEmpty()) {
            sb.appendLine("listeners = [${listeners.joinToString(", ") { "\"${escapeTomlString(it)}\"" }}]")
        }

        // virtual IP (optional, top-level field)
        if (!virtualIp.isNullOrBlank()) {
            sb.appendLine("ipv4 = \"${escapeTomlString(virtualIp)}\"")
        }

        // ─── SECTIONS (must come after all top-level fields) ───

        // network_identity section
        sb.appendLine()
        sb.appendLine("[network_identity]")
        sb.appendLine("network_name = \"${escapeTomlString(networkName)}\"")
        if (networkSecret.isNotEmpty()) {
            sb.appendLine("network_secret = \"${escapeTomlString(networkSecret)}\"")
        }

        // peers — must use [[peer]] array-of-tables syntax
        // Rust Config field: peer: Option<Vec<PeerConfig>> where PeerConfig { uri: url::Url }
        for (peer in peers) {
            sb.appendLine()
            sb.appendLine("[[peer]]")
            sb.appendLine("uri = \"${escapeTomlString(peer)}\"")
        }

        // flags section — no_tun is critical for v2rayNG coexistence
        // Must come last so it doesn't swallow top-level fields.
        sb.appendLine()
        sb.appendLine("[flags]")
        sb.appendLine("no_tun = $noTun")
        if (mtu != null) {
            sb.appendLine("mtu = $mtu")
        }

        // ─── Power-saving flags (balance tier) ───
        // When enabled, injects flags that reduce CPU wakeups and background
        // traffic on Android without sacrificing TCP/UDP hole punching,
        // P2P, or encryption.  These override the EasyTier Rust defaults
        // (which are tuned for desktop/server use).
        //
        // Key trade-offs:
        //   lazy_p2p=true           — delay P2P connections until needed
        //   disable_upnp=true       — no background UPnP port mapping
        //   latency_first=true      — prefer stable low-latency paths
        //   multi_thread_count=1    — single worker thread (fewer wakeups)
        //   mobile_power_saving=true— Rust core uses longer interval timers
        //   peer_conn_max_heartbeat_interval_secs=60 — max 60s between pings
        //   peer_conn_max_missed_heartbeats=5         — tolerate 5 missed pongs
        //   peer_conn_pong_timeout_secs=5             — 5s pong wait
        //   quic_keepalive_interval_secs=30           — 30s QUIC keepalive
        //
        // NOT changed (preserved for connectivity):
        //   enable_encryption       — stays true (security)
        //   disable_p2p             — stays false (P2P still works)
        //   disable_tcp_hole_punching — stays false (TCP hole punching active)
        //   disable_udp_hole_punching — stays false (UDP hole punching active)
        if (powerSaving) {
            sb.appendLine("lazy_p2p = true")
            sb.appendLine("disable_upnp = true")
            sb.appendLine("latency_first = true")
            sb.appendLine("multi_thread_count = 1")
            sb.appendLine("mobile_power_saving = true")
            sb.appendLine("peer_conn_max_heartbeat_interval_secs = 60")
            sb.appendLine("peer_conn_max_missed_heartbeats = 5")
            sb.appendLine("peer_conn_pong_timeout_secs = 5")
            sb.appendLine("quic_keepalive_interval_secs = 30")
        }

        // ─── Console logger ───
        // Control the tracing subscriber level to avoid the CPU overhead
        // of formatting and writing info/debug/trace messages on Android.
        // The plugin default is "warn"; the user can change it via logLevel.
        sb.appendLine()
        sb.appendLine("[console_logger]")
        sb.appendLine("level = \"${escapeTomlString(logLevel)}\"")

        return sb.toString().trimEnd()
    }

    companion object {
        /** Default EasyTier log level. */
        const val DEFAULT_LOG_LEVEL = "warn"

        /**
         * Escape a string for safe embedding in a TOML basic string.
         *
         * Handles backslash and double-quote (the two characters that must be
         * escaped in TOML basic strings) and strips control characters that
         * could break the TOML structure or confuse the parser. Newlines and
         * other control chars are replaced with a space to keep the value on
         * a single line.
         */
        private fun escapeTomlString(value: String?): String {
            if (value.isNullOrEmpty()) return ""
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ")
                .filter { it.code >= 0x20 } // strip remaining control chars
        }
    }
}
