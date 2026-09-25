package dev.parker.rewind.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import dev.parker.rewind.Destination
import dev.parker.rewind.MainViewModel
import dev.parker.rewind.archive.ArchiveViewModel
import dev.parker.rewind.engine.Downloads
import dev.parker.rewind.engine.DownloadJob
import dev.parker.rewind.engine.TorrentEngine

private data class NavItem(val dest: Destination, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val navItems = listOf(
    NavItem(Destination.Archive, "Archive", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    NavItem(Destination.Browse, "Browse", Icons.Outlined.Folder, Icons.Filled.Folder),
    NavItem(Destination.Downloads, "Downloads", Icons.Outlined.Download, Icons.Filled.Download),
    NavItem(Destination.Settings, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun App(vm: MainViewModel, archiveVm: ArchiveViewModel) {
    val context = LocalContext.current
    var hasStorage by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = Environment.isExternalStorageManager()
        if (now && !hasStorage) vm.refresh()
        hasStorage = now
    }

    if (!hasStorage) {
        PermissionScreen {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        }
        return
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askForNotifications = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val destination by vm.destination.collectAsStateWithLifecycle()
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    val activeCount = jobs.count { it.active }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { archiveVm.messages.collect { snackbar.showSnackbar(it) } }

    val stream: (DownloadJob) -> Unit = { job ->
        askForNotifications()
        if (job.active && !job.streaming) Downloads.enableStreaming(job.id)
        if (!launchPlayer(context, job)) vm.message("No app can open ${job.fileName}")
    }

    // Also prompt when a plain download starts; the notification is how progress is shown outside the app.
    LaunchedEffect(jobs.isNotEmpty()) { if (jobs.isNotEmpty()) askForNotifications() }

    Box {
        NavigationSuiteScaffold(
            // Rail items centred vertically on medium/expanded widths (tablets, unfolded foldables), like the Play Store.
            navigationItemVerticalArrangement = Arrangement.Center,
            navigationItems = {
                navItems.forEach { item ->
                    val selected = destination == item.dest
                    NavigationSuiteItem(
                        selected = selected,
                        onClick = { vm.destination.value = item.dest },
                        label = { Text(item.label) },
                        icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                        badge = if (item.dest == Destination.Downloads && activeCount > 0) {
                            { Badge { Text("$activeCount") } }
                        } else null,
                    )
                }
            },
        ) {
            when (destination) {
                Destination.Browse -> BrowseScreen(vm, onStream = stream)
                Destination.Archive -> ArchiveScreen(
                    archiveVm,
                    onStream = stream,
                    onStreamUrl = { uri, name -> if (!launchPlayer(context, uri, name)) vm.message("No app can open $name") },
                )
                Destination.Downloads -> DownloadsScreen(onStream = stream)
                Destination.Settings -> SettingsScreen(vm.settings)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconBadge(
                    Icons.Outlined.FolderZip, size = 96.dp,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text("Allow all-files access", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    "Rewind browses your storage for .torrent files and writes downloads into " +
                        "folders named after each torrent. Android needs \"All files access\" for that.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onGrant) { Text("Open settings") }
            }
        }
    }
}
