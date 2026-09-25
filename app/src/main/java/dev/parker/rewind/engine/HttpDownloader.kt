package dev.parker.rewind.engine

import android.util.Log
import dev.parker.rewind.archive.ArchiveApi
import dev.parker.rewind.archive.ArchiveFile
import dev.parker.rewind.archive.ArchiveItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Plain HTTPS downloads for archive.org items. Data goes to `<target>.part` and is renamed when
 * complete; an existing .part is resumed with a Range request.
 */
object HttpDownloader {
    private const val TAG = "HttpDownloader"
    const val ID_PREFIX = "ia:"
    private const val MAX_PARALLEL = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(MAX_PARALLEL)
    private val running = HashMap<String, Job>()

    fun jobId(item: ArchiveItem, file: ArchiveFile) = "$ID_PREFIX${item.identifier}/${file.name}"

    fun targetFor(saveRoot: File, item: ArchiveItem, file: ArchiveFile) =
        File(File(saveRoot, item.identifier), file.path.joinToString("/"))

    /** Queue [file]; returns the job id. Re-queuing a running file is a no-op. */
    @Synchronized
    fun enqueue(item: ArchiveItem, file: ArchiveFile, saveRoot: File, stream: Boolean): String {
        val id = jobId(item, file)
        if (running[id]?.isActive == true) {
            if (stream) update(id) { it.copy(streaming = true) }
            return id
        }
        val job = DownloadJob(
            id = id,
            torrentName = item.title,
            fileName = file.path.last(),
            pathInTorrent = file.path,
            target = targetFor(saveRoot, item, file),
            size = file.size,
            streaming = stream,
            remoteUrl = item.downloadUrl(file),
        )
        Downloads.state.update { list -> list.filterNot { it.id == id } + job }
        running[id] = scope.launch {
            try {
                permits.withPermit { download(job) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download of ${job.remoteUrl} failed", e)
                update(id) { it.copy(state = DownloadJob.State.Error, error = e.message ?: e.javaClass.simpleName) }
            } finally {
                synchronized(this@HttpDownloader) { running.remove(id) }
            }
        }
        return id
    }

    private suspend fun download(job: DownloadJob) {
        val part = File(job.target.path + ".part")
        job.target.parentFile?.mkdirs()
        var offset = part.takeIf { it.exists() }?.length() ?: 0L
        if (job.size > 0 && offset > job.size) { part.delete(); offset = 0 }

        val conn = ArchiveApi.open(job.remoteUrl!!, range = offset)
        try {
            val code = conn.responseCode
            when {
                code == 206 -> Unit
                code == 200 -> offset = 0 // server ignored the range; start over
                code == 416 && offset == job.size -> Unit // already have it all
                code == 401 || code == 403 -> throw IOException("archive.org refused access (HTTP $code); the file may be private")
                else -> throw IOException("HTTP $code from archive.org")
            }
            update(job.id) { it.copy(state = DownloadJob.State.Downloading, downloaded = offset) }
            if (code != 416) {
                conn.inputStream.use { input ->
                    FileOutputStream(part, offset > 0).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = offset
                        var windowStart = System.nanoTime()
                        var windowBytes = 0L
                        var lastPublish = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            windowBytes += n
                            val now = System.nanoTime()
                            if (now - lastPublish > 500_000_000L) {
                                val rate = (windowBytes * 1_000_000_000L / (now - windowStart).coerceAtLeast(1)).toInt()
                                update(job.id) { it.copy(downloaded = done, rateBytes = rate) }
                                lastPublish = now
                                if (now - windowStart > 3_000_000_000L) { windowStart = now; windowBytes = 0 }
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val got = part.length()
        if (job.size > 0 && got != job.size) throw IOException("Download ended early ($got of ${job.size} bytes); try again to resume")
        if (job.target.exists()) job.target.delete()
        if (!part.renameTo(job.target)) throw IOException("Couldn't move download into place")
        update(job.id) { it.copy(state = DownloadJob.State.Complete, downloaded = got, rateBytes = 0) }
    }

    private fun update(id: String, f: (DownloadJob) -> DownloadJob) {
        Downloads.state.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    /** Stops the download; the .part file stays so it can resume later. */
    @Synchronized
    fun cancel(id: String) {
        running.remove(id)?.cancel()
        Downloads.state.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        Downloads.state.update { list -> list.filterNot { it.remoteUrl != null && !it.active } }
    }

    @Synchronized
    fun stopAll() {
        running.values.forEach { it.cancel() }
        running.clear()
        Downloads.state.update { list -> list.filter { it.remoteUrl == null } }
    }
}
