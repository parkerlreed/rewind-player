package dev.parker.rewind.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.parker.rewind.BrowserEntry
import dev.parker.rewind.Location
import dev.parker.rewind.MainViewModel
import dev.parker.rewind.engine.Downloads
import dev.parker.rewind.engine.DownloadJob
import dev.parker.rewind.engine.TorrentEngine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun BrowseScreen(vm: MainViewModel, onStream: (DownloadJob) -> Unit) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(scaffoldDirective = rememberFoldableDirective())
    val scope = rememberCoroutineScope()

    val location by vm.location.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val twoPane = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded

    // Leaving a folder (or the selection being cleared) closes the detail pane on single-pane layouts.
    LaunchedEffect(selection) {
        if (selection == null && navigator.canNavigateBack()) navigator.navigateBack()
    }

    BackHandler(enabled = navigator.canNavigateBack() || vm.canGoUp || selection != null) {
        when {
            navigator.canNavigateBack() -> {
                vm.clearSelection()
                scope.launch { navigator.navigateBack() }
            }
            selection != null && twoPane -> vm.clearSelection()
            else -> vm.goUp()
        }
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                BrowserList(
                    vm = vm,
                    location = location,
                    selectedPath = selection?.node?.path.takeIf { twoPane },
                    onOpen = { entry ->
                        vm.open(entry)
                        if (entry is BrowserEntry.TorrentFile) {
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                        }
                    },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val sel = selection
                val loc = location
                when {
                    sel != null -> FileDetailPane(
                        vm = vm,
                        selection = sel,
                        showBack = !twoPane,
                        onBack = {
                            vm.clearSelection()
                            scope.launch { navigator.navigateBack() }
                        },
                        onStream = onStream,
                    )
                    loc is Location.Torrent -> TorrentSummaryPane(loc) { file ->
                        vm.select(loc, file)
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                    }
                    else -> EmptyState(
                        Icons.Outlined.TouchApp,
                        "Open a torrent",
                        "Tap a .torrent file to browse it like a folder, then pick a file to download or stream.",
                    )
                }
            }
        },
    )
}

/**
 * The default directive only splits at "expanded" widths. A book-posture foldable (~700dp) is
 * medium, and that's exactly where list + detail shines, so allow two panes from medium up.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberFoldableDirective(): PaneScaffoldDirective {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    return remember(adaptiveInfo) {
        val base = calculatePaneScaffoldDirective(adaptiveInfo)
        val wide = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
        if (wide) base.copy(maxHorizontalPartitions = 2) else base
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserList(
    vm: MainViewModel,
    location: Location,
    selectedPath: List<String>?,
    onOpen: (BrowserEntry) -> Unit,
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    // One list state per folder, restored from the view model so "up" returns to the same spot.
    val listState = remember(location) {
        val (index, offset) = vm.scrollPositions[location] ?: (0 to 0)
        LazyListState(index, offset)
    }
    DisposableEffect(location) {
        onDispose { vm.scrollPositions[location] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(vm.labelFor(location), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (vm.canGoUp) {
                        IconButton(onClick = { vm.goUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    }
                },
                actions = { StorageMenu(vm) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp),
        ) {
            item(key = "crumbs") { Breadcrumbs(vm) }
            if (location is Location.Torrent && location.path.isEmpty()) {
                item(key = "header") { TorrentHeader(location) }
            }
            val list = entries
            when {
                list == null -> item(key = "loading") {
                    CircularProgressIndicator(Modifier.padding(48.dp).size(40.dp))
                }
                list.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        Icons.Outlined.FolderOff,
                        if (location is Location.Torrent) "Empty folder" else "No folders or torrents here",
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
                else -> items(list, key = { it.key() }) { entry ->
                    EntryRow(
                        entry = entry,
                        selected = entry is BrowserEntry.TorrentFile && entry.node.path == selectedPath,
                        job = (entry as? BrowserEntry.TorrentFile)?.let { e ->
                            (location as? Location.Torrent)?.let { jobs.firstOrNull { j -> j.id == TorrentEngine.jobId(it.file, e.node) } }
                        },
                        onClick = { onOpen(entry) },
                    )
                }
            }
        }
    }
}

private fun BrowserEntry.key(): String = when (this) {
    is BrowserEntry.FsDir -> "d:" + file.path
    is BrowserEntry.FsTorrent -> "t:" + file.path
    is BrowserEntry.TorrentDir -> "td:" + node.path.joinToString("/")
    is BrowserEntry.TorrentFile -> "tf:" + node.path.joinToString("/")
}

@Composable
private fun EntryRow(entry: BrowserEntry, selected: Boolean, job: DownloadJob?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fmt = rememberSizeFormatter()
    val (icon, supporting) = when (entry) {
        is BrowserEntry.FsDir -> Icons.Outlined.Folder to null
        is BrowserEntry.FsTorrent -> Icons.Outlined.FolderZip to formatSize(entry.file.length()) + " · torrent"
        is BrowserEntry.TorrentDir -> Icons.Outlined.Folder to
            "${entry.node.fileCount} files · ${formatSize(entry.node.size)}"
        is BrowserEntry.TorrentFile -> FileKind.of(entry.name).icon to formatSize(entry.node.size)
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { s -> { Text(job?.statusLine(fmt) ?: s, maxLines = 1) } },
        leadingContent = {
            if (entry is BrowserEntry.FsTorrent) {
                IconBadge(icon, container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
            } else {
                IconBadge(icon)
            }
        },
        trailingContent = when {
            job?.state == DownloadJob.State.Complete -> { { Icon(Icons.Outlined.CheckCircle, "Downloaded", tint = scheme.primary) } }
            job != null -> { { CircularProgressIndicator(progress = { job.progress }, modifier = Modifier.size(24.dp)) } }
            entry is BrowserEntry.FsTorrent || entry is BrowserEntry.TorrentDir ->
                { { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) } }
            else -> null
        },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = scheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
    )
}

@Composable
private fun Breadcrumbs(vm: MainViewModel) {
    val crumbs = vm.crumbs
    val state = rememberLazyListState()
    LaunchedEffect(crumbs.size) { state.scrollToItem((crumbs.size - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { i, crumb ->
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
                )
            }
            val last = i == crumbs.lastIndex
            FilterChip(
                selected = last,
                onClick = { if (!last) vm.navigate(crumb.location) },
                label = { Text(crumb.label, maxLines = 1) },
                leadingIcon = if (crumb.location is Location.Torrent && (crumb.location as Location.Torrent).path.isEmpty()) {
                    { Icon(Icons.Outlined.FolderZip, null, Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

@Composable
private fun StorageMenu(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.SdStorage, contentDescription = "Storage") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        vm.volumes.forEach { (label, dir) ->
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(Icons.Outlined.SdStorage, null) },
                onClick = { open = false; vm.navigate(Location.Fs(dir)) },
            )
        }
        val downloads = vm.settings.downloadRoot.value
        DropdownMenuItem(
            text = { Text("Download folder") },
            leadingIcon = { Icon(Icons.Outlined.Download, null) },
            onClick = {
                open = false
                downloads.mkdirs()
                vm.navigate(Location.Fs(downloads))
            },
        )
    }
}
