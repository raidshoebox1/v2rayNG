package com.easytier.plugin

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.LogCallback
import com.easytier.plugin.BuildConfig
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
        // In-memory log buffer for UI display
        // ------------------------------------------------------------------

        data class LogEntry(
            val timestamp: Long,
            val level: String,
            val message: String
        )

        private const val MAX_LOG_ENTRIES = 500
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
         * Called from both EasyTierPlugin instance methods and CoreServiceManager.
         */
        @JvmStatic
        fun log(level: String, message: String, throwable: Throwable? = null) {
            val msg = if (throwable != null) {
                "$message: ${throwable.javaClass.simpleName}: ${throwable.message}"
            } else {
                message
            }
            val entry = LogEntry(System.currentTimeMillis(), level, msg)
            synchronized(logBuffer) {
                logBuffer.add(entry)
                if (logBuffer.size > MAX_LOG_ENTRIES) logBuffer.removeAt(0)
            }
            // In release builds suppress I/D logs from logcat to reduce info leakage.
            when (level) {
                "E" -> Log.e(TAG, message, throwable)
                "W" -> Log.w(TAG, message, throwable)
                "I" -> if (BuildConfig.DEBUG) Log.i(TAG, message)
                "D" -> if (BuildConfig.DEBUG) Log.d(TAG, message)
            }
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
        private const val MESH_CIDR_CACHE_TTL_MS = 5_000L // 5 seconds

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
                            val virtualIpv4 = myNodeInfo.getAsJsonObject("virtual_ipv4")
                            if (virtualIpv4 != null) {
                                val addr = virtualIpv4.get("address")?.asString
                                val networkLength = virtualIpv4.get("network_length")?.asInt
                                if (addr != null && networkLength != null) {
                                    val cidr = "$addr/$networkLength"
                                    if (isSafeMeshCidr(cidr)) cidrs.add(cidr)
                                }
                            }
                        }

                        // 2. Extract each remote peer's virtual IP from route entries (ipv4_addr).
                        //    Each route entry has an ipv4_addr with the peer's virtual IP and the
                        //    network's subnet mask (typically /24).  We add the full CIDR so that
                        //    all peers in the same subnet are routable.
                        val routes = obj.getAsJsonArray("routes")
                        if (routes != null) {
                            for (route in routes) {
                                val routeObj = route.asJsonObject

                                // Peer's virtual IP address (Ipv4Inet: { address, network_length })
                                val ipv4Addr = routeObj.getAsJsonObject("ipv4_addr")
                                if (ipv4Addr != null) {
                                    val addr = ipv4Addr.get("address")?.asString
                                    val networkLength = ipv4Addr.get("network_length")?.asInt
                                    if (addr != null && networkLength != null) {
                                        val cidr = "$addr/$networkLength"
                                        if (isSafeMeshCidr(cidr)) cidrs.add(cidr)
                                    }
                                }

                                // IPv6 virtual address (Ipv6Inet: { address, network_length })
                                val ipv6Addr = routeObj.getAsJsonObject("ipv6_addr")
                                if (ipv6Addr != null) {
                                    val addr = ipv6Addr.get("address")?.asString
                                    val networkLength = ipv6Addr.get("network_length")?.asInt
                                    if (addr != null && networkLength != null) {
                                        val cidr = "$addr/$networkLength"
                                        if (isSafeMeshCidr(cidr)) cidrs.add(cidr)
                                    }
                                }

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
         * Static version of getNetworkInfoJson() for UI display without
         * needing a plugin instance. Calls the JNI directly.
         */
        @JvmStatic
        fun getNetworkInfoJsonStatic(): String? {
            if (!isJniAvailable()) return null
            return try {
                EasyTierJNI.collectNetworkInfos(50)
            } catch (e: Throwable) {
                log("W", "Failed to get network info", e)
                null
            }
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
        fun startTest(context: Context, config: EasyTierConfig): Boolean {
            // Refuse to start a test instance if the VPN service already has one running.
            // Both use the same instance name (DEFAULT_INSTANCE_NAME), so starting a second
            // would conflict, and stopAllInstances() in stop() would kill the VPN instance.
            if (isRunningStatic()) {
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
        fun stopTest() {
            testInstance?.let { plugin ->
                plugin.stop()
                testInstance = null
            }
        }

        /**
         * Check if any EasyTier instance is running (either via VPN or test).
         */
        @JvmStatic
        fun isRunningStatic(): Boolean {
            if (!isJniAvailable()) return false
            return try {
                val json = EasyTierJNI.collectNetworkInfos(10)
                if (json.isNullOrBlank()) return false
                val parsed = JsonParser.parseString(json)
                if (parsed.isJsonObject) {
                    val mapObj = parsed.asJsonObject.getAsJsonObject("map")
                        ?: parsed.asJsonObject
                    for ((_, info) in mapObj.entrySet()) {
                        val obj = info.asJsonObject
                        if (obj.has("running") && obj.get("running").asBoolean) return true
                    }
                }
                false
            } catch (e: Throwable) {
                false
            }
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
                log("D", "EasyTier config: instance=${config.instanceName}, network=${config.networkName}, peers=${config.peers.size}, socks5=${config.socks5Port}, noTun=${config.noTun}")
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
            // fail its first connection attempt.  Best-effort: if the listener
            // isn't ready after 2s we log a warning but still return true —
            // EasyTier may still be initialising and the listener will appear shortly.
            waitForSocks5(config.socks5Port)

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
     * Logs a warning if the listener is not ready after all attempts but
     * does NOT fail the start — EasyTier may still be initialising.
     */
    private fun waitForSocks5(port: Int) {
        val maxAttempts = 5
        val connectTimeoutMs = 200
        val retryDelayMs = 400L
        for (attempt in 1..maxAttempts) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
                }
                log("D", "EasyTier SOCKS5 listener ready on port $port (attempt $attempt)")
                return
            } catch (e: Throwable) {
                if (attempt < maxAttempts) {
                    Thread.sleep(retryDelayMs)
                }
            }
        }
        log("W", "EasyTier SOCKS5 listener on port $port not ready after $maxAttempts attempts (non-fatal — may still be initialising)")
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
