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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.mossdial.ui.SettingsScreen
import com.mossdial.ui.TunnelScreen
import com.mossdial.ui.theme.CardLarge
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
 * The height of the navigation bar's box: room for the 24dp icon in its pill, its label, and the
 * 48dp the thumb needs, with the box's own breathing around them.
 */
private val NavBarHeight = 68.dp

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
                        .padding(start = Spacing.Inset, end = Spacing.Inset, bottom = Spacing.Base)
                ) {
                    Surface(
                        shape = CardLarge,
                        color = MaterialTheme.colorScheme.surface,
                        // The same hairline the cards use, so bar, cards and background read as one
                        // material rather than three blues-grey tones trying to agree.
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(NavBarHeight)
                                .padding(horizontal = Spacing.Snug, vertical = Spacing.Tight)
                        ) {
                            Destination.entries.forEachIndexed { index, item ->
                                DestinationItem(
                                    destination = item,
                                    selected = index == selected,
                                    onClick = { selected = index }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            when (destination) {
                Destination.Server -> ServerScreen(
                    contentPadding = innerPadding,
                    onOpenSettings = { selected = Destination.entries.indexOf(Destination.Settings) }
                )
                Destination.Files -> FileManagerScreen(contentPadding = innerPadding)
                Destination.Tunnel -> TunnelScreen(contentPadding = innerPadding)
                Destination.Ai -> AiScreen(contentPadding = innerPadding)
                Destination.Settings -> SettingsScreen(
                    contentPadding = innerPadding,
                    onOpenTunnel = { selected = Destination.entries.indexOf(Destination.Tunnel) }
                )
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
            .padding(vertical = Spacing.Tight + 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Tiniest)
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
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .size(24.dp)
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