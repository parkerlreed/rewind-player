package dev.parker.rewind.ui

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.parker.rewind.archive.ArchiveFile
import dev.parker.rewind.archive.ArchiveItem
import dev.parker.rewind.archive.ArchiveViewModel
import dev.parker.rewind.archive.FileSource
import dev.parker.rewind.archive.OpenItem
import dev.parker.rewind.archive.SavedItem
import dev.parker.rewind.archive.SavedSort
import dev.parker.rewind.engine.DownloadJob
import dev.parker.rewind.engine.Downloads
import dev.parker.rewind.torrent.TorrentNode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ArchiveScreen(vm: ArchiveViewModel, onStream: (DownloadJob) -> Unit, onStreamUrl: (Uri, String) -> Unit) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(scaffoldDirective = rememberFoldableDirective())
    val scope = rememberCoroutineScope()
    val open by vm.open.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val twoPane = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded

    LaunchedEffect(selection) {
        if (selection == null && navigator.canNavigateBack()) navigator.navigateBack()
    }

    BackHandler(enabled = navigator.canNavigateBack() || open != null) {
        when {
            navigator.canNavigateBack() -> {
                vm.clearSelection()
                scope.launch { navigator.navigateBack() }
            }
            selection != null && twoPane -> vm.clearSelection()
            else -> vm.goUp()
        }
    }

    val showDetail: () -> Unit = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) } }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                val o = open
                if (o == null) SavedItemsList(vm) else ItemFileList(vm, o, selection?.path.takeIf { twoPane }) { path ->
                    vm.select(path)
                    showDetail()
                }
            }
        },
        detailPane = {
            AnimatedPane {
                val o = open
                val sel = selection
                when {
                    o != null && sel != null -> ArchiveFileDetail(
                        vm, o.item, sel,
                        showBack = !twoPane,
                        onBack = {
                            vm.clearSelection()
                            scope.launch { navigator.navigateBack() }
                        },
                        onStream = onStream,
                        onStreamUrl = onStreamUrl,
                    )
                    o != null -> ItemSummary(vm, o.item) { f ->
                        vm.select(f.path)
                        showDetail()
                    }
                    else -> EmptyState(
                        Icons.Outlined.AccountBalance,
                        "Browse archive.org items",
                        "Add an item by its URL or identifier. Its file list comes live from archive.org, " +
                            "so it's always current, unlike the item's .torrent.",
                    )
                }
            }
        },
    )
}

// ---- Saved items ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedItemsList(vm: ArchiveViewModel) {
    val items by vm.saved.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val saved = remember(items, sort) { vm.sorted(items, sort) }
    val listState = rememberLazyListState()
    // Rows are keyed, so a re-sort would otherwise keep the old first-visible item in view and leave
    // you partway down. Jump to the top on every sort change (but not on first composition).
    var sortedFor by remember { mutableStateOf(sort) }
    LaunchedEffect(sort) {
        if (sort != sortedFor) {
            listState.scrollToItem(0)
            sortedFor = sort
        }
    }
    val loading by vm.loading.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<SavedItem?>(null) }

    // Same scroll-tinted top bar as the other lists.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Archive") }, actions = { SortMenu(vm) }, scrollBehavior = scrollBehavior) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Add item") },
            )
        },
    ) { padding ->
        if (saved.isEmpty()) {
            EmptyState(
                Icons.Outlined.AccountBalance,
                "No items yet",
                "Tap Add item and paste an archive.org link, e.g. archive.org/details/<identifier>. " +
                    "You can also share a link to this app from your browser.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(saved, key = { it.identifier }) { item ->
                    ListItem(
                        modifier = Modifier.clickable { vm.openSaved(item.identifier) },
                        leadingContent = { IconBadge(mediatypeIcon(item.mediatype)) },
                        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(item.identifier, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            if (loading == item.identifier) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            } else {
                                IconButton(onClick = { confirmRemove = item }) { Icon(Icons.Outlined.Delete, "Remove") }
                            }
                        },
                    )
                }
            }
        }
    }

    if (adding) AddItemDialog(vm, onDismiss = { adding = false })
    confirmRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove item?") },
            text = { Text("\"${item.title}\" will be removed from this list. Downloaded files stay on your device.") },
            confirmButton = { TextButton(onClick = { vm.remove(item.identifier); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SortMenu(vm: ArchiveViewModel) {
    val sort by vm.sort.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SavedSort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = { vm.setSort(option); open = false },
                trailingIcon = if (option == sort) {
                    { Icon(Icons.Outlined.Check, contentDescription = "Selected") }
                } else null,
            )
        }
    }
}

@Composable
private fun AddItemDialog(vm: ArchiveViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val submit = {
        scope.launch {
            busy = true
            error = vm.add(text)
            busy = false
            if (error == null) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Outlined.AccountBalance, null) },
        title = { Text("Add archive.org item") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                label = { Text("Item URL or identifier") },
                placeholder = { Text("archive.org/details/…") },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it; error = null }
                    }) { Icon(Icons.Outlined.ContentPaste, "Paste") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !busy && text.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

// ---- Inside an item ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemFileList(vm: ArchiveViewModel, open: OpenItem, selectedPath: List<String>?, onSelect: (List<String>) -> Unit) {
    val context = LocalContext.current
    val show by vm.show.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    val fmt = rememberSizeFormatter()
    val item = open.item
    // Recomputed when filters change (the view model caches per filter combination).
    val dir = remember(open, show) { vm.dirAt(open) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(open.path.lastOrNull() ?: item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.goUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                },
                actions = {
                    if (loading == item.identifier) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                    } else {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh file list") }
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.detailsUrl)))
                    }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open on archive.org") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp),
        ) {
            item(key = "crumbs") { ItemBreadcrumbs(vm, open) }
            if (open.path.isEmpty()) item(key = "header") { ItemHeader(item) }
            item(key = "filters") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Each chip means "show only this kind"; with none selected, everything is listed.
                    FileSource.entries.forEach { source ->
                        FilterChip(
                            selected = source in show,
                            onClick = { vm.toggleShow(source) },
                            label = { Text(source.label) },
                            leadingIcon = if (source in show) {
                                { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                        )
                    }
                }
            }
            if (dir.children.isEmpty()) {
                item(key = "empty") {
                    EmptyState(Icons.Outlined.FolderOff, "Nothing here with these filters", modifier = Modifier.padding(top = 32.dp))
                }
            }
            items(dir.children, key = { (if (it is TorrentNode.Dir) "d:" else "f:") + it.path.joinToString("/") }) { node ->
                when (node) {
                    is TorrentNode.Dir -> ListItem(
                        modifier = Modifier.clickable { vm.navigate(node.path) },
                        leadingContent = { IconBadge(Icons.Outlined.Folder) },
                        headlineContent = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${node.fileCount} files · ${fmt(node.size)}") },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    )
                    is TorrentNode.File -> {
                        val file = item.fileAt(node.path)
                        val job = file?.let { f -> jobs.firstOrNull { it.id == vm.jobId(item, f) } }
                        ArchiveFileRow(node, file, job, selected = node.path == selectedPath, fmt = fmt) { onSelect(node.path) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveFileRow(
    node: TorrentNode.File,
    file: ArchiveFile?,
    job: DownloadJob?,
    selected: Boolean,
    fmt: (Long) -> String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val details = listOfNotNull(fmt(node.size), file?.format, file?.source?.takeIf { it != "original" }).joinToString(" · ")
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { IconBadge(FileKind.of(node.name).icon) },
        headlineContent = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(job?.statusLine(fmt) ?: details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = when {
            file?.private == true -> { { Icon(Icons.Outlined.Lock, "Private", tint = scheme.onSurfaceVariant) } }
            job?.state == DownloadJob.State.Complete -> { { Icon(Icons.Outlined.CheckCircle, "Downloaded", tint = scheme.primary) } }
            job != null && job.active -> { { CircularProgressIndicator(progress = { job.progress }, modifier = Modifier.size(24.dp)) } }
            else -> null
        },
        colors = if (selected) ListItemDefaults.colors(containerColor = scheme.secondaryContainer) else ListItemDefaults.colors(),
    )
}

@Composable
private fun ItemBreadcrumbs(vm: ArchiveViewModel, open: OpenItem) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crumbs = listOf(open.item.identifier) + open.path
        FilterChip(selected = false, onClick = { vm.close() }, label = { Text("Archive") },
            leadingIcon = { Icon(Icons.Outlined.AccountBalance, null, Modifier.size(18.dp)) })
        crumbs.forEachIndexed { i, label ->
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            val last = i == crumbs.lastIndex
            FilterChip(
                selected = last,
                onClick = { if (!last) vm.navigate(open.path.take(i)) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

@Composable
private fun ItemHeader(item: ArchiveItem) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(mediatypeIcon(item.mediatype), container = scheme.tertiary, content = scheme.onTertiary)
            Column(Modifier.padding(start = 16.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                val by = listOfNotNull(item.creator, item.date).joinToString(" · ")
                if (by.isNotEmpty()) Text(by, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.files.size} files · ${formatSize(item.totalSize)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Detail pane when an item is open but no file is picked: its biggest originals. */
@Composable
private fun ItemSummary(vm: ArchiveViewModel, item: ArchiveItem, onSelect: (ArchiveFile) -> Unit) {
    val show by vm.show.collectAsStateWithLifecycle()
    val largest = remember(item, show) {
        item.files.filter { it.visible(show) }
            .sortedByDescending { it.size }.take(6)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Largest files", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick a file on the left, or jump straight to one of these.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                largest.forEach { f ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(f) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        leadingContent = { IconBadge(FileKind.of(f.name).icon) },
                        headlineContent = { Text(f.path.last(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(listOfNotNull(formatSize(f.size), f.format).joinToString(" · "), maxLines = 1)
                        },
                    )
                }
            }
        }
    }
}

// ---- File detail ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveFileDetail(
    vm: ArchiveViewModel,
    item: ArchiveItem,
    file: ArchiveFile,
    showBack: Boolean,
    onBack: () -> Unit,
    onStream: (DownloadJob) -> Unit,
    onStreamUrl: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    val downloadRoot by vm.settings.downloadRoot.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == vm.jobId(item, file) }
    val kind = FileKind.of(file.name)
    val target = remember(item, file, downloadRoot) { vm.targetFor(item, file) }
    val onDevice = remember(target, job?.state) { job == null && target.isFile && target.length() == file.size }
    val fmt = rememberSizeFormatter()
    val name = file.path.last()

    DetailColumn(showBack = showBack, onBack = onBack) {
        IconBadge(
            kind.icon, size = 88.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            listOfNotNull(fmt(file.size), file.format, file.lengthSeconds?.let(::formatDuration)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            InfoRow(Icons.Outlined.AccountBalance, "In item", (listOf(item.title) + file.path.dropLast(1)).joinToString(" / "))
            InfoRow(Icons.Outlined.Inventory2, "Source", file.source ?: "unknown")
            InfoRow(Icons.Outlined.Download, if (onDevice) "Already on this device" else "Saves to", target.absolutePath, mono = true)
            file.md5?.let { InfoRow(Icons.Outlined.Fingerprint, "MD5", it, mono = true) }
        }

        if (job != null) JobProgressCard(job, fmt)

        if (file.private) {
            Text(
                "This file is private on archive.org and needs a login to download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            ArchiveActions(
                job = job,
                onDevice = onDevice,
                streamable = kind.streamable,
                onDownload = { vm.download(item, file, stream = false) },
                onDownloadAndStream = { vm.download(item, file, stream = true, onQueued = onStream) },
                onStreamOnly = { onStreamUrl(Uri.parse(item.downloadUrl(file)), name) },
                onOpenLocal = { if (!openLocal(context, target)) vm.message("No app can open $name") },
                onStreamJob = { job?.let(onStream) },
                onCancel = { job?.let { Downloads.cancel(it.id) } },
                onRetry = { vm.download(item, file, stream = job?.streaming == true) },
            )
        }
    }
}

@Composable
private fun ArchiveActions(
    job: DownloadJob?,
    onDevice: Boolean,
    streamable: Boolean,
    onDownload: () -> Unit,
    onDownloadAndStream: () -> Unit,
    onStreamOnly: () -> Unit,
    onOpenLocal: () -> Unit,
    onStreamJob: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val full = Modifier.fillMaxWidth()
        @Composable
        fun Label(icon: ImageVector, text: String) {
            Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
            Text(text, Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        when {
            job == null || job.state == DownloadJob.State.Error -> {
                if (job != null) {
                    Button(onClick = onRetry, modifier = full) { Label(Icons.Outlined.Refresh, "Retry (resumes)") }
                } else if (onDevice) {
                    Button(onClick = onOpenLocal, modifier = full) { Label(Icons.AutoMirrored.Outlined.OpenInNew, "Open with…") }
                    OutlinedButton(onClick = onDownload, modifier = full) { Label(Icons.Outlined.Download, "Download again") }
                    return@Column
                } else {
                    Button(onClick = onDownload, modifier = full) { Label(Icons.Outlined.Download, "Download") }
                    if (streamable) {
                        FilledTonalButton(onClick = onDownloadAndStream, modifier = full) {
                            Label(Icons.Outlined.PlayCircle, "Download & stream")
                        }
                    }
                }
                // Streaming only makes sense for audio/video; other files are just downloaded.
                if (streamable) {
                    OutlinedButton(onClick = onStreamOnly, modifier = full) { Label(Icons.Outlined.Stream, "Stream without downloading") }
                }
            }
            job.state == DownloadJob.State.Complete -> {
                Button(onClick = onStreamJob, modifier = full) { Label(Icons.AutoMirrored.Outlined.OpenInNew, "Open with…") }
            }
            else -> {
                if (streamable) {
                    FilledTonalButton(onClick = onStreamJob, modifier = full) { Label(Icons.Outlined.PlayCircle, "Stream") }
                }
                OutlinedButton(onClick = onCancel, modifier = full) { Label(Icons.Outlined.Close, "Cancel download") }
            }
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val s = seconds.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun mediatypeIcon(mediatype: String?): ImageVector = when (mediatype) {
    "movies" -> Icons.Outlined.Movie
    "audio", "etree" -> Icons.Outlined.Audiotrack
    "texts" -> Icons.AutoMirrored.Outlined.MenuBook
    "image" -> Icons.Outlined.Image
    "software" -> Icons.Outlined.Memory
    else -> Icons.Outlined.AccountBalance
}

