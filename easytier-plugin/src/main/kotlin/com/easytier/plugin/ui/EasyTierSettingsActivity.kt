package com.easytier.plugin.ui

import android.os.Bundle
import android.text.InputType
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
        }
    }
}
