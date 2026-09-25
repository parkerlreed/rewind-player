package dev.parker.rewind

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.parker.rewind.engine.Downloads
import dev.parker.rewind.engine.DownloadJob
import dev.parker.rewind.engine.DownloadService
import dev.parker.rewind.engine.TorrentEngine
import dev.parker.rewind.torrent.TorrentMeta
import dev.parker.rewind.torrent.TorrentNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Destination { Archive, Browse, Downloads, Settings }

/** Where the browser is. A torrent is just another kind of folder. */
sealed interface Location {
    data class Fs(val dir: File) : Location

    data class Torrent(
        val file: File,
        val meta: TorrentMeta,
        val path: List<String>,
        /** Folder to go "up" to; differs from [file]'s parent for torrents opened from other apps. */
        val parent: File,
    ) : Location {
        val dir: TorrentNode.Dir get() = meta.dirAt(path) ?: meta.root
    }
}

sealed interface BrowserEntry {
    val name: String

    data class FsDir(val file: File) : BrowserEntry { override val name: String get() = file.name }
    data class FsTorrent(val file: File) : BrowserEntry { override val name: String get() = file.name }
    data class TorrentDir(val node: TorrentNode.Dir) : BrowserEntry { override val name: String get() = node.name }
    data class TorrentFile(val node: TorrentNode.File) : BrowserEntry { override val name: String get() = node.name }
}

data class Selection(val torrentFile: File, val meta: TorrentMeta, val node: TorrentNode.File) {
    val jobId: String get() = TorrentEngine.jobId(torrentFile, node)
}

data class Crumb(val label: String, val location: Location)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings.get(app)

    init {
        TorrentEngine.init(app)
    }

    // The first tab is where the app opens.
    val destination = MutableStateFlow(Destination.Archive)

    val volumes: List<Pair<String, File>> =
        app.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { v ->
            v.directory?.let { v.getDescription(app) to it }
        }

    private val _location = MutableStateFlow<Location>(Location.Fs(Environment.getExternalStorageDirectory()))
    val location: StateFlow<Location> = _location.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Bumped to force a reload of the current folder (e.g. after permission is granted). */
    private val reload = MutableStateFlow(0)

    val entries: StateFlow<List<BrowserEntry>?> = combine(_location, settings.showHidden, reload) { loc, hidden, _ -> loc to hidden }
        .mapLatest { (loc, hidden) -> withContext(Dispatchers.IO) { list(loc, hidden) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Saved scroll positions so going back up lands where you were. */
    val scrollPositions = HashMap<Location, Pair<Int, Int>>()

    val crumbs: List<Crumb>
        get() {
            val out = ArrayList<Crumb>()
            var loc: Location? = _location.value
            while (loc != null) {
                out += Crumb(labelFor(loc), loc)
                loc = parentOf(loc)
            }
            return out.asReversed()
        }

    val canGoUp: Boolean get() = parentOf(_location.value) != null

    fun labelFor(loc: Location): String = when (loc) {
        is Location.Fs -> volumes.firstOrNull { it.second == loc.dir }?.first ?: loc.dir.name
        is Location.Torrent -> loc.path.lastOrNull() ?: loc.meta.name
    }

    private fun parentOf(loc: Location): Location? = when (loc) {
        is Location.Fs -> if (volumes.any { it.second == loc.dir }) null else loc.dir.parentFile?.let { Location.Fs(it) }
        is Location.Torrent ->
            if (loc.path.isNotEmpty()) loc.copy(path = loc.path.dropLast(1)) else Location.Fs(loc.parent)
    }

    private fun list(loc: Location, showHidden: Boolean): List<BrowserEntry> = when (loc) {
        is Location.Fs -> (loc.dir.listFiles() ?: emptyArray())
            .filter { showHidden || !it.name.startsWith('.') }
            .mapNotNull {
                when {
                    it.isDirectory -> BrowserEntry.FsDir(it)
                    it.isFile && it.name.endsWith(".torrent", ignoreCase = true) -> BrowserEntry.FsTorrent(it)
                    else -> null
                }
            }
            .sortedWith(compareBy<BrowserEntry> { it !is BrowserEntry.FsDir }.thenBy { it.name.lowercase() })
        is Location.Torrent -> loc.dir.children.map {
            when (it) {
                is TorrentNode.Dir -> BrowserEntry.TorrentDir(it)
                is TorrentNode.File -> BrowserEntry.TorrentFile(it)
            }
        }
    }

    fun refresh() { reload.value++ }

    fun navigate(loc: Location) {
        _location.value = loc
        _selection.value = null
    }

    fun goUp(): Boolean {
        val parent = parentOf(_location.value) ?: return false
        navigate(parent)
        return true
    }

    fun open(entry: BrowserEntry) {
        when (entry) {
            is BrowserEntry.FsDir -> navigate(Location.Fs(entry.file))
            is BrowserEntry.FsTorrent -> openTorrent(entry.file, entry.file.parentFile ?: entry.file)
            is BrowserEntry.TorrentDir -> (_location.value as? Location.Torrent)?.let { navigate(it.copy(path = entry.node.path)) }
            is BrowserEntry.TorrentFile -> (_location.value as? Location.Torrent)?.let {
                _selection.value = Selection(it.file, it.meta, entry.node)
            }
        }
    }

    fun select(loc: Location.Torrent, node: TorrentNode.File) {
        _selection.value = Selection(loc.file, loc.meta, node)
    }

    fun clearSelection() { _selection.value = null }

    private fun openTorrent(file: File, parent: File) {
        viewModelScope.launch {
            val meta = runCatching { withContext(Dispatchers.IO) { TorrentMeta.parse(file) } }
                .getOrElse { e ->
                    _messages.send("Couldn't read ${file.name}: ${e.message}")
                    return@launch
                }
            destination.value = Destination.Browse
            navigate(Location.Torrent(file, meta, emptyList(), parent))
        }
    }

    /** A .torrent handed to us by another app. content:// gets copied to cache so libtorrent can read it. */
    fun openIncoming(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    if (uri.scheme == "file") return@withContext File(requireNotNull(uri.path))
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?.substringAfterLast('/')
                        ?: "incoming.torrent"
                    val dir = File(app.cacheDir, "incoming").apply { mkdirs() }
                    File(dir, name).also { out ->
                        app.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                    }
                }
            }.getOrElse { e ->
                _messages.send("Couldn't open torrent: ${e.message}")
                return@launch
            }
            val parent = file.parentFile?.takeUnless { it.startsWith(app.cacheDir) } ?: Environment.getExternalStorageDirectory()
            openTorrent(file, parent)
        }
    }

    /** Where [selection] will be written, before anything is downloaded. */
    fun targetFor(sel: Selection): File {
        val root = settings.downloadRoot.value
        return if (sel.meta.singleFile) {
            File(File(root, sel.node.name.substringBeforeLast('.')), sel.node.name)
        } else {
            File(root, (listOf(sel.meta.name) + sel.node.path).joinToString("/"))
        }
    }

    fun download(sel: Selection, stream: Boolean, onQueued: (DownloadJob) -> Unit = {}) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val id = runCatching {
                withContext(Dispatchers.IO) {
                    TorrentEngine.enqueue(sel.torrentFile, sel.meta, sel.node, settings.downloadRoot.value, stream)
                }
            }.getOrElse { e ->
                _messages.send("Couldn't start download: ${e.message}")
                return@launch
            }
            DownloadService.start(app)
            Downloads.jobFor(id)?.let(onQueued)
        }
    }

    fun message(text: String) { viewModelScope.launch { _messages.send(text) } }
}
