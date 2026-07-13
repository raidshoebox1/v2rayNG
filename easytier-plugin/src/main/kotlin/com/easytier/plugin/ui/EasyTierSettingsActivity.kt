package com.easytier.plugin.ui

import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.easytier.plugin.EasyTierPlugin
import com.easytier.plugin.EasyTierSettingsManager
import com.easytier.plugin.R

/**
 * EasyTier plugin settings activity.
 *
 * Provides UI for configuring the EasyTier mesh network:
 * - Enable/disable toggle
 * - Network name and secret
 * - Virtual IP (optional)
 * - Peer addresses
 * - SOCKS5 port
 * - No-TUN mode (always on for v2rayNG coexistence)
 * - Log level
 * - Runtime status and log viewer
 *
 * Accessed from v2rayNG settings via intent:
 *   Intent(context, EasyTierSettingsActivity::class.java)
 */
class EasyTierSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.easytier_settings_activity)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.easytier_settings_container, EasyTierSettingsFragment())
                .commit()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.easytier_settings_title)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class EasyTierSettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // PreferenceFragmentCompat uses default SharedPreferences by default.
            // v2rayNG's MMKV data store is only set on the main SettingsFragment,
            // so our EasyTier fragment stays isolated with standard SharedPreferences.
            setPreferencesFromResource(R.xml.easytier_preferences, rootKey)

            // Wire up summary providers
            findPreference<SwitchPreferenceCompat>(EasyTierSettingsManager.KEY_ENABLED)?.apply {
                title = getString(R.string.easytier_pref_enable_title)
                summary = getString(R.string.easytier_pref_enable_summary)
            }

            findPreference<EditTextPreference>(EasyTierSettingsManager.KEY_NETWORK_NAME)?.apply {
                title = getString(R.string.easytier_pref_network_name_title)
                summary = getString(R.string.easytier_pref_network_name_summary)
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT }
            }

            findPreference<EditTextPreference>(EasyTierSettingsManager.KEY_NETWORK_SECRET)?.apply {
                title = getString(R.string.easytier_pref_network_secret_title)
                summary = getString(R.string.easytier_pref_network_secret_summary)
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            findPreference<EditTextPreference>(EasyTierSettingsManager.KEY_VIRTUAL_IP)?.apply {
                title = getString(R.string.easytier_pref_virtual_ip_title)
                summary = getString(R.string.easytier_pref_virtual_ip_summary)
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT }
            }

            findPreference<EditTextPreference>(EasyTierSettingsManager.KEY_PEERS)?.apply {
                title = getString(R.string.easytier_pref_peers_title)
                summary = getString(R.string.easytier_pref_peers_summary)
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT }
            }

            findPreference<EditTextPreference>(EasyTierSettingsManager.KEY_SOCKS5_PORT)?.apply {
                title = getString(R.string.easytier_pref_socks5_port_title)
                summary = getString(R.string.easytier_pref_socks5_port_summary)
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val port = pref.text?.toIntOrNull() ?: EasyTierPlugin.DEFAULT_SOCKS5_PORT
                    getString(R.string.easytier_pref_socks5_port_summary_value, port)
                }
            }

            findPreference<SwitchPreferenceCompat>(EasyTierSettingsManager.KEY_NO_TUN)?.apply {
                title = getString(R.string.easytier_pref_no_tun_title)
                summary = getString(R.string.easytier_pref_no_tun_summary)
                // Always true for v2rayNG coexistence; disable toggle
                isChecked = true
                isEnabled = false
            }

            findPreference<EditTextPreference>(EasyTierSettingsManager.KEY_LOG_LEVEL)?.apply {
                title = getString(R.string.easytier_pref_log_level_title)
                summary = getString(R.string.easytier_pref_log_level_summary)
            }

            // Status preference — shows current EasyTier status
            findPreference<Preference>("easytier_status")?.apply {
                title = getString(R.string.easytier_pref_status_title)
                isSelectable = false
                summary = formatStatus()
            }

            // Start EasyTier now (for testing without VPN)
            findPreference<Preference>("easytier_start_now")?.setOnPreferenceClickListener {
                startEasyTierFromSettings()
                true
            }

            // Stop EasyTier
            findPreference<Preference>("easytier_stop_now")?.setOnPreferenceClickListener {
                EasyTierPlugin.stopTest()
                // Also stop any VPN-started instance via JNI
                try {
                    com.easytier.jni.EasyTierJNI.stopAllInstances()
                    EasyTierPlugin.log("I", "EasyTier: stopped all instances via stop button")
                } catch (e: Throwable) {
                    EasyTierPlugin.log("E", "EasyTier: stop button failed", e)
                }
                findPreference<Preference>("easytier_status")?.summary = formatStatus()
                true
            }

            // View logs preference — shows a dialog with recent EasyTier logs
            findPreference<Preference>("easytier_view_logs")?.setOnPreferenceClickListener {
                showLogDialog()
                true
            }

            // Clear logs preference
            findPreference<Preference>("easytier_clear_logs")?.setOnPreferenceClickListener {
                EasyTierPlugin.clearLogs()
                true
            }

            // Network info preference — shows raw network info from EasyTier
            findPreference<Preference>("easytier_network_info")?.setOnPreferenceClickListener {
                showNetworkInfoDialog()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            // Refresh status summary when returning to the page
            findPreference<Preference>("easytier_status")?.summary = formatStatus()
        }

        private fun formatStatus(): String {
            val status = EasyTierPlugin.getStatus()
            val error = EasyTierPlugin.getLastError()
            return when (status) {
                "running" -> getString(R.string.easytier_status_running)
                "starting" -> getString(R.string.easytier_status_starting)
                "error" -> getString(R.string.easytier_status_error) + (error?.let { ": $it" } ?: "")
                else -> getString(R.string.easytier_status_stopped)
            }
        }

        private fun startEasyTierFromSettings() {
            val ctx = requireContext()
            val config = EasyTierSettingsManager.getEasyTierConfig(ctx)
            if (config == null) {
                EasyTierPlugin.log("E", "EasyTier: cannot start — plugin disabled or network name empty")
                android.widget.Toast.makeText(ctx, "Enable EasyTier and set Network Name first", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            EasyTierPlugin.log("I", "EasyTier: starting from settings UI (network=${config.networkName}, peers=${config.peers}, socks5=${config.socks5Port})")
            val started = EasyTierPlugin.startTest(ctx, config)
            if (started) {
                android.widget.Toast.makeText(ctx, "EasyTier started — check Status & Logs", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(ctx, "EasyTier failed to start — check Logs", android.widget.Toast.LENGTH_LONG).show()
            }
            // Refresh status
            findPreference<Preference>("easytier_status")?.summary = formatStatus()
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

            val textView = TextView(requireContext()).apply {
                text = sb.toString()
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(48, 32, 48, 32)
                movementMethod = ScrollingMovementMethod.getInstance()
                isVerticalScrollBarEnabled = true
                setHorizontallyScrolling(true)
            }

            AlertDialog.Builder(requireContext())
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
                // Pretty-print JSON if possible
                try {
                    val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    val parsed = com.google.gson.JsonParser.parseString(info)
                    gson.toJson(parsed)
                } catch (e: Exception) {
                    info
                }
            }

            val textView = TextView(requireContext()).apply {
                text = displayText
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(48, 32, 48, 32)
                movementMethod = ScrollingMovementMethod.getInstance()
                isVerticalScrollBarEnabled = true
                setHorizontallyScrolling(true)
            }

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.easytier_pref_network_info_title)
                .setView(textView)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
