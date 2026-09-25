package dev.parker.rewind.archive

import dev.parker.rewind.torrent.TorrentMeta
import dev.parker.rewind.torrent.TorrentNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ArchiveFile(
    /** Path inside the item, '/'-separated. */
    val name: String,
    val size: Long,
    val format: String?,
    /** "original", "derivative" or "metadata". */
    val source: String?,
    val md5: String?,
    /** Duration in seconds for audio/video, when archive.org knows it. */
    val lengthSeconds: Double?,
    /** Private files need an archive.org login, which this app doesn't do. */
    val private: Boolean,
) {
    val path: List<String> get() = name.split('/').filter { it.isNotEmpty() }
    /**
     * archive.org's own bookkeeping. Only the .torrent is tagged source=metadata; _meta.xml,
     * _files.xml etc. are source=original with format "Metadata", and the thumbnail is "Item Tile".
     */
    val isMetadata: Boolean get() = source == "metadata" || format in METADATA_FORMATS
    val isOriginal: Boolean get() = source == "original" && !isMetadata

    val kind: FileSource get() = when {
        isMetadata -> FileSource.Metadata
        isOriginal -> FileSource.Original
        else -> FileSource.Derivative
    }

    /** "Show only" filter: an empty selection means everything. */
    fun visible(show: Set<FileSource>): Boolean = show.isEmpty() || kind in show

    private companion object {
        val METADATA_FORMATS = setOf("Metadata", "Item Tile", "Archive BitTorrent", "Item Image")
    }
}

enum class FileSource(val label: String) {
    Original("Originals"),
    Derivative("Derivatives"),
    Metadata("Metadata"),
}

data class ArchiveItem(
    val identifier: String,
    val title: String,
    val creator: String?,
    val date: String?,
    val mediatype: String?,
    val files: List<ArchiveFile>,
) {
    val totalSize: Long get() = files.sumOf { it.size }

    val detailsUrl: String get() = "https://archive.org/details/$identifier"

    fun downloadUrl(file: ArchiveFile): String =
        "https://archive.org/download/$identifier/" + file.path.joinToString("/") { encodeSegment(it) }

    /** The item's files as a folder tree, reusing the torrent browser's model. */
    fun tree(show: Set<FileSource>): TorrentNode.Dir =
        TorrentMeta.buildTree(
            files.filter { it.visible(show) }.map { it.path to it.size }
        )

    fun fileAt(path: List<String>): ArchiveFile? = files.firstOrNull { it.path == path }
}

object ArchiveApi {
    private const val USER_AGENT = "Rewind/0.1 (Android; +https://archive.org/developers)"
    private val IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /**
     * Pull an item identifier out of whatever the user pasted: a details/download/metadata/embed URL
     * (with or without scheme or `www.`), or a bare identifier. Null if it isn't recognisable.
     */
    fun parseIdentifier(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val url = Regex("""(?:https?://)?(?:www\.)?archive\.org/(?:details|download|metadata|embed|stream)/([^/?#\s]+)""")
            .find(text)
        val candidate = url?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: text
        return candidate.takeIf { IDENTIFIER.matches(it) }
    }

    /** Blocking; call off the main thread. */
    fun fetch(identifier: String): ArchiveItem {
        val conn = open("https://archive.org/metadata/${encodeSegment(identifier)}")
        try {
            if (conn.responseCode != 200) throw IOException("archive.org returned HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(identifier, JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    fun parse(identifier: String, json: JSONObject): ArchiveItem {
        if (json.length() == 0 || !json.has("files")) throw IOException("No archive.org item called \"$identifier\"")
        if (json.optBoolean("is_dark")) throw IOException("\"$identifier\" has been taken down")
        val meta = json.optJSONObject("metadata") ?: JSONObject()
        val filesJson = json.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesJson.length()).mapNotNull { i ->
            val f = filesJson.optJSONObject(i) ?: return@mapNotNull null
            val name = f.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ArchiveFile(
                name = name,
                size = f.optString("size").toLongOrNull() ?: 0L,
                format = f.optString("format").ifEmpty { null },
                source = f.optString("source").ifEmpty { null },
                md5 = f.optString("md5").ifEmpty { null },
                lengthSeconds = f.optString("length").toDoubleOrNull(),
                private = f.optString("private") == "true",
            )
        }
        return ArchiveItem(
            identifier = meta.text("identifier") ?: identifier,
            title = meta.text("title") ?: identifier,
            creator = meta.text("creator"),
            date = meta.text("date") ?: meta.text("year"),
            mediatype = meta.text("mediatype"),
            files = files,
        )
    }

    fun open(url: String, range: Long = 0): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            if (range > 0) setRequestProperty("Range", "bytes=$range-")
        }

    /** Metadata values are a string or a list of strings depending on the item. */
    private fun JSONObject.text(key: String): String? = when (val v = opt(key)) {
        is String -> v.ifBlank { null }
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).ifBlank { null } }.joinToString(", ").ifEmpty { null }
        else -> null
    }
}

internal fun encodeSegment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
