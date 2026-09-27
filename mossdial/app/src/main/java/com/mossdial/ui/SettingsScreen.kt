package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mossdial.data.ServerSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.data.TunnelSettings
import com.mossdial.service.ServerState
import com.mossdial.tunnel.TunnelManager
import com.mossdial.tunnel.TunnelPhase

/**
 * Configuration for the local server, plus a way into the tunnel configuration.
 *
 * Every value is written to [ServerSettings] as it is edited, so there is one place the server,
 * the widget and the boot receiver read from and no draft state to lose. A value that is still
 * invalid is shown with the reason and is not written, rather than being silently replaced.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onOpenTunnel: () -> Unit) {
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
        onDispose { handler.removeCallbacks(poll) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            if (running) {
                "The server is running, so changes apply the next time it starts"
            } else {
                "Changes apply the next time the server starts"
            },
            style = MaterialTheme.typography.bodySmall
        )

        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Server", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        val digits = value.filter(Char::isDigit).take(ServerSettingsRules.MAX_PORT_DIGITS)
                        portText = digits
                        val parsed = ServerSettingsRules.parsePort(digits)
                        portError = when {
                            digits.isEmpty() -> ""
                            parsed == null -> "Use a port between ${ServerSettingsRules.MIN_PORT} and ${ServerSettingsRules.MAX_PORT}"
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
                        "Listening on ${ServerSettingsRules.ANY_ADDRESS}, so other devices on this network can reach the site"
                    } else {
                        "Listening on ${ServerSettingsRules.LOOPBACK_ADDRESS}, reachable from this device only"
                    }
                )
                SwitchRow(
                    checked = enableTls,
                    onCheckedChange = {
                        enableTls = it
                        settings.enableSsl = it
                    },
                    title = "Use HTTPS",
                    summary = "Serves over TLS with a generated app-private certificate, so browsers will warn about it"
                )
                SwitchRow(
                    checked = autoStart,
                    onCheckedChange = {
                        autoStart = it
                        settings.autoStart = it
                    },
                    title = "Start after a restart",
                    summary = "Brings the server back after a reboot. Stopping the server from the app, the notification or the widget turns this off."
                )
                SwitchRow(
                    checked = requestLogging,
                    onCheckedChange = {
                        requestLogging = it
                        settings.requestLogging = it
                    },
                    title = "Keep a request log",
                    summary = if (requestLogging) {
                        "Remembers the last ${ServerSettingsRules.normalizeLogLines(logLines)} requests in memory, so the AI tab can show them. Nothing is written to disk."
                    } else {
                        "No request is remembered. Counters in the status line are still kept, because they record no path."
                    }
                )
                if (requestLogging) {
                    Text("Requests kept", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ServerSettingsRules.LOG_LINES_CHOICES.forEach { choice ->
                            FilterChip(
                                selected = logLines == choice,
                                onClick = {
                                    logLines = choice
                                    settings.requestLogLines = choice
                                },
                                label = { Text(choice.toString()) }
                            )
                        }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Public tunnel", fontWeight = FontWeight.Bold)
                Text(
                    tunnelSummary(
                        enabled = tunnelSettings.enabled,
                        tokenStored = tokenStored,
                        protocol = tunnelSettings.protocol,
                        phase = tunnelPhase
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Mossdial does not ship a tunnel binary. The token is stored encrypted under an " +
                        "Android Keystore key and is never written to the log.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onOpenTunnel, modifier = Modifier.fillMaxWidth()) {
                    Text("Open tunnel configuration")
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.padding(horizontal = 8.dp))
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun tunnelSummary(
    enabled: Boolean,
    tokenStored: Boolean,
    protocol: String,
    phase: TunnelPhase
): String {
    val state = when (phase) {
        TunnelPhase.Running -> "connected"
        TunnelPhase.Starting -> "starting"
        TunnelPhase.Failed -> "not connected"
        TunnelPhase.Stopped -> "idle"
    }
    return buildString {
        append(if (enabled) "Enabled" else "Disabled")
        append(" · ").append(protocol)
        append(" · ").append(if (tokenStored) "a token is stored" else "no token stored")
        append(" · ").append(state)
    }
}
