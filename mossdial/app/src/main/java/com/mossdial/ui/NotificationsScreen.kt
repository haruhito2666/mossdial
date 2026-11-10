package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mossdial.service.NoticeItem
import com.mossdial.service.NoticeKind
import com.mossdial.service.NotificationCenter
import com.mossdial.ui.components.Band
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.EmptyState
import com.mossdial.ui.components.SectionHeader
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.components.pageSection
import com.mossdial.ui.theme.Spacing

/**
 * The in-app notification page the server tab's bell opens.
 *
 * It is a quiet read-out, not a settings screen: the newest events first, one row per event, and
 * nothing to configure. Opening it clears the unread marker, and clearing the list is the one
 * action the page offers. The page covers the navigation bar so the list reads as its own screen.
 */
@Composable
fun NotificationsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit
) {
    var items by remember { mutableStateOf(NotificationCenter.snapshot()) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        val listener: () -> Unit = { handler.post { items = NotificationCenter.snapshot() } }
        NotificationCenter.addListener(listener)
        onDispose {
            NotificationCenter.removeListener(listener)
            handler.removeCallbacksAndMessages(null)
        }
    }

    LaunchedEffect(Unit) {
        NotificationCenter.markAllRead()
    }

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.Snug
        )
    ) {
        pageSection {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notifications",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close notifications"
                    )
                }
            }
            SupportingText(
                text = "What the server, the tunnel and the app have done while it was open."
            )
        }

        item { Band() }

        if (items.isEmpty()) {
            pageSection {
                EmptyState(
                    title = "Nothing yet",
                    body = "Events appear here when the server or tunnel changes state.",
                    icon = Icons.Default.Info
                )
            }
        } else {
            pageSection {
                SectionHeader(title = "Recent")
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                    items.forEachIndexed { index, item ->
                        NoticeRow(item)
                        if (index != items.lastIndex) {
                            Spacer(modifier = Modifier.height(Spacing.Snug))
                        }
                    }
                }
            }

            item { Band() }

            pageSection {
                SecondaryButton(
                    onClick = { NotificationCenter.clear() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ButtonContent(Icons.Default.Delete, "Clear all")
                }
                SupportingText("Clearing the list does not stop anything that is running.")
            }
        }
    }
}

/**
 * One event: its kind's icon, the title it arrived with, and how long ago it happened.
 *
 * The relative time is the only thing that changes while the page is open, so it is kept to a
 * single line under the body rather than replacing the body outright.
 */
@Composable
private fun NoticeRow(item: NoticeItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color = MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (item.kind) {
                    NoticeKind.Server -> Icons.Default.Dns
                    NoticeKind.Tunnel -> Icons.Default.Cloud
                    NoticeKind.Info -> Icons.Default.Info
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = DateUtils.getRelativeTimeSpanString(
                    item.atMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
