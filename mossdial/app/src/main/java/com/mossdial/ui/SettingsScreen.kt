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
import androidx.compose.foundation.layout.fillMaxSize
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
import com.mossdial.data.TunnelSettings
import com.mossdial.data.WebRoot
import com.mossdial.tunnel.BundledCloudflared
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

/** Tunnel status, background permissions, storage and build information. */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenTunnel: () -> Unit
) {
    val context = LocalContext.current
    val tunnelSettings = remember { TunnelSettings(context) }
    var tunnelPhase by remember { mutableStateOf(TunnelManager.snapshot().phase) }
    val tunnelTokenStored = remember { tunnelSettings.hasToken() }
    val webRootPath = remember { WebRoot.ensure(context).absolutePath }

    // Re-checked on resume: the answer changes in the system dialog, which is a different app.
    val resumed = rememberIsResumed()
    val batteryExempt = remember(resumed) {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    val handler = remember { Handler(Looper.getMainLooper()) }
    // Observe tunnel state without polling while the settings page is open.
    DisposableEffect(Unit) {
        val tunnelListener: (TunnelStatus) -> Unit = { next ->
            handler.post { tunnelPhase = next.phase }
        }
        TunnelManager.addListener(tunnelListener)
        onDispose {
            TunnelManager.removeListener(tunnelListener)
            handler.removeCallbacksAndMessages(null)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
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
                    value = if (tunnelSettings.useBundled && BundledCloudflared.isAvailable(context)) {
                        "Built-in ${BundledCloudflared.VERSION}"
                    } else {
                        tunnelSettings.executablePath.ifBlank { "Not set" }
                    },
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
