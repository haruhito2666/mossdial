package com.mossdial.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mossdial.data.ServerSettingsRules

private val PAGES = listOf(
    OnboardingPage(
        title = "Host a site from this phone",
        body = "Mossdial runs a small web server on this device and serves the files you keep in " +
            "its private storage. Start it, then open the address it shows from any browser on the " +
            "same network."
    ),
    OnboardingPage(
        title = "Choose who can reach it",
        body = "By default the server binds ${ServerSettingsRules.LOOPBACK_ADDRESS}, so only this " +
            "device can reach it. Turning on LAN access binds ${ServerSettingsRules.ANY_ADDRESS} and " +
            "makes your site reachable by every device on the same network. HTTPS uses a generated " +
            "app-private certificate, so browsers will warn that the certificate is not trusted."
    ),
    OnboardingPage(
        title = "Add a home screen widget",
        body = "The widget shows whether the server is running and can start or stop it without " +
            "opening the app. You can also let the server come back after a restart from Settings, " +
            "and optionally expose it through a Cloudflare tunnel using a token that is stored " +
            "encrypted under an Android Keystore key."
    )
)

private data class OnboardingPage(val title: String, val body: String)

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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Mossdial", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("A minute to set up", style = MaterialTheme.typography.bodyLarge)
        LinearProgressIndicator(
            progress = { (page + 1f) / PAGES.size },
            modifier = Modifier.fillMaxWidth()
        )
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(current.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(current.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (page > 0) {
                OutlinedButton(onClick = { page -= 1 }) { Text("Back") }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (isLast) onFinished() else page += 1 }) {
                Text(if (isLast) "Get started" else "Next")
            }
        }
    }
}
