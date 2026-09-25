package dev.parker.rewind.engine

import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * Loopback-only HTTP server that serves a file while it downloads. Reads block until libtorrent
 * has the needed pieces, so any player that handles slow HTTP (mpv, VLC, ...) can play and seek.
 */
object StreamServer {
    private const val TAG = "StreamServer"
    private const val BUFFER = 256 * 1024

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stream").apply { isDaemon = true } }

    @Synchronized
    private fun ensureStarted(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: IOException) { break }
                pool.execute { handle(client) }
            }
        }, "stream-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
    }

    fun urlFor(job: DownloadJob): Uri {
        val port = ensureStarted()
        return Uri.Builder().scheme("http").encodedAuthority("127.0.0.1:$port")
            .appendPath(tokenFor(job.id))
            .appendPath(job.fileName)
            .build()
    }

    fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mkv" -> "video/x-matroska"
                "flac" -> "audio/flac"
                "opus" -> "audio/opus"
                else -> "application/octet-stream"
            }
    }

    // Opaque tokens so the URL doesn't leak local paths; mapped back to job ids.
    private val tokens = HashMap<String, String>()

    @Synchronized
    private fun tokenFor(jobId: String): String =
        tokens.entries.firstOrNull { it.value == jobId }?.key
            ?: java.util.UUID.randomUUID().toString().replace("-", "").also { tokens[it] = jobId }

    @Synchronized
    private fun jobIdFor(token: String): String? = tokens[token]

    private fun handle(socket: Socket): Unit = socket.use {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val out = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: return
            val path = parts.getOrNull(1) ?: return
            if (method != "GET" && method != "HEAD") return respondError(out, 405, "Method Not Allowed")

            val token = path.trimStart('/').substringBefore('/')
            val source = jobIdFor(token)?.let { TorrentEngine.streamSource(it) }
                ?: return respondError(out, 404, "Not Found")
            val job = source.job
            val size = job.size

            val range = headers["range"]?.let { parseRange(it, size) }
            if (headers["range"] != null && range == null) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val start = range?.first ?: 0L
            val end = range?.last ?: (size - 1)
            val length = end - start + 1

            val head = buildString {
                append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${mimeTypeFor(job.fileName)}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            // Send headers now: the body may block for a while waiting on pieces.
            out.flush()
            Log.i(TAG, "$method ${job.fileName} range=${headers["range"] ?: "none"} -> $start-$end/$size")
            if (method == "HEAD") return

            streamBody(source, out, start, length)
            out.flush()
        } catch (_: SocketException) {
            // Player closed the connection (seek, stop); normal.
            Log.d(TAG, "Player closed connection")
        } catch (e: IOException) {
            Log.d(TAG, "Stream ended: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun streamBody(source: TorrentEngine.StreamSource, out: OutputStream, start: Long, length: Long) {
        val buf = ByteArray(BUFFER)
        var pos = start
        val end = start + length
        var raf: RandomAccessFile? = null
        try {
            while (pos < end) {
                // Never leave bytes the player needs sitting in our buffer while we block on the next piece.
                if (!source.isAvailable(pos)) out.flush()
                val available = source.awaitAvailable(pos).coerceAtMost(end - pos)
                // The file appears on disk with the first piece libtorrent writes.
                val file = raf ?: run {
                    while (!source.job.target.exists()) Thread.sleep(100)
                    RandomAccessFile(source.job.target, "r").also { raf = it }
                }
                var left = available
                while (left > 0) {
                    file.seek(pos)
                    val n = file.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (n <= 0) { Thread.sleep(50); continue } // not flushed yet
                    out.write(buf, 0, n)
                    pos += n
                    left -= n
                }
            }
        } finally {
            raf?.close()
        }
    }

    /** Supports `bytes=a-b`, `bytes=a-` and `bytes=-n`. Multi-range requests use their first range. */
    internal fun parseRange(header: String, size: Long): LongRange? {
        if (!header.startsWith("bytes=") || size <= 0) return null
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        val range = when {
            a.isEmpty() -> {
                val n = b.toLongOrNull() ?: return null
                (size - n).coerceAtLeast(0)..<size
            }
            else -> {
                val from = a.toLongOrNull() ?: return null
                val to = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(size - 1)
                from..to
            }
        }
        return range.takeIf { it.first in 0..<size && it.last >= it.first }
    }

    private fun respondError(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) throw IOException("Header line too long")
        }
    }
}
