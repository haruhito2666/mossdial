package com.mossdial.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mossdial.data.ManagedFile
import com.mossdial.data.WebRoot
import com.mossdial.data.WebRootFileStore
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.ButtonRow
import com.mossdial.ui.components.EmptyState
import com.mossdial.ui.components.ErrorNotice
import com.mossdial.ui.components.Notice
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.theme.Spacing
import java.io.FileNotFoundException
import java.util.concurrent.Executors

@Composable
fun FileManagerScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val store = remember { WebRootFileStore(WebRoot.ensure(context)) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ManagedFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateTextDialog by remember { mutableStateOf(false) }
    var showCreateDirectoryDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ManagedFile?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ManagedFile?>(null) }
    var createName by rememberSaveable { mutableStateOf("") }
    var createContent by rememberSaveable { mutableStateOf("") }

    fun operationError(error: Exception) {
        errorMessage = when (error) {
            is FileNotFoundException -> "That item no longer exists."
            is SecurityException -> "That path is not allowed."
            is java.nio.file.FileAlreadyExistsException -> "An item with that name already exists."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "The file operation failed."
        }
        statusMessage = null
    }

    fun refresh(path: String) {
        isLoading = true
        executor.execute {
            try {
                val loadedEntries = store.list(path)
                mainHandler.post {
                    if (currentPath == path) {
                        entries = loadedEntries
                        isLoading = false
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (currentPath == path) {
                        isLoading = false
                        operationError(error)
                    }
                }
            }
        }
    }

    fun runOperation(successMessage: String, operation: () -> Unit) {
        if (isBusy) return
        isBusy = true
        statusMessage = null
        errorMessage = null
        executor.execute {
            try {
                operation()
                mainHandler.post {
                    isBusy = false
                    statusMessage = successMessage
                    refresh(currentPath)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    isBusy = false
                    operationError(error)
                }
            }
        }
    }

    fun importDocument(uri: Uri) {
        if (isBusy) return
        isBusy = true
        statusMessage = null
        errorMessage = null
        val directory = currentPath
        executor.execute {
            try {
                val resolver = context.contentResolver
                val mimeType = resolver.getType(uri)
                val displayName = queryDisplayName(resolver, uri)
                val name = importedFileName(displayName, uri, mimeType)
                val input = resolver.openInputStream(uri)
                    ?: throw FileNotFoundException("The selected document is unavailable")
                input.use {
                    store.importFile(directory, name, it, mimeType)
                }
                mainHandler.post {
                    isBusy = false
                    statusMessage = "File imported."
                    refresh(currentPath)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    isBusy = false
                    operationError(error)
                }
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importDocument(it) }
    }

    BackHandler(enabled = currentPath.isNotEmpty()) {
        currentPath = parentPath(currentPath)
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdownNow() }
    }

    DisposableEffect(currentPath) {
        refresh(currentPath)
        onDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = Spacing.Inset,
                end = Spacing.Inset,
                top = contentPadding.calculateTopPadding() + Spacing.Base,
                bottom = contentPadding.calculateBottomPadding() + Spacing.BottomBreathing
            )
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Snug)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Files",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            Text(
                text = "Everything here is served by the web host",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The breadcrumb replaces a back arrow and a path string side by side. Each segment is its
        // own target, so a deep path can be climbed without a back press per level.
        Breadcrumb(
            path = currentPath,
            enabled = !isBusy,
            onNavigate = { currentPath = it }
        )

        ButtonRow(modifier = Modifier.padding(top = Spacing.Tight)) {
            PrimaryButton(
                onClick = {
                    createName = "untitled.txt"
                    createContent = ""
                    showCreateTextDialog = true
                },
                enabled = !isBusy
            ) {
                ButtonContent(Icons.AutoMirrored.Filled.NoteAdd, "Text file")
            }
            SecondaryButton(
                onClick = {
                    createName = "new-folder"
                    showCreateDirectoryDialog = true
                },
                enabled = !isBusy
            ) {
                ButtonContent(Icons.Default.CreateNewFolder, "Folder")
            }
            SecondaryButton(
                onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                enabled = !isBusy
            ) {
                ButtonContent(Icons.Default.UploadFile, "Import")
            }
        }

        val feedback = errorMessage ?: statusMessage
        if (feedback != null) {
            Box(modifier = Modifier.padding(top = Spacing.Base)) {
                if (errorMessage != null) {
                    ErrorNotice(message = feedback)
                } else {
                    Notice(message = feedback)
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.Base)
            )
        }

        if (!isLoading && entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    title = if (currentPath.isEmpty()) "No files yet" else "This folder is empty",
                    body = if (currentPath.isEmpty()) {
                        "Create a text file, make a folder, or import something from this device. " +
                            "Whatever is here is what the site serves."
                    } else {
                        "There is nothing in this folder."
                    },
                    icon = if (currentPath.isEmpty()) Icons.Default.FolderOpen else Icons.Default.Folder
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.Base)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Snug),
                contentPadding = PaddingValues(bottom = Spacing.Snug)
            ) {
                items(entries, key = { it.relativePath }) { entry ->
                    ManagedFileRow(
                        entry = entry,
                        enabled = !isBusy,
                        onOpen = { currentPath = entry.relativePath },
                        onRename = {
                            renameTarget = entry
                            renameText = entry.name
                        },
                        onDelete = { deleteTarget = entry }
                    )
                }
            }
        }
    }

    if (showCreateTextDialog) {
        AlertDialog(
            onDismissRequest = { showCreateTextDialog = false },
            title = { Text("Create text file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Base)) {
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it },
                        label = { Text("File name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = createContent,
                        onValueChange = { createContent = it },
                        label = { Text("Initial text (optional)") },
                        minLines = 3,
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createName.trim()
                        val content = createContent
                        val directory = currentPath
                        showCreateTextDialog = false
                        runOperation("Text file created.") {
                            store.createTextFile(directory, name, content)
                        }
                    },
                    enabled = !isBusy && createName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTextDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreateDirectoryDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDirectoryDialog = false },
            title = { Text("Create folder") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createName.trim()
                        val directory = currentPath
                        showCreateDirectoryDialog = false
                        runOperation("Folder created.") {
                            store.createDirectory(directory, name)
                        }
                    },
                    enabled = !isBusy && createName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDirectoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameText.trim()
                        renameTarget = null
                        runOperation("Item renamed.") {
                            store.rename(target.relativePath, name)
                        }
                    },
                    enabled = !isBusy && renameText.isNotBlank()
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete item?") },
            text = {
                Text(
                    if (target.isDirectory) {
                        "Delete ${target.name} and everything inside it?"
                    } else {
                        "Delete ${target.name}?"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        runOperation("Item deleted.") {
                            store.delete(target.relativePath)
                        }
                    },
                    enabled = !isBusy
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * The path as a row of segments, each one tappable.
 *
 * This replaces a back arrow beside a flat "WebRoot/a/b/c" string. That string had to be truncated
 * on a narrow screen, and the part that got truncated was the end of the path, which is the part
 * that says where you are. Segments scroll instead, and every level is reachable by tapping it.
 */
@Composable
private fun Breadcrumb(
    path: String,
    enabled: Boolean,
    onNavigate: (String) -> Unit
) {
    val segments = remember(path) {
        if (path.isEmpty()) listOf("" to "Web root") else buildList {
            add("" to "Web root")
            var accumulated = ""
            path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
                add(accumulated to segment)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Base)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onNavigate(parentPath(path)) },
            enabled = enabled && path.isNotEmpty(),
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Go to the parent folder",
                modifier = Modifier.size(20.dp)
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            segments.forEachIndexed { index, (target, label) ->
                if (index > 0) {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.Tight)
                    )
                }
                Text(
                    text = label,
                    style = if (index == segments.lastIndex) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = if (index == segments.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(enabled = enabled && target != path) { onNavigate(target) }
                        .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight)
                )
            }
        }
    }
}

@Composable
private fun ManagedFileRow(
    entry: ManagedFile,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val isDirectory = entry.isDirectory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row opens a folder, but only a folder: tapping a file does nothing, so the
            // row is not clickable for one and does not offer the ripple that would imply otherwise.
            .clickable(enabled = enabled && isDirectory, onClick = onOpen)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            )
            .padding(
                start = Spacing.Base,
                end = Spacing.Snug,
                top = Spacing.Base,
                bottom = Spacing.Base
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isDirectory) {
                Icons.Default.Folder
            } else {
                Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.Base)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isDirectory) "Folder" else "${formatBytes(entry.sizeBytes)} · ${entry.type}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onRename,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                imageVector = Icons.Default.DriveFileRenameOutline,
                contentDescription = "Rename ${entry.name}",
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = onDelete,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete ${entry.name}",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    val cursor = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    ) ?: return null
    return try {
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    } finally {
        cursor.close()
    }
}

private fun importedFileName(displayName: String?, uri: Uri, mimeType: String?): String {
    val extension = when {
        mimeType?.startsWith("text/") == true -> ".txt"
        mimeType == "application/json" -> ".json"
        mimeType?.startsWith("image/") == true -> ".img"
        else -> ""
    }
    val fallback = "imported-file$extension"
    val candidate = (displayName ?: uri.lastPathSegment.orEmpty())
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    if (
        candidate.isEmpty() ||
        candidate == "." ||
        candidate == ".." ||
        candidate.any { it == '\u0000' || it.isISOControl() }
    ) {
        return fallback
    }
    return if ('.' in candidate) candidate else candidate + extension
}

private fun parentPath(path: String): String = path.substringBeforeLast('/', "")