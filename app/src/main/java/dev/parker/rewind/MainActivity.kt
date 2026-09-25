package dev.parker.rewind

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.parker.rewind.archive.ArchiveViewModel
import dev.parker.rewind.ui.App
import dev.parker.rewind.ui.RewindTheme
import dev.parker.rewind.ui.SolidStatusBar

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val archiveVm: ArchiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            RewindTheme { SolidStatusBar { App(vm, archiveVm) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.host?.endsWith("archive.org") == true) openArchive(uri.toString()) else vm.openIncoming(uri)
            }
            // "Share" from a browser: the text usually is (or contains) the item URL.
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::openArchive)
            ACTION_SHOW_DOWNLOADS -> vm.destination.value = Destination.Downloads
        }
    }

    private fun openArchive(text: String) {
        vm.destination.value = Destination.Archive
        archiveVm.addInBackground(text)
    }

    companion object {
        const val ACTION_SHOW_DOWNLOADS = "dev.parker.rewind.SHOW_DOWNLOADS"
    }
}
