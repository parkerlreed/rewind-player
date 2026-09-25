package dev.parker.rewind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.parker.rewind.engine.Downloads
import dev.parker.rewind.engine.DownloadJob
import dev.parker.rewind.engine.TorrentEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onStream: (DownloadJob) -> Unit) {
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    val fmt = rememberSizeFormatter()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    if (jobs.any { !it.active }) {
                        IconButton(onClick = { Downloads.clearFinished() }) {
                            Icon(Icons.Outlined.ClearAll, contentDescription = "Clear finished")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            EmptyState(
                Icons.Outlined.CloudDownload, "Nothing downloading",
                "Files you download or stream from a torrent show up here.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        // Cards reflow into columns on unfolded / wide screens.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(jobs, key = { it.id }) { job -> JobCard(job, fmt, onStream) }
        }
    }
}

@Composable
private fun JobCard(job: DownloadJob, fmt: (Long) -> String, onStream: (DownloadJob) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(FileKind.of(job.fileName).icon)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(job.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        job.torrentName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (job.state) {
                DownloadJob.State.Starting, DownloadJob.State.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                job.statusLine(fmt), style = MaterialTheme.typography.bodySmall,
                color = if (job.state == DownloadJob.State.Error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (job.active && job.webSeedError != null) {
                Text(
                    "Web seed: ${job.webSeedError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                if (job.active) {
                    TextButton(onClick = { Downloads.cancel(job.id) }) {
                        Icon(Icons.Outlined.Close, null)
                        Text("Cancel", Modifier.padding(start = 8.dp))
                    }
                }
                // In-progress files can only be streamed if they're audio/video; finished ones can always be opened.
                val canStream = FileKind.of(job.fileName).streamable
                if (job.state == DownloadJob.State.Complete || (job.active && canStream)) {
                    FilledTonalButton(onClick = { onStream(job) }) {
                        Icon(Icons.Outlined.PlayCircle, null)
                        Text(if (job.active) "Stream" else "Open", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
