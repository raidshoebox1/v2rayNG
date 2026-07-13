package com.easytier.plugin

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.LogCallback

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
 *   2. [buildOutboundJson] — produce an Xray-core SOCKS5 outbound JSON fragment
 *   3. [buildRoutingRules] — produce routing rules that direct LAN/mesh CIDRs to EasyTier
 *   4. [stop] — stop the EasyTier instance
 *
 * The native library is loaded lazily on first [start] to avoid crashes on
 * devices/architectures where the .so is not present.
 */
class EasyTierPlugin(private val context: Context) {

    /**
     * Static version of getMeshCidrs() that does not require a plugin instance.
     * Calls the JNI directly — only returns meaningful results when an EasyTier
     * instance is actually running (started via start()).
     */
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
         * Idempotent — safe to call multiple times.
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
            when (level) {
                "E" -> Log.e(TAG, message, throwable)
                "W" -> Log.w(TAG, message, throwable)
                "I" -> Log.i(TAG, message)
                "D" -> Log.d(TAG, message)
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
         */
        @JvmStatic
        fun getMeshCidrsStatic(): List<String> {
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
                        val routes = obj.getAsJsonArray("routes") ?: continue
                        for (route in routes) {
                            val routeObj = route.asJsonObject
                            val proxyCidrs = routeObj.getAsJsonArray("proxy_cidrs")
                            if (proxyCidrs != null) {
                                for (cidr in proxyCidrs) {
                                    cidrs.add(cidr.asString)
                                }
                            }
                            val directCidrs = routeObj.getAsJsonArray("direct_cidrs")
                            if (directCidrs != null) {
                                for (cidr in directCidrs) {
                                    cidrs.add(cidr.asString)
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
         * Static version of getNetworkInfoJson() for UI display without
         * needing a plugin instance. Calls the JNI directly.
         */
        @JvmStatic
        fun getNetworkInfoJsonStatic(): String? {
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

    private var running = false
    private var currentConfig: EasyTierConfig? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Start the EasyTier mesh instance with the given configuration.
     *
     * The instance runs in **no-tun** mode with a SOCKS5 listener on
     * [EasyTierConfig.socks5Port].  It does NOT create an Android VPN
     * interface, so it peacefully coexists with v2rayNG's VpnService.
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

            // Register JNI log callback so Rust-side logs flow into logBuffer
            ensureLogCallbackRegistered()
            // Set log level from config
            setLogLevel(config.logLevel)

            val toml = config.toToml()
            log("I", "Starting EasyTier instance: ${config.instanceName}")
            log("D", "EasyTier TOML config:\n$toml")

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

            running = true
            currentConfig = config
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
            setStatus("stopped")
        }
    }

    /** Whether the EasyTier instance is currently running. */
    fun isRunning(): Boolean {
        if (!running) return false
        return try {
            val json = EasyTierJNI.collectNetworkInfos(10)
            if (json.isNullOrBlank()) return false
            // collectNetworkInfos returns NetworkInstanceRunningInfoMap:
            // {"map": {"instance_name": {"running": true, ...}}}
            val parsed = JsonParser.parseString(json)
            if (parsed.isJsonObject) {
                val mapObj = parsed.asJsonObject.getAsJsonObject("map")
                    ?: parsed.asJsonObject  // fallback: flat object
                for ((_, info) in mapObj.entrySet()) {
                    val obj = info.asJsonObject
                    if (obj.has("running") && obj.get("running").asBoolean) return true
                }
            }
            return false
        } catch (e: Throwable) {
            log("W", "isRunning check failed", e)
            return false
        }
    }

    /**
     * Get the SOCKS5 endpoint for the running EasyTier instance.
     * Returns `null` if the instance is not running.
     */
    fun getSocks5Endpoint(): Socks5Endpoint? {
        val cfg = currentConfig ?: return null
        if (!running) return null
        return Socks5Endpoint("127.0.0.1", cfg.socks5Port)
    }

    // ------------------------------------------------------------------
    // Xray-core config injection helpers
    // ------------------------------------------------------------------

    /**
     * Build an Xray-core outbound JSON object for the EasyTier SOCKS5 endpoint.
     *
     * This can be appended to the `outbounds` array in the Xray config.
     *
     * ```json
     * {
     *   "tag": "easytier",
     *   "protocol": "socks",
     *   "settings": {
     *     "servers": [{ "address": "127.0.0.1", "port": 10852 }]
     *   }
     * }
     * ```
     */
    fun buildOutboundJson(): JsonObject? {
        val cfg = currentConfig ?: return null
        if (!running) return null

        val outbound = JsonObject()
        outbound.addProperty("tag", OUTBOUND_TAG)
        outbound.addProperty("protocol", "socks")

        val settings = JsonObject()
        val servers = com.google.gson.JsonArray()
        val server = JsonObject()
        server.addProperty("address", "127.0.0.1")
        server.addProperty("port", cfg.socks5Port)
        servers.add(server)
        settings.add("servers", servers)
        outbound.add("settings", settings)

        return outbound
    }

    /**
     * Build Xray-core routing rule JSON objects that direct internal/mesh
     * traffic to the EasyTier outbound.
     *
     * @param customCidrs Additional CIDRs to route through EasyTier (merged with [DEFAULT_LAN_CIDRS]).
     * @return A list of routing rule JSON objects, or `null` if EasyTier is not running.
     */
    fun buildRoutingRules(customCidrs: List<String> = emptyList()): List<JsonObject>? {
        if (!running) return null

        val cidrs = (DEFAULT_LAN_CIDRS + customCidrs).distinct()
        val rule = JsonObject()
        rule.addProperty("type", "field")
        rule.add("ip", Gson().toJsonTree(cidrs))
        rule.addProperty("outboundTag", OUTBOUND_TAG)
        return listOf(rule)
    }

    /**
     * Get the list of mesh peer CIDRs discovered by EasyTier.
     *
     * This calls the EasyTier RPC to collect route information, extracting
     * proxy_cidrs from all running instances.  The result can be used to
     * build dynamic routing rules.
     *
     * @return List of CIDR strings (e.g. `["10.144.144.0/24"]`), or empty if unavailable.
     */
    fun getMeshCidrs(): List<String> {
        if (!running) return emptyList()
        return try {
            val json = EasyTierJNI.collectNetworkInfos(10)
            if (json.isNullOrBlank()) return emptyList()

            // collectNetworkInfos returns NetworkInstanceRunningInfoMap:
            // {"map": {"instance_name": {"running": true, "routes": [...], ...}}}
            val cidrs = mutableSetOf<String>()
            val parsed = JsonParser.parseString(json)
            if (parsed.isJsonObject) {
                val mapObj = parsed.asJsonObject.getAsJsonObject("map")
                    ?: parsed.asJsonObject
                for ((_, info) in mapObj.entrySet()) {
                    val obj = info.asJsonObject
                    // routes is an array of route objects, each may have proxy_cidrs
                    val routes = obj.getAsJsonArray("routes") ?: continue
                    for (route in routes) {
                        val routeObj = route.asJsonObject
                        // proxy_cidrs may be in the route object
                        val proxyCidrs = routeObj.getAsJsonArray("proxy_cidrs")
                        if (proxyCidrs != null) {
                            for (cidr in proxyCidrs) {
                                cidrs.add(cidr.asString)
                            }
                        }
                        // Also check direct_cidrs and other CIDR fields
                        val directCidrs = routeObj.getAsJsonArray("direct_cidrs")
                        if (directCidrs != null) {
                            for (cidr in directCidrs) {
                                cidrs.add(cidr.asString)
                            }
                        }
                    }
                }
            }
            cidrs.toList()
        } catch (e: Throwable) {
            log("W", "Failed to get mesh CIDRs", e)
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Get the raw network info JSON from EasyTier for debugging or UI display.
     */
    fun getNetworkInfoJson(): String? {
        if (!running) return null
        return try {
            EasyTierJNI.collectNetworkInfos(50)
        } catch (e: Throwable) {
            null
        }
    }
}

/** SOCKS5 endpoint for the EasyTier listener. */
data class Socks5Endpoint(
    val host: String,
    val port: Int
)
