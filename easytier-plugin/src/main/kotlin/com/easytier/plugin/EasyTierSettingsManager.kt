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

    /** Schema version for backup/restore format. Increment when the JSON shape changes. */
    private const val SCHEMA_VERSION = 1

    /** Backup/restore JSON key for the schema version. */
    private const val KEY_SCHEMA_VERSION = "__version"

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
    const val DEFAULT_LOG_LEVEL = EasyTierConfig.DEFAULT_LOG_LEVEL

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // ------------------------------------------------------------------
    // Typed preference helpers (reduce duplication across getters)
    // ------------------------------------------------------------------

    /** Read a Boolean from the snapshot (if available) or SharedPreferences. */
    private fun getBoolPref(context: Context, key: String, default: Boolean): Boolean {
        val snap = readSnapshot(context)
        return snap?.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean
            ?: prefs(context).getBoolean(key, default)
    }

    /** Read a String from the snapshot (if available) or SharedPreferences. */
    private fun getStringPref(context: Context, key: String, default: String?): String? {
        val snap = readSnapshot(context)
        return snap?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            ?: prefs(context).getString(key, default)
    }

    /** Read a nullable String (stored as "null" or missing) from the snapshot or SharedPreferences. */
    private fun getNullableStringPref(context: Context, key: String): String? {
        return getStringPref(context, key, null)?.takeIf { it.isNotBlank() }
    }

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
     * Build a JSON object containing all non-secret settings.
     *
     * Used by both [writeSnapshot] (cross-process file) and [exportToJson]
     * (backup), ensuring the two representations never diverge.
     *
     * @param includeVersion if true, adds a `__version` field for backup
     *   format migration support.
     */
    private fun buildSettingsJsonObject(context: Context, includeVersion: Boolean = false): JsonObject {
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
        if (includeVersion) {
            json.addProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        }
        return json
    }

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
            val json = buildSettingsJsonObject(context, includeVersion = false)
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

    fun isEnabled(context: Context): Boolean = getBoolPref(context, KEY_ENABLED, false)

    fun getHostname(context: Context): String? = getNullableStringPref(context, KEY_HOSTNAME)

    fun getNetworkName(context: Context): String = getStringPref(context, KEY_NETWORK_NAME, "") ?: ""

    fun getNetworkSecret(context: Context): String {
        // The network secret is NEVER stored in the cross-process snapshot
        // file.  It lives only in EncryptedSharedPreferences, which is backed
        // by an encrypted XML file on disk.  The VPN service process reads
        // this file fresh on first access (SharedPreferences are cached per
        // process, and the VPN process has not cached it yet at start time).
        return secretPrefs(context)?.getString(KEY_NETWORK_SECRET, "") ?: ""
    }

    fun getVirtualIp(context: Context): String? = getNullableStringPref(context, KEY_VIRTUAL_IP)

    fun getPeers(context: Context): List<String> {
        val raw = getStringPref(context, KEY_PEERS, "")
        return raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    fun getSocks5Port(context: Context): Int {
        val stored = getStringPref(context, KEY_SOCKS5_PORT, DEFAULT_SOCKS5_PORT.toString()) ?: DEFAULT_SOCKS5_PORT.toString()
        val port = stored.toIntOrNull()
        return if (port != null && port in 1..65535) port else DEFAULT_SOCKS5_PORT
    }

    fun isLogEnabled(context: Context): Boolean = getBoolPref(context, KEY_LOG_ENABLED, true)

    fun getMtu(context: Context): Int? = getStringPref(context, KEY_MTU, null)?.toIntOrNull()

    fun getLogLevel(context: Context): String = getStringPref(context, KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL

    // ------------------------------------------------------------------
    // Setters — write to SharedPreferences AND snapshot file
    //
    // SharedPreferences writes use commit() (synchronous) rather than
    // apply() (async) so that the snapshot file — which is written
    // immediately after — always reflects the persisted state.  This
    // avoids a window where the snapshot contains newer data than the
    // SharedPreferences file (which the VPN process may read directly).
    // ------------------------------------------------------------------

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        writeSnapshot(context)
    }

    fun setHostname(context: Context, hostname: String?) {
        prefs(context).edit().putString(KEY_HOSTNAME, hostname).commit()
        writeSnapshot(context)
    }

    fun setNetworkName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NETWORK_NAME, name).commit()
        writeSnapshot(context)
    }

    fun setNetworkSecret(context: Context, secret: String) {
        val sp = secretPrefs(context)
        if (sp == null) {
            Log.e(TAG, "Cannot persist network secret: encrypted store unavailable")
            return
        }
        sp.edit().putString(KEY_NETWORK_SECRET, secret).commit()
        // No writeSnapshot() — secret is intentionally excluded from the snapshot file.
    }

    fun setVirtualIp(context: Context, ip: String?) {
        prefs(context).edit().putString(KEY_VIRTUAL_IP, ip).commit()
        writeSnapshot(context)
    }

    fun setPeers(context: Context, peers: List<String>) {
        prefs(context).edit().putString(KEY_PEERS, peers.joinToString(",")).commit()
        writeSnapshot(context)
    }

    fun setSocks5Port(context: Context, port: Int) {
        if (port in 1..65535) {
            prefs(context).edit().putString(KEY_SOCKS5_PORT, port.toString()).commit()
            writeSnapshot(context)
        }
    }

    fun setLogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOG_ENABLED, enabled).commit()
        writeSnapshot(context)
    }

    fun setMtu(context: Context, mtu: Int?) {
        prefs(context).edit().putString(KEY_MTU, mtu?.toString()).commit()
        writeSnapshot(context)
    }

    fun setLogLevel(context: Context, level: String) {
        prefs(context).edit().putString(KEY_LOG_LEVEL, level).commit()
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
     * Delegates to the individual typed getters so that the reading logic
     * (snapshot → SharedPreferences fallback) lives in exactly one place
     * per field.  This is slightly less efficient than reading the snapshot
     * once (each getter calls [readSnapshot] independently), but
     * [getEasyTierConfig] is called only once per VPN start, so the
     * overhead is negligible.
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
            noTun = true,
            mtu = getMtu(context),
            logLevel = getLogLevel(context),
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
     *
     * The JSON includes a `__version` field for forward-compatible migration.
     */
    fun exportToJson(context: Context): JsonObject {
        val ctx = context.applicationContext
        val json = buildSettingsJsonObject(ctx, includeVersion = true)
        // Network secret is NOT in buildSettingsJsonObject (it lives only in
        // EncryptedSharedPreferences), so add it separately for backup.
        json.addProperty(KEY_NETWORK_SECRET, getNetworkSecret(ctx))
        Log.i(TAG, "exportToJson: exported ${json.size()} keys")
        return json
    }

    /**
     * Import EasyTier settings from a [JsonObject] previously produced by
     * [exportToJson].  Writes all non-secret fields to SharedPreferences in
     * a single [commit] (synchronous disk write) and re-encrypts the network
     * secret via [setNetworkSecret].  Missing keys are silently skipped so a
     * partial or older-format backup does not corrupt current settings.
     *
     * If the JSON contains a `__version` field, it is validated against
     * [SCHEMA_VERSION].  Unknown future versions are imported on a best-effort
     * basis with a warning log.
     */
    fun importFromJson(context: Context, json: JsonObject) {
        val ctx = context.applicationContext
        Log.i(TAG, "importFromJson: received ${json.size()} keys")

        // Schema version check (best-effort; missing field = version 0 = legacy)
        if (json.has(KEY_SCHEMA_VERSION)) {
            val version = json.get(KEY_SCHEMA_VERSION)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            if (version > SCHEMA_VERSION) {
                Log.w(TAG, "importFromJson: backup schema version $version is newer than current $SCHEMA_VERSION; importing on best-effort basis")
            }
        }

        // Batch all default-SharedPreferences writes into a single editor
        // and commit synchronously to ensure they are persisted to disk
        // before the caller checks any values.
        val sp = prefs(ctx)
        val editor = sp.edit()
        var hasAny = false

        json.get(KEY_ENABLED)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
            editor.putBoolean(KEY_ENABLED, it); hasAny = true
        }
        json.get(KEY_HOSTNAME)?.takeIf { it.isJsonPrimitive }?.asString?.let {
            editor.putString(KEY_HOSTNAME, it.ifBlank { null }); hasAny = true
        }
        json.get(KEY_NETWORK_NAME)?.takeIf { it.isJsonPrimitive }?.asString?.let {
            editor.putString(KEY_NETWORK_NAME, it); hasAny = true
        }
        json.get(KEY_VIRTUAL_IP)?.takeIf { it.isJsonPrimitive }?.asString?.let {
            editor.putString(KEY_VIRTUAL_IP, it.ifBlank { null }); hasAny = true
        }
        json.get(KEY_PEERS)?.takeIf { it.isJsonPrimitive }?.asString?.let {
            val peers = it.split(",").map { p -> p.trim() }.filter { p.isNotEmpty() }
            editor.putString(KEY_PEERS, peers.joinToString(",")); hasAny = true
        }
        json.get(KEY_SOCKS5_PORT)?.takeIf { it.isJsonPrimitive }?.asInt?.let { port ->
            if (port in 1..65535) {
                editor.putString(KEY_SOCKS5_PORT, port.toString()); hasAny = true
            }
        }
        json.get(KEY_LOG_ENABLED)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
            editor.putBoolean(KEY_LOG_ENABLED, it); hasAny = true
        }
        // MTU: only write if the value parses as a valid integer; an empty
        // or non-numeric string would otherwise clear the stored value.
        json.get(KEY_MTU)?.takeIf { it.isJsonPrimitive }?.asString?.let { mtuStr ->
            mtuStr.toIntOrNull()?.let { mtu ->
                editor.putString(KEY_MTU, mtu.toString()); hasAny = true
            }
        }
        json.get(KEY_LOG_LEVEL)?.takeIf { it.isJsonPrimitive }?.asString?.let {
            editor.putString(KEY_LOG_LEVEL, it); hasAny = true
        }

        if (hasAny) {
            editor.commit()
        }

        // Network secret is stored in EncryptedSharedPreferences — write
        // separately via setNetworkSecret, which also updates the snapshot.
        json.get(KEY_NETWORK_SECRET)?.takeIf { it.isJsonPrimitive }?.asString?.let {
            setNetworkSecret(ctx, it)
        }

        // Write the cross-process snapshot file from the now-persisted
        // SharedPreferences so the VPN service process sees the restored values.
        flushSnapshot(ctx)
        Log.i(TAG, "importFromJson: done (hasAny=$hasAny)")
    }
}
