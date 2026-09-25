package dev.parker.rewind.engine

import android.content.Context
import android.util.Log
import dev.parker.rewind.torrent.TorrentMeta
import dev.parker.rewind.torrent.TorrentNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.UrlSeedAlert
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.IOException

/** Snapshot of one requested file, published to the UI and notification. */
data class DownloadJob(
    val id: String,
    val torrentName: String,
    val fileName: String,
    val pathInTorrent: List<String>,
    val target: File,
    val size: Long,
    val downloaded: Long = 0,
    val rateBytes: Int = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val streaming: Boolean = false,
    val state: State = State.Starting,
    val error: String? = null,
    /** Last failure from an HTTP(S) web seed. Not fatal: peers may still deliver the data. */
    val webSeedError: String? = null,
    /** Set for plain HTTP downloads (archive.org); players stream this URL directly. */
    val remoteUrl: String? = null,
) {
    enum class State { Starting, Checking, Downloading, Complete, Error }

    val progress: Float get() = if (size > 0) (downloaded.toFloat() / size).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = state != State.Complete && state != State.Error
}

/**
 * One libtorrent session for the whole app. Each torrent is added once; every file the user asks
 * for flips that file's priority on, everything else stays at IGNORE.
 */
object TorrentEngine {
    private const val TAG = "TorrentEngine"

    internal class Entry(val info: TorrentInfo, val saveDir: File, val webSeeds: List<String>) {
        @Volatile var handle: TorrentHandle? = null
        /** Notified whenever a piece finishes, so stream readers can re-check. */
        val lock = Object()
        val fileIndexes = LinkedHashSet<Int>()
    }

    private class Tracked(val entry: Entry, val fileIndex: Int, @Volatile var cancelled: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private var session: SessionManager? = null
    private var poller: Job? = null
    private val entries = HashMap<String, Entry>() // by info-hash hex
    private val tracked = HashMap<String, Tracked>() // by job id

    // Shared with HttpDownloader so both kinds of download live in one list.
    private val _jobs get() = Downloads.state

    fun jobFor(id: String): DownloadJob? = _jobs.value.firstOrNull { it.id == id }

    fun jobId(torrentFile: File, node: TorrentNode.File): String =
        "${torrentFile.absolutePath}::${node.path.joinToString("/")}"

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    private fun ensureSession(): SessionManager {
        session?.let { return it }
        // OpenSSL reads SSL_CERT_FILE when libtorrent builds its TLS context at session start.
        CaBundle.install(appContext)
        val s = SessionManager(false)
        s.addListener(object : AlertListener {
            override fun types() = intArrayOf(
                AlertType.PIECE_FINISHED.swig(),
                AlertType.TORRENT_ERROR.swig(),
                AlertType.FILE_ERROR.swig(),
                AlertType.URL_SEED.swig(),
                AlertType.PEER_DISCONNECTED.swig(),
                AlertType.PEER_ERROR.swig(),
            )

            override fun alert(alert: Alert<*>) {
                val handle = (alert as? TorrentAlert<*>)?.handle() ?: return
                val entry = synchronized(this@TorrentEngine) { entries[handle.infoHash().toHex()] } ?: return
                if (alert.type() == AlertType.PEER_DISCONNECTED || alert.type() == AlertType.PEER_ERROR) {
                    val msg = alert.message()
                    // libtorrent retries these on its own; log only, the UI doesn't need them.
                    if ("asio.ssl" in msg) Log.d(TAG, "Web seed TLS failure: $msg")
                    return
                }
                if (alert is UrlSeedAlert) {
                    val msg = alert.errorMessage().ifBlank { alert.error()?.message.orEmpty() }
                    Log.w(TAG, "Web seed ${alert.serverUrl()} failed: $msg")
                    markWebSeedError(entry, msg)
                } else if (alert.type() != AlertType.PIECE_FINISHED) {
                    val msg = (alert as? TorrentErrorAlert)?.error()?.message ?: alert.message()
                    Log.w(TAG, "Torrent error: $msg")
                    markError(entry, msg)
                }
                synchronized(entry.lock) { entry.lock.notifyAll() }
            }
        })
        s.start(SessionParams(SettingsPack()))
        session = s
        poller = scope.launch {
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
        return s
    }

    /**
     * Queue [node] from [meta] for download under [saveRoot]. Multi-file torrents land in
     * `saveRoot/<torrent name>/<path>`; single-file torrents get a folder named after the file too.
     * Returns the job id. Calling again for the same file is harmless and can upgrade it to streaming.
     */
    @Synchronized
    fun enqueue(torrentFile: File, meta: TorrentMeta, node: TorrentNode.File, saveRoot: File, stream: Boolean): String {
        val s = ensureSession()
        val info = TorrentInfo(torrentFile)
        val hash = info.infoHash().toHex()
        val id = jobId(torrentFile, node)

        val entry = entries.getOrPut(hash) {
            val saveDir = if (meta.singleFile) File(saveRoot, node.name.substringBeforeLast('.')) else saveRoot
            Entry(info, saveDir, meta.webSeeds)
        }
        val index = findFileIndex(entry.info, meta, node)
            ?: throw IOException("Couldn't find ${node.name} inside the torrent")
        val target = File(entry.info.files().filePath(index, entry.saveDir.absolutePath))

        val existing = tracked[id]
        if (existing != null && !existing.cancelled) {
            if (stream) setStreaming(id, entry)
            return id
        }

        tracked[id] = Tracked(entry, index)
        entry.fileIndexes += index
        _jobs.update { list ->
            list.filterNot { it.id == id } + DownloadJob(
                id = id,
                torrentName = meta.name,
                fileName = node.name,
                pathInTorrent = node.path,
                target = target,
                size = node.size,
                streaming = stream,
            )
        }

        val handle = entry.handle?.takeIf { it.isValid }
        if (handle != null) {
            handle.filePriority(index, Priority.DEFAULT)
            if (stream) handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle.resume()
        } else {
            entry.saveDir.mkdirs()
            val priorities = Array(entry.info.numFiles()) { i ->
                if (i in entry.fileIndexes) Priority.DEFAULT else Priority.IGNORE
            }
            val flags = if (stream) TorrentFlags.SEQUENTIAL_DOWNLOAD else torrent_flags_t()
            s.download(entry.info, entry.saveDir, null, priorities, null, flags)
            scope.launch { awaitHandle(entry, s) }
        }
        return id
    }

    private suspend fun awaitHandle(entry: Entry, s: SessionManager) {
        repeat(100) {
            s.find(entry.info.infoHash())?.takeIf { it.isValid }?.let { h ->
                entry.webSeeds.forEach(h::addUrlSeed)
                entry.handle = h
                synchronized(entry.lock) { entry.lock.notifyAll() }
                return
            }
            delay(100)
        }
        markError(entry, "libtorrent never added the torrent")
    }

    /** Switch an in-progress download to sequential, playhead-first mode. */
    @Synchronized
    fun enableStreaming(id: String) {
        val t = tracked[id] ?: return
        setStreaming(id, t.entry)
    }

    private fun setStreaming(id: String, entry: Entry) {
        entry.handle?.takeIf { it.isValid }?.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        _jobs.update { list -> list.map { if (it.id == id) it.copy(streaming = true) else it } }
    }

    /** Match our parsed path to libtorrent's file index; libtorrent counts pad files and may sanitise names. */
    private fun findFileIndex(info: TorrentInfo, meta: TorrentMeta, node: TorrentNode.File): Int? {
        val fs = info.files()
        val want = (if (meta.singleFile) node.path else listOf(meta.name) + node.path).joinToString("/")
        val candidates = (0 until fs.numFiles()).filterNot { fs.padFileAt(it) }
        return candidates.firstOrNull { fs.filePath(it).replace('\\', '/') == want }
            ?: candidates.firstOrNull { fs.fileName(it) == node.name && fs.fileSize(it) == node.size }
            ?: candidates.singleOrNull { fs.fileSize(it) == node.size }
    }

    @Synchronized
    fun cancel(id: String) {
        val t = tracked.remove(id) ?: return
        t.cancelled = true
        val entry = t.entry
        entry.fileIndexes -= t.fileIndex
        entry.handle?.takeIf { it.isValid }?.filePriority(t.fileIndex, Priority.IGNORE)
        synchronized(entry.lock) { entry.lock.notifyAll() }
        _jobs.update { list -> list.filterNot { it.id == id } }
        releaseIfIdle(entry)
    }

    /** Remove finished jobs from the list (the files stay on disk). */
    @Synchronized
    fun clearFinished() {
        val done = _jobs.value.filter { !it.active && it.remoteUrl == null }.map { it.id }.toSet()
        done.forEach { tracked.remove(it) }
        _jobs.update { list -> list.filterNot { it.id in done } }
    }

    @Synchronized
    fun stopAll() {
        tracked.keys.toList().forEach { cancel(it) }
        _jobs.update { list -> list.filter { it.remoteUrl != null } }
    }

    /** Drop a torrent from the session once none of its jobs still need it. Nothing is deleted. */
    private fun releaseIfIdle(entry: Entry) {
        val stillNeeded = tracked.values.any { it.entry === entry && jobStateOf(it)?.active == true }
        if (stillNeeded) return
        entry.handle?.takeIf { it.isValid }?.let { session?.remove(it) }
        entry.handle = null
        entries.values.remove(entry)
    }

    private fun jobStateOf(t: Tracked): DownloadJob? =
        tracked.entries.firstOrNull { it.value === t }?.key?.let { jobFor(it) }

    private fun markError(entry: Entry, message: String) = synchronized(this) {
        val ids = tracked.filterValues { it.entry === entry }.keys
        _jobs.update { list ->
            list.map { if (it.id in ids && it.active) it.copy(state = DownloadJob.State.Error, error = message) else it }
        }
    }

    private fun markWebSeedError(entry: Entry, message: String) = synchronized(this) {
        val ids = tracked.filterValues { it.entry === entry }.keys
        _jobs.update { list ->
            list.map { if (it.id in ids && it.rateBytes == 0 && it.active) it.copy(webSeedError = message) else it }
        }
    }

    @Synchronized
    private fun refresh() {
        if (tracked.isEmpty()) return
        val byEntry = tracked.entries.groupBy { it.value.entry }
        val updates = HashMap<String, DownloadJob>()
        for ((entry, jobs) in byEntry) {
            val handle = entry.handle?.takeIf { it.isValid } ?: continue
            val status = handle.status()
            val progress = handle.fileProgress()
            val checking = status.state().name.startsWith("CHECKING")
            for ((id, t) in jobs) {
                val job = jobFor(id) ?: continue
                if (!job.active) continue
                val done = progress.getOrElse(t.fileIndex) { 0L }
                val state = when {
                    done >= job.size -> DownloadJob.State.Complete
                    checking -> DownloadJob.State.Checking
                    else -> DownloadJob.State.Downloading
                }
                updates[id] = job.copy(
                    downloaded = done,
                    rateBytes = status.downloadPayloadRate(),
                    peers = status.numPeers(),
                    seeds = status.numSeeds(),
                    state = state,
                    // Web seeds come and go (redirects, dropped TLS connections libtorrent retries);
                    // only keep the error around while nothing is actually arriving.
                    webSeedError = if (status.downloadPayloadRate() > 0 || state == DownloadJob.State.Complete) null
                    else job.webSeedError,
                )
            }
        }
        if (updates.isEmpty()) return
        _jobs.update { list -> list.map { updates[it.id] ?: it } }
        // Finished torrents leave the session: this app downloads, it doesn't seed.
        for (entry in byEntry.keys) {
            if (entry.handle != null) {
                synchronized(entry.lock) { entry.lock.notifyAll() }
                releaseIfIdle(entry)
            }
        }
    }

    // ---- Streaming support ----

    class StreamSource internal constructor(val job: DownloadJob, private val entry: Entry, private val fileIndex: Int) {
        private val pieceLength = entry.info.pieceLength().toLong()
        private val fileOffset = entry.info.files().fileOffset(fileIndex)
        private val lastPiece = pieceOf(job.size - 1)
        private var lastDeadlineFrom = -1
        /** Pieces this reader put deadlines on, so it can withdraw only its own (other readers may be active). */
        private val myDeadlines = HashSet<Int>()

        private fun pieceOf(pos: Long) = ((fileOffset + pos.coerceAtLeast(0)) / pieceLength).toInt()

        fun isAvailable(pos: Long): Boolean {
            if (isComplete()) return true
            return entry.handle?.takeIf { it.isValid }?.havePiece(pieceOf(pos)) == true
        }

        /**
         * Block until the byte at [pos] is on disk, then return how many contiguous bytes from [pos]
         * are safe to read. Throws if the job is cancelled.
         */
        fun awaitAvailable(pos: Long): Long {
            val remaining = job.size - pos
            val piece = pieceOf(pos)
            val started = System.currentTimeMillis()
            var waited = false
            var nextLog = started + 5000
            synchronized(entry.lock) {
                while (true) {
                    if (isComplete()) return remaining
                    if (isCancelled()) throw IOException("Download cancelled")
                    val handle = entry.handle?.takeIf { it.isValid }
                    if (handle != null) {
                        if (handle.havePiece(piece)) break
                        if (!waited) Log.i(TAG, "Stream waiting on piece $piece (file offset $pos)")
                        waited = true
                        prioritise(handle, piece)
                        val now = System.currentTimeMillis()
                        if (now >= nextLog) {
                            nextLog = now + 5000
                            val st = handle.status()
                            Log.w(TAG, "Still waiting on piece $piece after ${(now - started) / 1000}s: " +
                                "rate=${st.downloadPayloadRate()} peers=${st.numPeers()} " +
                                "prio=${handle.piecePriority(piece)} seq=${handle.getFlags().and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero()}")
                        }
                    }
                    entry.lock.wait(500)
                }
            }
            if (waited) Log.i(TAG, "Stream got piece $piece after ${System.currentTimeMillis() - started} ms")
            val pieceEnd = (piece + 1) * pieceLength - fileOffset
            return (pieceEnd - pos).coerceAtMost(remaining)
        }

        /** Ask for the pieces just ahead of the reader first so playback starts (or seeks) quickly. */
        private fun prioritise(handle: TorrentHandle, piece: Int) {
            if (piece == lastDeadlineFrom) return
            lastDeadlineFrom = piece
            val window = (16L * 1024 * 1024 / pieceLength).toInt().coerceIn(4, 64)
            val wanted = (piece until minOf(piece + window, lastPiece + 1)).toSet()
            // Withdraw only our own stale deadlines; another connection may be reading elsewhere in the file.
            (myDeadlines - wanted).forEach(handle::resetPieceDeadline)
            wanted.forEachIndexed { i, p -> handle.setPieceDeadline(p, 500 + i * 250) }
            myDeadlines.clear()
            myDeadlines += wanted
        }

        private fun isComplete() = jobFor(job.id)?.state == DownloadJob.State.Complete
        private fun isCancelled() = jobFor(job.id) == null
    }

    @Synchronized
    fun streamSource(id: String): StreamSource? {
        val t = tracked[id] ?: return null
        val job = jobFor(id) ?: return null
        return StreamSource(job, t.entry, t.fileIndex)
    }

}
