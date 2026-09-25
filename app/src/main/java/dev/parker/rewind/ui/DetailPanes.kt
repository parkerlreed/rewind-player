package dev.parker.rewind.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.parker.rewind.Location
import dev.parker.rewind.MainViewModel
import dev.parker.rewind.Selection
import dev.parker.rewind.engine.Downloads
import dev.parker.rewind.engine.DownloadJob
import dev.parker.rewind.engine.TorrentEngine
import dev.parker.rewind.torrent.TorrentNode

@Composable
fun FileDetailPane(
    vm: MainViewModel,
    selection: Selection,
    showBack: Boolean,
    onBack: () -> Unit,
    onStream: (DownloadJob) -> Unit,
) {
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    val downloadRoot by vm.settings.downloadRoot.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == selection.jobId }
    val node = selection.node
    val kind = FileKind.of(node.name)
    val target = remember(selection, downloadRoot) { job?.target ?: vm.targetFor(selection) }
    val fmt = rememberSizeFormatter()

    DetailColumn(showBack = showBack, onBack = onBack) {
        IconBadge(
            kind.icon, size = 88.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(node.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "${fmt(node.size)} · ${kind.name.lowercase()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            InfoRow(Icons.Outlined.FolderZip, "In torrent",
                (listOf(selection.meta.name) + node.path.dropLast(1)).joinToString(" / "))
            InfoRow(Icons.Outlined.Download, "Saves to", target.absolutePath, mono = true)
        }

        if (job != null) JobProgressCard(job, fmt)

        Actions(
            job = job,
            streamable = kind.streamable,
            onDownload = { vm.download(selection, stream = false) },
            onStream = {
                if (job != null) onStream(job) else vm.download(selection, stream = true, onQueued = onStream)
            },
            onCancel = { Downloads.cancel(selection.jobId) },
            onRetry = {
                Downloads.cancel(selection.jobId)
                vm.download(selection, stream = job?.streaming == true)
            },
        )
    }
}

@Composable
private fun Actions(
    job: DownloadJob?,
    streamable: Boolean,
    onDownload: () -> Unit,
    onStream: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val full = Modifier.fillMaxWidth()
        when {
            job == null -> {
                Button(onClick = onDownload, modifier = full) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(ButtonDefaults.IconSize))
                    Text("Download", Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
                // Streaming only makes sense for audio/video; other files are just downloaded.
                if (streamable) {
                    FilledTonalButton(onClick = onStream, modifier = full) {
                        Icon(Icons.Outlined.PlayCircle, null, Modifier.size(ButtonDefaults.IconSize))
                        Text("Download & stream", Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
            job.state == DownloadJob.State.Error -> {
                Button(onClick = onRetry, modifier = full) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(ButtonDefaults.IconSize))
                    Text("Retry", Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
            job.state == DownloadJob.State.Complete -> {
                Button(onClick = onStream, modifier = full) {
                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(ButtonDefaults.IconSize))
                    Text("Open with…", Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
            else -> {
                if (streamable) {
                    FilledTonalButton(onClick = onStream, modifier = full) {
                        Icon(Icons.Outlined.PlayCircle, null, Modifier.size(ButtonDefaults.IconSize))
                        Text(if (job.streaming) "Open stream" else "Stream now", Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
                OutlinedButton(onClick = onCancel, modifier = full) {
                    Icon(Icons.Outlined.Close, null, Modifier.size(ButtonDefaults.IconSize))
                    Text("Cancel download", Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

@Composable
fun JobProgressCard(job: DownloadJob, fmt: (Long) -> String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.state == DownloadJob.State.Starting || job.state == DownloadJob.State.Checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(job.statusLine(fmt), style = MaterialTheme.typography.bodyMedium)
            if (job.active && job.webSeedError != null) {
                Text(
                    "Web seed: ${job.webSeedError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (job.streaming && job.active) {
                Text(
                    "Streaming · pieces near the playhead download first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
internal fun InfoRow(icon: ImageVector, label: String, value: String, mono: Boolean = false) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        overlineContent = { Text(label) },
        headlineContent = {
            Text(
                value,
                style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                else MaterialTheme.typography.bodyMedium,
            )
        },
    )
}

/** Shown atop the list at a torrent's root. */
@Composable
fun TorrentHeader(loc: Location.Torrent) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.FolderZip, container = scheme.secondary, content = scheme.onSecondary)
            Column(Modifier.padding(start = 16.dp)) {
                Text(loc.meta.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${loc.meta.fileCount} files · ${formatSize(loc.meta.totalSize)} · ${loc.file.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Detail pane for a torrent with nothing selected: a summary and shortcuts to its biggest files. */
@Composable
fun TorrentSummaryPane(loc: Location.Torrent, onSelect: (TorrentNode.File) -> Unit) {
    val largest = remember(loc.meta) {
        buildList {
            fun walk(d: TorrentNode.Dir) {
                d.children.forEach { if (it is TorrentNode.Dir) walk(it) else add(it as TorrentNode.File) }
            }
            walk(loc.meta.root)
        }.sortedByDescending { it.size }.take(6)
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
                        headlineContent = { Text(f.name, maxLines = 1) },
                        supportingContent = {
                            Text(
                                (f.path.dropLast(1).joinToString(" / ").ifEmpty { "Top level" }) + " · " + formatSize(f.size),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }
    }
}
