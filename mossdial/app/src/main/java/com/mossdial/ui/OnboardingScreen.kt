package com.mossdial.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.ButtonRow
import com.mossdial.ui.components.MicroLabelText
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.theme.Spacing

private val PAGES = listOf(
    OnboardingPage(
        icon = Icons.Default.Dns,
        title = "Host a site from this phone",
        body = "Mossdial serves a folder from this device. Start it, then open the address it " +
            "shows in any browser on this network."
    ),
    OnboardingPage(
        icon = Icons.Default.Https,
        title = "Choose who can reach it",
        body = "By default only this device can reach it. LAN access opens it to this network. " +
            "HTTPS uses a self-signed certificate, so browsers will warn."
    ),
    OnboardingPage(
        icon = Icons.Default.Widgets,
        title = "Add a home screen widget",
        body = "The widget starts and stops the server without opening the app. Settings can bring " +
            "it back after a reboot, and an optional Cloudflare tunnel can publish it."
    )
)

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String
)

/**
 * The introduction shown on the first launch only.
 *
 * Completion is recorded in [com.mossdial.data.OnboardingState], so the screens behind it are not
 * built until the user has read this, and the last page is what turns auto-start consent on: the
 * auto-start switch in Settings stays off until it is explicitly turned on there.
 */
@Composable
fun OnboardingScreen(modifier: Modifier = Modifier, onFinished: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val current = PAGES[page.coerceIn(PAGES.indices)]
    val isLast = page >= PAGES.lastIndex

    BackHandler(enabled = page > 0) { page -= 1 }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.Inset),
        verticalArrangement = Arrangement.spacedBy(Spacing.Spacious)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
            Text(
                text = "Mossdial",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            MicroLabelText("A minute to set up")
        }

        // Page dots rather than a bar: three fixed pages, and a dot says "three" in a way a
        // progress line does not.
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
            PAGES.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == page) 24.dp else 8.dp, 8.dp)
                        .background(
                            color = if (index == page) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.Spacious)
        ) {
            Icon(
                imageVector = current.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = current.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = current.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 480.dp)
            )
        }

        ButtonRow {
            if (page > 0) {
                SecondaryButton(onClick = { page -= 1 }) {
                    Text("Back", style = MaterialTheme.typography.labelLarge)
                }
            }
            PrimaryButton(
                onClick = { if (isLast) onFinished() else page += 1 },
                modifier = Modifier.weight(1f, fill = false)
            ) {
                ButtonContent(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    if (isLast) "Get started" else "Next"
                )
            }
        }
    }
}
