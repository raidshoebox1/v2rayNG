package com.easytier.plugin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.easytier.plugin.EasyTierPlugin
import com.easytier.plugin.EasyTierSettingsManager
import com.easytier.plugin.R
import com.v2ray.ang.compose.AppTopBar
import com.v2ray.ang.compose.PreferenceGroupHeader
import com.v2ray.ang.compose.SettingsEditItem
import com.v2ray.ang.compose.SettingsMenuItem
import com.v2ray.ang.compose.SettingsSwitchItem
import com.v2ray.ang.compose.verticalScrollbar
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.ui.BaseComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EasyTier plugin settings activity.
 *
 * Rewritten as a Jetpack Compose activity extending [BaseComponentActivity] so
 * that it inherits v2rayNG's [AppTheme], locale handling, and edge-to-edge
 * layout — matching the look-and-feel of the main [SettingsActivity].
 *
 * The activity lives in the app module (not the easytier-plugin module) so it
 * can directly use v2rayNG's Compose components (AppTopBar, SettingsSwitchItem,
 * SettingsEditItem, SettingsMenuItem, PreferenceGroupHeader, InputDialog,
 * AlertDialog, toast/toastError/toastSuccess extensions, etc.).  All EasyTier
 * logic (JNI, settings persistence, test instance) remains in the
 * easytier-plugin module and is accessed via [EasyTierPlugin] /
 * [EasyTierSettingsManager].
 *
 * State is held in [rememberSaveable] Compose state and persisted to
 * [EasyTierSettingsManager] on every change.  A background status poller
 * refreshes the status panel every 2 seconds while the activity is resumed.
 * The test EasyTier instance (if any) is stopped on pause.
 */
class EasyTierSettingsActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Ensure the cross-process snapshot file is up-to-date so the VPN
        // service process sees the latest settings.
        EasyTierSettingsManager.flushSnapshot(applicationContext)
    }

    override fun onPause() {
        super.onPause()
        // Stop any test instance started from this activity — it should not
        // outlive the settings activity.
        EasyTierPlugin.stopTest()
        // Safety net: flush the snapshot even if an individual setter missed it.
        EasyTierSettingsManager.flushSnapshot(applicationContext)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun ScreenContent() {
        EasyTierSettingsScreen(onBackClick = { finish() })
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Main settings screen
// ──────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EasyTierSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // ── Form state (rememberSaveable so values survive config changes) ──
    var enabled by rememberSaveable {
        mutableStateOf(EasyTierSettingsManager.isEnabled(context))
    }
    var networkName by rememberSaveable {
        mutableStateOf(EasyTierSettingsManager.getNetworkName(context))
    }
    var networkSecret by rememberSaveable {
        mutableStateOf(EasyTierSettingsManager.getNetworkSecret(context))
    }
    var hostname by rememberSaveable {
        mutableStateOf(EasyTierSettingsManager.getHostname(context) ?: "")
    }
    var virtualIp by rememberSaveable {
        mutableStateOf(EasyTierSettingsManager.getVirtualIp(context) ?: "")
    }
    var peers by rememberSaveable {
        mutableStateOf(EasyTierSettingsManager.getPeers(context).joinToString("\n"))
    }
    var socks5Port by rememberSaveable {
        mutableStateOf(EasyTierSettingsManager.getSocks5Port(context).toString())
    }
    var logEnabled by rememberSaveable {
        mutableStateOf(EasyTierSettingsManager.isLogEnabled(context))
    }

    // ── Status panel state ──
    var statusText by remember { mutableStateOf("") }
    var isTestRunning by remember { mutableStateOf(EasyTierPlugin.isTestRunning()) }

    // ── Dialog state ──
    var showLogDialog by remember { mutableStateOf(false) }
    var showNetworkInfoDialog by remember { mutableStateOf(false) }

    // ── Sync isTestRunning on resume (activity may have been recreated) ──
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isTestRunning = EasyTierPlugin.isTestRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Status refresh: poll every 2 seconds while the composable is active ──
    LaunchedEffect(Unit) {
        while (isActive) {
            val status = withContext(Dispatchers.IO) {
                EasyTierPlugin.getPeerStatus(context)
            }
            statusText = formatStatus(context, status)
            delay(2000)
        }
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.easytier_settings_title),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
        ) {
            // ── General ──
            PreferenceGroupHeader(title = stringResource(R.string.easytier_pref_category_general))
            SettingsSwitchItem(
                title = stringResource(R.string.easytier_pref_enable_title),
                summary = stringResource(
                    if (enabled) R.string.easytier_enable_summary_on
                    else R.string.easytier_enable_summary_off
                ),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    EasyTierSettingsManager.setEnabled(context, it)
                }
            )

            // ── Network ──
            PreferenceGroupHeader(title = stringResource(R.string.easytier_pref_category_network))
            SettingsEditItem(
                title = stringResource(R.string.easytier_pref_network_name_title),
                value = networkName,
                onValueChanged = { newValue ->
                    networkName = newValue
                    EasyTierSettingsManager.setNetworkName(context, newValue.trim())
                }
            )
            SettingsEditItem(
                title = stringResource(R.string.easytier_pref_network_secret_title),
                value = networkSecret,
                isPassword = true,
                onValueChanged = { newValue ->
                    networkSecret = newValue
                    EasyTierSettingsManager.setNetworkSecret(context, newValue)
                }
            )
            SettingsEditItem(
                title = stringResource(R.string.easytier_pref_hostname_title),
                value = hostname,
                onValueChanged = { newValue ->
                    hostname = newValue
                    EasyTierSettingsManager.setHostname(context, newValue.trim().ifEmpty { null })
                }
            )
            SettingsEditItem(
                title = stringResource(R.string.easytier_pref_virtual_ip_title),
                value = virtualIp,
                onValueChanged = { newValue ->
                    val trimmed = newValue.trim()
                    if (trimmed.isEmpty() || isValidVirtualIp(trimmed)) {
                        virtualIp = trimmed
                        EasyTierSettingsManager.setVirtualIp(context, trimmed.ifEmpty { null })
                    } else {
                        context.toastError(R.string.easytier_invalid_virtual_ip)
                    }
                }
            )
            SettingsEditItem(
                title = stringResource(R.string.easytier_pref_peers_title),
                value = peers,
                onValueChanged = { newValue ->
                    val peerList = newValue.split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val invalid = peerList.filter { !isValidPeerUri(it) }
                    if (invalid.isEmpty()) {
                        peers = newValue
                        EasyTierSettingsManager.setPeers(context, peerList)
                    } else {
                        context.toastError(context.getString(R.string.easytier_invalid_peer, invalid.first()))
                    }
                }
            )
            SettingsEditItem(
                title = stringResource(R.string.easytier_pref_socks5_port_title),
                value = socks5Port,
                onValueChanged = { newValue ->
                    val trimmed = newValue.trim()
                    val port = trimmed.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        socks5Port = trimmed
                        EasyTierSettingsManager.setSocks5Port(context, port)
                    } else {
                        context.toastError(R.string.easytier_invalid_port)
                        // Restore the saved value
                        socks5Port = EasyTierSettingsManager.getSocks5Port(context).toString()
                    }
                }
            )

            // ── Status ──
            PreferenceGroupHeader(title = stringResource(R.string.easytier_pref_status_title))
            Text(
                text = statusText.ifEmpty { stringResource(R.string.easytier_no_status) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            )

            // ── Diagnostics ──
            PreferenceGroupHeader(title = stringResource(R.string.easytier_pref_category_diagnostics))
            SettingsSwitchItem(
                title = stringResource(R.string.easytier_pref_log_enabled_title),
                summary = stringResource(R.string.easytier_pref_log_enabled_summary),
                checked = logEnabled,
                onCheckedChange = {
                    logEnabled = it
                    EasyTierSettingsManager.setLogEnabled(context, it)
                }
            )
            SettingsMenuItem(
                title = stringResource(
                    if (isTestRunning) R.string.easytier_test_stop_title
                    else R.string.easytier_pref_start_now_title
                ),
                onClick = {
                    if (isTestRunning) {
                        EasyTierPlugin.stopTest()
                        isTestRunning = false
                        statusText = context.getString(R.string.easytier_no_status)
                    } else {
                        startTestConnection(
                            context = context,
                            scope = scope,
                            onStatusUpdate = { statusText = it },
                            onTestStarted = { isTestRunning = it }
                        )
                    }
                }
            )
            SettingsMenuItem(
                title = stringResource(R.string.easytier_pref_view_logs_title),
                onClick = { showLogDialog = true }
            )
            SettingsMenuItem(
                title = stringResource(R.string.easytier_pref_clear_logs_title),
                onClick = {
                    EasyTierPlugin.clearLogs()
                    context.toast(R.string.easytier_logs_cleared)
                }
            )
            SettingsMenuItem(
                title = stringResource(R.string.easytier_pref_network_info_title),
                onClick = { showNetworkInfoDialog = true }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Dialogs ──
    if (showLogDialog) {
        val logs = EasyTierPlugin.getLogs()
        val sb = StringBuilder()
        if (logs.isEmpty()) {
            sb.append(context.getString(R.string.easytier_logs_empty))
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
        ScrollableTextDialog(
            title = context.getString(R.string.easytier_pref_view_logs_title),
            text = sb.toString(),
            onDismiss = { showLogDialog = false },
            onCopy = {
                copyToClipboard(context, sb.toString())
                context.toast(R.string.easytier_copied_to_clipboard)
            },
            onClear = {
                EasyTierPlugin.clearLogs()
                context.toast(R.string.easytier_logs_cleared)
                showLogDialog = false
            }
        )
    }

    if (showNetworkInfoDialog) {
        val info = try {
            EasyTierPlugin.getNetworkInfoJsonStatic(context)
        } catch (e: Throwable) {
            "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
        val displayText = if (info.isNullOrBlank()) {
            context.getString(R.string.easytier_network_info_empty)
        } else {
            try {
                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                val parsed = com.google.gson.JsonParser.parseString(info)
                gson.toJson(parsed)
            } catch (e: Exception) {
                info
            }
        }
        ScrollableTextDialog(
            title = context.getString(R.string.easytier_pref_network_info_title),
            text = displayText,
            onDismiss = { showNetworkInfoDialog = false },
            onCopy = {
                copyToClipboard(context, displayText)
                context.toast(R.string.easytier_copied_to_clipboard)
            }
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Test Connection
// ──────────────────────────────────────────────────────────────────────────

/**
 * Start a temporary EasyTier instance in the main process so the user can
 * verify mesh connectivity without starting v2rayNG VPN.
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
private fun startTestConnection(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onStatusUpdate: (String) -> Unit,
    onTestStarted: (Boolean) -> Unit
) {
    val config = EasyTierSettingsManager.getEasyTierConfig(context)
    if (config == null) {
        EasyTierPlugin.log("E", "EasyTier: cannot start — plugin disabled or network name empty")
        context.toastError(R.string.easytier_start_failed_disabled)
        return
    }
    if (EasyTierPlugin.isRunningStatic(context)) {
        EasyTierPlugin.log("W", "EasyTier: cannot start test instance — VPN instance is already running")
        context.toastError(R.string.easytier_test_vpn_running)
        return
    }

    onStatusUpdate(context.getString(R.string.easytier_status_starting))
    EasyTierPlugin.log(
        "I",
        "EasyTier: test connection from settings UI (network=${config.networkName}, hostname=${config.hostname}, peers=${config.peers.size} peer(s), socks5=${config.socks5Port})"
    )

    scope.launch {
        // Stop any existing test instance first
        EasyTierPlugin.stopTest()
        val started = withContext(Dispatchers.IO) {
            EasyTierPlugin.startTest(context, config)
        }

        if (!started) {
            val err = EasyTierPlugin.getLastError()
            onStatusUpdate(context.getString(R.string.easytier_status_error) + (err?.let { ": $it" } ?: ""))
            context.toastError(R.string.easytier_start_failed)
            return@launch
        }

        // Wait for peers to converge (up to 10 seconds, poll every 2 seconds)
        var found = false
        var peerCount = 0
        var meshCidrCount = 0
        val pollStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - pollStart < 10000) {
            delay(2000)
            val status = withContext(Dispatchers.IO) { EasyTierPlugin.getPeerStatus(null) }
            if (status != null && status.running && status.peers.isNotEmpty()) {
                peerCount = status.peers.size
                meshCidrCount = status.meshCidrs.size
                found = true
                break
            }
        }

        if (!found) {
            // Even if no peers, check if the instance is running
            val status = withContext(Dispatchers.IO) { EasyTierPlugin.getPeerStatus(null) }
            if (status != null && status.running) {
                peerCount = 0
                meshCidrCount = status.meshCidrs.size
            }
        }

        onTestStarted(true)
        if (found) {
            context.toastSuccess(context.getString(R.string.easytier_test_connected, peerCount, meshCidrCount))
        } else {
            context.toast(context.getString(R.string.easytier_test_no_peers))
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Status formatting
// ──────────────────────────────────────────────────────────────────────────

/**
 * Format a [EasyTierPlugin.MeshStatus] into a multi-line string for the
 * status panel.  Returns an empty string if [status] is null or not running
 * (so the caller can substitute a default "no status" message).
 */
private fun formatStatus(context: Context, status: EasyTierPlugin.MeshStatus?): String {
    if (status == null || !status.running) return ""

    val sb = StringBuilder()
    sb.append("● ").append(context.getString(R.string.easytier_status_running))
    sb.append("\n")

    if (status.virtualIp != null) {
        sb.append(context.getString(R.string.easytier_status_virtual_ip, status.virtualIp))
        sb.append("\n")
    }

    if (status.peers.isEmpty()) {
        sb.append(context.getString(R.string.easytier_status_converging))
    } else {
        sb.append(context.getString(R.string.easytier_status_peers, status.peers.size))
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
                sb.append(context.getString(R.string.easytier_peer_closed))
            } else if (peer.isDirect) {
                sb.append(context.getString(R.string.easytier_peer_direct))
            } else {
                sb.append(context.getString(R.string.easytier_peer_relay))
            }
            sb.append("\n")
        }
    }

    if (status.meshCidrs.isNotEmpty()) {
        sb.append(context.getString(R.string.easytier_status_mesh_cidrs))
        sb.append("\n")
        for (cidr in status.meshCidrs) {
            sb.append("  ").append(cidr).append("\n")
        }
    }

    return sb.toString().trimEnd()
}

// ──────────────────────────────────────────────────────────────────────────
//  Input validation
// ──────────────────────────────────────────────────────────────────────────

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

// ──────────────────────────────────────────────────────────────────────────
//  Scrollable text dialog (logs / network info)
// ──────────────────────────────────────────────────────────────────────────

/**
 * A scrollable, selectable text dialog with optional Copy and Clear buttons.
 *
 * Used for the "View Logs" and "Network Info" dialogs.  The text is shown
 * in a monospace font inside a [SelectionContainer] so the user can select
 * and copy it manually, and a "Copy" button copies the entire text to the
 * clipboard via [ClipboardManager].
 *
 * The dialog height is limited to ~60% of the screen height so it doesn't
 * overflow on long content.
 */
@Composable
private fun ScrollableTextDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = screenHeight * 0.6f)
                    .verticalScroll(scrollState)
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                if (onCopy != null) {
                    TextButton(onClick = onCopy) {
                        Text(stringResource(R.string.easytier_copy_to_clipboard))
                    }
                }
                if (onClear != null) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.easytier_clear_logs))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/**
 * Copy [text] to the system clipboard.
 */
private fun copyToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EasyTier", text))
    } catch (e: Throwable) {
        // ignore — clipboard may not be available in some contexts
    }
}
