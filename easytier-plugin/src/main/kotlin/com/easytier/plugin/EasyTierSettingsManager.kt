package com.easytier.plugin

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Settings manager for EasyTier plugin configuration.
 *
 * Stores all EasyTier-related settings in SharedPreferences (default
 * SharedPreferences) using the `easytier_` key prefix.  This keeps
 * EasyTier settings separate from v2rayNG's MMKV storage, avoiding
 * any coupling to v2rayNG internals.
 *
 * v2rayNG's SettingsManager can call [EasyTierSettingsManager.getEasyTierConfig]
 * to get a ready-to-use [EasyTierConfig] instance.
 */
object EasyTierSettingsManager {

    private const val PREFIX = "easytier_"

    // Keys
    const val KEY_ENABLED = PREFIX + "enabled"
    const val KEY_HOSTNAME = PREFIX + "hostname"
    const val KEY_NETWORK_NAME = PREFIX + "network_name"
    const val KEY_NETWORK_SECRET = PREFIX + "network_secret"
    const val KEY_VIRTUAL_IP = PREFIX + "virtual_ip"
    const val KEY_PEERS = PREFIX + "peers"
    const val KEY_SOCKS5_PORT = PREFIX + "socks5_port"
    const val KEY_NO_TUN = PREFIX + "no_tun"
    const val KEY_MTU = PREFIX + "mtu"
    const val KEY_LOG_LEVEL = PREFIX + "log_level"

    // Defaults
    const val DEFAULT_SOCKS5_PORT = EasyTierPlugin.DEFAULT_SOCKS5_PORT
    const val DEFAULT_LOG_LEVEL = "warn"

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun getHostname(context: Context): String? =
        prefs(context).getString(KEY_HOSTNAME, null)?.takeIf { it.isNotBlank() }

    fun getNetworkName(context: Context): String =
        prefs(context).getString(KEY_NETWORK_NAME, "") ?: ""

    fun getNetworkSecret(context: Context): String =
        prefs(context).getString(KEY_NETWORK_SECRET, "") ?: ""

    fun getVirtualIp(context: Context): String? =
        prefs(context).getString(KEY_VIRTUAL_IP, null)?.takeIf { it.isNotBlank() }

    fun getPeers(context: Context): List<String> =
        prefs(context).getString(KEY_PEERS, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun getSocks5Port(context: Context): Int =
        prefs(context).getString(KEY_SOCKS5_PORT, DEFAULT_SOCKS5_PORT.toString())?.toIntOrNull()
            ?: DEFAULT_SOCKS5_PORT

    fun isNoTun(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NO_TUN, true)

    fun getMtu(context: Context): Int? =
        prefs(context).getString(KEY_MTU, null)?.toIntOrNull()

    fun getLogLevel(context: Context): String =
        prefs(context).getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL

    // ------------------------------------------------------------------
    // Setters
    // ------------------------------------------------------------------

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setHostname(context: Context, hostname: String?) {
        prefs(context).edit().putString(KEY_HOSTNAME, hostname).apply()
    }

    fun setNetworkName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NETWORK_NAME, name).apply()
    }

    fun setNetworkSecret(context: Context, secret: String) {
        prefs(context).edit().putString(KEY_NETWORK_SECRET, secret).apply()
    }

    fun setVirtualIp(context: Context, ip: String?) {
        prefs(context).edit().putString(KEY_VIRTUAL_IP, ip).apply()
    }

    fun setPeers(context: Context, peers: List<String>) {
        prefs(context).edit().putString(KEY_PEERS, peers.joinToString(",")).apply()
    }

    fun setSocks5Port(context: Context, port: Int) {
        prefs(context).edit().putString(KEY_SOCKS5_PORT, port.toString()).apply()
    }

    fun setNoTun(context: Context, noTun: Boolean) {
        prefs(context).edit().putBoolean(KEY_NO_TUN, noTun).apply()
    }

    fun setMtu(context: Context, mtu: Int?) {
        prefs(context).edit().putString(KEY_MTU, mtu?.toString()).apply()
    }

    fun setLogLevel(context: Context, level: String) {
        prefs(context).edit().putString(KEY_LOG_LEVEL, level).apply()
    }

    // ------------------------------------------------------------------
    // Composite
    // ------------------------------------------------------------------

    /**
     * Build an [EasyTierConfig] from stored settings.
     * Returns `null` if EasyTier is disabled or network name is empty.
     */
    fun getEasyTierConfig(context: Context): EasyTierConfig? {
        if (!isEnabled(context)) return null
        val networkName = getNetworkName(context)
        if (networkName.isBlank()) return null

        return EasyTierConfig(
            enabled = true,
            instanceName = EasyTierPlugin.DEFAULT_INSTANCE_NAME,
            hostname = getHostname(context),
            networkName = networkName,
            networkSecret = getNetworkSecret(context),
            virtualIp = getVirtualIp(context),
            peers = getPeers(context),
            socks5Port = getSocks5Port(context),
            noTun = isNoTun(context),
            mtu = getMtu(context),
            logLevel = getLogLevel(context),
        )
    }
}
