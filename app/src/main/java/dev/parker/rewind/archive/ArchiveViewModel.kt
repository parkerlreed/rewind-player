package dev.parker.rewind.archive

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.parker.rewind.AppSettings
import dev.parker.rewind.engine.DownloadJob
import dev.parker.rewind.engine.DownloadService
import dev.parker.rewind.engine.Downloads
import dev.parker.rewind.engine.HttpDownloader
import dev.parker.rewind.torrent.TorrentNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

data class SavedItem(val identifier: String, val title: String, val mediatype: String?)

enum class SavedSort(val label: String) {
    Recent("Recently opened"),
    Title("Title"),
    Identifier("Identifier"),
}

/** An open item and the folder inside it being shown. */
data class OpenItem(val item: ArchiveItem, val path: List<String> = emptyList())

class ArchiveViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("archive", Context.MODE_PRIVATE)
    val settings = AppSettings.get(app)

    /** Stored most-recently-opened first; that order is the "Recent" sort. Sort with [sorted]. */
    private val _saved = MutableStateFlow(loadSaved())
    val saved: StateFlow<List<SavedItem>> = _saved.asStateFlow()

    private val _sort = MutableStateFlow(
        SavedSort.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: SavedSort.Recent
    )
    val sort: StateFlow<SavedSort> = _sort.asStateFlow()

    /**
     * Sorting is done by the caller in composition rather than in a flow, so a new sort and the
     * reordered list land in the same frame (the list can then reliably jump to the top).
     */
    fun sorted(items: List<SavedItem>, sort: SavedSort): List<SavedItem> = when (sort) {
        SavedSort.Recent -> items
        SavedSort.Title -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SavedItem::title).thenBy { it.identifier })
        SavedSort.Identifier -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SavedItem::identifier))
    }

    fun setSort(sort: SavedSort) {
        prefs.edit { putString(KEY_SORT, sort.name) }
        _sort.value = sort
    }

    private val _open = MutableStateFlow<OpenItem?>(null)
    val open: StateFlow<OpenItem?> = _open.asStateFlow()

    /** Identifier currently being fetched, for spinners. */
    private val _loading = MutableStateFlow<String?>(null)
    val loading: StateFlow<String?> = _loading.asStateFlow()

    private val _selection = MutableStateFlow<ArchiveFile?>(null)
    val selection: StateFlow<ArchiveFile?> = _selection.asStateFlow()

    /** Which kinds of file to list; empty = all. Defaults to originals, hiding the transcoded copies. */
    private val _show = MutableStateFlow(
        prefs.getStringSet(KEY_SHOW, null)
            ?.mapNotNull { name -> FileSource.entries.firstOrNull { it.name == name } }?.toSet()
            ?: setOf(FileSource.Original)
    )
    val show: StateFlow<Set<FileSource>> = _show.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Folder tree of the open item after filters; cached per item + filter combination. */
    private var treeCache: Triple<ArchiveItem, Set<FileSource>, TorrentNode.Dir>? = null

    fun tree(item: ArchiveItem): TorrentNode.Dir {
        val key = _show.value
        treeCache?.let { (i, k, t) -> if (i === item && k == key) return t }
        return item.tree(key).also { treeCache = Triple(item, key, it) }
    }

    fun dirAt(open: OpenItem): TorrentNode.Dir {
        var dir = tree(open.item)
        for (s in open.path) dir = dir.children.firstOrNull { it is TorrentNode.Dir && it.name == s } as? TorrentNode.Dir ?: break
        return dir
    }

    /**
     * Resolve [input] (URL or identifier), fetch it, remember it and open it.
     * Returns an error message, or null on success.
     */
    suspend fun add(input: String): String? {
        val id = ArchiveApi.parseIdentifier(input) ?: return "That doesn't look like an archive.org item URL or identifier"
        return load(id)
    }

    fun message(text: String) { viewModelScope.launch { _messages.send(text) } }

    fun addInBackground(input: String) {
        viewModelScope.launch { add(input)?.let { _messages.send(it) } }
    }

    fun openSaved(identifier: String) {
        viewModelScope.launch { load(identifier)?.let { _messages.send(it) } }
    }

    fun refresh() {
        _open.value?.let { openSaved(it.item.identifier) }
    }

    private suspend fun load(identifier: String): String? {
        _loading.value = identifier
        try {
            val item = withContext(Dispatchers.IO) { ArchiveApi.fetch(identifier) }
            remember(SavedItem(item.identifier, item.title, item.mediatype))
            val keepPath = _open.value?.takeIf { it.item.identifier == item.identifier }?.path ?: emptyList()
            _open.value = OpenItem(item, keepPath)
            _selection.value = null
            return null
        } catch (e: Exception) {
            return e.message ?: "Couldn't load $identifier"
        } finally {
            _loading.value = null
        }
    }

    fun remove(identifier: String) {
        _saved.value = _saved.value.filterNot { it.identifier == identifier }
        persist()
    }

    private fun remember(item: SavedItem) {
        // Most recently opened first.
        _saved.value = listOf(item) + _saved.value.filterNot { it.identifier == item.identifier }
        persist()
    }

    fun navigate(path: List<String>) {
        _open.value = _open.value?.copy(path = path)
        _selection.value = null
    }

    fun close() {
        _open.value = null
        _selection.value = null
    }

    /** Up one folder, or out of the item. Returns false if already at the item list. */
    fun goUp(): Boolean {
        val o = _open.value ?: return false
        if (o.path.isEmpty()) close() else navigate(o.path.dropLast(1))
        return true
    }

    fun select(path: List<String>) {
        _selection.value = _open.value?.item?.fileAt(path)
    }

    fun clearSelection() { _selection.value = null }

    fun toggleShow(source: FileSource) {
        val next = _show.value.let { if (source in it) it - source else it + source }
        prefs.edit { putStringSet(KEY_SHOW, next.map { it.name }.toSet()) }
        _show.value = next
        resetPathIfGone()
    }

    /** Filtering can remove the folder being viewed; fall back to the item root. */
    private fun resetPathIfGone() {
        val o = _open.value ?: return
        var dir = tree(o.item)
        for (s in o.path) {
            dir = dir.children.firstOrNull { it is TorrentNode.Dir && it.name == s } as? TorrentNode.Dir
                ?: return navigate(emptyList())
        }
        val sel = _selection.value
        if (sel != null && !sel.visible(_show.value)) clearSelection()
    }

    fun targetFor(item: ArchiveItem, file: ArchiveFile): File =
        HttpDownloader.targetFor(settings.downloadRoot.value, item, file)

    fun jobId(item: ArchiveItem, file: ArchiveFile) = HttpDownloader.jobId(item, file)

    fun download(item: ArchiveItem, file: ArchiveFile, stream: Boolean, onQueued: (DownloadJob) -> Unit = {}) {
        val id = HttpDownloader.enqueue(item, file, settings.downloadRoot.value, stream)
        DownloadService.start(getApplication())
        Downloads.jobFor(id)?.let(onQueued)
    }

    /** Write the saved list to [uri] (from a CreateDocument picker). */
    fun exportTo(uri: Uri) {
        val items = _saved.value
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(SavedItemsFile.export(items).toByteArray())
                    }
                }
            }
            _messages.send(result.fold({ "Exported ${items.size} items" }, { "Export failed: ${it.message}" }))
        }
    }

    /** Merge items from [uri] into the saved list: existing ones stay put, new ones go after them. */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    SavedItemsFile.parse(text)
                }
            }
            val message = result.fold(
                onSuccess = { incoming ->
                    val known = _saved.value.map { it.identifier }.toSet()
                    val added = incoming.filterNot { it.identifier in known }
                    _saved.value = _saved.value + added
                    persist()
                    when {
                        added.isEmpty() -> "Nothing new: all ${incoming.size} items were already in the list"
                        added.size == incoming.size -> "Imported ${added.size} items"
                        else -> "Imported ${added.size} new items (${incoming.size - added.size} already in the list)"
                    }
                },
                onFailure = { "Import failed: ${it.message}" },
            )
            _messages.send(message)
        }
    }

    private fun loadSaved(): List<SavedItem> = runCatching {
        SavedItemsFile.decodeArray(JSONArray(prefs.getString(KEY_ITEMS, "[]")))
    }.getOrDefault(emptyList())

    private fun persist() {
        prefs.edit { putString(KEY_ITEMS, SavedItemsFile.encodeArray(_saved.value).toString()) }
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_SORT = "saved_sort"
        private const val KEY_SHOW = "show_sources"
    }
}
