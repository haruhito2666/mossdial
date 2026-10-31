package com.mossdial.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mossdial.data.ServerSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.server.LanAddress
import com.mossdial.service.ServerControl
import com.mossdial.service.ServerPhase
import com.mossdial.service.ServerState
import com.mossdial.service.ServerStatus
import com.mossdial.service.TrafficState
import com.mossdial.ui.components.Band
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.pageSection
import com.mossdial.ui.components.DonutChart
import com.mossdial.ui.components.DonutSegment
import com.mossdial.ui.components.LabeledValue
import com.mossdial.ui.components.MicroLabelText
import com.mossdial.ui.components.PulseDot
import com.mossdial.ui.components.MiniAreaChart
import com.mossdial.ui.components.NavigationRow
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.rememberIsResumed
import com.mossdial.ui.components.RowDivider
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.theme.Control
import com.mossdial.ui.theme.GreenLight
import com.mossdial.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** How many remembered requests the page shows; the log itself can hold more. */
private const val RECENT_REQUESTS_SHOWN = 8

/**
 * The server page.
 *
 * There is no page title — the navigator already names the tab, and the phase word that leads this
 * page says more than "Server" ever did. The page is one column on the background, divided by
 * hairlines rather than boxed by cards: what the server is and where it answers, the one action the
 * moment calls for, a strip of live counters, the facts the address is built from, and then what
 * the server has actually done. Every number on it moves: counters tick to their new values, the
 * phase word and the action crossfade, and the dot announces life by breathing.
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
    // A user who completed onboarding before Android 13, or who denied the prompt then, would
    // otherwise run a foreground server with no visible notification. Ask once more at the moment
    // the notification starts to matter; a denial still starts the server.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        ServerControl.start(context, settings)
    }
    // The traffic snapshot changes once a second while the server serves. It lives in its own
    // holder so only the sections that show live numbers recompose, not the whole page.
    val live = remember { ServerLiveState() }
    val resumed = rememberIsResumed()
    val handler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(resumed) {
        if (!resumed) {
            // The app is not on screen; a ticker here would wake the process for nobody.
            onDispose { }
        } else {
            status = ServerState.snapshot()
            live.traffic = TrafficState.snapshot()
            live.samples = TrafficState.samples()
            val poll = object : Runnable {
                override fun run() {
                    status = ServerState.snapshot()
                    // Traffic moves in whole requests, so a slower tick than the phase would still
                    // look alive; the poll itself drives the uptime, rate and live-graph displays.
                    if (status.isActive) {
                        live.traffic = TrafficState.snapshot()
                        live.samples = TrafficState.samples()
                    }
                    handler.postDelayed(this, 1_000)
                }
            }
            handler.post(poll)
            onDispose { handler.removeCallbacksAndMessages(null) }
        }
    }

    val running = status.phase == ServerPhase.Running
    // While running, the published status describes the configuration the server was actually
    // started with; otherwise the stored configuration is shown. Every recomposition re-reads the
    // store, so returning from the settings page shows what was just changed.
    val port = if (running) status.port else settings.port
    val allowLan = if (running) status.allowLan else settings.allowLan
    val useTls = if (running) status.enableTls else settings.enableSsl
    val loggingEnabled = settings.requestLogging
    val host = if (allowLan) {
        LanAddress.siteLocalIPv4() ?: ServerSettingsRules.LOOPBACK_ADDRESS
    } else {
        ServerSettingsRules.LOOPBACK_ADDRESS
    }
    val address = "${if (useTls) "https" else "http"}://$host:$port"

    // The page's rhythm is one column of content divided by full-bleed tonal bands — the sections
    // themselves are inset, the bands span everything, and the two together are what make the page
    // read as laid out by one hand. Horizontal padding therefore lives on the sections, not here.
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 0.dp,
            top = contentPadding.calculateTopPadding(),
            end = 0.dp,
            bottom = contentPadding.calculateBottomPadding() + Spacing.Snug
        ),
        verticalArrangement = Arrangement.Top
    ) {
        pageSection {
            StatusHeader(
                phase = status.phase,
                address = address,
                reachText = if (allowLan && running) {
                    "This device and the local network"
                } else {
                    "This device only"
                },
                detail = if (running) "" else status.detail
            )
            ActionButton(
                status = status,
                onStart = {
                    if (needsNotificationPermission(context)) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        ServerControl.start(context, settings)
                    }
                },
                onStop = {
                    settings.autoStart = false
                    ServerControl.stop(context)
                }
            )
        }

        item { Band() }

        pageSection {
            MetricsSection(
                live = live,
                running = running,
                startedAtElapsedMillis = status.startedAtElapsedMillis
            )
        }

        item { Band() }

        pageSection {
            AnalyticsSection(live = live, running = running)
        }

        item { Band() }

        pageSection {
            ConnectionSection(
                port = port,
                useTls = useTls,
                allowLan = allowLan
            )
        }

        item { Band() }

        if (running) {
            pageSection {
                RequestsSection(
                    live = live,
                    loggingEnabled = loggingEnabled,
                    onOpenSettings = onOpenSettings
                )
            }
            item { Band() }
        }

        // The tab is a read-out; every knob that configures the server lives one page away, open
        // from this button. One door, one room.
        pageSection {
            SecondaryButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                ButtonContent(Icons.Default.Settings, "Server settings")
            }
        }
    }
}

/** The word for a lifecycle phase, as the page's anchor shows it. */
private fun phaseLabel(phase: ServerPhase): String = when (phase) {
    ServerPhase.Stopped -> "Offline"
    ServerPhase.Starting -> "Starting"
    ServerPhase.Running -> "Running"
    ServerPhase.Failed -> "Failed"
}

/**
 * The top of the page: the breathing dot and the phase word, the address itself, and one line
 * under it.
 *
 * The address is the reason most people come here, so the whole line is the copy target — tap it
 * and it is on the clipboard, and the line under it becomes the confirmation before reverting to
 * what the address means. No icon announces the affordance: the one line the page exists to hand
 * over is worth teaching once, and a copy button beside every address is the kind of furniture
 * this page is trying not to have.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusHeader(
    phase: ServerPhase,
    address: String,
    reachText: String,
    detail: String
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
        ) {
            PulseDot(
            color = when (phase) {
                ServerPhase.Running -> MaterialTheme.colorScheme.primary
                ServerPhase.Starting -> MaterialTheme.colorScheme.onSurfaceVariant
                ServerPhase.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
                ServerPhase.Failed -> MaterialTheme.colorScheme.onSurface
            },
            pulsing = phase == ServerPhase.Running || phase == ServerPhase.Starting
        )
            AnimatedContent(
                targetState = phaseLabel(phase),
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 }) togetherWith
                        (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 })
                },
                label = "phase"
            ) { word ->
                Text(
                    text = word,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // The address is the one value the page exists to hand over, so it sits in its own quiet
        // shade rather than on the bare background — the same treatment the rest of the page uses
        // for the one thing worth finding first.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = Control)
                .combinedClickable {
                    clipboard.setText(AnnotatedString(address))
                    copied = true
                }
                .padding(Spacing.Gap),
            verticalArrangement = Arrangement.spacedBy(Spacing.Snug)
        ) {
            Text(
                text = address,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
            AnimatedContent(
                targetState = copied,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "copied"
            ) { isCopied ->
                Text(
                    text = if (isCopied) "Copied to the clipboard" else reachText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCopied) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * The one action the phase calls for, crossfading in place: Start when stopped or failed, a
 * disabled Starting while the socket is being opened, Stop while serving. One button, always in
 * the same spot, is easier to aim at than two buttons whose relevance swaps underneath them.
 */
@Composable
private fun ActionButton(
    status: ServerStatus,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    AnimatedContent(
        targetState = status.phase,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
        label = "action"
    ) { phase ->
        when (phase) {
            ServerPhase.Running -> SecondaryButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(Icons.Default.Stop, "Stop")
            }
            ServerPhase.Starting -> SecondaryButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(Icons.Default.PlayArrow, "Starting")
            }
            else -> PrimaryButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(Icons.Default.PlayArrow, "Start")
            }
        }
    }
}

/**
 * The live counters, as three centred figures with hairlines between them and the running rate
 * beneath.
 *
 * The counters tick: the request count animates from its last value rather than snapping, and the
 * cells set tabular figures so a changing number does not shuffle its neighbours sideways. The
 * uptime is measured by the server when it comes up and published with its status, so it stays
 * honest across tab switches instead of restarting whenever the screen is rebuilt. The rate is an
 * estimate — requests divided by minutes up — and says so.
 */
@Composable
private fun MetricsSection(
    live: ServerLiveState,
    running: Boolean,
    startedAtElapsedMillis: Long
) {
    val traffic = live.traffic
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        MicroLabelText("Traffic")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricCell(
                label = "Requests",
                modifier = Modifier.weight(1f)
            ) {
                val animated = animateIntAsState(
                    targetValue = if (running) {
                        traffic.totalRequests.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    } else {
                        0
                    },
                    animationSpec = tween(600),
                    label = "requests"
                )
                if (running) {
                    String.format(Locale.getDefault(), "%,d", animated.value)
                } else {
                    "—"
                }
            }
            MetricHairline()
            MetricCell(
                label = "Data sent",
                modifier = Modifier.weight(1f)
            ) {
                if (running) formatBytes(traffic.bytesSent) else "—"
            }
            MetricHairline()
            MetricCell(
                label = "Uptime",
                monospace = true,
                modifier = Modifier.weight(1f)
            ) {
                uptimeText(running, startedAtElapsedMillis)
            }
        }

        val rate = requestsPerMinute(running, traffic.totalRequests, startedAtElapsedMillis)
        if (rate != null) {
            val animatedRate by animateFloatAsState(
                targetValue = rate,
                animationSpec = tween(600),
                label = "ratePerMinute"
            )
            Text(
                text = String.format(Locale.getDefault(), "≈ %.1f requests per minute", animatedRate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** One figure in the strip: a quiet label over the value, centred, with tabular figures. */
@Composable
private fun MetricCell(
    label: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    value: @Composable () -> String
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Snug)
    ) {
        MicroLabelText(label)
        val style = if (monospace) {
            MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.titleMedium
        }.copy(fontFeatureSettings = "tnum")
        Text(
            text = value(),
            style = style,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The hairline between two cells of the strip, the height of one row of figures. */
@Composable
private fun MetricHairline() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(42.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

private fun uptimeText(running: Boolean, startedAtElapsedMillis: Long): String {
    if (!running || startedAtElapsedMillis <= 0L) return "—"
    val elapsed = SystemClock.elapsedRealtime() - startedAtElapsedMillis
    if (elapsed < 0) return "—"
    val totalSeconds = elapsed / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun requestsPerMinute(
    running: Boolean,
    totalRequests: Long,
    startedAtElapsedMillis: Long
): Float? {
    if (!running || startedAtElapsedMillis <= 0L || totalRequests == 0L) return null
    val elapsed = SystemClock.elapsedRealtime() - startedAtElapsedMillis
    if (elapsed < 5_000) return null
    return totalRequests / (elapsed / 60_000f)
}

/**
 * The analytics: two live graphs of the last minute, and the donut of what the server has answered
 * with since it came up.
 *
 * The graphs plot the per-second deltas the service records while it runs — requests on the brand
 * green, bytes on black — so what scrolls past is what the server actually served while you
 * watched. The donut splits those answers by status class, because a server that has answered a
 * thousand requests is summarised by its shape: a ring that is nearly all green is healthy, and a
 * growing black slice is the thing to look at. The legend beside it carries the actual numbers the
 * ring only shows as angles.
 */
@Composable
private fun AnalyticsSection(
    live: ServerLiveState,
    running: Boolean
) {
    val statusCounts = live.traffic.statusCounts
    val samples = live.samples
    // Nothing is plotted until the server has actually produced samples. A synthetic flat series
    // would be a chart of data that never happened; an empty panel says that honestly.
    val hasTraffic = running && samples.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        MicroLabelText("Live")
        // The graphs live on their own canvas: a shaded panel that bounds them the way a plot
        // area bounds a chart, with each caption inside it, tight against its line, so a
        // reader never has to guess which name belongs to which graph.
        ShadedPanel {
            if (hasTraffic) {
                val requestValues = samples.map { it.requestsDelta.toFloat() }
                val byteValues = samples.map { it.bytesDelta.toFloat() }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
                    ChartCaption(
                        title = "Requests · per second",
                        current = "${samples.last().requestsDelta}/s",
                        accent = true
                    )
                    MiniAreaChart(values = requestValues, height = 96.dp)
                }
                RowDivider()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
                    ChartCaption(
                        title = "Data · per second",
                        current = "${formatBytes(samples.last().bytesDelta)}/s",
                        accent = false
                    )
                    MiniAreaChart(
                        values = byteValues,
                        lineColor = MaterialTheme.colorScheme.onSurface,
                        height = 96.dp
                    )
                }
            } else {
                SupportingText(
                    if (running) "Waiting for the first request." else "No traffic recorded yet."
                )
            }
        }

        ShadedPanel {
            MicroLabelText("Answer mix")
            val classes = answerClasses(statusCounts)
            val total = classes.sumOf { it.count }
            if (total == 0L) {
                SupportingText("No responses yet.")
            } else {
                Row(
                    modifier = Modifier.padding(top = Spacing.Base),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)
                ) {
                    DonutChart(
                        segments = classes.map { answerClass ->
                            DonutSegment(
                                weight = answerClass.count.toFloat(),
                                color = answerClassColor(answerClass.label)
                            )
                        },
                        centerValue = String.format(Locale.getDefault(), "%,d", total),
                        centerLabel = "requests",
                        modifier = Modifier.size(120.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Snug)
                    ) {
                        classes.forEach { answerClass ->
                            val share = answerClass.count * 100 / total
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Snug)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(answerClassColor(answerClass.label), CircleShape)
                                )
                                Text(
                                    text = answerClass.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = String.format(Locale.getDefault(), "%,d", answerClass.count),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "· $share%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!running) {
            SupportingText("Counters reset with each start.")
        }
    }
}

/** A shaded, rounded panel: the canvas the live graphs and the answer mix are drawn on. */
@Composable
private fun ShadedPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = Control)
            .padding(Spacing.Gap),
        verticalArrangement = Arrangement.spacedBy(Spacing.Base),
        content = content
    )
}

/** One caption above a chart: what the line is, and what the newest sample says. */
@Composable
private fun ChartCaption(title: String, current: String, accent: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MicroLabelText(title, modifier = Modifier.weight(1f))
        Text(
            text = current,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

private data class AnswerClass(val label: String, val count: Long)

/** Answers rolled up by status class, in class order, because the class is what the ring shows. */
private fun answerClasses(statusCounts: Map<Int, Long>): List<AnswerClass> {
    val buckets = linkedMapOf("2xx" to 0L, "3xx" to 0L, "4xx" to 0L, "5xx" to 0L)
    statusCounts.forEach { (code, count) ->
        val label = when {
            code in 200..299 -> "2xx"
            code in 300..399 -> "3xx"
            code in 400..499 -> "4xx"
            else -> "5xx"
        }
        buckets[label] = (buckets[label] ?: 0L) + count
    }
    return buckets.filterValues { it > 0 }.map { (label, count) -> AnswerClass(label, count) }
}

/** The ring's four colours, all inside the three-colour palette: two greens, a grey, black. */
@Composable
private fun answerClassColor(label: String): Color = when (label) {
    "2xx" -> MaterialTheme.colorScheme.primary
    "3xx" -> GreenLight
    "4xx" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * What has actually been asked for, newest first, one line to a request: when, with what verb,
 * the path, and how it was answered. Paths appear here only because the user turned the log on,
 * and the off state says that and offers the switch rather than showing a list that looks broken.
 */
@Composable
private fun RequestsSection(
    live: ServerLiveState,
    loggingEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val entries = live.traffic.recentRequests.takeLast(RECENT_REQUESTS_SHOWN).reversed()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
        MicroLabelText("Recent requests")
        entries.forEachIndexed { index, entry ->
            if (index > 0) RowDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Snug)
            ) {
                Text(
                    text = timeFormat.format(Date(entry.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.method,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        entry.status in 200..299 -> MaterialTheme.colorScheme.primary
                        entry.status in 300..399 -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
        if (!loggingEnabled) {
            NavigationRow(
                title = "Turn on the request log",
                summary = "Paths are not recorded while it is off",
                onClick = onOpenSettings
            )
        }
    }
}

/**
 * The per-second traffic state, held outside [ServerScreen].
 *
 * The published snapshot changes once a second while the server serves. Keeping it here means a
 * tick invalidates only the sections that display a number from it — the header, the action button,
 * the connection facts and the settings button do not recompose every second.
 */
private class ServerLiveState {
    var traffic by mutableStateOf(TrafficState.snapshot())
    var samples by mutableStateOf(TrafficState.samples())
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/** How the server is reachable: the three facts the address is built from, side by side. */
@Composable
private fun ConnectionSection(    port: Int,
    useTls: Boolean,
    allowLan: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        MicroLabelText("Connection")
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
        if (useTls) {
            SupportingText(
                text = "Self-signed certificate; a browser will warn that it is not trusted."
            )
        }
    }
}
