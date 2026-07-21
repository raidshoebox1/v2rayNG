package com.easytier.plugin

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

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
 * **Cross-process snapshot file:**
 * The v2rayNG VPN services (CoreVpnService, CoreProxyOnlyService,
 * CoreRootService) run in a separate process (`:RunSoLibV2RayDaemon`).
 * Android's SharedPreferences is NOT multi-process safe — the service
 * process would see stale cached values after the user changes settings
 * in the main process (EasyTierSettingsActivity).  To fix this, every
 * setter also writes a JSON snapshot of all settings to a file in the
 * app's private files directory.  [getEasyTierConfig] reads from this
 * file (always fresh from disk) instead of SharedPreferences, so the
 * service process always sees the latest values.
 *
 * v2rayNG's SettingsManager can call [EasyTierSettingsManager.getEasyTierConfig]
 * to get a ready-to-use [EasyTierConfig] instance.
 */
object EasyTierSettingsManager {

    private const val TAG = "EasyTierSettings"
    private const val PREFIX = "easytier_"
    private const val SECRET_FILE = "easytier_secret"
    private const val MIGRATED_FLAG = "easytier_secret_migrated"
    private const val SNAPSHOT_FILE = "easytier_config.json"

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

    // ------------------------------------------------------------------
    // Cross-process snapshot file
    // ------------------------------------------------------------------

    /**
     * The snapshot file lives in the app's private files directory, which
     * is shared across all processes of the app.  Writes are atomic
     * (write to temp file, then rename) to avoid corruption.
     */
    private fun snapshotFile(context: Context): File =
        File(context.applicationContext.filesDir, SNAPSHOT_FILE)

    /**
     * Write a JSON snapshot of all non-secret settings to the snapshot file.
     * Called by every setter so the service process always sees the
     * latest values when it reads from the file.
     *
     * The network secret is intentionally excluded from the snapshot —
     * it lives only in [EncryptedSharedPreferences] so that no plaintext
     * copy of it ever reaches the filesystem.
     */
    private fun writeSnapshot(context: Context) {
        try {
            val sp = prefs(context)
            val json = JsonObject().apply {
                addProperty(KEY_ENABLED, sp.getBoolean(KEY_ENABLED, false))
                addProperty(KEY_HOSTNAME, sp.getString(KEY_HOSTNAME, null) ?: "")
                addProperty(KEY_NETWORK_NAME, sp.getString(KEY_NETWORK_NAME, "") ?: "")
                addProperty(KEY_VIRTUAL_IP, sp.getString(KEY_VIRTUAL_IP, null) ?: "")
                addProperty(KEY_PEERS, sp.getString(KEY_PEERS, "") ?: "")
                addProperty(KEY_SOCKS5_PORT, sp.getString(KEY_SOCKS5_PORT, DEFAULT_SOCKS5_PORT.toString()) ?: DEFAULT_SOCKS5_PORT.toString())
                addProperty(KEY_LOG_ENABLED, sp.getBoolean(KEY_LOG_ENABLED, true))
                addProperty(KEY_MTU, sp.getString(KEY_MTU, null) ?: "")
                addProperty(KEY_LOG_LEVEL, sp.getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL)
            }
            val file = snapshotFile(context)
            val tmp = File(file.parentFile, "$SNAPSHOT_FILE.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write config snapshot", e)
        }
    }

    /**
     * Read the JSON snapshot from disk.  Returns null if the file does
     * not exist (e.g. first run before any setter has been called) or
     * cannot be parsed.  The caller falls back to SharedPreferences in
     * that case.
     */
    private fun readSnapshot(context: Context): JsonObject? {
        return try {
            val file = snapshotFile(context)
            if (!file.exists()) return null
            JsonParser.parseString(file.readText()).asJsonObject
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read config snapshot", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // Encrypted SharedPreferences for network secret
    // ------------------------------------------------------------------

    /**
     * Lazily-created encrypted SharedPreferences for the network secret.
     *
     * Returns `null` if EncryptedSharedPreferences cannot be created (e.g.
     * on a corrupted keystore).  In that case callers treat the secret as
     * empty/unavailable — we never fall back to plaintext storage, because
     * silently downgrading the network secret to plaintext would expose it
     * to anyone with read access to the app's SharedPreferences XML.
     */
    @Volatile
    private var secretPrefs: SharedPreferences? = null
    @Volatile
    private var secretMigrationDone = false

    private fun secretPrefs(context: Context): SharedPreferences? {
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
            Log.e(TAG, "Failed to create EncryptedSharedPreferences; secret storage unavailable", e)
            null
        }
    }

    /**
     * Whether the encrypted secret store is available.  When `false`,
     * [getNetworkSecret] returns an empty string and [setNetworkSecret]
     * is a no-op.  The UI should check this and warn the user.
     */
    fun isSecretStoreAvailable(context: Context): Boolean = secretPrefs(context) != null

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
    // Getters — read from snapshot file (cross-process safe), fall back to SharedPreferences
    // ------------------------------------------------------------------

    fun isEnabled(context: Context): Boolean {
        val snap = readSnapshot(context)
        return snap?.get(KEY_ENABLED)?.asBoolean ?: prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun getHostname(context: Context): String? {
        val snap = readSnapshot(context)
        val v = snap?.get(KEY_HOSTNAME)?.asString ?: prefs(context).getString(KEY_HOSTNAME, null)
        return v?.takeIf { it.isNotBlank() }
    }

    fun getNetworkName(context: Context): String {
        val snap = readSnapshot(context)
        return snap?.get(KEY_NETWORK_NAME)?.asString ?: (prefs(context).getString(KEY_NETWORK_NAME, "") ?: "")
    }

    fun getNetworkSecret(context: Context): String {
        // The network secret is NEVER stored in the cross-process snapshot
        // file.  It lives only in EncryptedSharedPreferences, which is backed
        // by an encrypted XML file on disk.  The VPN service process reads
        // this file fresh on first access (SharedPreferences are cached per
        // process, and the VPN process has not cached it yet at start time).
        return secretPrefs(context)?.getString(KEY_NETWORK_SECRET, "") ?: ""
    }

    fun getVirtualIp(context: Context): String? {
        val snap = readSnapshot(context)
        val v = snap?.get(KEY_VIRTUAL_IP)?.asString ?: prefs(context).getString(KEY_VIRTUAL_IP, null)
        return v?.takeIf { it.isNotBlank() }
    }

    fun getPeers(context: Context): List<String> {
        val snap = readSnapshot(context)
        val raw = snap?.get(KEY_PEERS)?.asString ?: prefs(context).getString(KEY_PEERS, "")
        return raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    fun getSocks5Port(context: Context): Int {
        val snap = readSnapshot(context)
        val stored = snap?.get(KEY_SOCKS5_PORT)?.asString ?: (prefs(context).getString(KEY_SOCKS5_PORT, DEFAULT_SOCKS5_PORT.toString()) ?: DEFAULT_SOCKS5_PORT.toString())
        val port = stored.toIntOrNull()
        return if (port != null && port in 1..65535) port else DEFAULT_SOCKS5_PORT
    }

    fun isLogEnabled(context: Context): Boolean {
        val snap = readSnapshot(context)
        return snap?.get(KEY_LOG_ENABLED)?.asBoolean ?: prefs(context).getBoolean(KEY_LOG_ENABLED, true)
    }

    fun getMtu(context: Context): Int? {
        val snap = readSnapshot(context)
        val v = snap?.get(KEY_MTU)?.asString ?: prefs(context).getString(KEY_MTU, null)
        return v?.toIntOrNull()
    }

    fun getLogLevel(context: Context): String {
        val snap = readSnapshot(context)
        return snap?.get(KEY_LOG_LEVEL)?.asString ?: (prefs(context).getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL)
    }

    // ------------------------------------------------------------------
    // Setters — write to SharedPreferences AND snapshot file
    // ------------------------------------------------------------------

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        writeSnapshot(context)
    }

    fun setHostname(context: Context, hostname: String?) {
        prefs(context).edit().putString(KEY_HOSTNAME, hostname).apply()
        writeSnapshot(context)
    }

    fun setNetworkName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NETWORK_NAME, name).apply()
        writeSnapshot(context)
    }

    fun setNetworkSecret(context: Context, secret: String) {
        val sp = secretPrefs(context)
        if (sp == null) {
            Log.e(TAG, "Cannot persist network secret: encrypted store unavailable")
            return
        }
        sp.edit().putString(KEY_NETWORK_SECRET, secret).apply()
        writeSnapshot(context)
    }

    fun setVirtualIp(context: Context, ip: String?) {
        prefs(context).edit().putString(KEY_VIRTUAL_IP, ip).apply()
        writeSnapshot(context)
    }

    fun setPeers(context: Context, peers: List<String>) {
        prefs(context).edit().putString(KEY_PEERS, peers.joinToString(",")).apply()
        writeSnapshot(context)
    }

    fun setSocks5Port(context: Context, port: Int) {
        if (port in 1..65535) {
            prefs(context).edit().putString(KEY_SOCKS5_PORT, port.toString()).apply()
            writeSnapshot(context)
        }
    }

    fun setLogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOG_ENABLED, enabled).apply()
        writeSnapshot(context)
    }

    fun setMtu(context: Context, mtu: Int?) {
        prefs(context).edit().putString(KEY_MTU, mtu?.toString()).apply()
        writeSnapshot(context)
    }

    fun setLogLevel(context: Context, level: String) {
        prefs(context).edit().putString(KEY_LOG_LEVEL, level).apply()
        writeSnapshot(context)
    }

    /**
     * Force-write a snapshot from the current SharedPreferences state.
     * Called from [EasyTierSettingsActivity.onPause] as a safety net
     * to ensure the snapshot file is up-to-date even if an individual
     * setter was missed.
     */
    fun flushSnapshot(context: Context) {
        writeSnapshot(context)
    }

    // ------------------------------------------------------------------
    // Composite
    // ------------------------------------------------------------------

    /**
     * Build an [EasyTierConfig] from stored settings.
     * Returns `null` if EasyTier is disabled or network name is empty.
     *
     * Reads from the cross-process snapshot file (always fresh from disk)
     * so that the VPN service process (`:RunSoLibV2RayDaemon`) sees the
     * latest values written by the settings activity in the main process.
     * The snapshot is read exactly once to avoid inconsistency if the file
     * is being written concurrently.
     */
    fun getEasyTierConfig(context: Context): EasyTierConfig? {
        val snap = readSnapshot(context)
        val sp = prefs(context)

        val enabled = snap?.get(KEY_ENABLED)?.asBoolean ?: sp.getBoolean(KEY_ENABLED, false)
        if (!enabled) return null

        val networkName = snap?.get(KEY_NETWORK_NAME)?.asString ?: (sp.getString(KEY_NETWORK_NAME, "") ?: "")
        if (networkName.isBlank()) return null

        val hostname = (snap?.get(KEY_HOSTNAME)?.asString ?: sp.getString(KEY_HOSTNAME, null))?.takeIf { it.isNotBlank() }
        val networkSecret = secretPrefs(context)?.getString(KEY_NETWORK_SECRET, "") ?: ""
        val virtualIp = (snap?.get(KEY_VIRTUAL_IP)?.asString ?: sp.getString(KEY_VIRTUAL_IP, null))?.takeIf { it.isNotBlank() }
        val peersRaw = snap?.get(KEY_PEERS)?.asString ?: sp.getString(KEY_PEERS, "")
        val peers = peersRaw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val socks5PortStr = snap?.get(KEY_SOCKS5_PORT)?.asString ?: (sp.getString(KEY_SOCKS5_PORT, DEFAULT_SOCKS5_PORT.toString()) ?: DEFAULT_SOCKS5_PORT.toString())
        val socks5Port = socks5PortStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_SOCKS5_PORT
        val mtu = (snap?.get(KEY_MTU)?.asString ?: sp.getString(KEY_MTU, null))?.toIntOrNull()
        val logLevel = snap?.get(KEY_LOG_LEVEL)?.asString ?: (sp.getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL)

        return EasyTierConfig(
            enabled = true,
            instanceName = EasyTierPlugin.DEFAULT_INSTANCE_NAME,
            hostname = hostname,
            networkName = networkName,
            networkSecret = networkSecret,
            virtualIp = virtualIp,
            peers = peers,
            socks5Port = socks5Port,
            noTun = true,
            mtu = mtu,
            logLevel = logLevel,
        )
    }

    // ------------------------------------------------------------------
    // Backup / Restore
    // ------------------------------------------------------------------

    /**
     * Export all EasyTier settings (including the network secret in plaintext)
     * to a [JsonObject] suitable for inclusion in v2rayNG's backup ZIP.
     *
     * The network secret is included in plaintext so that it survives a
     * backup → uninstall → install → restore cycle, where the signing key
     * (and thus the Android Keystore key used by EncryptedSharedPreferences)
     * may have changed.  [importFromJson] re-encrypts the secret on restore.
     */
    fun exportToJson(context: Context): JsonObject {
        val ctx = context.applicationContext
        val json = JsonObject().apply {
            addProperty(KEY_ENABLED, isEnabled(ctx))
            addProperty(KEY_HOSTNAME, getHostname(ctx) ?: "")
            addProperty(KEY_NETWORK_NAME, getNetworkName(ctx))
            addProperty(KEY_NETWORK_SECRET, getNetworkSecret(ctx))
            addProperty(KEY_VIRTUAL_IP, getVirtualIp(ctx) ?: "")
            addProperty(KEY_PEERS, getPeers(ctx).joinToString(","))
            addProperty(KEY_SOCKS5_PORT, getSocks5Port(ctx))
            addProperty(KEY_LOG_ENABLED, isLogEnabled(ctx))
            addProperty(KEY_MTU, getMtu(ctx)?.toString() ?: "")
            addProperty(KEY_LOG_LEVEL, getLogLevel(ctx))
        }
        Log.i(TAG, "exportToJson: exported ${json.size()} keys")
        return json
    }

    /**
     * Import EasyTier settings from a [JsonObject] previously produced by
     * [exportToJson].  Writes all non-secret fields to SharedPreferences in
     * a single [commit] (synchronous disk write) and re-encrypts the network
     * secret via [setNetworkSecret].  Missing keys are silently skipped so a
     * partial or older-format backup does not corrupt current settings.
     */
    fun importFromJson(context: Context, json: JsonObject) {
        val ctx = context.applicationContext
        Log.i(TAG, "importFromJson: received ${json.size()} keys")

        // Batch all default-SharedPreferences writes into a single editor
        // and commit synchronously to ensure they are persisted to disk
        // before the caller checks any values.
        val sp = prefs(ctx)
        val editor = sp.edit()
        var hasAny = false

        if (json.has(KEY_ENABLED)) {
            editor.putBoolean(KEY_ENABLED, json.get(KEY_ENABLED).asBoolean)
            hasAny = true
        }
        if (json.has(KEY_HOSTNAME)) {
            editor.putString(KEY_HOSTNAME, json.get(KEY_HOSTNAME).asString.ifBlank { null })
            hasAny = true
        }
        if (json.has(KEY_NETWORK_NAME)) {
            editor.putString(KEY_NETWORK_NAME, json.get(KEY_NETWORK_NAME).asString)
            hasAny = true
        }
        if (json.has(KEY_VIRTUAL_IP)) {
            editor.putString(KEY_VIRTUAL_IP, json.get(KEY_VIRTUAL_IP).asString.ifBlank { null })
            hasAny = true
        }
        if (json.has(KEY_PEERS)) {
            val peers = json.get(KEY_PEERS).asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            editor.putString(KEY_PEERS, peers.joinToString(","))
            hasAny = true
        }
        if (json.has(KEY_SOCKS5_PORT)) {
            val port = json.get(KEY_SOCKS5_PORT).asInt
            if (port in 1..65535) {
                editor.putString(KEY_SOCKS5_PORT, port.toString())
                hasAny = true
            }
        }
        if (json.has(KEY_LOG_ENABLED)) {
            editor.putBoolean(KEY_LOG_ENABLED, json.get(KEY_LOG_ENABLED).asBoolean)
            hasAny = true
        }
        if (json.has(KEY_MTU)) {
            val mtuStr = json.get(KEY_MTU).asString
            editor.putString(KEY_MTU, mtuStr.toIntOrNull()?.toString())
            hasAny = true
        }
        if (json.has(KEY_LOG_LEVEL)) {
            editor.putString(KEY_LOG_LEVEL, json.get(KEY_LOG_LEVEL).asString)
            hasAny = true
        }

        if (hasAny) {
            editor.commit()
        }

        // Network secret is stored in EncryptedSharedPreferences — write
        // separately via setNetworkSecret, which also updates the snapshot.
        if (json.has(KEY_NETWORK_SECRET)) {
            setNetworkSecret(ctx, json.get(KEY_NETWORK_SECRET).asString)
        }

        // Write the cross-process snapshot file from the now-persisted
        // SharedPreferences so the VPN service process sees the restored values.
        flushSnapshot(ctx)
        Log.i(TAG, "importFromJson: done (hasAny=$hasAny)")
    }
}
