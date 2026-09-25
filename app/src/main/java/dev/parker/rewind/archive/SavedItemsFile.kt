package dev.parker.rewind.archive

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Saved archive.org items as JSON, for both app storage and export/import.
 *
 * Export format: `{"app": "rewind", "version": 1, "items": [{"id", "title", "mediatype"}]}`.
 * Import also accepts a bare items array (what the app keeps in its preferences, and what the old
 * Torrent Viewer builds stored) or plain text with one archive.org URL or identifier per line.
 */
object SavedItemsFile {
    private const val VERSION = 1

    fun encodeArray(items: List<SavedItem>): JSONArray = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.identifier).put("title", it.title).put("mediatype", it.mediatype)) }
    }

    fun export(items: List<SavedItem>): String =
        JSONObject().put("app", "rewind").put("version", VERSION).put("items", encodeArray(items)).toString(2)

    fun decodeArray(arr: JSONArray): List<SavedItem> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SavedItem(id, o.optString("title").ifBlank { id }, o.optString("mediatype").ifBlank { null })
    }

    /** Parse any supported import format. Throws [IllegalArgumentException] if nothing usable is found. */
    fun parse(text: String): List<SavedItem> {
        val trimmed = text.trim()
        val items = try {
            when {
                trimmed.startsWith("{") -> decodeArray(JSONObject(trimmed).optJSONArray("items") ?: JSONArray())
                trimmed.startsWith("[") -> decodeArray(JSONArray(trimmed))
                // One URL or identifier per line. The real title arrives the first time it's opened.
                else -> trimmed.lines().mapNotNull { ArchiveApi.parseIdentifier(it) }.map { SavedItem(it, it, null) }
            }
        } catch (e: JSONException) {
            throw IllegalArgumentException("Not a Rewind export: ${e.message}")
        }
        require(items.isNotEmpty()) { "No archive.org items found in that file" }
        return items.distinctBy { it.identifier }
    }
}
