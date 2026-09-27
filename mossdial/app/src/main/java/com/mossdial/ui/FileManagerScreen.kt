package com.mossdial.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.mossdial.data.ManagedFile
import com.mossdial.data.WebRoot
import com.mossdial.data.WebRootFileStore
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun FileManagerScreen() {
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

    val feedback = errorMessage ?: statusMessage
    val feedbackColor = if (errorMessage != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Web files",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    createName = "untitled.txt"
                    createContent = ""
                    showCreateTextDialog = true
                },
                enabled = !isBusy
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Text file")
            }
            OutlinedButton(
                onClick = {
                    createName = "new-folder"
                    showCreateDirectoryDialog = true
                },
                enabled = !isBusy
            ) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Folder")
            }
            OutlinedButton(
                onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                enabled = !isBusy
            ) {
                Text("Import")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { currentPath = parentPath(currentPath) },
                enabled = currentPath.isNotEmpty() && !isBusy
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Go to parent directory")
            }
            Text(
                text = displayPath(currentPath),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (feedback != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (errorMessage != null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Text(
                    text = feedback,
                    color = feedbackColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (!isLoading && entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("This directory is empty.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun ManagedFileRow(
    entry: ManagedFile,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = if (entry.isDirectory) "Folder" else "File",
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .clickable(enabled = enabled && entry.isDirectory, onClick = onOpen)
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (entry.isDirectory) {
                        "Directory"
                    } else {
                        "${formatBytes(entry.sizeBytes)} · ${entry.type}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onRename,
                enabled = enabled
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${entry.name}")
            }
            IconButton(
                onClick = onDelete,
                enabled = enabled
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${entry.name}")
            }
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

private fun displayPath(path: String): String = if (path.isEmpty()) {
    "WebRoot"
} else {
    "WebRoot/$path"
}

private fun parentPath(path: String): String = path.substringBeforeLast('/', "")

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}
