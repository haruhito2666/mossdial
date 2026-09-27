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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mossdial.data.SecretStoreException
import com.mossdial.data.TunnelSettings
import com.mossdial.tunnel.TunnelCommand
import com.mossdial.tunnel.TunnelConfig
import com.mossdial.tunnel.TunnelManager
import com.mossdial.tunnel.TunnelPhase
import com.mossdial.tunnel.TunnelResolution
import com.mossdial.tunnel.TunnelStatus

@Composable
fun TunnelScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { TunnelSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var path by remember { mutableStateOf(settings.executablePath) }
    var protocol by remember { mutableStateOf(settings.protocol) }
    var tokenText by remember { mutableStateOf("") }
    var tokenStored by remember { mutableStateOf(settings.hasToken()) }
    var notice by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(TunnelManager.snapshot()) }

    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        val poll = object : Runnable {
            override fun run() {
                status = TunnelManager.snapshot()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
        onDispose { handler.removeCallbacks(poll) }
    }

    fun saveToken() {
        notice = try {
            settings.storeToken(tokenText)
            tokenStored = true
            tokenText = ""
            preview = ""
            "Token stored encrypted"
        } catch (error: IllegalArgumentException) {
            error.message ?: "That token was rejected"
        } catch (_: SecretStoreException) {
            "Secure storage is unavailable on this device"
        }
    }

    fun startTunnel() {
        if (!enabled) {
            notice = "Enable the tunnel first"
            return
        }
        when (val resolved = settings.resolve()) {
            is TunnelResolution.Invalid -> {
                notice = resolved.reason
                preview = ""
            }
            is TunnelResolution.Ready -> {
                // The real command line carries the token, so only the redacted form is shown.
                preview = TunnelCommand.describe(resolved.config)
                notice = ""
                TunnelManager.start(resolved.config)
                status = TunnelManager.snapshot()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Public access", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Optional Cloudflare tunnel", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Mossdial does not ship a tunnel binary. Point the app at a cloudflared executable you " +
                "already trust and give it a Cloudflare remotely-managed tunnel token. The token is " +
                "stored encrypted under a key held by the Android Keystore and is never written to the log.",
            style = MaterialTheme.typography.bodySmall
        )
        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            settings.enabled = it
                            notice = ""
                        },
                        enabled = !status.isActive
                    )
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    Text("Enable tunnel")
                }
                OutlinedTextField(
                    value = path,
                    onValueChange = { value ->
                        path = value.filter { it != '\n' }
                            .take(TunnelConfig.MAX_EXECUTABLE_PATH_LENGTH)
                        settings.executablePath = path
                    },
                    label = { Text("cloudflared executable") },
                    supportingText = { Text("Absolute path, for example /data/local/tmp/cloudflared") },
                    singleLine = true,
                    enabled = !status.isActive,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TunnelConfig.ALLOWED_PROTOCOLS.forEach { option ->
                        FilterChip(
                            selected = protocol == option,
                            onClick = {
                                protocol = option
                                settings.protocol = option
                            },
                            enabled = !status.isActive,
                            label = { Text(option) }
                        )
                    }
                }
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { value ->
                        tokenText = value
                            .filter { it.code in 0x20..0x7e }
                            .take(TunnelConfig.MAX_TOKEN_LENGTH)
                    },
                    label = { Text("Tunnel token") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text(if (tokenStored) "A token is stored encrypted" else "No token stored yet")
                    },
                    singleLine = true,
                    enabled = !status.isActive,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { saveToken() },
                        enabled = !status.isActive && tokenText.isNotBlank()
                    ) { Text("Save token") }
                    OutlinedButton(
                        onClick = {
                            settings.clearToken()
                            tokenStored = false
                            tokenText = ""
                            preview = ""
                            notice = "Stored token removed"
                        },
                        enabled = !status.isActive && tokenStored
                    ) { Text("Clear") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startTunnel() }, enabled = enabled && !status.isActive) {
                        Text("Start tunnel")
                    }
                    OutlinedButton(
                        onClick = {
                            TunnelManager.stop()
                            status = TunnelManager.snapshot()
                        },
                        enabled = status.isActive
                    ) { Text("Stop") }
                }
            }
        }
        TunnelStatusCard(status)
        if (notice.isNotEmpty()) {
            Text(notice, style = MaterialTheme.typography.bodySmall)
        }
        if (preview.isNotEmpty()) {
            Text(preview, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TunnelStatusCard(status: TunnelStatus) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                when (status.phase) {
                    TunnelPhase.Stopped -> "Stopped"
                    TunnelPhase.Starting -> "Starting"
                    TunnelPhase.Running -> "Connected"
                    TunnelPhase.Failed -> "Not connected"
                },
                fontWeight = FontWeight.Bold
            )
            if (status.detail.isNotEmpty()) {
                Text(status.detail, style = MaterialTheme.typography.bodySmall)
            }
            if (status.diagnostic.isNotEmpty()) {
                Text(status.diagnostic, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
