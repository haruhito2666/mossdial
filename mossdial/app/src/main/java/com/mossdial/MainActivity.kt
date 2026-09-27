package com.mossdial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mossdial.data.OnboardingState
import com.mossdial.data.ServerSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.server.ServerStatisticsSnapshot
import com.mossdial.service.ServerControl
import com.mossdial.service.ServerPhase
import com.mossdial.service.ServerState
import com.mossdial.service.ServerStatus
import com.mossdial.service.TrafficState
import com.mossdial.ui.AiScreen
import com.mossdial.ui.FileManagerScreen
import com.mossdial.ui.OnboardingScreen
import com.mossdial.ui.SettingsScreen
import com.mossdial.ui.TunnelScreen
import com.mossdial.tunnel.TunnelManager
import com.mossdial.tunnel.TunnelStatus
import com.mossdial.ui.theme.MossdialTheme
import com.mossdial.widget.ServerWidget

/** How many remembered requests the traffic card shows; the log itself can hold more. */
private const val RECENT_REQUESTS_SHOWN = 8

private const val TAB_SERVER = 0
private const val TAB_FILES = 1
private const val TAB_TUNNEL = 2
private const val TAB_AI = 3
private const val TAB_SETTINGS = 4

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MossdialTheme {
                MossdialApp()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Composable
    private fun MossdialApp() {
        val context = LocalContext.current
        var onboarded by remember { mutableStateOf(OnboardingState(context).isComplete) }
        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        if (!onboarded) {
            OnboardingScreen(
                onFinished = {
                    OnboardingState(context).complete()
                    onboarded = true
                    if (needsNotificationPermission(context)) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
            return
        }

        var selectedTab by rememberSaveable { mutableIntStateOf(TAB_SERVER) }
        DisposableEffect(Unit) {
            val listener: (TunnelStatus) -> Unit = { ServerWidget.refresh(context) }
            TunnelManager.addListener(listener)
            onDispose { TunnelManager.removeListener(listener) }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == TAB_SERVER,
                    onClick = { selectedTab = TAB_SERVER },
                    text = { Text("Server") }
                )
                Tab(
                    selected = selectedTab == TAB_FILES,
                    onClick = { selectedTab = TAB_FILES },
                    text = { Text("Files") }
                )
                Tab(
                    selected = selectedTab == TAB_TUNNEL,
                    onClick = { selectedTab = TAB_TUNNEL },
                    text = { Text("Tunnel") }
                )
                Tab(
                    selected = selectedTab == TAB_AI,
                    onClick = { selectedTab = TAB_AI },
                    text = { Text("AI") }
                )
                Tab(
                    selected = selectedTab == TAB_SETTINGS,
                    onClick = { selectedTab = TAB_SETTINGS },
                    text = { Text("Settings") }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    TAB_SERVER -> ServerScreen(onOpenSettings = { selectedTab = TAB_SETTINGS })
                    TAB_FILES -> FileManagerScreen()
                    TAB_TUNNEL -> TunnelScreen()
                    TAB_AI -> AiScreen()
                    else -> SettingsScreen(onOpenTunnel = { selectedTab = TAB_TUNNEL })
                }
            }
        }
    }

    @Composable
    private fun ServerScreen(onOpenSettings: () -> Unit) {
        val context = LocalContext.current
        val settings = remember { ServerSettings(context) }
        var status by remember { mutableStateOf(ServerState.snapshot()) }
        var traffic by remember { mutableStateOf(TrafficState.snapshot()) }

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
            onDispose { handler.removeCallbacks(poll) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Mossdial", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Local web host", style = MaterialTheme.typography.bodyLarge)
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        when (status.phase) {
                            ServerPhase.Stopped -> "Offline"
                            ServerPhase.Starting -> "Starting"
                            ServerPhase.Running -> "Online"
                            ServerPhase.Failed -> "Not running"
                        },
                        fontWeight = FontWeight.Bold
                    )
                    Text(address(settings, status), style = MaterialTheme.typography.bodySmall)
                    if (status.detail.isNotEmpty() && status.phase != ServerPhase.Running) {
                        Text(status.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { ServerControl.start(context, settings) },
                            enabled = !status.isActive
                        ) { Text("Start") }
                        OutlinedButton(
                            onClick = {
                                settings.autoStart = false
                                ServerControl.stop(context)
                            },
                            enabled = status.isActive
                        ) { Text("Stop") }
                    }
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Server settings")
                    }
                }
            }
            Text(
                if (settings.enableSsl) {
                    "HTTPS uses an app-private certificate kept in the platform keystore, so it is the same one every time you start the server."
                } else {
                    "HTTP is loopback-only unless LAN access is enabled."
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (status.phase == ServerPhase.Running) {
                TrafficCard(traffic, settings.requestLogging, onOpenSettings = onOpenSettings)
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
        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Traffic", fontWeight = FontWeight.Bold)
                Text("Requests: ${traffic.totalRequests}", style = MaterialTheme.typography.bodySmall)
                Text("Sent: ${formatBytes(traffic.bytesSent)}", style = MaterialTheme.typography.bodySmall)
                if (traffic.statusCounts.isNotEmpty()) {
                    Text(
                        "Answers: " + traffic.statusCounts.entries
                            .sortedBy { it.key }
                            .joinToString("  ") { "${it.key} ×${it.value}" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (traffic.recentRequests.isNotEmpty()) {
                    Text("Recent requests", style = MaterialTheme.typography.labelMedium)
                    traffic.recentRequests.takeLast(RECENT_REQUESTS_SHOWN).forEach { entry ->
                        Text(
                            "${entry.method} ${entry.path} → ${entry.status}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    Text(
                        if (loggingEnabled) {
                            "No requests yet."
                        } else {
                            "The request log is off, so paths are not recorded. Turn it on in settings."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!loggingEnabled) {
                        OutlinedButton(onClick = onOpenSettings) { Text("Server settings") }
                    }
                }
            }
        }
    }

    /** Bytes as something a person can read, without pulling in a formatter. */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1_024 && index < units.lastIndex) {
            value /= 1_024
            index++
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[index])
    }

    /**
     * The address the server is on right now. While it runs, the published status describes the
     * configuration it was actually started with; otherwise the stored configuration is shown, so
     * the address never mixes a live port with settings the user has since changed.
     */
    private fun address(settings: ServerSettings, status: ServerStatus): String {
        val live = status.phase == ServerPhase.Running
        val port = if (live) status.port else settings.port
        val allowLan = if (live) status.allowLan else settings.allowLan
        val useTls = if (live) status.enableTls else settings.enableSsl
        val scheme = if (useTls) "https" else "http"
        return "$scheme://${ServerSettingsRules.bindAddress(allowLan)}:$port"
    }

    private fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
}
