package com.mossdial.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Delete

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mossdial.ai.AiController
import com.mossdial.ai.AiControllerRegistry
import com.mossdial.ai.ChatTurn
import com.mossdial.ai.OnnxModelInfo
import com.mossdial.aiapi.AiApiPhase
import com.mossdial.aiapi.AiApiState
import com.mossdial.aiapi.AiApiStatus
import com.mossdial.aiapi.AiApiToken
import com.mossdial.data.AiApiSettings
import com.mossdial.data.ServerSettingsRules
import com.mossdial.data.StoredModel
import com.mossdial.ui.components.Band
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.ButtonRow
import com.mossdial.ui.components.ChoiceChip
import com.mossdial.ui.components.ChipRow
import com.mossdial.ui.components.EmptyState
import com.mossdial.ui.components.FieldLabel
import com.mossdial.ui.components.LabeledValue
import com.mossdial.ui.components.Notice
import com.mossdial.ui.components.RowDivider
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.components.SectionHeader
import com.mossdial.ui.components.StatusPill
import com.mossdial.ui.components.StatusTone
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.components.SwitchRow
import com.mossdial.ui.components.pageSection
import com.mossdial.ui.theme.Spacing

private val CONTEXT_CHOICES = listOf(512, 1024, 2048, 4096, 8192)
private val TOKEN_CHOICES = listOf(64, 128, 256, 512)
private val TEMPERATURE_CHOICES = listOf(0.0f, 0.2f, 0.5f, 0.7f, 1.0f)

/** What the embedding field accepts, matching the tokenizer's own bound. */
private const val MAX_EMBEDDING_INPUT = 64 * 1024

/**
 * On-device models and the API that exposes them.
 *
 * This screen was one long scroll of five cards with no hierarchy, and every one of them repeated
 * the same heading-and-value pattern. It is now a lazy column of labelled sections, so the screen
 * is built once rather than composed of everything at once, and each section states what it is for
 * before showing its controls.
 */
@Composable
fun AiScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    // One controller for the process. The local API answers with it, so it must outlive this tab;
    // and building it per visit meant every visit loaded the native runtimes and unloaded the model.
    val controller = remember {
        AiController.shared(context).also { AiControllerRegistry.attach(it) }
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

    // The page's rhythm matches the server tab: inset sections separated by full-bleed bands, no
    // page title — the runtime read-out opens instead, because it is the first thing worth knowing.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + Spacing.Snug
        )
    ) {
        pageSection {
            RuntimeSection(controller)
            controller.message?.let { notice ->
                Notice(
                    message = notice,
                    action = {
                        TextButton(onClick = { controller.clearMessage() }) {
                            Text("Dismiss", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                )
            }
        }

        item { Band() }
        pageSection { ModelsSection(controller) { picker.launch(arrayOf("*/*")) } }

        item { Band() }
        pageSection { DownloadSection(controller) }

        item { Band() }
        pageSection { ChatSection(controller) }

        item { Band() }
        pageSection { AiApiSection(controller) }

        item { Band() }
        pageSection { OnnxSection(controller) { vocabularyPicker.launch(arrayOf("text/plain", "*/*")) } }

        item { Band() }
        pageSection {
            SupportingText("Weights are kept outside the web root and are never served.")
        }
    }
}

@Composable
private fun RuntimeSection(controller: AiController) {
    // A read-out rather than a form: what the runtimes report is set out directly on the screen,
    // so the page opens with information rather than with one more box.
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        SectionHeader(title = "Runtime")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            LabeledValue(
                label = "llama.cpp",
                value = controller.llamaStatus,
                modifier = Modifier.weight(1f)
            )
            LabeledValue(
                label = "ONNX Runtime",
                value = controller.onnxStatus,
                modifier = Modifier.weight(1f)
            )
        }
        LabeledValue(
            label = "Model directory",
            value = controller.modelDirectory,
            monospace = true
        )
        LabeledValue(
            label = "Worker",
            value = "${controller.config.threads} " +
                if (controller.config.threads == 1) "thread" else "threads"
        )
        if (controller.busy) {
            StatusPill(
                label = "Working on",
                tone = StatusTone.Starting,
                detail = controller.busyLabel
            )
        }
    }
}

@Composable
private fun ModelsSection(controller: AiController, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        SectionHeader(title = "Models")
        ButtonRow {
            SecondaryButton(
                onClick = { controller.refreshModels() },
                enabled = !controller.busy
            ) {
                ButtonContent(Icons.Default.Refresh, "Refresh")
            }
            PrimaryButton(onClick = onImport, enabled = !controller.busy) {
                ButtonContent(Icons.Default.UploadFile, "Import")
            }
        }

        if (controller.models.isEmpty()) {
            EmptyState(
                title = "No model files",
                body = "Import a .gguf or .onnx file, or download one below.",
                icon = Icons.Default.Memory
            )
        }

        controller.ggufModels.forEachIndexed { index, model ->
            if (index > 0) RowDivider()
            StoredModelRow(
                model = model,
                selected = controller.selectedGguf == model.name,
                primaryLabel = if (controller.loadedModel == model.name) "Loaded" else "Load",
                primaryEnabled = !controller.busy && controller.llamaAvailable &&
                    controller.loadedModel != model.name,
                onPrimary = { controller.selectGguf(model.name) },
                onDelete = { controller.deleteModel(model.name) },
                deleteEnabled = !controller.busy
            )
        }

        controller.onnxModels.forEach { model ->
            RowDivider()
            StoredModelRow(
                model = model,
                selected = controller.selectedOnnx == model.name,
                primaryLabel = "Inspect",
                primaryEnabled = !controller.busy && controller.onnxAvailable,
                onPrimary = { controller.selectOnnx(model.name) },
                onDelete = { controller.deleteModel(model.name) },
                deleteEnabled = !controller.busy
            )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Base),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (selected) {
                    StatusPill(
                        label = "Selected",
                        tone = StatusTone.Accent,
                        modifier = Modifier.padding(start = Spacing.Snug)
                    )
                }
            }
            Text(
                text = "${model.kind.label} · ${formatBytes(model.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SecondaryButton(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.padding(start = Spacing.Base)
        ) {
            Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
        }
        IconButton(
            onClick = onDelete,
            enabled = deleteEnabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete ${model.name}",
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun DownloadSection(controller: AiController) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        SectionHeader(title = "Download")
        OutlinedTextField(
            value = controller.downloadUrl,
            onValueChange = { controller.updateDownloadUrl(it) },
            label = { Text("Model URL") },
            supportingText = { Text("https only, .gguf or .onnx, at most 8 GiB") },
            singleLine = true,
            enabled = !controller.busy,
            modifier = Modifier.fillMaxWidth()
        )
        ButtonRow {
            PrimaryButton(
                onClick = { controller.startDownload() },
                enabled = !controller.busy
            ) {
                ButtonContent(Icons.Default.Download, "Download")
            }
        }
        // Read in its own scope: progress changes with every chunk, and the URL field above it
        // should not be recomposed at that rate.
        DownloadProgress(controller)
    }
}

@Composable
private fun DownloadProgress(controller: AiController) {
    val progress = controller.downloadProgress ?: return
    val (read, total) = progress
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        // A determinate bar when the server declared a length, indeterminate when it did
        // not. Showing a progress fraction that was guessed from nothing reads as a bug.
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (read.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = if (total > 0) {
                "${formatBytes(read)} of ${formatBytes(total)}"
            } else {
                formatBytes(read)
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatSection(controller: AiController) {
    val config = controller.config
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        SectionHeader(
            title = "GGUF chat",
            subtitle = controller.loadedModel?.let { "Loaded $it" } ?: "No model loaded",
            trailing = {
                if (controller.loadedModel != null) {
                    SecondaryButton(onClick = { controller.unloadModel() }, enabled = !controller.busy) {
                        Text("Unload", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        )

        if (controller.runtimesChecked && !controller.llamaAvailable) {
            SupportingText(controller.llamaStatus)
        }

        FieldLabel("Context")
        // Scrolling rather than a fixed Row: five sizes at the default type scale do not fit a
        // narrow phone, and the largest option was the one that fell off the end.
        ChipRow {
            CONTEXT_CHOICES.forEach { size ->
                ChoiceChip(
                    label = size.toString(),
                    selected = config.contextSize == size,
                    onClick = { controller.updateConfig(config.copy(contextSize = size)) },
                    enabled = !controller.busy
                )
            }
        }

        FieldLabel("Reply length")
        ChipRow {
            TOKEN_CHOICES.forEach { tokens ->
                ChoiceChip(
                    label = tokens.toString(),
                    selected = config.maxTokens == tokens,
                    onClick = { controller.updateConfig(config.copy(maxTokens = tokens)) },
                    enabled = !controller.busy
                )
            }
        }

        FieldLabel("Temperature")
        ChipRow {
            TEMPERATURE_CHOICES.forEach { temperature ->
                ChoiceChip(
                    label = String.format(java.util.Locale.ROOT, "%.1f", temperature),
                    selected = config.temperature == temperature,
                    onClick = { controller.updateConfig(config.copy(temperature = temperature)) },
                    enabled = !controller.busy
                )
            }
        }

        SupportingText("Context, reply length and temperature apply to the next load.")

        // Each of these reads one piece of state in its own scope, so a token arriving or a key
        // being typed does not recompose the chips above it or the transcript behind it.
        TranscriptBlock(controller)
        ReplyBlock(controller)
        ChatComposer(controller)
    }
}

/** The finished turns. Recomposes only when a turn is added, never while a reply streams. */
@Composable
private fun TranscriptBlock(controller: AiController) {
    val turns = controller.chatTurns
    if (turns.isEmpty()) return
    RowDivider()
    FieldLabel("Transcript")
    turns.forEach { turn ->
        TranscriptLine(turn)
    }
}

/**
 * The reply being streamed.
 *
 * This is the hot spot of the tab: it changes while the model talks. Reading it here rather than in
 * [ChatSection] means the text field, the chips and the transcript are not recomposed on every
 * update, and the controller publishes at most every 80ms on top of that.
 */
@Composable
private fun ReplyBlock(controller: AiController) {
    val reply = controller.reply
    if (reply.isEmpty()) return
    RowDivider()
    FieldLabel("Reply")
    Text(
        text = reply,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** The draft, the send button and the stop button: the only part a keystroke touches. */
@Composable
private fun ChatComposer(controller: AiController) {
    OutlinedTextField(
        value = controller.draft,
        onValueChange = { controller.updateDraft(it) },
        label = { Text("Message") },
        enabled = !controller.busy,
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
    ButtonRow {
        PrimaryButton(onClick = { controller.send() }, enabled = controller.canChat) {
            ButtonContent(Icons.AutoMirrored.Filled.Send, "Send")
        }
        SecondaryButton(
            onClick = { controller.stopGeneration() },
            enabled = controller.busy && controller.loadedModel != null
        ) {
            ButtonContent(Icons.Default.Stop, "Stop")
        }
    }
}

/** One turn of the transcript, labelled by who said it rather than by a prefix in the text. */
@Composable
private fun TranscriptLine(turn: ChatTurn) {
    val fromUser = turn.role == ChatTurn.Role.User
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Base)
    ) {
        Text(
            text = if (fromUser) "You" else "Model",
            style = MaterialTheme.typography.labelMedium,
            color = if (fromUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            text = turn.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The OpenAI-compatible API that runs beside the web server, and the settings that shape it.
 *
 * The endpoint and the token are read from what the service published and from the Keystore-backed
 * settings, so the card describes the listener that is actually up rather than a form of what
 * someone typed. The port, the LAN switch and the HTTPS switch are written as they are edited, like
 * the server settings, and take effect the next time the service starts.
 */
@Composable
private fun AiApiSection(controller: AiController) {
    val context = LocalContext.current
    val settings = remember { runCatching { AiApiSettings(context) }.getOrNull() }
    var status by remember { mutableStateOf(AiApiState.snapshot()) }
    var token by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf("") }
    var allowLan by remember { mutableStateOf(false) }
    var enableTls by remember { mutableStateOf(false) }

    val handler = remember { Handler(Looper.getMainLooper()) }
    // A listener rather than a 500ms poll: the API publishes only when its phase changes, so this
    // reacts exactly when there is something to show and does no work in between.
    DisposableEffect(Unit) {
        val listener: (AiApiStatus) -> Unit = { next -> handler.post { status = next } }
        AiApiState.addListener(listener)
        onDispose {
            AiApiState.removeListener(listener)
            handler.removeCallbacksAndMessages(null)
        }
    }
    DisposableEffect(settings) {
        if (settings == null) {
            onDispose { }
        } else {
            portText = settings.port.toString()
            allowLan = settings.allowLan
            enableTls = settings.enableTls
            // Opening the Keystore slot decrypts the token, which is slow enough to be a visible
            // hitch on the frame the tab opens. Read it on a short worker and deliver it as state.
            val worker = Thread({
                val stored = runCatching { settings.token() }.getOrNull().orEmpty()
                handler.post { token = stored }
            }, "mossdial-api-token").apply { isDaemon = true }
            worker.start()
            onDispose { worker.interrupt() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        SectionHeader(title = "Local AI API")

        StatusPill(
            label = when (status.phase) {
                AiApiPhase.Stopped -> "Not running"
                AiApiPhase.Running -> "Running"
                AiApiPhase.Failed -> "Not available"
            },
            tone = when (status.phase) {
                AiApiPhase.Stopped -> StatusTone.Idle
                AiApiPhase.Running -> StatusTone.Live
                AiApiPhase.Failed -> StatusTone.Error
            },
            detail = if (status.isRunning) "port ${status.port}" else null
        )

        if (status.detail.isNotEmpty() && status.phase == AiApiPhase.Failed) {
            Text(
                text = status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        status.endpoint().takeIf { it.isNotEmpty() }?.let { endpoint ->
            LabeledValue(label = "Endpoint", value = endpoint, monospace = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            LabeledValue(
                label = "Model",
                value = controller.loadedModel ?: "None, chat answers 503",
                modifier = Modifier.weight(1f)
            )
            LabeledValue(
                label = "Encoder",
                value = controller.selectedOnnx ?: "None, embeddings answer 503",
                modifier = Modifier.weight(1f)
            )
        }
        LabeledValue(
            label = "Paths",
            value = "GET /health · GET /v1/models · POST /v1/chat/completions · POST /v1/embeddings",
            monospace = true
        )
        SupportingText(
            text = "Started with the web server. Every route needs the token below."
        )

        if (settings == null) {
            SupportingText("Secure storage is unavailable on this device, so the API stays off.")
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
                Text(portError.ifEmpty { "Default ${AiApiSettings.DEFAULT_PORT}, separate from the site" })
            },
            isError = portError.isNotEmpty(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SwitchRow(
            checked = allowLan,
            onCheckedChange = {
                allowLan = it
                settings.allowLan = it
            },
            title = "Allow LAN access",
            summary = if (allowLan) {
                "Listening on ${ServerSettingsRules.ANY_ADDRESS} — callable on this network"
            } else {
                "Listening on ${ServerSettingsRules.LOOPBACK_ADDRESS} — this device only"
            }
        )

        SwitchRow(
            checked = enableTls,
            onCheckedChange = {
                enableTls = it
                settings.enableTls = it
            },
            title = "Use HTTPS",
            summary = if (enableTls) {
                "Self-signed certificate; clients must trust it or skip verification"
            } else {
                "Plain HTTP. The bearer token crosses the network in clear text."
            }
        )

        if (allowLan && !enableTls) {
            // The token is the only thing protecting the API, and on plain HTTP anyone on the
            // network can read it off the wire. Say so where the choice is made.
            Notice(message = "LAN access without HTTPS exposes the bearer token to this network.")
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
            trailingIcon = {
                // An eye on the field rather than a separate Show button beside it, so the control
                // is attached to the thing it reveals.
                IconButton(onClick = { tokenVisible = !tokenVisible }, enabled = token.isNotEmpty()) {
                    Icon(
                        imageVector = if (tokenVisible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = if (tokenVisible) "Hide the token" else "Show the token"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        ButtonRow {
            SecondaryButton(
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
            ) {
                Text("New token", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (notice.isNotEmpty()) {
            Notice(message = notice)
        }
        // The loopback endpoint is the one case where the URL is exact enough to paste. With LAN
        // access the endpoint is this device's own address, which the app resolves above.
        status.endpoint().takeIf { it.startsWith("http") && status.isRunning }?.let { base ->
            val insecure = if (status.enableTls) "-k " else ""
            Text(
                text = "curl ${insecure}-H \"Authorization: Bearer ${'$'}TOKEN\" $base/v1/models",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OnnxSection(controller: AiController, onImportVocabulary: () -> Unit) {
    val info = controller.onnxModel
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        SectionHeader(title = "ONNX encoder")
        if (info == null) {
            EmptyState(
                title = "No encoder selected",
                body = "Import an .onnx model above, then choose Inspect.",
                icon = Icons.Default.Memory
            )
            return@Column
        }
        OnnxModelCard(controller, info)
        controller.encoder?.let { encoder ->
            RowDivider()
            FieldLabel("Text encoder")
            SupportingText(encoder.summary)
        }
        RowDivider()
        EmbeddingCard(controller, onImportVocabulary)
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
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        LabeledValue(
            label = "Vocabulary",
            value = controller.vocabularyStatus
        )
        ButtonRow {
            PrimaryButton(onClick = onImportVocabulary, enabled = !controller.busy) {
                ButtonContent(
                    Icons.Default.UploadFile,
                    if (vocabulary == null) "Import vocab.txt" else "Replace vocab.txt"
                )
            }
            vocabulary?.let { name ->
                SecondaryButton(
                    onClick = { controller.deleteVocabulary(name) },
                    enabled = !controller.busy
                ) {
                    Text("Remove", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (encoder?.supported != true) {
            SupportingText(
                "A text encoder needs integer input_ids and attention_mask, a float output, and " +
                    "its vocab.txt beside it."
            )
            return@Column
        }
        EmbeddingInput(controller)
        ButtonRow {
            PrimaryButton(onClick = { controller.embedText() }, enabled = controller.canEmbed) {
                Text("Embed", style = MaterialTheme.typography.labelLarge)
            }
        }
        controller.embedding?.let { summary ->
            LabeledValue(
                label = "Result",
                value = "${summary.dimensions} dimensions from ${summary.tokens} tokens"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                LabeledValue(
                    label = "Norm",
                    value = String.format(java.util.Locale.ROOT, "%.4f", summary.norm),
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                LabeledValue(
                    label = "First values",
                    value = summary.firstValues.joinToString(", ") {
                        String.format(java.util.Locale.ROOT, "%.4f", it)
                    },
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun EmbeddingInput(controller: AiController) {
    // Its own scope so a keystroke recomposes the field, not the result block underneath it.
    OutlinedTextField(
        value = controller.embeddingInput,
        onValueChange = { controller.updateEmbeddingInput(it.take(MAX_EMBEDDING_INPUT)) },
        label = { Text("Text to embed") },
        supportingText = { Text("At most $MAX_EMBEDDING_INPUT characters, truncated to 512 tokens") },
        enabled = !controller.busy,
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun OnnxModelCard(controller: AiController, info: OnnxModelInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
        Text(
            text = info.path.substringAfterLast('/'),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        FieldLabel("Inputs")
        info.inputs.forEach { spec ->
            Text(
                text = "${spec.name}: ${spec.shapeText} ${spec.type}" +
                    if (spec.runnable) "" else " (not runnable)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FieldLabel("Outputs")
        info.outputs.forEach { spec ->
            Text(
                text = "${spec.name}: ${spec.shapeText} ${spec.type}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ButtonRow {
            PrimaryButton(onClick = { controller.runOnnx() }, enabled = !controller.busy && info.isRunnable) {
                Text("Run with zeros", style = MaterialTheme.typography.labelLarge)
            }
            SecondaryButton(onClick = { controller.closeOnnx() }, enabled = !controller.busy) {
                Text("Close", style = MaterialTheme.typography.labelLarge)
            }
        }
        info.lastRun?.let { output ->
            FieldLabel("Last run")
            Text(
                text = output,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}