package com.easytier.plugin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import com.easytier.plugin.EasyTierPlugin
import com.easytier.plugin.EasyTierSettingsManager
import com.easytier.plugin.R
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * EasyTier plugin settings activity — form-based UI matching v2rayNG's
 * server-edit style.
 *
 * Fields are auto-saved to SharedPreferences on focus loss (EditText) or
 * selection change (Switch).  Diagnostic buttons at the bottom allow
 * testing the connection and viewing logs/network info.
 *
 * A live status panel below the General settings shows the current mesh
 * state (virtual IP, peers, mesh CIDRs) when EasyTier is running, refreshing
 * every 2 seconds.
 *
 * Cross-process status:
 * The VPN service runs in `:RunSoLibV2RayDaemon` and the native EasyTier
 * instance lives in that process.  This activity runs in the main process
 * and cannot query the native instance directly via JNI.  To display live
 * status, the VPN process writes a status snapshot file every 3 seconds;
 * [refreshStatusAsync] reads it via [EasyTierPlugin.getPeerStatus] which
 * falls back to the snapshot when no local (test) instance is running.
 *
 * Test Connection:
 * Starts a temporary EasyTier instance in the main process (via JNI) so
 * the user can verify mesh connectivity without starting v2rayNG VPN.
 * The test instance stays running until the user taps "Stop Test" or
 * leaves the activity, so the status panel and Network Info dialog show
 * live data.
 *
 * Locale is provided by the launching app via [localeOverride] so the
 * plugin (which cannot access v2rayNG's SettingsManager) still respects
 * the user's language choice.
 */
class EasyTierSettingsActivity : AppCompatActivity() {

    companion object {
        /**
         * Set by the launcher before starting this activity so that
         * [attachBaseContext] can wrap the locale.  Cleared after use
         * to avoid leakage between launches.
         * Format: BCP-47 language tag, e.g. "zh-CN", "vi", "ru".
         */
        @Volatile
        var localeOverride: String? = null

        private const val STATUS_REFRESH_MS = 2000L
        private const val PEER_POLL_INTERVAL_MS = 2000L
        private const val PEER_POLL_MAX_MS = 10000L
    }

    private lateinit var swEnable: SwitchCompat
    private lateinit var tvEnableSummary: TextView
    private lateinit var etNetworkName: EditText
    private lateinit var etNetworkSecret: EditText
    private lateinit var etHostname: EditText
    private lateinit var etVirtualIp: EditText
    private lateinit var etPeers: EditText
    private lateinit var etSocks5Port: EditText
    private lateinit var swLogEnabled: SwitchCompat
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: MaterialButton

    private var isLoading = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshStatusAsync()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val tag = localeOverride
        localeOverride = null  // consume to avoid leakage
        if (!tag.isNullOrBlank()) {
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            config.setLocales(LocaleList(locale))
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.easytier_settings_activity)

        val toolbar = findViewById<Toolbar>(R.id.easytier_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.easytier_settings_title)

        bindViews()
        loadValues()
        setupAutoSave()
        setupDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        // Ensure the cross-process snapshot file exists and is up-to-date.
        EasyTierSettingsManager.flushSnapshot(applicationContext)
        // If a test instance is already running (e.g. activity recreated),
        // restore the button to "Stop Test" state.
        if (EasyTierPlugin.isTestRunning()) {
            btnStart.text = getString(R.string.easytier_test_stop_title)
        }
        refreshStatusAsync()
    }

    /**
     * Save all fields and stop any running test instance when the activity
     * is paused.  EditText fields normally save on focus loss, but the
     * currently-focused field may not lose focus before the activity is
     * destroyed (e.g. user presses the back button), causing v2rayNG to
     * read stale settings.
     */
    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(statusRunnable)
        saveAllFields()
        // Stop any test instance started from this UI — it should not
        // outlive the settings activity.
        EasyTierPlugin.stopTest()
        // Safety net: ensure the cross-process snapshot file is up-to-date
        // even if an individual setter missed writing it.
        EasyTierSettingsManager.flushSnapshot(applicationContext)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── View binding ──

    private fun bindViews() {
        swEnable = findViewById(R.id.sw_enable)
        tvEnableSummary = findViewById(R.id.tv_enable_summary)
        etNetworkName = findViewById(R.id.et_network_name)
        etNetworkSecret = findViewById(R.id.et_network_secret)
        etHostname = findViewById(R.id.et_hostname)
        etVirtualIp = findViewById(R.id.et_virtual_ip)
        etPeers = findViewById(R.id.et_peers)
        etSocks5Port = findViewById(R.id.et_socks5_port)
        swLogEnabled = findViewById(R.id.sw_log_enabled)
        tvStatus = findViewById(R.id.tv_status)
        btnStart = findViewById(R.id.btn_start)
    }

    // ── Load saved values into form fields ──

    private fun loadValues() {
        isLoading = true
        val ctx = applicationContext
        swEnable.isChecked = EasyTierSettingsManager.isEnabled(ctx)
        updateEnableSummary(swEnable.isChecked)
        etNetworkName.setText(EasyTierSettingsManager.getNetworkName(ctx))
        etNetworkSecret.setText(EasyTierSettingsManager.getNetworkSecret(ctx))
        etHostname.setText(EasyTierSettingsManager.getHostname(ctx) ?: "")
        etVirtualIp.setText(EasyTierSettingsManager.getVirtualIp(ctx) ?: "")
        etPeers.setText(EasyTierSettingsManager.getPeers(ctx).joinToString("\n"))
        etSocks5Port.setText(EasyTierSettingsManager.getSocks5Port(ctx).toString())

        swLogEnabled.isChecked = EasyTierSettingsManager.isLogEnabled(ctx)

        isLoading = false
    }

    // ── Auto-save ──

    private fun setupAutoSave() {
        swEnable.setOnCheckedChangeListener { _, isChecked ->
            if (!isLoading) {
                EasyTierSettingsManager.setEnabled(applicationContext, isChecked)
                updateEnableSummary(isChecked)
            }
        }

        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus && !isLoading) saveField(v.id)
        }
        etNetworkName.onFocusChangeListener = focusListener
        etNetworkSecret.onFocusChangeListener = focusListener
        etHostname.onFocusChangeListener = focusListener
        etVirtualIp.onFocusChangeListener = focusListener
        etPeers.onFocusChangeListener = focusListener
        etSocks5Port.onFocusChangeListener = focusListener

        swLogEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!isLoading) {
                EasyTierSettingsManager.setLogEnabled(applicationContext, isChecked)
            }
        }
    }

    private fun updateEnableSummary(enabled: Boolean) {
        tvEnableSummary.text = if (enabled) {
            getString(R.string.easytier_enable_summary_on)
        } else {
            getString(R.string.easytier_enable_summary_off)
        }
    }

    private fun saveField(viewId: Int) {
        val ctx = applicationContext
        when (viewId) {
            R.id.et_network_name -> EasyTierSettingsManager.setNetworkName(ctx, etNetworkName.text.toString().trim())
            R.id.et_network_secret -> EasyTierSettingsManager.setNetworkSecret(ctx, etNetworkSecret.text.toString())
            R.id.et_hostname -> EasyTierSettingsManager.setHostname(ctx, etHostname.text.toString().trim().ifEmpty { null })
            R.id.et_virtual_ip -> {
                val ip = etVirtualIp.text.toString().trim()
                if (ip.isEmpty() || isValidVirtualIp(ip)) {
                    EasyTierSettingsManager.setVirtualIp(ctx, ip.ifEmpty { null })
                } else {
                    Toast.makeText(this, R.string.easytier_invalid_virtual_ip, Toast.LENGTH_SHORT).show()
                }
            }
            R.id.et_peers -> {
                val peers = etPeers.text.toString()
                    .split("\n", ",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val invalid = peers.filter { !isValidPeerUri(it) }
                if (invalid.isEmpty()) {
                    EasyTierSettingsManager.setPeers(ctx, peers)
                } else {
                    Toast.makeText(this, getString(R.string.easytier_invalid_peer, invalid.first()), Toast.LENGTH_LONG).show()
                }
            }
            R.id.et_socks5_port -> {
                val port = etSocks5Port.text.toString().toIntOrNull()
                if (port != null && port in 1..65535) {
                    EasyTierSettingsManager.setSocks5Port(ctx, port)
                } else {
                    Toast.makeText(this, R.string.easytier_invalid_port, Toast.LENGTH_SHORT).show()
                    // Restore the saved value
                    etSocks5Port.setText(EasyTierSettingsManager.getSocks5Port(ctx).toString())
                }
            }
        }
    }

    /** Save all editable fields at once (used before starting EasyTier). */
    private fun saveAllFields() {
        saveField(R.id.et_network_name)
        saveField(R.id.et_network_secret)
        saveField(R.id.et_hostname)
        saveField(R.id.et_virtual_ip)
        saveField(R.id.et_peers)
        saveField(R.id.et_socks5_port)
    }

    // ── Input validation ──

    /** Valid EasyTier peer URI schemes. */
    private val peerSchemes = setOf("tcp", "udp", "ws", "wss", "wg", "ring")

    /**
     * Validate a peer URI.  Accepts schemes: tcp://, udp://, ws://, wss://, wg://, ring://.
     * Also accepts plain `host:port` (treated as TCP by EasyTier).
     */
    private fun isValidPeerUri(uri: String): Boolean {
        val scheme = uri.substringBefore("://", "")
        if (scheme.isNotEmpty()) return scheme.lowercase() in peerSchemes
        // Plain host:port — accept if it looks like host:port or a valid hostname
        return uri.contains(":") || uri.contains(".")
    }

    /**
     * Validate a virtual IP address.  Accepts IPv4 (a.b.c.d) or IPv4 with CIDR (a.b.c.d/n).
     */
    private fun isValidVirtualIp(ip: String): Boolean {
        val cidrRegex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(/(\d{1,2}))?$""")
        val m = cidrRegex.matchEntire(ip.trim()) ?: return false
        val o1 = m.groupValues[1].toIntOrNull() ?: return false
        val o2 = m.groupValues[2].toIntOrNull() ?: return false
        val o3 = m.groupValues[3].toIntOrNull() ?: return false
        val o4 = m.groupValues[4].toIntOrNull() ?: return false
        if (o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255 || o4 !in 0..255) return false
        if (m.groupValues[5].isNotEmpty()) {
            val prefix = m.groupValues[6].toIntOrNull() ?: return false
            if (prefix !in 0..32) return false
        }
        return true
    }

    // ── Status panel ──

    private fun setupDiagnostics() {
        btnStart.setOnClickListener {
            if (EasyTierPlugin.isTestRunning()) {
                stopTest()
            } else {
                testConnection()
            }
        }
        findViewById<View>(R.id.btn_view_logs).setOnClickListener { showLogDialog() }
        findViewById<View>(R.id.btn_clear_logs).setOnClickListener {
            EasyTierPlugin.clearLogs()
            Toast.makeText(this, R.string.easytier_logs_cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_network_info).setOnClickListener { showNetworkInfoDialog() }
    }

    /**
     * Refresh the status panel asynchronously.  Calls getPeerStatus() on a
     * background thread and updates the UI on the main thread, then schedules
     * the next refresh.
     */
    private fun refreshStatusAsync() {
        Thread {
            val status = EasyTierPlugin.getPeerStatus(applicationContext)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                updateStatusPanel(status)
                // Schedule next refresh only if the activity is still resumed
                mainHandler.postDelayed(statusRunnable, STATUS_REFRESH_MS)
            }
        }.start()
    }

    /**
     * Update the status panel with the given MeshStatus.  Shows a multi-line
     * summary including virtual IP, peer list with latency/direct/relay, and
     * mesh CIDRs.
     */
    private fun updateStatusPanel(status: EasyTierPlugin.MeshStatus?) {
        if (status == null || !status.running) {
            if (status?.errorMsg != null) {
                tvStatus.text = getString(R.string.easytier_status_error) + ": " + status.errorMsg
            } else {
                tvStatus.text = getString(R.string.easytier_no_status)
            }
            return
        }

        val sb = StringBuilder()
        sb.append("● ").append(getString(R.string.easytier_status_running))
        sb.append("\n")

        if (status.virtualIp != null) {
            sb.append(getString(R.string.easytier_status_virtual_ip, status.virtualIp))
            sb.append("\n")
        }

        if (status.peers.isEmpty()) {
            sb.append(getString(R.string.easytier_status_converging))
        } else {
            sb.append(getString(R.string.easytier_status_peers, status.peers.size))
            sb.append("\n")
            for (peer in status.peers) {
                sb.append("  ")
                sb.append(peer.hostname)
                if (peer.virtualIp != null) {
                    sb.append("  ").append(peer.virtualIp)
                }
                if (peer.latencyMs != null) {
                    sb.append("  ").append(peer.latencyMs).append("ms")
                }
                sb.append("  ")
                if (peer.isClosed) {
                    sb.append(getString(R.string.easytier_peer_closed))
                } else if (peer.isDirect) {
                    sb.append(getString(R.string.easytier_peer_direct))
                } else {
                    sb.append(getString(R.string.easytier_peer_relay))
                }
                sb.append("\n")
            }
        }

        if (status.meshCidrs.isNotEmpty()) {
            sb.append(getString(R.string.easytier_status_mesh_cidrs))
            sb.append("\n")
            for (cidr in status.meshCidrs) {
                sb.append("  ").append(cidr).append("\n")
            }
        }

        tvStatus.text = sb.toString().trimEnd()
    }

    // ── Test Connection ──

    /**
     * Start a temporary EasyTier instance in the main process so the user
     * can verify mesh connectivity without starting v2rayNG VPN.
     *
     * The test instance stays running after peers are found (or after the
     * 10-second poll window) so the user can inspect the status panel and
     * Network Info dialog.  The instance is stopped when:
     * - The user taps the button again (now labeled "Stop Test"), or
     * - The activity is paused (onPause)
     *
     * If the VPN service already has an EasyTier instance running, the
     * test is skipped — the status panel above already shows the live state
     * from the cross-process snapshot.
     */
    private fun testConnection() {
        saveAllFields()
        val ctx = applicationContext
        val config = EasyTierSettingsManager.getEasyTierConfig(ctx)
        if (config == null) {
            EasyTierPlugin.log("E", "EasyTier: cannot start — plugin disabled or network name empty")
            Toast.makeText(this, R.string.easytier_start_failed_disabled, Toast.LENGTH_LONG).show()
            return
        }
        if (EasyTierPlugin.isRunningStatic(ctx)) {
            EasyTierPlugin.log("W", "EasyTier: cannot start test instance — VPN instance is already running")
            Toast.makeText(this, R.string.easytier_test_vpn_running, Toast.LENGTH_LONG).show()
            refreshStatusAsync()
            return
        }

        // Disable the button and show "Starting…" while the test instance starts
        btnStart.isEnabled = false
        tvStatus.text = getString(R.string.easytier_status_starting)

        EasyTierPlugin.log("I", "EasyTier: test connection from settings UI (network=${config.networkName}, hostname=${config.hostname}, peers=${config.peers.size} peer(s), socks5=${config.socks5Port})")

        Thread {
            // Stop any existing test instance first
            EasyTierPlugin.stopTest()
            val started = EasyTierPlugin.startTest(ctx, config)

            if (!started) {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    btnStart.isEnabled = true
                    val err = EasyTierPlugin.getLastError()
                    tvStatus.text = getString(R.string.easytier_status_error) + (err?.let { ": $it" } ?: "")
                    Toast.makeText(this, R.string.easytier_start_failed, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            // Wait for peers to converge (up to 10 seconds, poll every 2 seconds)
            var found = false
            var peerCount = 0
            var meshCidrCount = 0
            val pollStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - pollStart < PEER_POLL_MAX_MS) {
                Thread.sleep(PEER_POLL_INTERVAL_MS)
                val status = EasyTierPlugin.getPeerStatus(null)
                if (status != null && status.running && status.peers.isNotEmpty()) {
                    peerCount = status.peers.size
                    meshCidrCount = status.meshCidrs.size
                    found = true
                    break
                }
            }

            if (!found) {
                // Even if no peers, check if the instance is running
                val status = EasyTierPlugin.getPeerStatus(null)
                if (status != null && status.running) {
                    peerCount = 0
                    meshCidrCount = status.meshCidrs.size
                }
            }

            val foundPeers = found
            val finalPeerCount = peerCount
            val finalMeshCidrCount = meshCidrCount
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                // Re-enable the button and change label to "Stop Test"
                btnStart.isEnabled = true
                btnStart.text = getString(R.string.easytier_test_stop_title)
                if (foundPeers) {
                    Toast.makeText(this, getString(R.string.easytier_test_connected, finalPeerCount, finalMeshCidrCount), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.easytier_test_no_peers, Toast.LENGTH_LONG).show()
                }
                // Refresh the status panel — will show live test data since
                // the test instance is still running in this process.
                refreshStatusAsync()
            }
        }.start()
    }

    /**
     * Stop the test instance started from this UI and restore the button
     * to "Test Connection".  Refresh the status panel afterwards.
     */
    private fun stopTest() {
        EasyTierPlugin.stopTest()
        btnStart.text = getString(R.string.easytier_pref_start_now_title)
        refreshStatusAsync()
    }

    private fun showLogDialog() {
        val logs = EasyTierPlugin.getLogs()
        val sb = StringBuilder()
        if (logs.isEmpty()) {
            sb.append(getString(R.string.easytier_logs_empty))
        } else {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            for (entry in logs) {
                sb.append("[")
                sb.append(sdf.format(java.util.Date(entry.timestamp)))
                sb.append("] ")
                sb.append(entry.level)
                sb.append(": ")
                sb.append(entry.message)
                sb.append("\n")
            }
        }

        showTextDialog(
            title = getString(R.string.easytier_pref_view_logs_title),
            text = sb.toString(),
            negativeLabel = getString(R.string.easytier_clear_logs),
            negativeAction = { EasyTierPlugin.clearLogs() }
        )
    }

    private fun showNetworkInfoDialog() {
        val info = try {
            EasyTierPlugin.getNetworkInfoJsonStatic(applicationContext)
        } catch (e: Throwable) {
            "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
        val displayText = if (info.isNullOrBlank()) {
            getString(R.string.easytier_network_info_empty)
        } else {
            try {
                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                val parsed = com.google.gson.JsonParser.parseString(info)
                gson.toJson(parsed)
            } catch (e: Exception) {
                info
            }
        }

        showTextDialog(
            title = getString(R.string.easytier_pref_network_info_title),
            text = displayText,
            negativeLabel = null,
            negativeAction = null
        )
    }

    /**
     * Show a scrollable, selectable text dialog with a Copy button.
     *
     * Uses a ScrollView + TextView layout (dialog_easytier_text.xml) that
     * wraps long lines automatically (no horizontal scrolling), supports
     * text selection/copy, and limits the dialog height to ~60% of the
     * screen so it doesn't overflow.
     *
     * @param title       Dialog title
     * @param text        Text content to display
     * @param negativeLabel  Label for the negative button (e.g. "Clear Logs"),
     *                       or null to hide the negative button
     * @param negativeAction  Action to perform when the negative button is clicked
     */
    private fun showTextDialog(
        title: String,
        text: String,
        negativeLabel: String?,
        negativeAction: (() -> Unit)?
    ) {
        val scrollView = LayoutInflater.from(this).inflate(R.layout.dialog_easytier_text, null) as ScrollView
        val textView = scrollView.findViewById<TextView>(R.id.tv_dialog_text)
        textView.text = text

        // Limit dialog height to ~60% of the screen so it doesn't overflow
        val displayMetrics = resources.displayMetrics
        val maxHeight = (displayMetrics.heightPixels * 0.6).toInt()
        scrollView.layoutParams = ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        )
        scrollView.post {
            if (scrollView.height > maxHeight) {
                val params = scrollView.layoutParams
                params.height = maxHeight
                scrollView.layoutParams = params
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.easytier_copy_to_clipboard) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, text))
                Toast.makeText(this, R.string.easytier_copied_to_clipboard, Toast.LENGTH_SHORT).show()
            }

        if (negativeLabel != null && negativeAction != null) {
            builder.setNegativeButton(negativeLabel) { _, _ -> negativeAction() }
        }

        builder.show()
    }
}
