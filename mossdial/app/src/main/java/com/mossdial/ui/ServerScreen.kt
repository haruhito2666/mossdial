package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mossdial.data.ServerSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.server.ServerStatisticsSnapshot
import com.mossdial.service.ServerControl
import com.mossdial.service.ServerPhase
import com.mossdial.service.ServerState
import com.mossdial.service.TrafficState
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.ButtonRow
import com.mossdial.ui.components.EmptyState
import com.mossdial.ui.components.LabeledValue
import com.mossdial.ui.components.NavigationRow
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.components.SectionCard
import com.mossdial.ui.components.SectionHeading
import com.mossdial.ui.components.StatusPill
import com.mossdial.ui.components.StatusTone
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.components.screenContentPadding
import com.mossdial.ui.theme.Spacing

/** How many remembered requests the traffic card shows; the log itself can hold more. */
private const val RECENT_REQUESTS_SHOWN = 8

/**
 * The server: whether it is running, where it is reachable, and what it has served.
 *
 * This is the screen a person opens most, so it answers three questions in order and stops: is it
 * running, where do I point a browser, and what has it done. Everything else about the server lives
 * in Settings.
 */
@Composable
fun ServerScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { ServerSettings(context) }
    var status by remember { mutableStateOf(ServerState.snapshot()) }
    var traffic by remember { mutableStateOf(TrafficState.snapshot()) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        val poll = object : Runnable {
            override fun run() {
                status = ServerState.snapshot()
                // Traffic moves in whole requests, so a slower tick than the status keeps the
                // recomposition down without anything looking stale.
                if (status.isActive) traffic = TrafficState.snapshot()
                handler.postDelayed(this, 1_000)
            }
        }
        handler.post(poll)
        onDispose { handler.removeCallbacksAndMessages(null) }
    }

    // What the server is reachable on right now. While it runs, the published status describes the
    // configuration it was actually started with; otherwise the stored configuration is shown, so
    // the address never mixes a live port with settings the user has since changed.
    val live = status.phase == ServerPhase.Running
    val port = if (live) status.port else settings.port
    val allowLan = if (live) status.allowLan else settings.allowLan
    val useTls = if (live) status.enableTls else settings.enableSsl
    val address = "${if (useTls) "https" else "http"}://${ServerSettingsRules.bindAddress(allowLan)}:$port"
    val stopping = status.phase == ServerPhase.Stopped

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        // The same helper every other screen passes its padding through, so this title starts on
        // the same line as the titles on the four tabs either side of it.
        contentPadding = screenContentPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.Gap)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
                Text(
                    text = "Server",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SupportingText(
                    text = if (status.isActive) {
                        "Serving the files in your web root"
                    } else {
                        "The server is stopped"
                    }
                )
            }
        }

        item {
            SectionCard {
                SectionHeading(
                    title = "Status",
                    trailing = {
                        StatusPill(
                            label = when (status.phase) {
                                ServerPhase.Stopped -> "Offline"
                                ServerPhase.Starting -> "Starting"
                                ServerPhase.Running -> "Online"
                                ServerPhase.Failed -> "Failed"
                            },
                            tone = when (status.phase) {
                                ServerPhase.Stopped -> StatusTone.Idle
                                ServerPhase.Starting -> StatusTone.Starting
                                ServerPhase.Running -> StatusTone.Live
                                ServerPhase.Failed -> StatusTone.Error
                            }
                        )
                    }
                )

                // The address is the reason most people open this screen, so it is selectable and
                // set in a monospace face: it is a value to read and retype, not prose.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(Spacing.Base),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight)
                ) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SupportingText(
                        text = if (allowLan && status.phase == ServerPhase.Running) {
                            "Reachable from other devices on this network"
                        } else {
                            "Reachable from this device only"
                        }
                    )
                }

                // The three facts the address is built from, as read-out values rather than buried
                // in the URL, so a changed port or protocol is visible without parsing anything.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
                ) {
                    LabeledValue(
                        label = "Port",
                        value = port.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    LabeledValue(
                        label = "Protocol",
                        value = if (useTls) "HTTPS" else "HTTP",
                        modifier = Modifier.weight(1f)
                    )
                    LabeledValue(
                        label = "Access",
                        value = if (allowLan) "LAN and here" else "This device",
                        modifier = Modifier.weight(1f)
                    )
                }

                // The one consequence of those facts that is not self-evident: what a browser does
                // with the app-private certificate.
                if (useTls) {
                    SupportingText(
                        text = "HTTPS is served with the certificate kept in the platform keystore, " +
                            "so it is the same one every time. A browser will warn that it is not trusted."
                    )
                }

                if (status.detail.isNotEmpty() && status.phase != ServerPhase.Running) {
                    Text(
                        text = status.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                ButtonRow {
                    PrimaryButton(
                        onClick = { ServerControl.start(context, settings) },
                        enabled = !status.isActive
                    ) {
                        ButtonContent(Icons.Default.PlayArrow, "Start")
                    }
                    SecondaryButton(
                        onClick = {
                            settings.autoStart = false
                            ServerControl.stop(context)
                        },
                        enabled = status.isActive
                    ) {
                        ButtonContent(Icons.Default.Stop, "Stop")
                    }
                }

                NavigationRow(
                    title = "Server settings",
                    summary = if (stopping) {
                        "Port, LAN access, HTTPS, start after restart and the request log"
                    } else {
                        "Changes apply the next time the server starts"
                    },
                    onClick = onOpenSettings
                )
            }
        }

        if (status.phase == ServerPhase.Running) {
            item {
                TrafficCard(
                    traffic = traffic,
                    loggingEnabled = settings.requestLogging,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

/**
 * What the server has served since it started.
 *
 * The counters are always there because they record no path: how much was asked for, and how it
 * was answered. The request list is only ever populated when the user turned the request log on,
 * and says so rather than showing an empty list that looks like a broken feature.
 */
@Composable
private fun TrafficCard(
    traffic: ServerStatisticsSnapshot,
    loggingEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    SectionCard {
        SectionHeading(
            title = "Traffic",
            subtitle = "Since the server started"
        )

        // Counters as a two-column grid rather than a stack of sentences, so the numbers line up
        // against each other and can be compared without reading the labels first.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
        ) {
            LabeledValue(
                label = "Requests",
                value = traffic.totalRequests.toString(),
                modifier = Modifier.weight(1f)
            )
            LabeledValue(
                label = "Sent",
                value = formatBytes(traffic.bytesSent),
                modifier = Modifier.weight(1f)
            )
        }

        if (traffic.statusCounts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
                Text(
                    text = "Answers",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Snug)
                ) {
                    traffic.statusCounts.entries
                        .sortedBy { it.key }
                        .forEach { entry ->
                            StatusPill(
                                label = entry.key.toString(),
                                tone = statusToneFor(entry.key),
                                detail = "×${entry.value}"
                            )
                        }
                }
            }
        }

        if (traffic.recentRequests.isNotEmpty()) {
            Text(
                text = "Recent requests",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            traffic.recentRequests.takeLast(RECENT_REQUESTS_SHOWN).reversed().forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Snug),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.method,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = Spacing.Tight)
                    )
                    Text(
                        text = entry.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = entry.status.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            // Within the three-colour palette the status reads by weight: success
                            // is the one green on the screen, the rest are marks of black.
                            entry.status in 200..299 -> MaterialTheme.colorScheme.primary
                            entry.status in 300..399 -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        } else {
            if (!loggingEnabled) {
                EmptyState(
                    title = "Request log is off",
                    body = "Paths are not recorded anywhere, which is the default. The counters above " +
                        "are kept because they record no path.",
                    icon = Icons.Default.BarChart
                )
            } else {
                EmptyState(
                    title = "No requests yet",
                    body = "Requests appear here once something asks the server for a file.",
                    icon = Icons.Default.BarChart
                )
            }
            if (!loggingEnabled) {
                SecondaryButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Turn on the request log", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A status code's pill tone, so a 200 and a 404 do not look alike in a list. */
private fun statusToneFor(status: Int) = when {
    status in 200..299 -> StatusTone.Live
    status in 300..399 -> StatusTone.Accent
    status in 400..499 -> StatusTone.Starting
    else -> StatusTone.Error
}