package com.easytier.plugin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings manager for EasyTier plugin configuration.
 *
 * Stores all EasyTier-related settings in SharedPreferences (default
 * SharedPreferences) using the `easytier_` key prefix.  This keeps
 * EasyTier settings separate from v2rayNG's MMKV storage, avoiding
 * any coupling to v2rayNG internals.
 *
 * The network secret is stored in a separate [EncryptedSharedPreferences]
 * file (`easytier_secret`) using AES-256-GCM, so it is not visible in
 * the plain XML of the default SharedPreferences.  On first read after
 * upgrade, the secret is migrated from plaintext to the encrypted store.
 *
 * v2rayNG's SettingsManager can call [EasyTierSettingsManager.getEasyTierConfig]
 * to get a ready-to-use [EasyTierConfig] instance.
 */
object EasyTierSettingsManager {

    private const val TAG = "EasyTierSettings"
    private const val PREFIX = "easytier_"
    private const val SECRET_FILE = "easytier_secret"
    private const val MIGRATED_FLAG = "easytier_secret_migrated"

    // Keys
    const val KEY_ENABLED = PREFIX + "enabled"
    const val KEY_HOSTNAME = PREFIX + "hostname"
    const val KEY_NETWORK_NAME = PREFIX + "network_name"
    const val KEY_NETWORK_SECRET = PREFIX + "network_secret"
    const val KEY_VIRTUAL_IP = PREFIX + "virtual_ip"
    const val KEY_PEERS = PREFIX + "peers"
    const val KEY_SOCKS5_PORT = PREFIX + "socks5_port"
    const val KEY_LOG_ENABLED = PREFIX + "log_enabled"
    const val KEY_MTU = PREFIX + "mtu"
    const val KEY_LOG_LEVEL = PREFIX + "log_level"

    // Defaults
    const val DEFAULT_SOCKS5_PORT = EasyTierPlugin.DEFAULT_SOCKS5_PORT
    const val DEFAULT_LOG_LEVEL = "warn"

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /**
     * Lazily-created encrypted SharedPreferences for the network secret.
     * Falls back to plaintext prefs if EncryptedSharedPreferences fails
     * (e.g. on a corrupted keystore), so the app never crashes on read.
     */
    @Volatile
    private var secretPrefs: SharedPreferences? = null
    @Volatile
    private var secretMigrationDone = false

    private fun secretPrefs(context: Context): SharedPreferences {
        secretPrefs?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val sp = EncryptedSharedPreferences.create(
                context.applicationContext,
                SECRET_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            secretPrefs = sp
            migrateSecretIfNeeded(context, sp)
            sp
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to plaintext", e)
            val fallback = prefs(context)
            secretPrefs = fallback
            fallback
        }
    }

    /**
     * One-time migration: move the network secret from plaintext default prefs
     * to the encrypted store, then delete the plaintext copy.
     */
    private fun migrateSecretIfNeeded(context: Context, encrypted: SharedPreferences) {
        if (secretMigrationDone) return
        if (encrypted.getBoolean(MIGRATED_FLAG, false)) {
            secretMigrationDone = true
            return
        }
        try {
            val plain = prefs(context)
            val plainSecret = plain.getString(KEY_NETWORK_SECRET, null)
            if (plainSecret != null && plainSecret.isNotEmpty()) {
                encrypted.edit().putString(KEY_NETWORK_SECRET, plainSecret).apply()
                plain.edit().remove(KEY_NETWORK_SECRET).apply()
                Log.i(TAG, "Migrated network secret from plaintext to encrypted store")
            }
            encrypted.edit().putBoolean(MIGRATED_FLAG, true).apply()
        } catch (e: Throwable) {
            Log.e(TAG, "Network secret migration failed (non-fatal)", e)
        }
        secretMigrationDone = true
    }

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
        secretPrefs(context).getString(KEY_NETWORK_SECRET, "") ?: ""

    fun getVirtualIp(context: Context): String? =
        prefs(context).getString(KEY_VIRTUAL_IP, null)?.takeIf { it.isNotBlank() }

    fun getPeers(context: Context): List<String> =
        prefs(context).getString(KEY_PEERS, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun getSocks5Port(context: Context): Int {
        val stored = prefs(context).getString(KEY_SOCKS5_PORT, DEFAULT_SOCKS5_PORT.toString())?.toIntOrNull()
        return if (stored != null && stored in 1..65535) stored else DEFAULT_SOCKS5_PORT
    }

    fun isLogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOG_ENABLED, true)

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
        secretPrefs(context).edit().putString(KEY_NETWORK_SECRET, secret).apply()
    }

    fun setVirtualIp(context: Context, ip: String?) {
        prefs(context).edit().putString(KEY_VIRTUAL_IP, ip).apply()
    }

    fun setPeers(context: Context, peers: List<String>) {
        prefs(context).edit().putString(KEY_PEERS, peers.joinToString(",")).apply()
    }

    fun setSocks5Port(context: Context, port: Int) {
        if (port in 1..65535) {
            prefs(context).edit().putString(KEY_SOCKS5_PORT, port.toString()).apply()
        }
    }

    fun setLogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOG_ENABLED, enabled).apply()
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
            noTun = true, // always on for v2rayNG coexistence
            mtu = getMtu(context),
            logLevel = getLogLevel(context),
        )
    }}
