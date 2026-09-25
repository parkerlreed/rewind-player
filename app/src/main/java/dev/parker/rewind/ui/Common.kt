package dev.parker.rewind.ui

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.parker.rewind.AppSettings
import dev.parker.rewind.PlayerChosenReceiver
import dev.parker.rewind.engine.DownloadJob
import dev.parker.rewind.engine.StreamServer
import java.io.File

enum class FileKind(val icon: ImageVector) {
    Video(Icons.Outlined.Movie),
    Audio(Icons.Outlined.Audiotrack),
    Image(Icons.Outlined.Image),
    Subtitle(Icons.Outlined.Subtitles),
    Text(Icons.Outlined.Description),
    Archive(Icons.Outlined.Archive),
    Other(Icons.AutoMirrored.Outlined.InsertDriveFile);

    /** Players can only do something useful with media. Everything else can still be streamed if the user insists. */
    val streamable: Boolean get() = this == Video || this == Audio

    companion object {
        fun of(name: String): FileKind {
            val mime = StreamServer.mimeTypeFor(name)
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("video/") -> Video
                mime.startsWith("audio/") -> Audio
                mime.startsWith("image/") -> Image
                ext in setOf("srt", "ass", "ssa", "sub", "vtt", "idx") -> Subtitle
                mime.startsWith("text/") || ext in setOf("nfo", "md", "pdf") -> Text
                ext in setOf("zip", "rar", "7z", "tar", "gz", "xz", "iso") -> Archive
                else -> Other
            }
        }
    }
}

@Composable
fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
fun rememberSizeFormatter(): (Long) -> String {
    val context = LocalContext.current
    return remember(context) { { bytes: Long -> Formatter.formatShortFileSize(context, bytes) } }
}

/**
 * Scrolling, centred column for detail panes. No app bar: content starts right below the status
 * bar. On single-pane layouts a back button is the first row and scrolls away with the content.
 */
@Composable
fun DetailColumn(showBack: Boolean, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showBack) {
                Box(Modifier.fillMaxWidth()) {
                    // Pull the icon's touch padding into the gutter so the arrow lines up with the content edge.
                    IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            }
            content()
        }
    }
}

/** Tinted circular icon used as the leading element of list rows and headers. */
@Composable
fun IconBadge(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 40.dp,
) {
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(size)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(size / 4))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(
            icon, size = 72.dp,
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Play or open [job]. Finished downloads open the file on disk; in-progress ones stream (HTTP jobs
 * straight from the server, torrents through the local stream server).
 */
fun launchPlayer(context: Context, job: DownloadJob): Boolean =
    if (job.state == DownloadJob.State.Complete && job.target.isFile) {
        openLocal(context, job.target)
    } else {
        launchPlayer(context, job.remoteUrl?.let(Uri::parse) ?: StreamServer.urlFor(job), job.fileName)
    }

/** Open a file on disk via FileProvider, in the remembered player or a chooser. */
fun openLocal(context: Context, file: File): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return launchPlayer(context, uri, file.name, grantRead = true)
}

/**
 * Open [uri] in the remembered player, or ask once and remember the pick. The system chooser has
 * no "always" option, and the regular resolver's "always" would change the default for every app,
 * so the choice is kept in this app's settings instead (reset it under Settings > Player).
 */
fun launchPlayer(context: Context, uri: Uri, fileName: String, grantRead: Boolean = false): Boolean {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, StreamServer.mimeTypeFor(fileName))
        .putExtra(Intent.EXTRA_TITLE, fileName)
        .putExtra("title", fileName) // mpv, VLC and friends read this
    if (grantRead) {
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Lets the chooser carry the grant through to whichever app is picked.
        view.clipData = ClipData.newRawUri(fileName, uri)
    }
    val settings = AppSettings.get(context)
    val mime = view.type.orEmpty()
    // Only audio and video get a remembered app; subtitles, text etc. always go through the chooser.
    val kind = when {
        mime.startsWith("video/") -> AppSettings.PlayerKind.Video
        mime.startsWith("audio/") -> AppSettings.PlayerKind.Audio
        else -> null
    }

    kind?.let { settings.player(it).value }?.let { player ->
        // Only use it if it actually declares support for this URI + type.
        val handles = context.packageManager.queryIntentActivities(Intent(view).setPackage(player.packageName), 0)
            .any { it.activityInfo.name == player.className }
        if (handles) {
            try {
                context.startActivity(Intent(view).setComponent(player))
                return true
            } catch (_: ActivityNotFoundException) {
                settings.setPlayer(kind, null)
            } catch (_: SecurityException) {
                settings.setPlayer(kind, null)
            }
        }
    }

    val verb = if (grantRead) "Open" else "Stream"
    val chooser = if (kind != null) {
        val chosen = PendingIntent.getBroadcast(
            context, kind.ordinal,
            Intent(context, PlayerChosenReceiver::class.java).putExtra(PlayerChosenReceiver.EXTRA_KIND, kind.name),
            // Mutable so the system can attach EXTRA_CHOSEN_COMPONENT; explicit intent, so this is safe.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        Intent.createChooser(view, "$verb with… (remembered for ${kind.name.lowercase()}, change in Settings)", chosen.intentSender)
    } else {
        Intent.createChooser(view, "$verb $fileName with…")
    }
    // Nothing installed can take it: say so rather than showing an empty chooser.
    if (context.packageManager.queryIntentActivities(view, 0).isEmpty()) return false
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Human name of the remembered player, or null if none/uninstalled. */
fun playerLabel(context: Context, component: ComponentName?): String? = component?.let {
    runCatching { context.packageManager.getActivityInfo(it, 0).loadLabel(context.packageManager).toString() }.getOrNull()
}

fun DownloadJob.statusLine(sizeFormatter: (Long) -> String): String = when (state) {
    DownloadJob.State.Starting -> if (remoteUrl != null) "Queued…" else "Finding peers…"
    DownloadJob.State.Checking -> "Checking existing data…"
    DownloadJob.State.Downloading ->
        "${sizeFormatter(downloaded)} of ${sizeFormatter(size)} · ${sizeFormatter(rateBytes.toLong())}/s" +
            when {
                remoteUrl != null -> ""
                peers == 1 -> " · 1 peer"
                else -> " · $peers peers"
            }
    DownloadJob.State.Complete -> "Complete · ${sizeFormatter(size)}"
    DownloadJob.State.Error -> error ?: "Error"
}
