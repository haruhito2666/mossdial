package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mossdial.data.ServerSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.server.LanAddress
import com.mossdial.service.ServerPhase
import com.mossdial.service.ServerState
import com.mossdial.service.ServerStatus
import com.mossdial.ui.components.Band
import com.mossdial.ui.components.BackendPortField
import com.mossdial.ui.components.BasePathField
import com.mossdial.ui.components.ChoiceChip
import com.mossdial.ui.components.ChipRow
import com.mossdial.ui.components.FieldLabel
import com.mossdial.ui.components.HttpsSetting
import com.mossdial.ui.components.LanAccessSetting
import com.mossdial.ui.components.PortField
import com.mossdial.ui.components.RequestLogSetting
import com.mossdial.ui.components.SectionHeader
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.components.SwitchRow
import com.mossdial.ui.components.pageSection
import com.mossdial.ui.theme.Control
import com.mossdial.ui.theme.Spacing

/**
 * The server's full configuration, on its own page.
 *
 * These controls used to sit inline on the server tab, which made the tab a dashboard with a form
 * stapled to its bottom. The tab now carries a button instead, and the form lives here alone — one
 * place to configure the server, one button to reach it, and the tab stays a read-out.
 *
 * The address preview at the top is the page's anchor: every change on this page rewrites it, so
 * the consequence of a toggle is visible before the server is restarted. While the server runs,
 * the preview shows the configuration it is actually serving with, and says so.
 */
@Composable
fun ServerSettingsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { ServerSettings(context) }
    var status by remember { mutableStateOf(ServerState.snapshot()) }
    // The store is not observable state, so each control keeps a mirror to recompose the preview;
    // the mirror is also what makes the address update the instant a switch flips.
    var portValue by remember { mutableStateOf(settings.port) }
    var allowLan by remember { mutableStateOf(settings.allowLan) }
    var useTls by remember { mutableStateOf(settings.enableSsl) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var logging by remember { mutableStateOf(settings.requestLogging) }
    var logLines by remember { mutableStateOf(settings.requestLogLines) }
    var backendEnabled by remember { mutableStateOf(settings.backendEnabled) }

    val handler = remember { Handler(Looper.getMainLooper()) }
    // The server publishes on every phase change and nowhere else, so a listener is exact where a
    // one-second poll was waking the page to read a value that had not changed.
    DisposableEffect(Unit) {
        val listener: (ServerStatus) -> Unit = { next -> handler.post { status = next } }
        ServerState.addListener(listener)
        onDispose {
            ServerState.removeListener(listener)
            handler.removeCallbacksAndMessages(null)
        }
    }

    BackHandler(onBack = onBack)

    val running = status.phase == ServerPhase.Running
    val port = if (running) status.port else portValue
    val lan = if (running) status.allowLan else allowLan
    val tls = if (running) status.enableTls else useTls
    val host = if (lan) {
        LanAddress.siteLocalIPv4() ?: ServerSettingsRules.LOOPBACK_ADDRESS
    } else {
        ServerSettingsRules.LOOPBACK_ADDRESS
    }
    val address = "${if (tls) "https" else "http"}://$host:$port"

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.Snug
        )
    ) {
        pageSection {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Server settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close server settings"
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = Control)
                    .padding(Spacing.Gap),
                verticalArrangement = Arrangement.spacedBy(Spacing.Snug)
            ) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (running) {
                        "The server is running with this address"
                    } else {
                        "The next start will listen here"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Connection")
            PortField(settings = settings, onPortChange = { portValue = it })
            LanAccessSetting(checked = allowLan) {
                allowLan = it
                settings.allowLan = it
            }
            HttpsSetting(checked = useTls) {
                useTls = it
                settings.enableSsl = it
            }
            SupportingText("Applies on the next start.")
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Site paths")
            BasePathField(settings = settings)
            SupportingText(
                text = "The hosted folder is chosen in Files. This prefix is only needed when a " +
                    "build expects a subpath such as /repo/. Applies on the next start."
            )
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Backend runtime")
            SwitchRow(
                checked = backendEnabled,
                onCheckedChange = {
                    backendEnabled = it
                    settings.backendEnabled = it
                },
                title = "Proxy to a local backend",
                summary = if (backendEnabled) {
                    "Next.js, Node, Bun, Deno, Python, PHP or any local HTTP runtime on port ${settings.backendPort}."
                } else {
                    "Off. The listener serves files only."
                }
            )
            if (backendEnabled) {
                BackendPortField(settings = settings)
                SupportingText(
                    text = "Start your runtime on this phone first. Mossdial forwards requests to " +
                        "127.0.0.1 and never starts or manages the process."
                )
            }
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Lifecycle")
            SwitchRow(
                checked = autoStart,
                onCheckedChange = {
                    autoStart = it
                    settings.autoStart = it
                },
                title = "Start after a restart",
                summary = "Brings it back after a reboot; stopping it turns this off."
            )
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Logging")
            RequestLogSetting(settings = settings, checked = logging) {
                logging = it
                settings.requestLogging = it
            }
            FieldLabel("Requests kept")
            ChipRow {
                ServerSettingsRules.LOG_LINES_CHOICES.forEach { choice ->
                    ChoiceChip(
                        label = choice.toString(),
                        selected = logLines == choice,
                        onClick = {
                            logLines = choice
                            settings.requestLogLines = choice
                        }
                    )
                }
            }
        }
    }
}
