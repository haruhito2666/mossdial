package com.mossdial.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mossdial.BuildConfig
import com.mossdial.aiapi.AiApiPhase
import com.mossdial.aiapi.AiApiState
import com.mossdial.aiapi.AiApiStatus
import com.mossdial.data.AiApiSettings
import com.mossdial.data.TunnelSettings
import com.mossdial.data.WebRoot
import com.mossdial.tunnel.TunnelManager
import com.mossdial.tunnel.TunnelPhase
import com.mossdial.tunnel.TunnelStatus
import com.mossdial.ui.components.Band
import com.mossdial.ui.components.LabeledValue
import com.mossdial.ui.components.NavigationRow
import com.mossdial.ui.components.SectionHeader
import com.mossdial.ui.components.StatusPill
import com.mossdial.ui.components.StatusTone
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.components.pageSection
import com.mossdial.ui.components.rememberIsResumed
import com.mossdial.ui.theme.Spacing
import java.io.File

/**
 * Everything outside the two tabs that run something: the tunnel, the local AI API, the background
 * permission the server needs to serve with the screen off, where the app keeps its files, and
 * which build this is.
 *
 * This tab is deliberately read-only apart from that one system dialog. A switch that configures
 * the server belongs on the server's own settings page (opened from the Server tab, and only from
 * there), and the tunnel and AI API controls belong with the things they run. What is left is a
 * directory of state and facts: what is configured, what is running, and where it lives.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenTunnel: () -> Unit,
    onOpenAi: () -> Unit
) {
    val context = LocalContext.current
    val tunnelSettings = remember { TunnelSettings(context) }
    val aiApiSettings = remember { runCatching { AiApiSettings(context) }.getOrNull() }
    var tunnelPhase by remember { mutableStateOf(TunnelManager.snapshot().phase) }
    var apiStatus by remember { mutableStateOf(AiApiState.snapshot()) }
    val tunnelTokenStored = remember { tunnelSettings.hasToken() }
    val apiTokenStored = remember { aiApiSettings?.hasToken() == true }
    val webRootPath = remember { WebRoot.ensure(context).absolutePath }
    val modelsPath = remember { File(context.noBackupFilesDir, "models").absolutePath }

    // Re-checked on resume: the answer changes in the system dialog, which is a different app.
    val resumed = rememberIsResumed()
    val batteryExempt = remember(resumed) {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    val handler = remember { Handler(Looper.getMainLooper()) }
    // Listeners rather than a poll: both states publish only when they change, so an idle Settings
    // page costs nothing and a change appears on the frame it happens.
    DisposableEffect(Unit) {
        val tunnelListener: (TunnelStatus) -> Unit = { next ->
            handler.post { tunnelPhase = next.phase }
        }
        val apiListener: (AiApiStatus) -> Unit = { next ->
            handler.post { apiStatus = next }
        }
        TunnelManager.addListener(tunnelListener)
        AiApiState.addListener(apiListener)
        onDispose {
            TunnelManager.removeListener(tunnelListener)
            AiApiState.removeListener(apiListener)
            handler.removeCallbacksAndMessages(null)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.Snug
        )
    ) {
        pageSection {
            SectionHeader(title = "Public tunnel")
            StatusPill(
                label = when (tunnelPhase) {
                    TunnelPhase.Running -> "Connected"
                    TunnelPhase.Starting -> "Starting"
                    TunnelPhase.Failed -> "Not connected"
                    TunnelPhase.Stopped -> "Idle"
                },
                tone = when (tunnelPhase) {
                    TunnelPhase.Running -> StatusTone.Live
                    TunnelPhase.Starting -> StatusTone.Starting
                    TunnelPhase.Failed -> StatusTone.Error
                    TunnelPhase.Stopped -> StatusTone.Idle
                },
                detail = tunnelSettings.protocol
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)
            ) {
                LabeledValue(
                    label = "Binary",
                    value = tunnelSettings.executablePath.ifBlank { "Not set" },
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                LabeledValue(
                    label = "Token",
                    value = if (tunnelTokenStored) "Stored" else "Not set",
                    modifier = Modifier.weight(1f)
                )
            }
            NavigationRow(
                title = "Open tunnel",
                summary = if (tunnelSettings.enabled) "Enabled" else "Disabled",
                onClick = onOpenTunnel
            )
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Local AI API")
            StatusPill(
                label = when (apiStatus.phase) {
                    AiApiPhase.Running -> "Running"
                    AiApiPhase.Failed -> "Unavailable"
                    AiApiPhase.Stopped -> "Not running"
                },
                tone = when (apiStatus.phase) {
                    AiApiPhase.Running -> StatusTone.Live
                    AiApiPhase.Failed -> StatusTone.Error
                    AiApiPhase.Stopped -> StatusTone.Idle
                },
                detail = if (apiStatus.isRunning) "port ${apiStatus.port}" else null
            )
            if (aiApiSettings == null) {
                SupportingText("Secure storage is unavailable on this device.")
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)
                ) {
                    // While the API runs, the published status is what it actually bound; otherwise
                    // the stored configuration is what the next start will use.
                    val apiPort = if (apiStatus.isRunning) apiStatus.port else aiApiSettings.port
                    val apiAllowLan = if (apiStatus.isRunning) {
                        apiStatus.allowLan
                    } else {
                        aiApiSettings.allowLan
                    }
                    LabeledValue(
                        label = "Port",
                        value = apiPort.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    LabeledValue(
                        label = "Access",
                        value = if (apiAllowLan) "LAN" else "This device",
                        modifier = Modifier.weight(1f)
                    )
                    LabeledValue(
                        label = "Token",
                        value = if (apiTokenStored) "Stored" else "Not set",
                        modifier = Modifier.weight(1f)
                    )
                }
                if (apiStatus.isRunning && apiStatus.endpoint().isNotEmpty()) {
                    LabeledValue(
                        label = "Endpoint",
                        value = apiStatus.endpoint(),
                        monospace = true
                    )
                }
            }
            NavigationRow(
                title = "Open AI",
                onClick = onOpenAi
            )
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Background")
            NavigationRow(
                title = "Battery optimization",
                summary = if (batteryExempt) {
                    "Unrestricted — keeps serving with the screen off"
                } else {
                    "Optimized — Android may pause the server when idle"
                },
                onClick = {
                    val intent = if (batteryExempt) {
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    } else {
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    }
                    runCatching { context.startActivity(intent) }
                }
            )
            SupportingText(
                if (batteryExempt) {
                    "Android will not defer the server's network while the phone is idle."
                } else {
                    "Allows the server to answer while the phone is in deep sleep."
                }
            )
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Storage")
            LabeledValue(label = "Web root", value = webRootPath, monospace = true)
            LabeledValue(label = "Models", value = modelsPath, monospace = true)
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "About")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)
            ) {
                LabeledValue(
                    label = "Version",
                    value = BuildConfig.VERSION_NAME,
                    modifier = Modifier.weight(1f)
                )
                LabeledValue(
                    label = "Build",
                    value = BuildConfig.BUILD_TYPE,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
