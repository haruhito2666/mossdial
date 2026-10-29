package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import com.mossdial.ui.components.Band
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.ButtonRow
import com.mossdial.ui.components.ChoiceChip
import com.mossdial.ui.components.ChipRow
import com.mossdial.ui.components.ErrorNotice
import com.mossdial.ui.components.FieldLabel
import com.mossdial.ui.components.LabeledValue
import com.mossdial.ui.components.Notice
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.PulseDot
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.components.SectionHeader
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.components.SwitchRow
import com.mossdial.ui.components.pageSection
import com.mossdial.ui.theme.Control
import com.mossdial.ui.theme.Spacing

/**
 * The optional Cloudflare tunnel.
 *
 * Like every page now, it is one column of inset sections separated by full-bleed bands, and it
 * leads with the state: what cloudflared is doing, the binary it is pointed at, and the word for
 * the phase. The configuration follows — the tunnel, its token, the one action the moment calls
 * for — and the page ends with the warning the tunnel deserves.
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
    // The manager publishes when the phase changes or the process exits, never per output line, so
    // a listener is exact where a twice-a-second poll was reading a value that had not moved.
    DisposableEffect(Unit) {
        val listener: (TunnelStatus) -> Unit = { next -> handler.post { status = next } }
        TunnelManager.addListener(listener)
        onDispose {
            TunnelManager.removeListener(listener)
            handler.removeCallbacksAndMessages(null)
        }
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
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.Snug
        )
    ) {
        val locked = status.isActive

        pageSection {
            TunnelHero(
                phase = status.phase,
                path = path,
                protocol = protocol,
                tokenStored = tokenStored
            )
            if (notice.isNotEmpty()) {
                if (noticeIsError) {
                    ErrorNotice(message = notice)
                } else {
                    Notice(message = notice)
                }
            }
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Configure")
            SwitchRow(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    settings.enabled = it
                    notice = ""
                },
                title = "Enable tunnel",
                summary = if (enabled) {
                    "Runs the cloudflared you name with the stored token"
                } else {
                    "Off. The site stays local."
                },
                enabled = !locked
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
                    Text("/data/local/tmp/cloudflared")
                },
                singleLine = true,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth()
            )
            FieldLabel("Protocol")
            ChipRow {
                TunnelConfig.ALLOWED_PROTOCOLS.forEach { option ->
                    ChoiceChip(
                        label = option,
                        selected = protocol == option,
                        onClick = {
                            protocol = option
                            settings.protocol = option
                        },
                        enabled = !locked
                    )
                }
            }
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Token")
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
                enabled = !locked,
                modifier = Modifier.fillMaxWidth()
            )
            ButtonRow {
                SecondaryButton(
                    onClick = { saveToken() },
                    enabled = !locked && tokenText.isNotBlank()
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
                    enabled = !locked && tokenStored
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        item { Band() }

        pageSection {
            ButtonRow {
                PrimaryButton(
                    onClick = { startTunnel() },
                    enabled = enabled && !locked
                ) {
                    ButtonContent(Icons.Default.PlayArrow, "Start tunnel")
                }
                SecondaryButton(
                    onClick = {
                        TunnelManager.stop()
                        status = TunnelManager.snapshot()
                    },
                    // Stop is also the way to cancel the pause before an automatic reconnect.
                    enabled = locked || status.phase == TunnelPhase.Failed
                ) {
                    ButtonContent(Icons.Default.Stop, "Stop")
                }
            }
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Status")
            if (status.detail.isEmpty() && status.diagnostic.isEmpty()) {
                SupportingText("Nothing reported yet.")
            } else {
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
            item { Band() }
            pageSection {
                SectionHeader(title = "Command", subtitle = "Token redacted")
                // A read-out in its own shade, the way the server page presents its address:
                // it is the one value here a person might want to read closely.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = Control
                        )
                        .padding(Spacing.Gap)
                ) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item { Band() }

        pageSection {
            SectionHeader(title = "Before you turn this on")
            SupportingText(
                text = "Map only the site's port in Cloudflare — the AI API is on another port " +
                    "and should stay off it."
            )
            SupportingText(
                text = "No tunnel binary is bundled; the app runs the one you name."
            )
        }
    }
}

private fun tunnelLabel(phase: TunnelPhase): String = when (phase) {
    TunnelPhase.Running -> "Connected"
    TunnelPhase.Starting -> "Starting"
    TunnelPhase.Failed -> "Failed"
    TunnelPhase.Stopped -> "Idle"
}

/**
 * The top of the page: the phase, and the shaded block naming the binary and the token behind it —
 * the two facts that decide whether Start can possibly work.
 */
@Composable
private fun TunnelHero(
    phase: TunnelPhase,
    path: String,
    protocol: String,
    tokenStored: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PulseDot(
                color = when (phase) {
                    TunnelPhase.Running -> MaterialTheme.colorScheme.primary
                    TunnelPhase.Starting -> MaterialTheme.colorScheme.onSurfaceVariant
                    TunnelPhase.Failed -> MaterialTheme.colorScheme.onSurface
                    TunnelPhase.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                pulsing = phase == TunnelPhase.Running || phase == TunnelPhase.Starting,
                modifier = Modifier.padding(end = Spacing.Snug)
            )
            AnimatedContent(
                targetState = tunnelLabel(phase),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "tunnelPhase"
            ) { word ->
                Text(
                    text = word,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
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
                text = path.ifBlank { "No cloudflared executable set" },
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = if (path.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = "protocol ${protocol} · ${if (tokenStored) "token stored" else "no token stored"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
