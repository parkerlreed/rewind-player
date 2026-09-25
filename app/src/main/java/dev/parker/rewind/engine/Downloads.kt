package dev.parker.rewind.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every download the app knows about, torrent or HTTP, and the operations the UI can apply to them. */
object Downloads {
    internal val state = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = state.asStateFlow()

    fun jobFor(id: String): DownloadJob? = state.value.firstOrNull { it.id == id }

    private fun isHttp(id: String) = id.startsWith(HttpDownloader.ID_PREFIX)

    fun cancel(id: String) = if (isHttp(id)) HttpDownloader.cancel(id) else TorrentEngine.cancel(id)

    /** Only meaningful for torrents; HTTP jobs are streamed from their remote URL. */
    fun enableStreaming(id: String) {
        if (!isHttp(id)) TorrentEngine.enableStreaming(id)
    }

    fun clearFinished() {
        TorrentEngine.clearFinished()
        HttpDownloader.clearFinished()
    }

    fun stopAll() {
        TorrentEngine.stopAll()
        HttpDownloader.stopAll()
    }
}
