package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.ButtonRow
import com.mossdial.ui.components.ChipRow
import com.mossdial.ui.components.ErrorNotice
import com.mossdial.ui.components.FieldLabel
import com.mossdial.ui.components.LabeledValue
import com.mossdial.ui.components.Notice
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.SecondaryButton
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
 * The optional Cloudflare tunnel.
 *
 * This screen was the one real layout bug in the app: it laid its controls out in a plain `Column`
 * with no scroll, so on a short screen the token field and the Start and Stop buttons sat below the
 * fold with no way to reach them. It is now a lazy column inside the same scaffold as every other
 * screen, so the whole form is reachable at any screen height.
 */
@Composable
fun TunnelScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val settings = remember { TunnelSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var path by remember { mutableStateOf(settings.executablePath) }
    var protocol by remember { mutableStateOf(settings.protocol) }
    var tokenText by remember { mutableStateOf("") }
    var tokenStored by remember { mutableStateOf(settings.hasToken()) }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }
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
        onDispose { handler.removeCallbacksAndMessages(null) }
    }

    fun saveToken() {
        try {
            settings.storeToken(tokenText)
            tokenStored = true
            tokenText = ""
            preview = ""
            notice = "Token stored encrypted"
            noticeIsError = false
        } catch (error: IllegalArgumentException) {
            notice = error.message ?: "That token was rejected"
            noticeIsError = true
        } catch (_: SecretStoreException) {
            notice = "Secure storage is unavailable on this device"
            noticeIsError = true
        }
    }

    fun startTunnel() {
        if (!enabled) {
            notice = "Enable the tunnel first"
            noticeIsError = true
            return
        }
        when (val resolved = settings.resolve()) {
            is TunnelResolution.Invalid -> {
                notice = resolved.reason
                noticeIsError = true
                preview = ""
            }
            is TunnelResolution.Ready -> {
                // The real command line carries the token, so only the redacted form is shown.
                preview = TunnelCommand.describe(resolved.config)
                notice = ""
                noticeIsError = false
                TunnelManager.start(resolved.config)
                status = TunnelManager.snapshot()
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = screenContentPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.Gap)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
                Text(
                    text = "Public access",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SupportingText("Optional Cloudflare tunnel, using a binary you supply")
            }
        }

        if (notice.isNotEmpty()) {
            item {
                if (noticeIsError) {
                    ErrorNotice(message = notice)
                } else {
                    Notice(message = notice)
                }
            }
        }

        item {
            SectionCard {
                SectionHeading(
                    title = "Tunnel",
                    subtitle = "A remotely-managed tunnel is configured in your Cloudflare dashboard"
                )

                SwitchRow(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        settings.enabled = it
                        notice = ""
                    },
                    title = "Enable tunnel",
                    summary = if (enabled) {
                        "Mossdial runs the cloudflared executable you name, with the token you store"
                    } else {
                        "Off. The site is reachable only on this device or the local network"
                    },
                    enabled = !status.isActive
                )

                OutlinedTextField(
                    value = path,
                    onValueChange = { value ->
                        path = value.filter { it != '\n' }
                            .take(TunnelConfig.MAX_EXECUTABLE_PATH_LENGTH)
                        settings.executablePath = path
                    },
                    label = { Text("cloudflared executable") },
                    supportingText = {
                        Text("Absolute path, for example /data/local/tmp/cloudflared")
                    },
                    singleLine = true,
                    enabled = !status.isActive,
                    modifier = Modifier.fillMaxWidth()
                )

                FieldLabel("Protocol")
                ChipRow {
                    TunnelConfig.ALLOWED_PROTOCOLS.forEach { option ->
                        FilterChip(
                            selected = protocol == option,
                            onClick = {
                                protocol = option
                                settings.protocol = option
                            },
                            enabled = !status.isActive,
                            label = { Text(option) },
                            shape = MaterialTheme.shapes.small
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

                ButtonRow {
                    PrimaryButton(
                        onClick = { startTunnel() },
                        enabled = enabled && !status.isActive
                    ) {
                        ButtonContent(Icons.Default.PlayArrow, "Start tunnel")
                    }
                    SecondaryButton(
                        onClick = {
                            TunnelManager.stop()
                            status = TunnelManager.snapshot()
                        },
                        enabled = status.isActive
                    ) {
                        ButtonContent(Icons.Default.Stop, "Stop")
                    }
                }

                ButtonRow {
                    SecondaryButton(
                        onClick = { saveToken() },
                        enabled = !status.isActive && tokenText.isNotBlank()
                    ) {
                        Text("Save token", style = MaterialTheme.typography.labelLarge)
                    }
                    SecondaryButton(
                        onClick = {
                            settings.clearToken()
                            tokenStored = false
                            tokenText = ""
                            preview = ""
                            notice = "Stored token removed"
                            noticeIsError = false
                        },
                        enabled = !status.isActive && tokenStored
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        item {
            SectionCard {
                SectionHeading(
                    title = "Status",
                    subtitle = "What cloudflared last reported"
                )
                StatusPill(
                    label = when (status.phase) {
                        TunnelPhase.Stopped -> "Stopped"
                        TunnelPhase.Starting -> "Starting"
                        TunnelPhase.Running -> "Connected"
                        TunnelPhase.Failed -> "Not connected"
                    },
                    tone = when (status.phase) {
                        TunnelPhase.Stopped -> StatusTone.Idle
                        TunnelPhase.Starting -> StatusTone.Starting
                        TunnelPhase.Running -> StatusTone.Live
                        TunnelPhase.Failed -> StatusTone.Error
                    }
                )
                if (status.detail.isNotEmpty()) {
                    LabeledValue(label = "Detail", value = status.detail)
                }
                if (status.diagnostic.isNotEmpty()) {
                    LabeledValue(
                        label = "Diagnostic",
                        value = status.diagnostic,
                        monospace = true
                    )
                }
            }
        }

        if (preview.isNotEmpty()) {
            item {
                // A read-out, not a control: the redacted command sits on the background rather
                // than in a box, because the person reading it has nothing to press on it.
                SectionColumn {
                    SectionHeading(
                        title = "Command",
                        subtitle = "With the token redacted, as it is shown everywhere"
                    )
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionColumn {
                SectionHeading(title = "Before you turn this on")
                SupportingText(
                    text = "Map only this device's site port in Cloudflare. The local AI API is on a " +
                        "different port, and publishing it would put a model on the public internet " +
                        "behind one token. Mossdial cannot enforce that for you: the tunnel " +
                        "configuration lives in your Cloudflare account, not in the app."
                )
                SupportingText(
                    text = "No tunnel binary and no Cloudflare SDK is bundled. The app runs the " +
                        "cloudflared executable you name, by absolute path, with no shell, and reads " +
                        "its output with a bound. Everything works without one."
                )
            }
        }
    }
}