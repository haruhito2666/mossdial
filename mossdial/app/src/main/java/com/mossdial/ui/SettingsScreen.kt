package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mossdial.data.ServerSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.data.TunnelSettings
import com.mossdial.service.ServerState
import com.mossdial.tunnel.TunnelManager
import com.mossdial.tunnel.TunnelPhase
import com.mossdial.ui.components.ChipRow
import com.mossdial.ui.components.FieldLabel
import com.mossdial.ui.components.NavigationRow
import com.mossdial.ui.components.SectionCard
import com.mossdial.ui.components.SectionColumn
import com.mossdial.ui.components.SectionHeading
import com.mossdial.ui.components.StatusPill
import com.mossdial.ui.components.StatusTone
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.components.SwitchRow
import com.mossdial.ui.components.screenContentPadding
import com.mossdial.ui.theme.Spacing

/**
 * Configuration for the local server, plus a way into the tunnel configuration.
 *
 * Every value is written to [ServerSettings] as it is edited, so there is one place the server,
 * the widget and the boot receiver read from and no draft state to lose. A value that is still
 * invalid is shown with the reason and is not written, rather than being silently replaced.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenTunnel: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { ServerSettings(context) }
    val tunnelSettings = remember { TunnelSettings(context) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var portError by remember { mutableStateOf("") }
    var allowLan by remember { mutableStateOf(settings.allowLan) }
    var enableTls by remember { mutableStateOf(settings.enableSsl) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var requestLogging by remember { mutableStateOf(settings.requestLogging) }
    var logLines by remember { mutableStateOf(settings.requestLogLines) }
    var running by remember { mutableStateOf(ServerState.isRunning()) }
    var tunnelPhase by remember { mutableStateOf(TunnelManager.snapshot().phase) }
    var tokenStored by remember { mutableStateOf(tunnelSettings.hasToken()) }

    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        val poll = object : Runnable {
            override fun run() {
                running = ServerState.isRunning()
                tunnelPhase = TunnelManager.snapshot().phase
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
        onDispose { handler.removeCallbacksAndMessages(null) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = screenContentPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.Gap)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SupportingText(
                    text = if (running) {
                        "The server is running, so changes apply the next time it starts"
                    } else {
                        "Changes apply the next time the server starts"
                    }
                )
            }
        }

        item {
            SectionCard {
                SectionHeading(
                    title = "Server",
                    subtitle = "Where it listens and what it records"
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        val digits = value.filter(Char::isDigit).take(ServerSettingsRules.MAX_PORT_DIGITS)
                        portText = digits
                        val parsed = ServerSettingsRules.parsePort(digits)
                        portError = when {
                            digits.isEmpty() -> ""
                            parsed == null -> "Use a port between " +
                                "${ServerSettingsRules.MIN_PORT} and ${ServerSettingsRules.MAX_PORT}"
                            else -> {
                                settings.port = parsed
                                ""
                            }
                        }
                    },
                    label = { Text("Port") },
                    supportingText = {
                        Text(
                            portError.ifEmpty {
                                "Default ${ServerSettingsRules.DEFAULT_PORT}"
                            }
                        )
                    },
                    isError = portError.isNotEmpty(),
                    singleLine = true,
                    // A numeric keyboard, because a port has no letters and the default keyboard
                    // offers a letter row the field will simply reject.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                SwitchRow(
                    checked = allowLan,
                    onCheckedChange = {
                        allowLan = it
                        settings.allowLan = it
                    },
                    title = "Allow LAN access",
                    summary = if (allowLan) {
                        "Listening on ${ServerSettingsRules.ANY_ADDRESS}, so other devices on this " +
                            "network can reach the site"
                    } else {
                        "Listening on ${ServerSettingsRules.LOOPBACK_ADDRESS}, reachable from this " +
                            "device only"
                    }
                )

                SwitchRow(
                    checked = enableTls,
                    onCheckedChange = {
                        enableTls = it
                        settings.enableSsl = it
                    },
                    title = "Use HTTPS",
                    summary = "Serves over TLS with a generated app-private certificate. Browsers will " +
                        "warn that it is not trusted, but it is the same certificate every time."
                )

                SwitchRow(
                    checked = autoStart,
                    onCheckedChange = {
                        autoStart = it
                        settings.autoStart = it
                    },
                    title = "Start after a restart",
                    summary = "Brings the server back after a reboot. Stopping it from the app, the " +
                        "notification or the widget turns this off."
                )

                SwitchRow(
                    checked = requestLogging,
                    onCheckedChange = {
                        requestLogging = it
                        settings.requestLogging = it
                    },
                    title = "Keep a request log",
                    summary = if (requestLogging) {
                        "Remembers the last ${ServerSettingsRules.normalizeLogLines(logLines)} requests " +
                            "in memory, so the Server tab can show them. Nothing is written to disk."
                    } else {
                        "No request is remembered. Counters in the traffic card are still kept, because " +
                            "they record no path."
                    }
                )

                if (requestLogging) {
                    FieldLabel("Requests kept")
                    ChipRow {
                        ServerSettingsRules.LOG_LINES_CHOICES.forEach { choice ->
                            FilterChip(
                                selected = logLines == choice,
                                onClick = {
                                    logLines = choice
                                    settings.requestLogLines = choice
                                },
                                label = { Text(choice.toString()) },
                                shape = MaterialTheme.shapes.small
                            )
                        }
                    }
                }
            }
        }

        item {
            // A pointer to another screen's configuration, read-out first: the tunnel's state, a
            // word about how its token is kept, and one row to open it. Not a card, because there
            // is nothing here to edit.
            SectionColumn {
                SectionHeading(
                    title = "Public tunnel",
                    subtitle = "Optional, and only if you supply the binary"
                )

                // The tunnel's own state is shown here as a pill rather than as a sentence built
                // out of four clauses, which is what this was and what made it unreadable.
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                }

                SupportingText(
                    text = buildString {
                        append(if (tunnelSettings.enabled) "Enabled" else "Disabled")
                        append(" · ")
                        append(if (tokenStored) "a token is stored" else "no token stored")
                        append(". Mossdial does not ship a tunnel binary. The token is stored encrypted " +
                            "under an Android Keystore key and is never written to the log.")
                    }
                )

                NavigationRow(
                    title = "Open tunnel configuration",
                    summary = "Point the app at a cloudflared executable and give it a token",
                    onClick = onOpenTunnel
                )
            }
        }
    }
}