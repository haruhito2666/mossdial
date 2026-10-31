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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.runtime.LaunchedEffect
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
import com.mossdial.data.GitHubSiteImporter
import com.mossdial.data.ManagedFile
import com.mossdial.data.ServerSettings
import com.mossdial.data.SiteArchiveImporter
import com.mossdial.data.SiteInspector
import com.mossdial.data.SiteInspection
import com.mossdial.data.SiteKind
import com.mossdial.data.WebRoot
import com.mossdial.data.WebRootFileStore
import com.mossdial.service.ServerControl
import com.mossdial.service.ServerState
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.ButtonRow
import com.mossdial.ui.components.ChoiceChip
import com.mossdial.ui.components.ChipRow
import com.mossdial.ui.components.EmptyState
import com.mossdial.ui.components.ErrorNotice
import com.mossdial.ui.components.MicroLabelText
import com.mossdial.ui.components.Notice
import com.mossdial.ui.components.PageSection
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.RowDivider
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.theme.Control
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
    val settings = remember { ServerSettings(context) }
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
    var showGitHubDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ManagedFile?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ManagedFile?>(null) }
    var createName by rememberSaveable { mutableStateOf("") }
    var createContent by rememberSaveable { mutableStateOf("") }
    var githubRepository by rememberSaveable { mutableStateOf("") }
    var githubBranch by rememberSaveable { mutableStateOf("") }
    var hostingPath by remember { mutableStateOf(settings.sitePath) }
    var siteKind by remember { mutableStateOf(settings.siteKind) }
    var inspection by remember { mutableStateOf<SiteInspection?>(null) }
    val githubImporter = remember { GitHubSiteImporter(context.cacheDir) }
    val archiveImporter = remember { SiteArchiveImporter(context.cacheDir) }

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

    fun <T> runOperation(
        successMessage: String,
        after: (T) -> Unit = {},
        operation: () -> T
    ) {
        if (isBusy) return
        isBusy = true
        statusMessage = null
        errorMessage = null
        executor.execute {
            try {
                val result = operation()
                mainHandler.post {
                    after(result)
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

    /**
     * Makes [path] the served folder and rebuilds a running listener immediately.
     *
     * Changing the setting alone leaves the old root in the live socket, which is exactly how a
     * correctly imported site can still show the previous page. If the server is up, restart it
     * with the new root now; if it is not, the next Start reads the same stored value.
     */
    fun hostFolder(path: String) {
        settings.sitePath = path
        hostingPath = path
        errorMessage = null
        if (ServerState.snapshot().isActive) {
            ServerControl.restart(context, settings)
            statusMessage = "Hosting ${path.ifEmpty { "the web root" }}. Restarting the server."
        } else {
            statusMessage = "Hosting ${path.ifEmpty { "the web root" }}. Start the server to serve it."
        }
    }

    fun chooseSiteKind(kind: SiteKind) {
        siteKind = kind
        settings.siteKind = kind
        if (ServerState.snapshot().isActive) {
            ServerControl.restart(context, settings)
            statusMessage = "${kind.label} selected. Restarting the server."
        }
    }

    /**
     * Points the server at an imported folder and applies the type/base path the import detected.
     * Must be called on the main thread.
     */
    fun applyImportedSite(path: String, basePath: String, kind: SiteKind, summary: String) {
        settings.sitePath = path
        hostingPath = path
        settings.siteBasePath = basePath
        settings.siteKind = kind
        siteKind = kind
        if (ServerState.snapshot().isActive) {
            ServerControl.restart(context, settings)
            statusMessage = "$summary Restarting the server."
        } else {
            statusMessage = "$summary Start the server to serve it."
        }
    }

    /**
     * Imports a repository and immediately makes the detected folder the hosted site.
     *
     * Importing without hosting is how a correct import still produced a blank localhost page:
     * the files were there, but the server was still pointed at the web root.
     */
    fun importGitHubSite(repositoryText: String, branchText: String?) {
        if (isBusy) return
        isBusy = true
        statusMessage = null
        errorMessage = null
        val directory = currentPath
        executor.execute {
            try {
                val imported = githubImporter.import(store, directory, repositoryText, branchText)
                mainHandler.post {
                    isBusy = false
                    applyImportedSite(
                        path = imported.path,
                        basePath = imported.basePath,
                        kind = imported.siteKind,
                        summary = "Imported and hosting ${imported.path}."
                    )
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
                val isSiteArchive = name.endsWith(".zip", ignoreCase = true) ||
                    mimeType == "application/zip" ||
                    mimeType == "application/x-zip-compressed"
                input.use { stream ->
                    if (isSiteArchive) {
                        val imported = archiveImporter.import(store, directory, name, stream)
                        mainHandler.post {
                            isBusy = false
                            applyImportedSite(
                                path = imported.path,
                                basePath = imported.basePath,
                                kind = imported.siteKind,
                                summary = "Imported and hosting ${imported.path}."
                            )
                            refresh(currentPath)
                        }
                    } else {
                        store.importFile(directory, name, stream, mimeType)
                        mainHandler.post {
                            isBusy = false
                            statusMessage = "File imported."
                            refresh(currentPath)
                        }
                    }
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

    LaunchedEffect(hostingPath, entries) {
        val hosted = hostingPath
        val configuredBasePath = settings.siteBasePath
        runCatching {
            executor.execute {
                val root = if (hosted.isEmpty()) store.resolve("") else store.resolve(hosted)
                val result = root
                    ?.takeIf { it.isDirectory }
                    ?.let { SiteInspector.inspect(it, configuredBasePath) }
                mainHandler.post { inspection = result }
            }
        }
    }

    // The page's rhythm matches every other page: one inset section for the controls, a full-width
    // band, then the listing under the same gutter.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + Spacing.Snug
            )
    ) {
        PageSection(spacing = Spacing.Base) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MicroLabelText("Web root — served here")
                Spacer(modifier = Modifier.weight(1f))
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            // The breadcrumb replaces a back arrow and a path string side by side. Each segment is
            // its own target, so a deep path can be climbed without a back press per level.
            Breadcrumb(
                path = currentPath,
                enabled = !isBusy,
                onNavigate = { currentPath = it }
            )

            // Which folder the server actually serves. The user can keep several sites in the web
            // root and point the server at one without moving files around.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MicroLabelText("Hosted site")
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = hostingPath.ifEmpty { "Web root" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val canHostHere = currentPath.isNotEmpty() && currentPath != hostingPath
            val canHostRoot = hostingPath.isNotEmpty()
            if (canHostHere || canHostRoot) {
                ButtonRow {
                    if (canHostHere) {
                        PrimaryButton(
                            onClick = { hostFolder(currentPath) },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonContent(Icons.Default.Home, "Host this folder")
                        }
                    }
                    if (canHostRoot) {
                        SecondaryButton(
                            onClick = { hostFolder("") },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonContent(Icons.Default.FolderOpen, "Host the web root")
                        }
                    }
                }
            }
            SupportingText(
                text = "The server serves this folder. Takes effect on the next server start."
            )

            // The build shape decides how routes resolve. A SPA needs the index fallback; a static
            // export already has one file per route, and a plain site should 404 loudly.
            MicroLabelText("Site type")
            ChipRow {
                SiteKind.entries.forEach { kind ->
                    ChoiceChip(
                        label = kind.label,
                        selected = siteKind == kind,
                        onClick = { chooseSiteKind(kind) },
                        enabled = !isBusy
                    )
                }
            }
            SupportingText(siteKind.summary)

            // The server answers `/` with the folder's index page. Check the page and the files it
            // asks for before the user has to open a browser to discover a blank screen.
            val site = inspection
            when {
                site == null -> Unit
                !site.hasIndex -> {
                    Notice(
                        message = "This folder has no index.html or index.htm. " +
                            "The server will answer 404 on /."
                    )
                }
                site.indexIsEmpty -> {
                    Notice(message = "The index page is empty, so the browser will show a blank page.")
                }
            }
            if (site != null && site.missingAssets.isNotEmpty()) {
                val preview = site.missingAssets.take(3).joinToString(", ")
                ErrorNotice(
                    message = "${site.missingAssets.size} referenced file(s) are missing. " +
                        "The page may load unstyled or blank. First: $preview"
                )
            }
            if (site != null && site.looksUnbuilt) {
                ErrorNotice(
                    message = "This page loads framework source, not built output. " +
                        "Run the site's build and host its dist, out, or build folder."
                )
            }
            if (site != null && site.basePath.isNotEmpty()) {
                Notice(
                    message = "Detected base path /${site.basePath}. " +
                        "It will be removed automatically when the server starts."
                )
            }

            ButtonRow {
                PrimaryButton(
                    onClick = {
                        createName = "untitled.txt"
                        createContent = ""
                        showCreateTextDialog = true
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ButtonContent(Icons.AutoMirrored.Filled.NoteAdd, "Text file")
                }
            }
            ButtonRow {
                SecondaryButton(
                    onClick = {
                        createName = "new-folder"
                        showCreateDirectoryDialog = true
                    },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    ButtonContent(Icons.Default.CreateNewFolder, "Folder")
                }
                SecondaryButton(
                    onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    ButtonContent(Icons.Default.UploadFile, "Import")
                }
            }
            SupportingText(
                "A .zip of a built site is extracted and hosted automatically. " +
                    "Source zips are refused."
            )
            ButtonRow {
                SecondaryButton(
                    onClick = { showGitHubDialog = true },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ButtonContent(Icons.Default.CloudDownload, "GitHub site")
                }
            }

            val feedback = errorMessage ?: statusMessage
            if (feedback != null) {
                if (errorMessage != null) {
                    ErrorNotice(message = feedback)
                } else {
                    Notice(message = feedback)
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (!isLoading && entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Inset)
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    title = if (currentPath.isEmpty()) "No files yet" else "This folder is empty",
                    body = if (currentPath.isEmpty()) {
                        "Create a file, make a folder, or import one from this device."
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
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = Spacing.Inset,
                    end = Spacing.Inset,
                    top = Spacing.Snug,
                    bottom = Spacing.Snug
                )
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.relativePath }) { index, entry ->
                    if (index > 0) RowDivider()
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

    if (showGitHubDialog) {
        AlertDialog(
            onDismissRequest = { showGitHubDialog = false },
            title = { Text("GitHub site") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Base)) {
                    OutlinedTextField(
                        value = githubRepository,
                        onValueChange = { githubRepository = it },
                        label = { Text("Repository") },
                        supportingText = { Text("owner/repo or a github.com URL") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = githubBranch,
                        onValueChange = { githubBranch = it },
                        label = { Text("Branch or tag (optional)") },
                        singleLine = true
                    )
                    Text(
                        text = "Public repositories only. The folder that holds the site is " +
                            "detected when it can be.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val repositoryText = githubRepository
                        val branchText = githubBranch.trim().takeIf { it.isNotEmpty() }
                        showGitHubDialog = false
                        importGitHubSite(repositoryText, branchText)
                    },
                    enabled = !isBusy && githubRepository.isNotBlank()
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showGitHubDialog = false }) { Text("Cancel") }
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
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = Control
            )
            .padding(horizontal = Spacing.Snug, vertical = Spacing.Snug),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onNavigate(parentPath(path)) },
            enabled = enabled && path.isNotEmpty(),
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Go to the parent folder",
                modifier = Modifier.size(22.dp)
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
                        .padding(horizontal = Spacing.Snug, vertical = Spacing.Tight)
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
            .padding(vertical = Spacing.Base),
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
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.Gap)
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
                modifier = Modifier.size(22.dp)
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
                modifier = Modifier.size(22.dp)
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
        mimeType == "application/zip" || mimeType == "application/x-zip-compressed" -> ".zip"
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