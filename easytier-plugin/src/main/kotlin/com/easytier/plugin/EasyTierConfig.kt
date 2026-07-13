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
        sb.appendLine("""instance_name = "$instanceName"""")

        // hostname (top-level, optional) — node name shown in mesh peer list.
        // Rust Config field: hostname: Option<String>
        // If empty/omitted, EasyTier falls back to the OS hostname (usually "localhost" on Android).
        // Max 32 chars; control chars stripped by EasyTier's get_hostname().
        if (!hostname.isNullOrBlank()) {
            val safe = hostname!!.replace("\"", "\\\"")
            sb.appendLine("""hostname = "$safe"""")
        }

        // SOCKS5 proxy listener — top-level field
        // Rust Config field: socks5_proxy: Option<url::Url>
        sb.appendLine("""socks5_proxy = "socks5://0.0.0.0:$socks5Port"""")

        // listeners (optional, for peer discovery — NOT for SOCKS5)
        // EasyTier listener schemes: tcp://, udp://, wg://, ws://, wss://
        if (listeners.isNotEmpty()) {
            sb.appendLine("listeners = [${listeners.joinToString(", ") { "\"$it\"" }}]")
        }

        // virtual IP (optional, top-level field)
        if (!virtualIp.isNullOrBlank()) {
            sb.appendLine("""ipv4 = "$virtualIp"""")
        }

        // ─── SECTIONS (must come after all top-level fields) ───

        // network_identity section
        sb.appendLine()
        sb.appendLine("[network_identity]")
        sb.appendLine("""network_name = "$networkName"""")
        if (networkSecret.isNotEmpty()) {
            sb.appendLine("""network_secret = "$networkSecret"""")
        }

        // peers — must use [[peer]] array-of-tables syntax
        // Rust Config field: peer: Option<Vec<PeerConfig>> where PeerConfig { uri: url::Url }
        for (peer in peers) {
            sb.appendLine()
            sb.appendLine("[[peer]]")
            sb.appendLine("""uri = "$peer"""")
        }

        // flags section — no_tun is critical for v2rayNG coexistence
        // Must come last so it doesn't swallow top-level fields.
        sb.appendLine()
        sb.appendLine("[flags]")
        sb.appendLine("no_tun = $noTun")
        if (mtu != null) {
            sb.appendLine("mtu = $mtu")
        }

        return sb.toString().trimEnd()
    }

    companion object {
        /**
         * Create an [EasyTierConfig] from a flat key-value map (e.g. from MMKV settings).
         *
         * Expected keys (all optional except `network_name`):
         * - `easytier_enabled` (Boolean)
         * - `easytier_hostname` (String, optional)
         * - `easytier_network_name` (String)
         * - `easytier_network_secret` (String)
         * - `easytier_virtual_ip` (String)
         * - `easytier_peers` (String, comma-separated)
         * - `easytier_socks5_port` (Int or String)
         * - `easytier_no_tun` (Boolean, default true)
         * - `easytier_log_level` (String, default "warn")
         */
        fun fromMap(map: Map<String, Any?>): EasyTierConfig {
            return EasyTierConfig(
                enabled = map["easytier_enabled"] as? Boolean ?: false,
                hostname = (map["easytier_hostname"] as? String)?.takeIf { it.isNotBlank() },
                networkName = map["easytier_network_name"] as? String ?: "",
                networkSecret = map["easytier_network_secret"] as? String ?: "",
                virtualIp = (map["easytier_virtual_ip"] as? String)?.takeIf { it.isNotBlank() },
                peers = (map["easytier_peers"] as? String)
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                socks5Port = (map["easytier_socks5_port"] as? Number)?.toInt()
                    ?: (map["easytier_socks5_port"] as? String)?.toIntOrNull()
                    ?: EasyTierPlugin.DEFAULT_SOCKS5_PORT,
                noTun = map["easytier_no_tun"] as? Boolean ?: true,
                logLevel = map["easytier_log_level"] as? String ?: "warn",
            )
        }
    }
}
