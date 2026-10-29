package com.mossdial.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.mossdial.data.ServerSettings
import com.mossdial.data.ServerSettingsRules

/**
 * The server's settings as controls, defined once and shown in two places.
 *
 * Most of these were tunable only on the Settings tab, which put three taps between "this site
 * should be reachable from my laptop" and the switch that does it. They now appear — with the same
 * wording, the same validation and the same write-through behaviour — on the server tab itself,
 * while these composables own the summary text so the two places cannot drift apart.
 */

/**
 * The port field. Owns its text and its error; writes through to [ServerSettings] the moment the
 * value parses, and never writes a value that does not. [onPortChange] lets a screen that shows
 * the port elsewhere keep its own copy current.
 */
@Composable
fun PortField(
    settings: ServerSettings,
    modifier: Modifier = Modifier,
    onPortChange: (Int) -> Unit = {}
) {
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var portError by remember { mutableStateOf("") }

    OutlinedTextField(
        value = portText,
        onValueChange = { value ->
            val digits = value.filter(Char::isDigit).take(ServerSettingsRules.MAX_PORT_DIGITS)
            portText = digits
            val parsed = ServerSettingsRules.parsePort(digits)
            portError = when {
                digits.isEmpty() -> ""
                parsed == null -> "Use a port between " +
                    "${ServerSettingsRules.MIN_PORT} and ${ServerSettingsRules.MAX_PORT}"
                else -> {
                    settings.port = parsed
                    onPortChange(parsed)
                    ""
                }
            }
        },
        label = { Text("Port") },
        supportingText = {
            Text(portError.ifEmpty { "Default ${ServerSettingsRules.DEFAULT_PORT}" })
        },
        isError = portError.isNotEmpty(),
        singleLine = true,
        // A numeric keyboard, because a port has no letters and the default keyboard offers a
        // letter row the field will simply reject.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

/** Whether devices other than this one may reach the site. */
@Composable
fun LanAccessSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRow(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = "Allow LAN access",
        summary = if (checked) {
            "Listening on ${ServerSettingsRules.ANY_ADDRESS} — reachable on this network"
        } else {
            "Listening on ${ServerSettingsRules.LOOPBACK_ADDRESS} — this device only"
        }
    )
}

/** Whether the site is served over TLS with the app's own certificate. */
@Composable
fun HttpsSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRow(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = "Use HTTPS",
        summary = "Self-signed certificate; browsers will warn."
    )
}

/** Whether the server keeps the recent-request list the server tab plots and lists. */
@Composable
fun RequestLogSetting(settings: ServerSettings, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRow(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = "Keep a request log",
        summary = if (checked) {
            "The last ${ServerSettingsRules.normalizeLogLines(settings.requestLogLines)} requests, " +
                "in memory only."
        } else {
            "No request is remembered."
        }
    )
}
