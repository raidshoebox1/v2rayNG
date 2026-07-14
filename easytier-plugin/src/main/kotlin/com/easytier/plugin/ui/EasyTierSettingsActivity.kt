package com.easytier.plugin.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import com.easytier.plugin.EasyTierPlugin
import com.easytier.plugin.EasyTierSettingsManager
import com.easytier.plugin.R
import java.util.Locale

/**
 * EasyTier plugin settings activity — form-based UI matching v2rayNG's
 * server-edit style.
 *
 * Fields are auto-saved to SharedPreferences on focus loss (EditText) or
 * selection change (Switch/Spinner).  Diagnostic buttons at the bottom
 * allow starting/stopping EasyTier and viewing logs/network info.
 *
 * Locale is provided by the launching app via the [EXTRA_LOCALE] intent
 * extra so the plugin (which cannot access v2rayNG's SettingsManager)
 * still respects the user's language choice.
 */
class EasyTierSettingsActivity : AppCompatActivity() {

    companion object {
        /** Optional BCP-47 language tag (e.g. "zh-CN", "vi") passed by the launcher. */
        const val EXTRA_LOCALE = "easytier_locale"
    }

    private lateinit var swEnable: SwitchCompat
    private lateinit var etNetworkName: EditText
    private lateinit var etNetworkSecret: EditText
    private lateinit var etHostname: EditText
    private lateinit var etVirtualIp: EditText
    private lateinit var etPeers: EditText
    private lateinit var etSocks5Port: EditText
    private lateinit var swLogEnabled: SwitchCompat
    private lateinit var spLogLevel: Spinner
    private lateinit var tvStatus: TextView

    private val logLevels = arrayOf("error", "warn", "info", "debug", "trace")
    private var isLoading = false

    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getStringExtra(EXTRA_LOCALE)
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
        refreshStatus()
    }

    /**
     * Save all fields when the activity is paused.  EditText fields normally
     * save on focus loss, but the currently-focused field may not lose focus
     * before the activity is destroyed (e.g. user presses the back button),
     * causing v2rayNG to read stale settings.
     */
    override fun onPause() {
        super.onPause()
        saveAllFields()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── View binding ──

    private fun bindViews() {
        swEnable = findViewById(R.id.sw_enable)
        etNetworkName = findViewById(R.id.et_network_name)
        etNetworkSecret = findViewById(R.id.et_network_secret)
        etHostname = findViewById(R.id.et_hostname)
        etVirtualIp = findViewById(R.id.et_virtual_ip)
        etPeers = findViewById(R.id.et_peers)
        etSocks5Port = findViewById(R.id.et_socks5_port)
        swLogEnabled = findViewById(R.id.sw_log_enabled)
        spLogLevel = findViewById(R.id.sp_log_level)
        tvStatus = findViewById(R.id.tv_status)
    }

    // ── Load saved values into form fields ──

    private fun loadValues() {
        isLoading = true
        val ctx = applicationContext
        swEnable.isChecked = EasyTierSettingsManager.isEnabled(ctx)
        etNetworkName.setText(EasyTierSettingsManager.getNetworkName(ctx))
        etNetworkSecret.setText(EasyTierSettingsManager.getNetworkSecret(ctx))
        etHostname.setText(EasyTierSettingsManager.getHostname(ctx) ?: "")
        etVirtualIp.setText(EasyTierSettingsManager.getVirtualIp(ctx) ?: "")
        etPeers.setText(EasyTierSettingsManager.getPeers(ctx).joinToString("\n"))
        etSocks5Port.setText(EasyTierSettingsManager.getSocks5Port(ctx).toString())

        swLogEnabled.isChecked = EasyTierSettingsManager.isLogEnabled(ctx)
        spLogLevel.isEnabled = EasyTierSettingsManager.isLogEnabled(ctx)

        val currentLevel = EasyTierSettingsManager.getLogLevel(ctx)
        spLogLevel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, logLevels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val levelIndex = logLevels.indexOf(currentLevel)
        spLogLevel.setSelection(if (levelIndex >= 0) levelIndex else 1) // default "warn"

        isLoading = false
    }

    // ── Auto-save ──

    private fun setupAutoSave() {
        swEnable.setOnCheckedChangeListener { _, isChecked ->
            if (!isLoading) EasyTierSettingsManager.setEnabled(applicationContext, isChecked)
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
                spLogLevel.isEnabled = isChecked
            }
        }

        spLogLevel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isLoading) EasyTierSettingsManager.setLogLevel(applicationContext, logLevels[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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

    // ── Diagnostics ──

    private fun setupDiagnostics() {
        findViewById<View>(R.id.btn_start).setOnClickListener { startEasyTier() }
        findViewById<View>(R.id.btn_stop).setOnClickListener { stopEasyTier() }
        findViewById<View>(R.id.btn_view_logs).setOnClickListener { showLogDialog() }
        findViewById<View>(R.id.btn_clear_logs).setOnClickListener {
            EasyTierPlugin.clearLogs()
            Toast.makeText(this, R.string.easytier_logs_cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_network_info).setOnClickListener { showNetworkInfoDialog() }
    }

    private fun refreshStatus() {
        val status = EasyTierPlugin.getStatus()
        val error = EasyTierPlugin.getLastError()
        tvStatus.text = when (status) {
            "running" -> getString(R.string.easytier_status_running)
            "starting" -> getString(R.string.easytier_status_starting)
            "error" -> getString(R.string.easytier_status_error) + (error?.let { ": $it" } ?: "")
            else -> getString(R.string.easytier_status_stopped)
        }
    }

    private fun startEasyTier() {
        saveAllFields()
        val ctx = applicationContext
        val config = EasyTierSettingsManager.getEasyTierConfig(ctx)
        if (config == null) {
            EasyTierPlugin.log("E", "EasyTier: cannot start — plugin disabled or network name empty")
            Toast.makeText(this, R.string.easytier_start_failed_disabled, Toast.LENGTH_LONG).show()
            return
        }
        EasyTierPlugin.log("I", "EasyTier: starting from settings UI (network=${config.networkName}, hostname=${config.hostname}, peers=${config.peers.size} peer(s), socks5=${config.socks5Port})")
        val started = EasyTierPlugin.startTest(ctx, config)
        if (started) {
            Toast.makeText(this, R.string.easytier_started, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.easytier_start_failed, Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    private fun stopEasyTier() {
        // Only stop the test instance started from this UI.
        // Do NOT call EasyTierJNI.stopAllInstances() here — that would also
        // kill the VPN service's EasyTier instance (started by CoreServiceManager),
        // breaking the active VPN connection.
        EasyTierPlugin.stopTest()
        refreshStatus()
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

        val textView = TextView(this).apply {
            text = sb.toString()
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 32, 48, 32)
            movementMethod = ScrollingMovementMethod.getInstance()
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(true)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.easytier_pref_view_logs_title)
            .setView(textView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.easytier_clear_logs) { _, _ ->
                EasyTierPlugin.clearLogs()
            }
            .show()
    }

    private fun showNetworkInfoDialog() {
        val info = try {
            EasyTierPlugin.getNetworkInfoJsonStatic()
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

        val textView = TextView(this).apply {
            text = displayText
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 32, 48, 32)
            movementMethod = ScrollingMovementMethod.getInstance()
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(true)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.easytier_pref_network_info_title)
            .setView(textView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
