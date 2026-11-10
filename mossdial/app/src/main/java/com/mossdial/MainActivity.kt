package com.mossdial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mossdial.data.OnboardingState
import com.mossdial.ui.FileManagerScreen
import com.mossdial.ui.OnboardingScreen
import com.mossdial.ui.RentalsScreen
import com.mossdial.ui.ServerScreen
import com.mossdial.ui.NotificationsScreen
import com.mossdial.ui.ServerSettingsScreen
import com.mossdial.ui.SettingsScreen
import com.mossdial.ui.TunnelScreen
import com.mossdial.ui.theme.BarShape
import com.mossdial.ui.theme.MossdialTheme
import com.mossdial.ui.theme.Spacing
import com.mossdial.service.NoticeKind
import com.mossdial.service.NotificationCenter
import com.mossdial.service.ServerNotifications
import com.mossdial.service.ServerStatus
import com.mossdial.service.ServerPhase
import com.mossdial.service.ServerState
import com.mossdial.tunnel.TunnelManager
import com.mossdial.tunnel.TunnelPhase
import com.mossdial.tunnel.TunnelStatus
import com.mossdial.widget.ServerWidget

/**
 * The five destinations.
 *
 * Modelled as data rather than as a `when` over separate constants so the label, the icon and the order
 * cannot drift apart. Each destination shows its label, and the black tile carries selection.
 */
internal enum class Destination(
    val label: String,
    val icon: ImageVector
) {
    Server("Server", Icons.Filled.Dns),
    Files("Files", Icons.Filled.Folder),
    Tunnel("Tunnel", Icons.Filled.Cloud),
    Rentals("Rentals", Icons.Filled.PhoneAndroid),
    Settings("Settings", Icons.Filled.Settings)
}

/** Restores the old numeric tab selection and saves a stable name from now on. */
internal val DestinationSaver = Saver<Destination, Any>(
    save = { it.name },
    restore = { saved ->
        when (saved) {
            is String -> Destination.entries.firstOrNull { it.name == saved } ?: Destination.Server
            // Both previous layouts used 0–2 for these tabs; 3 or 4 can be Settings.
            is Int -> when (saved) {
                0 -> Destination.Server
                1 -> Destination.Files
                2 -> Destination.Tunnel
                3, 4 -> Destination.Settings
                else -> Destination.Server
            }
            else -> Destination.Server
        }
    }
)

/**
 * The navigation bar fits a visible label under each icon and keeps a generous touch target.
 */
private val NavBarHeight = 72.dp

/** The widest the floating pill grows; on a phone it uses the full gutter-to-gutter width. */
private val NavBarMaxWidth = 380.dp

/** How far the content dissolves into the background above the navigation pill. */
private val BottomFadeHeight = NavBarHeight + Spacing.Snug

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The interface draws its own background, so the system bars are transparent and the
        // content is inset past them by the Scaffold. Without this the navigation bar is an opaque
        // strip across the bottom of every screen.
        //
        // `SystemBarStyle.light` is passed explicitly rather than letting `enableEdgeToEdge` pick:
        // the default follows the system dark-mode setting, and this app is light-only, so on a
        // device set to dark the bars would get light-on-dark icons over a light app background
        // and become unreadable.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            MossdialTheme {
                MossdialApp()
            }
        }
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

        var destination by rememberSaveable(stateSaver = DestinationSaver) {
            mutableStateOf(Destination.Server)
        }
        // The server's settings are a page of their own, opened from the server tab.
        // It is an overlay over the tabs rather than another destination: switching
        // tabs closes it, and the page itself owns the Back handling that closes it.
        var serverSettingsOpen by rememberSaveable { mutableStateOf(false) }
        var notificationsOpen by rememberSaveable { mutableStateOf(false) }
        // Content now draws to the very top of the window so a scrim can fade it under the status
        // bar. The screens receive the status-bar height as their own top content padding, which
        // keeps the first row below the bar while still letting scrolled content pass beneath it.
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        DisposableEffect(Unit) {
            val serverListener: (ServerStatus) -> Unit = { status ->
                ServerWidget.refresh(context)
                NotificationCenter.post(
                    kind = NoticeKind.Server,
                    title = when (status.phase) {
                        ServerPhase.Running -> "Server is running"
                        ServerPhase.Starting -> "Server is starting"
                        ServerPhase.Failed -> "Server failed to start"
                        ServerPhase.Stopped -> "Server stopped"
                    },
                    body = ServerNotifications.text(context, status)
                )
            }
            val tunnelListener: (TunnelStatus) -> Unit = { status ->
                val title = when (status.phase) {
                    TunnelPhase.Running -> "Tunnel is connected"
                    TunnelPhase.Starting -> "Tunnel is starting"
                    TunnelPhase.Failed -> "Tunnel failed"
                    TunnelPhase.Stopped -> "Tunnel stopped"
                }
                val body = buildString {
                    append(status.detail.ifBlank { "No extra detail." })
                    if (status.url.isNotBlank()) {
                        append(" Public URL: ")
                        append(status.url)
                    }
                }
                NotificationCenter.post(kind = NoticeKind.Tunnel, title = title, body = body)
            }
            ServerState.addListener(serverListener)
            TunnelManager.addListener(tunnelListener)
            onDispose {
                ServerState.removeListener(serverListener)
                TunnelManager.removeListener(tunnelListener)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            // The screen owns the status-bar inset through its content padding; if Scaffold also
            // applied it here, the fade would start below the bar and there would be nothing for it
            // to fade. The bottom bar still reports its own height so the content stops above it.
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
            // The bar draws its own background, so it handles the bottom inset itself and only the
            // content is padded away from the top of the bar.
            bottomBar = {
                // A floating pill, not a full-width strip: softly rounded, inset from the screen
                // edges and centred when the window is wider than a phone. It deliberately has no
                // shadow; the pale green surface and the space around it are the separation.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        .padding(
                            start = Spacing.Inset,
                            end = Spacing.Inset,
                            bottom = Spacing.Snug
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // At larger font scales, show the selected label and give it more width. Every
                    // tab retains its spoken label even when there is not room to show every tab.
                    val compact = minOf(maxWidth, NavBarMaxWidth).value /
                        LocalDensity.current.fontScale < 300f
                    Surface(
                        modifier = Modifier
                            .widthIn(max = NavBarMaxWidth)
                            .fillMaxWidth(),
                        shape = BarShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(NavBarHeight)
                                .selectableGroup()
                                .padding(horizontal = Spacing.Tight, vertical = Spacing.Snug),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Every destination owns an equal share of the bar, so the icon and
                            // label stay in place when selection changes.
                            Destination.entries.forEach { item ->
                                DestinationItem(
                                    destination = item,
                                    selected = item == destination,
                                    onClick = {
                                        destination = item
                                        serverSettingsOpen = false
                                    },
                                    showLabel = !compact || item == destination,
                                    modifier = Modifier.weight(
                                        if (!compact) 1f else if (item == destination) 1.5f else 0.875f
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            // The Scaffold's bottom inset is applied to the viewport, not to the scrolling content.
            // The top inset is deliberately absent: the screens add [statusBarTop] to their own
            // content padding, which allows items to scroll under the top fade.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // The screens no longer need the window insets themselves; the box has taken the
                // bottom one and passes the top one through this padding value. The extra bottom
                // space lets the final row scroll clear of the fade rather than staying washed out
                // at the end of the list.
                val pageInsets = PaddingValues(
                    top = statusBarTop,
                    bottom = BottomFadeHeight + Spacing.BottomBreathing
                )
                when (destination) {
                    Destination.Server -> ServerScreen(
                            contentPadding = pageInsets,
                            onOpenSettings = { serverSettingsOpen = true },
                            onOpenNotifications = { notificationsOpen = true }
                        )
                    Destination.Files -> FileManagerScreen(contentPadding = pageInsets)
                    Destination.Tunnel -> TunnelScreen(contentPadding = pageInsets)
                    Destination.Rentals -> RentalsScreen(contentPadding = pageInsets)
                    Destination.Settings -> SettingsScreen(
                            contentPadding = pageInsets,
                            onOpenTunnel = { destination = Destination.Tunnel }
                        )
                }
                // The top and bottom scrims are the iOS-style edge treatment from the reference:
                // content dissolves into the background as it leaves the viewport. The top one
                // also covers the status-bar strip, which is why the screens start below it but
                // may scroll through it.
                EdgeFade(
                    modifier = Modifier.align(Alignment.TopCenter),
                    height = statusBarTop + Spacing.Section,
                    fromTop = true
                )
                EdgeFade(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    height = BottomFadeHeight,
                    fromTop = false
                )
            }
        }

        // The server's settings are a full-screen page, not a tab: it covers the navigation bar so
        // nothing from the tab it was opened from shows through, and its own Back handler closes it.
        if (serverSettingsOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                ServerSettingsScreen(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = statusBarTop,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    onBack = { serverSettingsOpen = false }
                )
            }
        }

        if (notificationsOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                NotificationsScreen(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = statusBarTop,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    onBack = { notificationsOpen = false }
                )
            }
        }
        }
    }

    private fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
}

/**
 * One destination in the navigation bar. A visible label makes every tab discoverable; selection
 * changes only the surface and tint, so the icons and labels stay in place between tabs.
 */
@Composable
private fun DestinationItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) { contentDescription = destination.label }
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color.Transparent
            }
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
                if (showLabel) {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }
        }
    }
}

/**
 * A vertical fade from the page background into transparency, or the reverse.
 *
 * It sits above the scrolling content but has no pointer input, so it fades what passes underneath
 * without taking a tap. The status-bar-height top inset is included in [height] by the caller; the
 * screens start their content below that inset and can scroll through it.
 */
@Composable
private fun EdgeFade(
    modifier: Modifier = Modifier,
    height: Dp,
    fromTop: Boolean
) {
    val background = MaterialTheme.colorScheme.background
    val colors = if (fromTop) {
        listOf(background, background.copy(alpha = 0f))
    } else {
        listOf(background.copy(alpha = 0f), background)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Brush.verticalGradient(colors))
    )
}
