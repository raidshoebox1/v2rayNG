package com.easytier.plugin

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.LogCallback
import com.easytier.plugin.BuildConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * EasyTier Plugin for v2rayNG
 *
 * Manages an EasyTier mesh-network instance inside the v2rayNG process,
 * running in no-tun + SOCKS5 mode so it does NOT compete for the Android
 * VpnService slot.  v2rayNG's Xray-core then routes internal-network
 * traffic to the EasyTier SOCKS5 endpoint via a custom outbound.
 *
 * Lifecycle:
 *   1. [start] — parse config, run EasyTier instance (no-tun, SOCKS5 only)
 *   2. [stop] — stop the EasyTier instance
 *
 * The native library is loaded lazily on first [start] to avoid crashes on
 * devices/architectures where the .so is not present.
 */
class EasyTierPlugin(private val context: Context) {

    /**
     * Information about a single mesh peer, for UI display.
     */
    data class MeshPeerInfo(
        val hostname: String,
        val virtualIp: String?,
        val latencyMs: Int?,
        val isDirect: Boolean,
        val isClosed: Boolean,
        val tunnelType: String?,
        val rxBytes: Long,
        val txBytes: Long
    )

    /**
     * Overall mesh status for UI display.
     */
    data class MeshStatus(
        val running: Boolean,
        val virtualIp: String?,
        val hostname: String?,
        val peers: List<MeshPeerInfo>,
        val meshCidrs: List<String>,
        val errorMsg: String?
    )

    companion object {
        private const val TAG = "EasyTierPlugin"

        /** Default SOCKS5 port for the EasyTier listener. */
        const val DEFAULT_SOCKS5_PORT = 10852

        /** Default instance name used in EasyTier TOML config. */
        const val DEFAULT_INSTANCE_NAME = "v2rayng_plugin"

        /** Outbound tag used in Xray-core routing. */
        const val OUTBOUND_TAG = "easytier"

        /** CIDRs that should be routed through EasyTier. */
        val DEFAULT_LAN_CIDRS = listOf(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16"
        )

        // ------------------------------------------------------------------
        // Tuning constants (centralized for easy adjustment)
        // ------------------------------------------------------------------

        /** Status snapshot is considered stale after this many ms. */
        const val STATUS_SNAPSHOT_TTL_MS = 30_000L

        /** Mesh CIDR cache TTL — avoids repeated JNI RPC calls during a single VPN start. */
        const val MESH_CIDR_CACHE_TTL_MS = 5_000L

        /** Maximum number of log entries kept in the in-memory ring buffer. */
        const val MAX_LOG_ENTRIES = 500

        /** SOCKS5 listener readiness probe: max connection attempts. */
        const val SOCKS5_MAX_ATTEMPTS = 5

        /** SOCKS5 listener readiness probe: connect timeout per attempt (ms). */
        const val SOCKS5_CONNECT_TIMEOUT_MS = 200

        /** SOCKS5 listener readiness probe: delay between attempts (ms). */
        const val SOCKS5_RETRY_DELAY_MS = 400L

        /** Status writer thread: interval between snapshot writes (ms). */
        const val STATUS_WRITER_INTERVAL_MS = 3_000L

        /** Mesh CIDR polling: max number of poll attempts after VPN start. */
        const val MESH_CIDR_POLL_MAX_ROUNDS = 3

        /** Mesh CIDR polling: delay between poll attempts (ms). */
        const val MESH_CIDR_POLL_INTERVAL_MS = 500L

        // ------------------------------------------------------------------
        // Cross-process status snapshot
        // ------------------------------------------------------------------

        /**
         * The VPN service process (`:RunSoLibV2RayDaemon`) runs the EasyTier
         * native instance.  The Settings UI runs in the main process and
         * cannot query the native instance directly via JNI (each process
         * has its own native state).  To bridge this gap, the VPN process
         * periodically writes the raw `collectNetworkInfos()` JSON to a
         * file in the app's private files directory (shared across processes).
         * The Settings UI reads this file to display status when no local
         * (test) instance is running.
         */
        private const val STATUS_SNAPSHOT_FILE = "easytier_status.json"

        /**
         * Check whether the EasyTier native library is available on this device.
         * The result is cached after the first check so subsequent calls are free.
         * Returns false on architectures where the .so is not bundled.
         */
        @Volatile
        private var jniAvailabilityCached: Boolean? = null

        @JvmStatic
        fun isJniAvailable(): Boolean {
            jniAvailabilityCached?.let { return it }
            val available = try {
                EasyTierJNI.getLastError()
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            } catch (e: Throwable) {
                // Any other throwable during class init also means JNI is unavailable
                false
            }
            jniAvailabilityCached = available
            if (!available) {
                Log.w(TAG, "EasyTier JNI native library not available on this device")
            }
            return available
        }

        // ------------------------------------------------------------------
        // Cross-process status snapshot (written by VPN process, read by UI)
        // ------------------------------------------------------------------

        private fun statusSnapshotFile(context: Context): File =
            File(context.applicationContext.filesDir, STATUS_SNAPSHOT_FILE)

        /**
         * Write the current `collectNetworkInfos()` JSON to the status
         * snapshot file.  Called periodically by the VPN service process
         * so the Settings UI (main process) can display live status.
         *
         * Atomic: writes to a temp file then renames.
         */
        @JvmStatic
        fun writeStatusSnapshot(context: Context) {
            if (!isJniAvailable()) return
            try {
                val json = EasyTierJNI.collectNetworkInfos(50)
                if (json.isNullOrBlank()) return
                val file = statusSnapshotFile(context)
                val tmp = File(file.parentFile, "$STATUS_SNAPSHOT_FILE.tmp")
                tmp.writeText(json)
                tmp.renameTo(file)
            } catch (e: Throwable) {
                log("W", "Failed to write status snapshot", e)
            }
        }

        /**
         * Read the status snapshot JSON, or null if it doesn't exist or
         * is stale (older than [STATUS_SNAPSHOT_TTL_MS]).
         */
        @JvmStatic
        fun readStatusSnapshot(context: Context): String? {
            return try {
                val file = statusSnapshotFile(context)
                if (!file.exists()) return null
                val age = System.currentTimeMillis() - file.lastModified()
                if (age > STATUS_SNAPSHOT_TTL_MS) return null
                file.readText()
            } catch (e: Throwable) {
                log("W", "Failed to read status snapshot", e)
                null
            }
        }

        /**
         * Delete the status snapshot file.  Called when the VPN instance
         * is stopped so the UI doesn't show stale "running" state.
         */
        @JvmStatic
        fun deleteStatusSnapshot(context: Context) {
            try {
                statusSnapshotFile(context).delete()
            } catch (e: Throwable) {
                // ignore
            }
        }

        // ------------------------------------------------------------------
        // In-memory log buffer for UI display
        // ------------------------------------------------------------------

        data class LogEntry(
            val timestamp: Long,
            val level: String,
            val message: String
        )

        private val logBuffer = mutableListOf<LogEntry>()

        @Volatile
        private var currentStatus: String = "stopped"

        @Volatile
        private var lastError: String? = null

        /**
         * JNI log callback that forwards Rust-side EasyTier logs into
         * [logBuffer] so they appear in the in-app log viewer.
         * Registered once via [ensureLogCallbackRegistered].
         */
        private val jniLogCallback = LogCallback { level, target, message ->
            // Map JNI log levels to our logBuffer level codes
            val bufLevel = when (level) {
                "E" -> "E"
                "W" -> "W"
                "I" -> "I"
                "D" -> "D"
                "T" -> "D" // trace → debug for buffer display
                else -> "I"
            }
            // Prefix with target for context (e.g. "CORE::peer_manager")
            val prefixed = if (target.isNotEmpty() && target != "easytier_android_jni") {
                "[$target] $message"
            } else {
                message
            }
            log(bufLevel, prefixed)
        }

        @Volatile
        private var logCallbackRegistered = false

        /**
         * Register the JNI log callback so Rust-side logs flow into [logBuffer].
         * Idempotent — safe to call multiple times. The callback stays registered
         * for the lifetime of the process; subsequent calls are no-ops.
         */
        @JvmStatic
        fun ensureLogCallbackRegistered() {
            if (logCallbackRegistered) return
            try {
                EasyTierJNI.setLogCallback(jniLogCallback)
                logCallbackRegistered = true
                log("I", "EasyTier: JNI log callback registered")
            } catch (e: Throwable) {
                log("E", "EasyTier: failed to register JNI log callback", e)
            }
        }

        /**
         * Unregister the JNI log callback, stopping Rust-side logs from
         * flowing into [logBuffer]. Logs still go to Android logcat.
         */
        @JvmStatic
        fun disableLogCallback() {
            if (!logCallbackRegistered) return
            try {
                EasyTierJNI.setLogCallback(null)
                logCallbackRegistered = false
                log("I", "EasyTier: JNI log callback disabled")
            } catch (e: Throwable) {
                log("E", "EasyTier: failed to disable JNI log callback", e)
            }
        }

        /**
         * Set the EasyTier log level (controls which Rust-side log messages
         * are forwarded to the callback and logcat).
         * @param level one of "off", "error", "warn", "info", "debug", "trace"
         */
        @JvmStatic
        fun setLogLevel(level: String) {
            try {
                EasyTierJNI.setLogLevel(level)
            } catch (e: Throwable) {
                log("W", "EasyTier: failed to set log level to $level", e)
            }
        }

        /**
         * Returns a copy of the current log buffer for UI display.
         */
        @JvmStatic
        fun getLogs(): List<LogEntry> = synchronized(logBuffer) { logBuffer.toList() }

        /**
         * Returns the current EasyTier status: "stopped", "starting", "running", "error".
         */
        @JvmStatic
        fun getStatus(): String = currentStatus

        /**
         * Returns the last error message, or null if no error.
         */
        @JvmStatic
        fun getLastError(): String? = lastError

        /**
         * Clear the log buffer (for UI "clear logs" button).
         */
        @JvmStatic
        fun clearLogs() = synchronized(logBuffer) { logBuffer.clear() }

        /**
         * Append a log entry to the in-memory buffer and also to Android logcat.
         *
         * In release builds the in-memory buffer always receives the entry (for
         * the in-app log viewer), but logcat output is restricted to W/E to avoid
         * leaking sensitive information (peer URIs, network secrets, mesh CIDRs)
         * via a world-readable logcat on rooted devices or ADB.
         *
         * Credentials embedded in peer URIs (e.g. tcp://user:pass@host:port)
         * are redacted (the user:pass segment is replaced with ***) before
         * the message is stored in the log buffer or written to logcat, so
         * they do not appear in the in-app log viewer or in logcat output
         * at any level.
         *
         * Called from both EasyTierPlugin instance methods and CoreServiceManager.
         */
        @JvmStatic
        fun log(level: String, message: String, throwable: Throwable? = null) {
            val raw = if (throwable != null) {
                "$message: ${throwable.javaClass.simpleName}: ${throwable.message}"
            } else {
                message
            }
            val msg = redactCredentials(raw)
            val entry = LogEntry(System.currentTimeMillis(), level, msg)
            synchronized(logBuffer) {
                logBuffer.add(entry)
                if (logBuffer.size > MAX_LOG_ENTRIES) logBuffer.removeAt(0)
            }
            // In release builds suppress I/D logs from logcat to reduce info leakage.
            when (level) {
                "E" -> Log.e(TAG, msg, throwable)
                "W" -> Log.w(TAG, msg, throwable)
                "I" -> if (BuildConfig.DEBUG) Log.i(TAG, msg)
                "D" -> if (BuildConfig.DEBUG) Log.d(TAG, msg)
            }
        }

        /**
         * Redact credentials from URIs in log messages.
         *
         * Matches patterns like `scheme://user:pass@host` and replaces the
         * `user:pass@` part with `***@`.  Handles multiple URIs in a single
         * message.  Only matches when both a colon and an at-sign are present
         * after `://`, so plain `host:port` URIs without credentials are
         * left untouched.
         */
        private val credentialPattern = Regex("://[^\\s/@:]+:[^\\s/@]+@")

        private fun redactCredentials(message: String): String {
            return credentialPattern.replace(message, "://***@")
        }

        internal fun setStatus(status: String, error: String? = null) {
            currentStatus = status
            if (status == "running") {
                lastError = null
            } else if (error != null) {
                lastError = error
            }
        }

        /**
         * Query mesh CIDRs from any running EasyTier instance without needing
         * a plugin object. Returns empty list if no instance is running.
         *
         * Results are cached for [MESH_CIDR_CACHE_TTL_MS] to avoid repeated
         * JNI RPC calls during a single VPN start flow (which may invoke this
         * 3-5 times across CoreConfigManager, CoreVpnService, RootProxyManager).
         */
        @Volatile
        private var meshCidrsCache: List<String>? = null
        @Volatile
        private var meshCidrsCacheTime: Long = 0L

        @JvmStatic
        fun getMeshCidrsStatic(): List<String> {
            if (!isJniAvailable()) return emptyList()
            val now = System.currentTimeMillis()
            meshCidrsCache?.let { cached ->
                if (now - meshCidrsCacheTime < MESH_CIDR_CACHE_TTL_MS) {
                    return cached
                }
            }
            val result = collectMeshCidrs()
            meshCidrsCache = result
            meshCidrsCacheTime = now
            return result
        }

        /**
         * Force-clear the mesh CIDR cache. Call after stopping EasyTier or
         * when the caller knows the network topology has changed.
         */
        @JvmStatic
        fun clearMeshCidrsCache() {
            meshCidrsCache = null
            meshCidrsCacheTime = 0L
        }

        // ------------------------------------------------------------------
        // Ipv4Inet / Ipv6Inet JSON parsing helpers
        // ------------------------------------------------------------------

        /**
         * Convert a uint32 IPv4 address (big-endian) to dotted-decimal string.
         * Example: 0x0A909001 -> "10.144.144.1"
         */
        private fun formatIpv4(addr: Long): String {
            return "${(addr shr 24) and 0xFF}.${(addr shr 16) and 0xFF}.${(addr shr 8) and 0xFF}.${addr and 0xFF}"
        }

        /**
         * Parse an Ipv4Inet JSON object ({ "address": { "addr": <uint32> }, "network_length": <int> })
         * into a CIDR string "a.b.c.d/n".  Returns null if the object is null or malformed.
         */
        private fun parseIpv4InetCidr(obj: com.google.gson.JsonObject?): String? {
            if (obj == null) return null
            val addressObj = obj.getAsJsonObject("address") ?: return null
            val addr = addressObj.get("addr")?.asLong ?: return null
            val networkLength = obj.get("network_length")?.asInt ?: return null
            return "${formatIpv4(addr)}/$networkLength"
        }

        /**
         * Parse an Ipv4Inet JSON object and return just the IP address (without prefix).
         */
        private fun parseIpv4Addr(obj: com.google.gson.JsonObject?): String? {
            if (obj == null) return null
            val addressObj = obj.getAsJsonObject("address") ?: return null
            val addr = addressObj.get("addr")?.asLong ?: return null
            return formatIpv4(addr)
        }

        /**
         * Parse an Ipv6Inet JSON object ({ "address": { "part1..part4": <uint32> }, "network_length": <int> })
         * into a CIDR string "xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx/n".  Returns null if malformed.
         */
        private fun parseIpv6InetCidr(obj: com.google.gson.JsonObject?): String? {
            if (obj == null) return null
            val addressObj = obj.getAsJsonObject("address") ?: return null
            val p1 = addressObj.get("part1")?.asLong ?: return null
            val p2 = addressObj.get("part2")?.asLong ?: return null
            val p3 = addressObj.get("part3")?.asLong ?: return null
            val p4 = addressObj.get("part4")?.asLong ?: return null
            val networkLength = obj.get("network_length")?.asInt ?: return null
            val groups = arrayOf(
                ((p1 shr 16) and 0xFFFF).toInt(),
                (p1 and 0xFFFF).toInt(),
                ((p2 shr 16) and 0xFFFF).toInt(),
                (p2 and 0xFFFF).toInt(),
                ((p3 shr 16) and 0xFFFF).toInt(),
                (p3 and 0xFFFF).toInt(),
                ((p4 shr 16) and 0xFFFF).toInt(),
                (p4 and 0xFFFF).toInt()
            )
            return "${groups.joinToString(":") { it.toString(16) }}/$networkLength"
        }

        private fun collectMeshCidrs(): List<String> {
            return try {
                val json = EasyTierJNI.collectNetworkInfos(10)
                if (json.isNullOrBlank()) return emptyList()

                val cidrs = mutableSetOf<String>()
                val parsed = JsonParser.parseString(json)
                if (parsed.isJsonObject) {
                    val mapObj = parsed.asJsonObject.getAsJsonObject("map")
                        ?: parsed.asJsonObject
                    for ((_, info) in mapObj.entrySet()) {
                        val obj = info.asJsonObject

                        // 1. Extract the local node's virtual IP subnet from my_node_info.virtual_ipv4.
                        //    This gives us the on-link subnet (e.g. 10.144.144.0/24) that EasyTier
                        //    uses for its virtual network.  Without this, the mesh CIDR list would
                        //    be empty when no peers advertise proxy_cidrs, and traffic to the
                        //    virtual LAN would not be routed through EasyTier.
                        val myNodeInfo = obj.getAsJsonObject("my_node_info")
                        if (myNodeInfo != null) {
                            val cidr = parseIpv4InetCidr(myNodeInfo.getAsJsonObject("virtual_ipv4"))
                            if (cidr != null && isSafeMeshCidr(cidr)) cidrs.add(cidr)
                        }

                        // 2. Extract each remote peer's virtual IP from route entries (ipv4_addr).
                        //    Each route entry has an ipv4_addr with the peer's virtual IP and the
                        //    network's subnet mask (typically /24).  We add the full CIDR so that
                        //    all peers in the same subnet are routable.
                        val routes = obj.getAsJsonArray("routes")
                        if (routes != null) {
                            for (route in routes) {
                                val routeObj = route.asJsonObject

                                // Peer's virtual IPv4 address (Ipv4Inet: { address: { addr: uint32 }, network_length })
                                val ipv4Cidr = parseIpv4InetCidr(routeObj.getAsJsonObject("ipv4_addr"))
                                if (ipv4Cidr != null && isSafeMeshCidr(ipv4Cidr)) cidrs.add(ipv4Cidr)

                                // IPv6 virtual address (Ipv6Inet: { address: { part1..part4: uint32 }, network_length })
                                val ipv6Cidr = parseIpv6InetCidr(routeObj.getAsJsonObject("ipv6_addr"))
                                if (ipv6Cidr != null && isSafeMeshCidr(ipv6Cidr)) cidrs.add(ipv6Cidr)

                                // proxy_cidrs: additional subnet ranges the peer proxies
                                // (e.g. a LAN behind the peer, or a VPN portal client network).
                                val proxyCidrs = routeObj.getAsJsonArray("proxy_cidrs")
                                if (proxyCidrs != null) {
                                    for (cidr in proxyCidrs) {
                                        val c = cidr.asString
                                        if (isSafeMeshCidr(c)) cidrs.add(c)
                                    }
                                }
                            }
                        }
                    }
                }
                cidrs.toList()
            } catch (e: Throwable) {
                emptyList()
            }
        }

        /**
         * Validate a CIDR advertised by a mesh peer before injecting it into
         * routing rules.  Rejects overly-broad prefixes (<= 7), public IP
         * ranges, and anything that doesn't parse as a valid CIDR.
         *
         * Allowed ranges:
         * - IPv4 private: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
         * - IPv4 link-local: 169.254.0.0/16
         * - IPv4 CGNAT: 100.64.0.0/10
         * - IPv6 ULA: fc00::/7
         * - IPv6 link-local: fe80::/10
         */
        private fun isSafeMeshCidr(cidr: String): Boolean {
            val parts = cidr.split("/")
            if (parts.size != 2) return false
            val ip = parts[0]
            val prefix = parts[1].toIntOrNull() ?: return false

            // Reject overly-broad prefixes
            if (prefix < 0) return false
            if (ip.contains(":")) {
                // IPv6
                if (prefix > 128) return false
                if (prefix <= 7) return false  // reject ::/0 through ::/7
                // Only allow ULA (fc00::/7) and link-local (fe80::/10)
                val ipLower = ip.lowercase()
                return ipLower.startsWith("fc") || ipLower.startsWith("fd") ||
                       ipLower.startsWith("fe8") || ipLower.startsWith("fe9") ||
                       ipLower.startsWith("fea") || ipLower.startsWith("feb")
            } else {
                // IPv4
                if (prefix > 32) return false
                if (prefix <= 7) return false  // reject 0.0.0.0/0 through /7
                val octets = ip.split(".")
                if (octets.size != 4) return false
                val o = octets.map { it.toIntOrNull() ?: return false }
                if (o.any { it !in 0..255 }) return false
                // Only allow private / special-use ranges
                return when {
                    o[0] == 10 -> true                              // 10.0.0.0/8
                    o[0] == 172 && o[1] in 16..31 -> true            // 172.16.0.0/12
                    o[0] == 192 && o[1] == 168 -> true               // 192.168.0.0/16
                    o[0] == 169 && o[1] == 254 -> true               // 169.254.0.0/16 (link-local)
                    o[0] == 100 && o[1] in 64..127 -> true           // 100.64.0.0/10 (CGNAT)
                    else -> false
                }
            }
        }

        /**
         * Query raw network info JSON from any running EasyTier instance
         * for UI display (Network Info dialog).
         *
         * Tries the JNI directly first (works for test instances running in
         * the same process).  If no running instance is found locally,
         * falls back to the cross-process status snapshot file written by
         * the VPN service process.
         */
        @JvmStatic
        fun getNetworkInfoJsonStatic(context: Context? = null): String? {
            // Try JNI first (same-process instance, e.g. test instance)
            if (isJniAvailable()) {
                try {
                    val json = EasyTierJNI.collectNetworkInfos(50)
                    if (!json.isNullOrBlank() && hasRunningInstance(json)) {
                        return json
                    }
                } catch (e: Throwable) {
                    log("W", "Failed to get network info from JNI", e)
                }
            }
            // Fall back to cross-process snapshot (VPN process instance)
            if (context != null) {
                return readStatusSnapshot(context)
            }
            return null
        }

        // ------------------------------------------------------------------
        // Static test instance (for manual start/stop from settings UI)
        // ------------------------------------------------------------------

        @Volatile
        private var testInstance: EasyTierPlugin? = null

        /**
         * Start EasyTier from the settings UI without needing v2rayNG VPN.
         * Uses a static testInstance to avoid conflicting with CoreServiceManager's instance.
         * @return true if started successfully.
         */
        @JvmStatic
        @Synchronized
        fun startTest(context: Context, config: EasyTierConfig): Boolean {
            // Refuse to start a test instance if the VPN service already has one running.
            // Both use the same instance name (DEFAULT_INSTANCE_NAME), so starting a second
            // would conflict, and stopAllInstances() in stop() would kill the VPN instance.
            if (isRunningStatic(context)) {
                log("W", "EasyTier: cannot start test instance — VPN instance is already running")
                return false
            }
            // Stop any existing test instance first
            stopTest()
            log("I", "EasyTier: manual start from settings UI")
            val plugin = EasyTierPlugin(context)
            val started = plugin.start(config)
            if (started) {
                testInstance = plugin
            }
            return started
        }

        /**
         * Stop the test instance started from settings UI.
         */
        @JvmStatic
        @Synchronized
        fun stopTest() {
            testInstance?.let { plugin ->
                plugin.stop()
                testInstance = null
            }
        }

        /**
         * Check if the test instance (started from settings UI) is running.
         * Returns false if no test instance exists or if the VPN service's
         * instance is the one running.
         */
        @JvmStatic
        fun isTestRunning(): Boolean = testInstance != null && testInstance!!.isRunning()

        /**
         * Check if any EasyTier instance is running (either via VPN or test).
         *
         * Tries the JNI directly first (same-process test instance).  If no
         * running instance is found locally, checks the cross-process status
         * snapshot file (VPN process instance).
         *
         * @param context optional; if provided, falls back to the status
         *   snapshot file when JNI reports no running instance.
         */
        @JvmStatic
        @JvmOverloads
        fun isRunningStatic(context: Context? = null): Boolean {
            // Try JNI first (same-process instance)
            if (isJniAvailable()) {
                try {
                    val json = EasyTierJNI.collectNetworkInfos(10)
                    if (!json.isNullOrBlank() && hasRunningInstance(json)) {
                        return true
                    }
                } catch (e: Throwable) {
                    // fall through to snapshot
                }
            }
            // Fall back to cross-process snapshot
            if (context != null) {
                val snap = readStatusSnapshot(context) ?: return false
                return try {
                    hasRunningInstance(snap)
                } catch (e: Throwable) {
                    false
                }
            }
            return false
        }

        /**
         * Check if a collectNetworkInfos JSON string contains a running instance.
         */
        private fun hasRunningInstance(json: String): Boolean {
            val parsed = JsonParser.parseString(json)
            if (!parsed.isJsonObject) return false
            val mapObj = parsed.asJsonObject.getAsJsonObject("map")
                ?: parsed.asJsonObject
            for ((_, info) in mapObj.entrySet()) {
                val obj = info.asJsonObject
                if (obj.has("running") && obj.get("running").asBoolean) return true
            }
            return false
        }

        // ------------------------------------------------------------------
        // Structured peer status for UI display
        // ------------------------------------------------------------------

        /**
         * Query the running EasyTier instance for structured status information.
         *
         * Tries the JNI directly first (works for test instances running in
         * the same process).  If no running instance is found locally, falls
         * back to the cross-process status snapshot file written by the VPN
         * service process.
         *
         * Returns null if neither source has a running instance.
         *
         * Parses the collectNetworkInfos JSON to extract:
         * - Local node's virtual IP and hostname from my_node_info
         * - Peer list with latency, direct/relay status, tunnel type, traffic counters
         * - Mesh CIDRs (same as collectMeshCidrs)
         *
         * @param context optional; if provided, falls back to the status
         *   snapshot file when JNI reports no running instance.
         */
        @JvmStatic
        @JvmOverloads
        fun getPeerStatus(context: Context? = null): MeshStatus? {
            // Try JNI first (same-process instance, e.g. test instance)
            if (isJniAvailable()) {
                try {
                    val json = EasyTierJNI.collectNetworkInfos(50)
                    if (!json.isNullOrBlank()) {
                        val status = parsePeerStatusJson(json)
                        if (status != null && status.running) return status
                    }
                } catch (e: Throwable) {
                    log("W", "Failed to get peer status from JNI", e)
                }
            }
            // Fall back to cross-process snapshot (VPN process instance)
            if (context != null) {
                val snap = readStatusSnapshot(context) ?: return null
                return try {
                    parsePeerStatusJson(snap)
                } catch (e: Throwable) {
                    log("W", "Failed to parse status snapshot", e)
                    null
                }
            }
            return null
        }

        /**
         * Parse a collectNetworkInfos JSON string into a [MeshStatus].
         * Returns null if the JSON is blank or cannot be parsed.
         * If no running instance is found, returns a MeshStatus with
         * running=false (and errorMsg if present).
         */
        private fun parsePeerStatusJson(json: String): MeshStatus? {
            val parsed = JsonParser.parseString(json)
            if (!parsed.isJsonObject) return null
            val mapObj = parsed.asJsonObject.getAsJsonObject("map")
                ?: parsed.asJsonObject

            // Find the first running instance, or fall back to the first instance
            var runningInfo: com.google.gson.JsonObject? = null
            for ((_, info) in mapObj.entrySet()) {
                val obj = info.asJsonObject
                if (obj.has("running") && obj.get("running").asBoolean) {
                    runningInfo = obj
                    break
                }
                // Also check for error state — return the info even if not running
                if (runningInfo == null) runningInfo = obj
            }
            if (runningInfo == null) return null

            val running = runningInfo.has("running") && runningInfo.get("running").asBoolean
            val errorMsg = runningInfo.get("error_msg")?.takeIf { !it.isJsonNull }?.asString

            // Local node info
            val myNodeInfo = runningInfo.getAsJsonObject("my_node_info")
            val virtualIp = if (myNodeInfo != null) {
                parseIpv4InetCidr(myNodeInfo.getAsJsonObject("virtual_ipv4"))
            } else null
            val localHostname = myNodeInfo?.get("hostname")?.takeIf { !it.isJsonNull }?.asString

            // Build a peer_id -> route map for hostname and virtual IP lookup
            val routeMap = mutableMapOf<Long, com.google.gson.JsonObject>()
            val routes = runningInfo.getAsJsonArray("routes")
            if (routes != null) {
                for (route in routes) {
                    val routeObj = route.asJsonObject
                    val peerId = routeObj.get("peer_id")?.asLong ?: continue
                    routeMap[peerId] = routeObj
                }
            }

            // Parse peers
            val peers = mutableListOf<MeshPeerInfo>()
            val peersArray = runningInfo.getAsJsonArray("peers")
            if (peersArray != null) {
                for (peerEntry in peersArray) {
                    val peerObj = peerEntry.asJsonObject
                    val peerId = peerObj.get("peer_id")?.asLong ?: continue

                    // Get hostname and virtual IP from the matching route
                    val route = routeMap[peerId]
                    val hostname = route?.get("hostname")?.takeIf { !it.isJsonNull }?.asString
                        ?: "peer-$peerId"
                    val virtualIp = route?.let { parseIpv4Addr(it.getAsJsonObject("ipv4_addr")) }

                    // Check directly_connected_conns — non-empty means at least one direct connection
                    val directlyConnected = peerObj.getAsJsonArray("directly_connected_conns")
                    val isDirect = directlyConnected != null && directlyConnected.size() > 0

                    // Find the first non-closed connection for stats
                    val conns = peerObj.getAsJsonArray("conns")
                    var latencyMs: Int? = null
                    var tunnelType: String? = null
                    var rxBytes = 0L
                    var txBytes = 0L
                    var isClosed = true

                    if (conns != null) {
                        for (connEntry in conns) {
                            val conn = connEntry.asJsonObject
                            val connClosed = conn.get("is_closed")?.asBoolean ?: false
                            if (!connClosed) {
                                isClosed = false
                                // Sum traffic across all non-closed connections
                                val stats = conn.getAsJsonObject("stats")
                                if (stats != null) {
                                    rxBytes += stats.get("rx_bytes")?.asLong ?: 0L
                                    txBytes += stats.get("tx_bytes")?.asLong ?: 0L
                                    // Use latency from the first non-closed connection
                                    if (latencyMs == null) {
                                        val latencyUs = stats.get("latency_us")?.asLong
                                        if (latencyUs != null && latencyUs > 0) {
                                            latencyMs = (latencyUs / 1000).toInt()
                                        }
                                    }
                                }
                                // Use tunnel type from the first non-closed connection
                                if (tunnelType == null) {
                                    val tunnel = conn.getAsJsonObject("tunnel")
                                    tunnelType = tunnel?.get("tunnel_type")?.takeIf { !it.isJsonNull }?.asString
                                }
                            }
                        }
                    }

                    // Fall back to route's path_latency if no connection latency
                    if (latencyMs == null && route != null) {
                        val pathLatency = route.get("path_latency")?.asLong
                        if (pathLatency != null && pathLatency > 0) {
                            latencyMs = (pathLatency / 1000).toInt()
                        }
                    }

                    peers.add(MeshPeerInfo(
                        hostname = hostname,
                        virtualIp = virtualIp,
                        latencyMs = latencyMs,
                        isDirect = isDirect,
                        isClosed = isClosed,
                        tunnelType = tunnelType,
                        rxBytes = rxBytes,
                        txBytes = txBytes
                    ))
                }
            }

            val meshCidrs = collectMeshCidrs()

            return MeshStatus(
                running = running,
                virtualIp = virtualIp,
                hostname = localHostname,
                peers = peers,
                meshCidrs = meshCidrs,
                errorMsg = errorMsg
            )
        }
    }

    @Volatile
    private var running = false
    @Volatile
    private var currentConfig: EasyTierConfig? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Start the EasyTier mesh instance with the given configuration.
     *
     * The instance runs in **no-tun** mode with a SOCKS5 listener on
     * [EasyTierConfig.socks5Port] (loopback only).  It does NOT create an
     * Android VPN interface, so it peacefully coexists with v2rayNG's VpnService.
     *
     * @return `true` if the instance started successfully.
     */
    @Synchronized
    fun start(config: EasyTierConfig): Boolean {
        if (running) {
            log("W", "EasyTier instance already running")
            return true
        }

        return try {
            setStatus("starting")

            // Stop any stale native EasyTier instance from a previous lifecycle.
            // If a previous start succeeded but stop() was never called (or the
            // plugin object was orphaned without stop()), a native instance with
            // the same name may still be running, causing runNetworkInstance()
            // to fail with "instance already exists" and leaving the OLD config
            // active. stopAllInstances() is a no-op if no instances are running.
            try {
                // Diagnose stale instances before stopping them — this helps
                // understand crash-recovery scenarios where a previous native
                // instance survived the Java process restart.
                val instances = EasyTierJNI.listInstances(10)
                if (!instances.isNullOrBlank() && instances != "{}") {
                    log("W", "EasyTier: found stale native instance(s) before start: $instances")
                }
            } catch (e: Throwable) {
                // listInstances may fail on some JNI versions; non-fatal
            }
            try {
                EasyTierJNI.stopAllInstances()
            } catch (e: Throwable) {
                log("W", "EasyTier: failed to stop stale instances before start", e)
            }
            clearMeshCidrsCache()

            // Register/deregister JNI log callback based on logEnabled setting
            val logEnabled = EasyTierSettingsManager.isLogEnabled(context)
            if (logEnabled) {
                ensureLogCallbackRegistered()
                setLogLevel(config.logLevel)
            } else {
                disableLogCallback()
            }

            val toml = config.toToml()
            log("I", "Starting EasyTier instance: ${config.instanceName}")
            // Do NOT log the full TOML — it contains network_secret in cleartext.
            // Only log non-sensitive fields at debug level.
            if (logEnabled && BuildConfig.DEBUG) {
                log("D", "EasyTier config: instance=${config.instanceName}, network=${config.networkName}, peers=${config.peers.size}, socks5=${config.socks5Port}, noTun=${config.noTun}, lazyP2p=${config.lazyP2p}")
            }

            // Parse first to catch config errors early.
            val parseResult = EasyTierJNI.parseConfig(toml)
            if (parseResult != 0) {
                val err = EasyTierJNI.getLastError() ?: "unknown error (code=$parseResult)"
                log("E", "EasyTier config parse failed: $err")
                setStatus("error", err)
                return false
            }

            // Start the network instance.
            val runResult = EasyTierJNI.runNetworkInstance(toml)
            if (runResult != 0) {
                val err = EasyTierJNI.getLastError() ?: "unknown error (code=$runResult)"
                log("E", "EasyTier runNetworkInstance failed: $err")
                setStatus("error", err)
                return false
            }

            // Wait for the SOCKS5 listener to be ready so Xray-core doesn't
            // fail its first connection attempt.  If the listener is not ready
            // after all retries, we treat the start as failed and clean up.
            if (!waitForSocks5(config.socks5Port)) {
                log("E", "EasyTier SOCKS5 listener on port ${config.socks5Port} not ready after all retries — aborting start")
                try {
                    EasyTierJNI.stopAllInstances()
                } catch (e: Throwable) {
                    log("W", "EasyTier: failed to stop instance after SOCKS5 timeout", e)
                }
                setStatus("error", "SOCKS5 listener not ready on port ${config.socks5Port}")
                return false
            }

            running = true
            currentConfig = config
            clearMeshCidrsCache()
            setStatus("running")
            log("I", "EasyTier instance '${config.instanceName}' started (SOCKS5 on 127.0.0.1:${config.socks5Port})")
            true
        } catch (e: Throwable) {
            // Catch Throwable, not just Exception — UnsatisfiedLinkError is an Error
            log("E", "Failed to start EasyTier instance", e)
            setStatus("error", "${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Stop the EasyTier mesh instance.
     *
     * Calls [EasyTierJNI.stopAllInstances] to stop all running EasyTier
     * instances.  This is intentional — there should only ever be one
     * EasyTier instance (the VPN service's), and a global stop ensures
     * cleanup even if internal state is inconsistent.
     */
    @Synchronized
    fun stop() {
        if (!running) return
        try {
            EasyTierJNI.stopAllInstances()
            log("I", "EasyTier instance stopped")
        } catch (e: Throwable) {
            log("E", "Failed to stop EasyTier instance", e)
        } finally {
            running = false
            currentConfig = null
            clearMeshCidrsCache()
            setStatus("stopped")
        }
    }

    /**
     * Best-effort check that the SOCKS5 listener is accepting connections.
     * Tries to connect to 127.0.0.1:[port] up to 5 times with 400ms delays.
     * @return `true` if the listener accepted a connection within the retry
     *   window, `false` if it was not ready after all attempts.
     */
    private fun waitForSocks5(port: Int): Boolean {
        for (attempt in 1..SOCKS5_MAX_ATTEMPTS) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), SOCKS5_CONNECT_TIMEOUT_MS)
                }
                log("D", "EasyTier SOCKS5 listener ready on port $port (attempt $attempt)")
                return true
            } catch (e: Throwable) {
                if (attempt < SOCKS5_MAX_ATTEMPTS) {
                    Thread.sleep(SOCKS5_RETRY_DELAY_MS)
                }
            }
        }
        log("W", "EasyTier SOCKS5 listener on port $port not ready after $SOCKS5_MAX_ATTEMPTS attempts")
        return false
    }

    /** Whether the EasyTier instance is currently running. */
    fun isRunning(): Boolean {
        if (!running) return false
        return try {
            val json = EasyTierJNI.collectNetworkInfos(10)
            if (json.isNullOrBlank()) return false
            val parsed = JsonParser.parseString(json)
            if (parsed.isJsonObject) {
                val mapObj = parsed.asJsonObject.getAsJsonObject("map")
                    ?: parsed.asJsonObject  // fallback: flat object
                for ((_, info) in mapObj.entrySet()) {
                    val obj = info.asJsonObject
                    if (obj.has("running") && obj.get("running").asBoolean) return true
                }
            }
            false
        } catch (e: Throwable) {
            log("W", "isRunning check failed", e)
            false
        }
    }
}
