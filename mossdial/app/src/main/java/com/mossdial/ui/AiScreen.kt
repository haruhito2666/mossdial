package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mossdial.ai.AiController
import com.mossdial.ai.AiControllerRegistry
import com.mossdial.ai.ChatTurn
import com.mossdial.ai.OnnxModelInfo
import com.mossdial.aiapi.AiApiPhase
import com.mossdial.aiapi.AiApiState
import com.mossdial.aiapi.AiApiToken
import com.mossdial.data.AiApiSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.data.StoredModel

private val CONTEXT_CHOICES = listOf(512, 1024, 2048, 4096, 8192)
private val TOKEN_CHOICES = listOf(64, 128, 256, 512)
private val TEMPERATURE_CHOICES = listOf(0.0f, 0.2f, 0.5f, 0.7f, 1.0f)

/** What the embedding field accepts, matching the tokenizer's own bound. */
private const val MAX_EMBEDDING_INPUT = 64 * 1024

@Composable
fun AiScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { AiController.forContext(context) }
    DisposableEffect(controller) {
        // The tab owns the controller. Registering it here is what lets the local API answer with
        // the model that is loaded below, instead of loading a second copy of it.
        AiControllerRegistry.attach(controller)
        onDispose {
            AiControllerRegistry.detach(controller)
            controller.release()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { location ->
        if (location != null) {
            controller.importModel(location.toString())
        }
    }
    // A vocabulary is a text file beside an ONNX encoder, so it needs its own picker: asking for
    // every file type and then refusing three quarters of them is a worse answer than asking for
    // text.
    val vocabularyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { location ->
        if (location != null) {
            controller.importVocabulary(location.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("On-device AI", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "GGUF chat through llama.cpp, and ONNX text encoders through ONNX Runtime.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Weights stay in the app-private no-backup directory and are never served by the web host.",
            style = MaterialTheme.typography.bodySmall
        )

        RuntimeStatusCard(controller)

        controller.message?.let { notice ->
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { controller.clearMessage() }) { Text("Dismiss") }
                }
            }
        }

        ModelsCard(controller) { picker.launch(arrayOf("*/*")) }
        DownloadCard(controller)
        ChatCard(controller)
        AiApiCard(controller)
        OnnxCard(controller) { vocabularyPicker.launch(arrayOf("text/plain", "*/*")) }
    }
}

@Composable
private fun RuntimeStatusCard(controller: AiController) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Runtime status", fontWeight = FontWeight.Bold)
            StatusLine("llama.cpp", controller.llamaStatus)
            StatusLine("ONNX Runtime", controller.onnxStatus)
            StatusLine("Model directory", controller.modelDirectory)
            StatusLine("Worker", "${controller.config.threads} threads")
            if (controller.busy) {
                StatusLine("Working on", controller.busyLabel)
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModelsCard(controller: AiController, onImport: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Models", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { controller.refreshModels() }, enabled = !controller.busy) {
                    Text("Refresh")
                }
                Button(onClick = onImport, enabled = !controller.busy) { Text("Import") }
            }

            if (controller.models.isEmpty()) {
                Text(
                    "No model files yet. Import a .gguf or .onnx file, or download one below.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            controller.ggufModels.forEach { model ->
                StoredModelRow(
                    model = model,
                    selected = controller.selectedGguf == model.name,
                    primaryLabel = if (controller.loadedModel == model.name) "Loaded" else "Load",
                    primaryEnabled = !controller.busy && controller.llama.isAvailable() &&
                        controller.loadedModel != model.name,
                    onPrimary = { controller.selectGguf(model.name) },
                    onDelete = { controller.deleteModel(model.name) },
                    deleteEnabled = !controller.busy
                )
            }

            controller.onnxModels.forEach { model ->
                StoredModelRow(
                    model = model,
                    selected = controller.selectedOnnx == model.name,
                    primaryLabel = "Inspect",
                    primaryEnabled = !controller.busy && controller.onnx.isAvailable(),
                    onPrimary = { controller.selectOnnx(model.name) },
                    onDelete = { controller.deleteModel(model.name) },
                    deleteEnabled = !controller.busy
                )
            }
        }
    }
}

@Composable
private fun StoredModelRow(
    model: StoredModel,
    selected: Boolean,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                Text(
                    "${model.kind.label} · ${formatBytes(model.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = onPrimary, enabled = primaryEnabled) { Text(primaryLabel) }
            OutlinedButton(onClick = onDelete, enabled = deleteEnabled) { Text("Delete") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DownloadCard(controller: AiController) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Download", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = controller.downloadUrl,
                onValueChange = { controller.updateDownloadUrl(it) },
                label = { Text("Model URL") },
                supportingText = { Text("https only, .gguf or .onnx, at most 8 GiB") },
                singleLine = true,
                enabled = !controller.busy,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.startDownload() }, enabled = !controller.busy) {
                    Text("Download")
                }
            }
            controller.downloadProgress?.let { (read, total) ->
                Text(
                    if (total > 0) "${formatBytes(read)} of ${formatBytes(total)}" else formatBytes(read),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                "Downloads stream to a temporary file and are renamed into place only after the last byte.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ChatCard(controller: AiController) {
    val config = controller.config
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GGUF chat", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (controller.loadedModel != null) {
                    OutlinedButton(onClick = { controller.unloadModel() }, enabled = !controller.busy) {
                        Text("Unload")
                    }
                }
            }
            Text(
                controller.loadedModel?.let { "Loaded $it" } ?: "No model loaded",
                style = MaterialTheme.typography.bodySmall
            )
            if (!controller.llama.isAvailable()) {
                Text(
                    controller.llama.unavailableReason() ?: "llama.cpp is unavailable",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text("Context", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CONTEXT_CHOICES.forEach { size ->
                    FilterChip(
                        selected = config.contextSize == size,
                        onClick = { controller.updateConfig(config.copy(contextSize = size)) },
                        enabled = !controller.busy,
                        label = { Text(size.toString()) }
                    )
                }
            }

            Text("Reply length", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TOKEN_CHOICES.forEach { tokens ->
                    FilterChip(
                        selected = config.maxTokens == tokens,
                        onClick = { controller.updateConfig(config.copy(maxTokens = tokens)) },
                        enabled = !controller.busy,
                        label = { Text(tokens.toString()) }
                    )
                }
            }

            Text("Temperature", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TEMPERATURE_CHOICES.forEach { temperature ->
                    FilterChip(
                        selected = config.temperature == temperature,
                        onClick = { controller.updateConfig(config.copy(temperature = temperature)) },
                        enabled = !controller.busy,
                        label = { Text("%.1f".format(temperature)) }
                    )
                }
            }
            Text(
                "Context, reply length and temperature apply to the next load and the next turn.",
                style = MaterialTheme.typography.bodySmall
            )

            if (controller.chatTurns.isNotEmpty()) {
                Text("Transcript", style = MaterialTheme.typography.labelMedium)
                controller.chatTurns.forEach { turn ->
                    Text(
                        "${if (turn.role == ChatTurn.Role.User) "You" else "Model"}: ${turn.text}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (controller.reply.isNotEmpty()) {
                Text("Reply", style = MaterialTheme.typography.labelMedium)
                Text(controller.reply, style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = controller.draft,
                onValueChange = { controller.updateDraft(it) },
                label = { Text("Message") },
                minLines = 2,
                enabled = !controller.busy,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.send() }, enabled = controller.canChat) { Text("Send") }
                OutlinedButton(
                    onClick = { controller.stopGeneration() },
                    enabled = controller.busy && controller.loadedModel != null
                ) { Text("Stop") }
            }
        }
    }
}

/**
 * The OpenAI-compatible API that runs beside the web server, and the settings that shape it.
 *
 * The endpoint and the token are read from what the service published and from the Keystore-backed
 * settings, so the card describes the listener that is actually up rather than a form of what
 * someone typed. The port and the LAN switch are written as they are edited, like the server
 * settings, and take effect the next time the service starts.
 */
@Composable
private fun AiApiCard(controller: AiController) {
    val context = LocalContext.current
    val settings = remember { runCatching { AiApiSettings(context) }.getOrNull() }
    var status by remember { mutableStateOf(AiApiState.snapshot()) }
    var token by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf("") }
    var allowLan by remember { mutableStateOf(false) }

    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        val poll = object : Runnable {
            override fun run() {
                status = AiApiState.snapshot()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
        onDispose { handler.removeCallbacks(poll) }
    }
    DisposableEffect(settings) {
        if (settings != null) {
            portText = settings.port.toString()
            allowLan = settings.allowLan
            token = runCatching { settings.token() }.getOrNull().orEmpty()
        }
        onDispose { }
    }

    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Local AI API", fontWeight = FontWeight.Bold)
            Text(
                when (status.phase) {
                    AiApiPhase.Stopped -> "Not running"
                    AiApiPhase.Running -> "Running"
                    AiApiPhase.Failed -> "Not available"
                },
                fontWeight = FontWeight.Bold
            )
            if (status.detail.isNotEmpty()) {
                Text(status.detail, style = MaterialTheme.typography.bodySmall)
            }
            status.endpoint().takeIf { it.isNotEmpty() }?.let { endpoint ->
                StatusLine("Endpoint", endpoint)
            }
            StatusLine("Model", controller.loadedModel ?: "None loaded, so chat answers 503")
            StatusLine(
                "Encoder",
                controller.selectedOnnx ?: "None selected, so embeddings answer 503"
            )
            StatusLine(
                "Paths",
                "GET /health · GET /v1/models · POST /v1/chat/completions · POST /v1/embeddings"
            )
            Text(
                "The API uses the model loaded in this tab; there is no second copy. It is started " +
                    "and stopped with the web server, and every route needs the bearer token below.",
                style = MaterialTheme.typography.bodySmall
            )

            if (settings == null) {
                Text(
                    "Secure storage is unavailable on this device, so the API stays off.",
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }

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
                            ""
                        }
                    }
                },
                label = { Text("API port") },
                supportingText = {
                    Text(
                        portError.ifEmpty { "Default ${AiApiSettings.DEFAULT_PORT}, separate from the site" }
                    )
                },
                isError = portError.isNotEmpty(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.Top) {
                Switch(checked = allowLan, onCheckedChange = {
                    allowLan = it
                    settings.allowLan = it
                })
                Spacer(Modifier.padding(horizontal = 8.dp))
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text("Allow LAN access")
                    Text(
                        if (allowLan) {
                            "Listening on ${ServerSettingsRules.ANY_ADDRESS}, so other devices on " +
                                "this network can call the model"
                        } else {
                            "Listening on ${ServerSettingsRules.LOOPBACK_ADDRESS}, reachable from this device only"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            OutlinedTextField(
                value = token,
                onValueChange = {},
                readOnly = true,
                label = { Text("Bearer token") },
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                supportingText = {
                    Text(
                        if (token.isEmpty()) {
                            "No token yet. One is created when the server starts."
                        } else {
                            "Stored encrypted under an Android Keystore key"
                        }
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { tokenVisible = !tokenVisible },
                    enabled = token.isNotEmpty()
                ) { Text(if (tokenVisible) "Hide token" else "Show token") }
                OutlinedButton(
                    onClick = {
                        val fresh = AiApiToken.generate()
                        notice = if (runCatching { settings.rotateToken(fresh) }.isSuccess) {
                            token = fresh
                            tokenVisible = true
                            "A new token was generated. Restart the server for it to take effect."
                        } else {
                            "The token could not be stored on this device"
                        }
                    }
                ) { Text("New token") }
            }
            if (notice.isNotEmpty()) {
                Text(notice, style = MaterialTheme.typography.bodySmall)
            }
            // The loopback endpoint is the one case where the URL is exact enough to paste. With LAN
            // access the client has to use this device's own address, which the app does not hold.
            status.endpoint().takeIf { it.startsWith("http") && status.isRunning }?.let { base ->
                Text(
                    "curl -H \"Authorization: Bearer ${'$'}TOKEN\" $base/v1/models",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun OnnxCard(controller: AiController, onImportVocabulary: () -> Unit) {
    val info = controller.onnxModel
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("ONNX", fontWeight = FontWeight.Bold)
            if (info == null) {
                Text("Select an ONNX model to open a session.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            OnnxModelCard(controller, info)
            controller.encoder?.let { encoder ->
                HorizontalDivider()
                Text("Text encoder", style = MaterialTheme.typography.labelMedium)
                Text(encoder.summary, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            EmbeddingCard(controller, onImportVocabulary)
        }
    }
}

/**
 * The text embedding path: a WordPiece vocabulary beside the encoder, the sentence to encode, and
 * the shape of what came back. The vector itself is not shown, because a few hundred floats are
 * unreadable on a phone; the norm, the width and the first few values are enough to tell a working
 * encoder from a broken one.
 */
@Composable
private fun EmbeddingCard(controller: AiController, onImportVocabulary: () -> Unit) {
    val encoder = controller.encoder
    val vocabulary = controller.vocabularyName
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Embeddings", style = MaterialTheme.typography.labelMedium)
        StatusLine("Vocabulary", controller.vocabularyStatus)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportVocabulary, enabled = !controller.busy) {
                Text(if (vocabulary == null) "Import vocab.txt" else "Replace vocab.txt")
            }
            vocabulary?.let { name ->
                OutlinedButton(
                    onClick = { controller.deleteVocabulary(name) },
                    enabled = !controller.busy
                ) {
                    Text("Remove")
                }
            }
        }
        if (encoder?.supported != true) {
            Text(
                "A text encoder needs integer input_ids and attention_mask, a float output, and its " +
                    "vocab.txt beside it.",
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }
        OutlinedTextField(
            value = controller.embeddingInput,
            onValueChange = { controller.updateEmbeddingInput(it.take(MAX_EMBEDDING_INPUT)) },
            label = { Text("Text to embed") },
            supportingText = { Text("At most $MAX_EMBEDDING_INPUT characters, truncated to 512 tokens") },
            enabled = !controller.busy,
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { controller.embedText() }, enabled = controller.canEmbed) {
            Text("Embed")
        }
        controller.embedding?.let { summary ->
            StatusLine("Result", "${summary.dimensions} dimensions from ${summary.tokens} tokens")
            StatusLine("Norm", String.format(java.util.Locale.ROOT, "%.4f", summary.norm))
            StatusLine(
                "First values",
                summary.firstValues.joinToString(", ") { String.format(java.util.Locale.ROOT, "%.4f", it) }
            )
        }
    }
}

@Composable
private fun OnnxModelCard(controller: AiController, info: OnnxModelInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(info.path.substringAfterLast('/'), fontWeight = FontWeight.Bold)
        Text("Inputs", style = MaterialTheme.typography.labelMedium)
        info.inputs.forEach { spec ->
            Text(
                "${spec.name}: ${spec.shapeText} ${spec.type}" + if (spec.runnable) "" else " (not runnable)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        Text("Outputs", style = MaterialTheme.typography.labelMedium)
        info.outputs.forEach { spec ->
            Text(
                "${spec.name}: ${spec.shapeText} ${spec.type}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.runOnnx() }, enabled = !controller.busy && info.isRunnable) {
                Text("Run with zeros")
            }
            OutlinedButton(onClick = { controller.closeOnnx() }, enabled = !controller.busy) {
                Text("Close")
            }
        }
        info.lastRun?.let { output ->
            Text("Last run", style = MaterialTheme.typography.labelMedium)
            Text(output, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
