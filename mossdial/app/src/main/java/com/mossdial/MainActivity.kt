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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mossdial.data.OnboardingState
import com.mossdial.ui.AiScreen
import com.mossdial.ui.FileManagerScreen
import com.mossdial.ui.OnboardingScreen
import com.mossdial.ui.ServerScreen
import com.mossdial.ui.ServerSettingsScreen
import com.mossdial.ui.SettingsScreen
import com.mossdial.ui.TunnelScreen
import com.mossdial.ui.theme.BarShape
import com.mossdial.ui.theme.MossdialTheme
import com.mossdial.ui.theme.Spacing
import com.mossdial.tunnel.TunnelManager
import com.mossdial.tunnel.TunnelStatus
import com.mossdial.widget.ServerWidget

/**
 * The five destinations.
 *
 * Modelled as data rather than as a `when` over five constants so the label, the icon and the order
 * cannot drift apart. The icon is a pair because a filled icon reads as selected and an outlined
 * one does not, which is the standard Material signal and needs no colour to carry it.
 */
private enum class Destination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Server("Server", Icons.Filled.Dns, Icons.Outlined.Dns),
    Files("Files", Icons.Filled.Folder, Icons.Outlined.Folder),
    Tunnel("Tunnel", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    Ai("AI", Icons.Filled.AutoAwesome, Icons.Filled.AutoAwesome),
    Settings("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

/**
 * The height of the navigation bar's box: room for the 26dp icon in its pill, its 13sp label, and
 * the 48dp the thumb needs, with the box's own breathing around them.
 */
private val NavBarHeight = 76.dp

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

        var selected by rememberSaveable { mutableIntStateOf(0) }
        val destination = Destination.entries[selected]
        // The server's settings are a page of their own, opened from the server tab or from the
        // settings tab. It is an overlay over the tabs rather than a sixth destination: switching
        // tabs closes it, and the page itself owns the Back handling that closes it.
        var serverSettingsOpen by rememberSaveable { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val listener: (TunnelStatus) -> Unit = { ServerWidget.refresh(context) }
            TunnelManager.addListener(listener)
            onDispose { TunnelManager.removeListener(listener) }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            // The bar draws its own background, so it handles the bottom inset itself and only the
            // content is padded away from the status bar and the top of the bar.
            bottomBar = {
                // A floating rectangle box, not a full-width strip: rounded like the cards it sits
                // above, inset from the screen edges, and lifted clear of the gesture area by its
                // own padding rather than the Scaffold's.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = Spacing.Inset, end = Spacing.Inset, bottom = Spacing.Snug)
                ) {
                    Surface(
                        shape = BarShape,
                        color = MaterialTheme.colorScheme.surface,
                        // The bar is white on white, so it is separated by a soft standing shadow
                        // rather than by an outline: a hairline would draw the four straight
                        // lines this bar is deliberately without, and the shadow never touches
                        // the thumb the way a tap ripple would.
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(NavBarHeight)
                                .padding(horizontal = Spacing.Snug, vertical = Spacing.Tight)
                        ) {
                            // Each destination owns an equal fifth of the bar and centres itself
                            // in it, so the five are neither bunched at the start nor drifting
                            // apart by their differences in width.
                            Destination.entries.forEachIndexed { index, item ->
                                DestinationItem(
                                    destination = item,
                                    selected = index == selected,
                                    onClick = {
                                        selected = index
                                        serverSettingsOpen = false
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            // The Scaffold's insets are applied to the viewport, not to the scrolling content: a
            // lazy list's contentPadding only makes room at the ends of its scroll range, so items
            // passing through the middle of the screen would draw under the status bar and behind
            // the floating bar — and, through the bar's own bottom margin, in the gesture strip
            // below it. Padding the box clips the page cleanly above the bar instead.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // The screens no longer need the window insets themselves; the box has taken them.
                val pageInsets = PaddingValues(0.dp)
                if (serverSettingsOpen) {
                    ServerSettingsScreen(
                        contentPadding = pageInsets,
                        onBack = { serverSettingsOpen = false }
                    )
                } else {
                    when (destination) {
                        Destination.Server -> ServerScreen(
                            contentPadding = pageInsets,
                            onOpenSettings = { serverSettingsOpen = true }
                        )
                        Destination.Files -> FileManagerScreen(contentPadding = pageInsets)
                        Destination.Tunnel -> TunnelScreen(contentPadding = pageInsets)
                        Destination.Ai -> AiScreen(contentPadding = pageInsets)
                        Destination.Settings -> SettingsScreen(
                            contentPadding = pageInsets,
                            onOpenTunnel = { selected = Destination.entries.indexOf(Destination.Tunnel) },
                            onOpenAi = { selected = Destination.entries.indexOf(Destination.Ai) }
                        )
                    }
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
 * One destination inside the navigation bar: the icon inside its indicator pill, the label under
 * it, and the whole column as the tap target.
 *
 * The pill occupies its space whether or not the tab is selected — transparent when it is not — so
 * the icon never jumps when its destination comes on, and all five labels sit on the same line.
 * The selected tab is black on the light green pill, which is the palette's own pair; the others
 * are their grey. There is deliberately no ripple: the selection state is itself the feedback, and
 * a splash under the thumb said nothing the new tab colour didn't.
 */
@Composable
private fun DestinationItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = Spacing.Tight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight)
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Transparent
            }
        ) {
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                contentDescription = destination.label,
                tint = tint,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 5.dp)
                    .size(26.dp)
            )
        }
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}