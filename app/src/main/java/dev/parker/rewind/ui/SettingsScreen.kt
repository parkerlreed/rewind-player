package dev.parker.rewind.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.parker.rewind.AppSettings
import dev.parker.rewind.archive.ArchiveViewModel
import dev.parker.rewind.BuildConfig
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, archiveVm: ArchiveViewModel) {
    val saved by archiveVm.saved.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(archiveVm::exportTo)
    }
    // Accept anything: exports are JSON, but hand-made URL lists may be .txt and some pickers mislabel types.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(archiveVm::importFrom)
    }
    val root by settings.downloadRoot.collectAsStateWithLifecycle()
    val showHidden by settings.showHidden.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                SectionHeader("Downloads")
                ListItem(
                    modifier = Modifier.clickable { editing = true },
                    leadingContent = { Icon(Icons.Outlined.Folder, null) },
                    headlineContent = { Text("Download folder") },
                    supportingContent = {
                        Text("${root.absolutePath}\nFiles go in <folder>/<torrent name>/<path inside torrent>")
                    },
                )
                SectionHeader("Streaming")
                val context = LocalContext.current
                AppSettings.PlayerKind.entries.forEach { kind ->
                    val player by settings.player(kind).collectAsStateWithLifecycle()
                    val label = remember(player) { playerLabel(context, player) }
                    ListItem(
                        leadingContent = {
                            Icon(if (kind == AppSettings.PlayerKind.Video) Icons.Outlined.Movie else Icons.Outlined.Audiotrack, null)
                        },
                        headlineContent = { Text(kind.label) },
                        supportingContent = { Text(label ?: "Ask the first time, then remember") },
                        trailingContent = if (player != null) {
                            { TextButton(onClick = { settings.setPlayer(kind, null) }) { Text("Reset") } }
                        } else null,
                    )
                }
                SectionHeader("Archive items")
                ListItem(
                    modifier = Modifier.clickable(enabled = saved.isNotEmpty()) { exportLauncher.launch("rewind-archive-items.json") },
                    leadingContent = { Icon(Icons.Outlined.FileUpload, null) },
                    headlineContent = { Text("Export list") },
                    supportingContent = {
                        Text(if (saved.isEmpty()) "No saved items yet" else "Save your ${saved.size} items to a file")
                    },
                )
                ListItem(
                    modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    leadingContent = { Icon(Icons.Outlined.FileDownload, null) },
                    headlineContent = { Text("Import list") },
                    supportingContent = {
                        Text("Adds items from a Rewind export, or a text file with one archive.org URL per line. Existing items are kept.")
                    },
                )
                SectionHeader("Browser")
                ListItem(
                    modifier = Modifier.clickable { settings.setShowHidden(!showHidden) },
                    leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) },
                    headlineContent = { Text("Show hidden folders") },
                    trailingContent = { Switch(checked = showHidden, onCheckedChange = settings::setShowHidden) },
                )
                SectionHeader("About")
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    headlineContent = { Text("Rewind ${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text("Powered by libtorrent via libtorrent4j") },
                )
            }
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(root.absolutePath) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Download folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Path") })
                    TextButton(onClick = { text = AppSettings.defaultDownloadRoot.absolutePath }) { Text("Reset to default") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = File(text.trim())
                    settings.setDownloadRoot(if (dir == AppSettings.defaultDownloadRoot) null else dir)
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}
