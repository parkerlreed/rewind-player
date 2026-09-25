package dev.parker.rewind.archive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ArchiveApiTest {
    @Test
    fun parseIdentifierFromUrlsAndBareIds() {
        val id = "the-lion-king-1995-vhs_202412"
        listOf(
            "https://archive.org/details/$id",
            "https://archive.org/details/$id/Toy+Story.mp4?autoplay=1",
            "http://www.archive.org/download/$id/file.mp4",
            "archive.org/metadata/$id",
            "Check this out: https://archive.org/details/$id#reviews",
            "  $id  ",
        ).forEach { assertEquals(it, id, ArchiveApi.parseIdentifier(it)) }
        assertNull(ArchiveApi.parseIdentifier("https://example.com/details/foo"))
        assertNull(ArchiveApi.parseIdentifier("not an id"))
        assertNull(ArchiveApi.parseIdentifier(""))
    }

    @Test
    fun parseMetadata() {
        val json = JSONObject(
            """
            {"metadata": {"identifier": "item1", "title": "An Item", "creator": ["A", "B"], "mediatype": "movies"},
             "files": [
               {"name": "Season 1/ep1.mp4", "source": "original", "format": "MPEG4", "size": "100", "md5": "abc", "length": "60.5"},
               {"name": "Season 1/ep1.ia.mp4", "source": "derivative", "format": "h.264 IA", "size": "50"},
               {"name": "item1_archive.torrent", "source": "metadata", "format": "Archive BitTorrent"},
               {"name": "item1_meta.xml", "source": "original", "format": "Metadata"},
               {"name": "__ia_thumb.jpg", "source": "original", "format": "Item Tile", "size": "3"},
               {"name": "secret.mp4", "source": "original", "size": "7", "private": "true"}
             ]}
            """
        )
        val item = ArchiveApi.parse("item1", json)
        assertEquals("An Item", item.title)
        assertEquals("A, B", item.creator)
        assertEquals(6, item.files.size)
        assertEquals(160L, item.totalSize)
        assertEquals(60.5, item.fileAt(listOf("Season 1", "ep1.mp4"))!!.lengthSeconds!!, 0.0)
        assertEquals(true, item.fileAt(listOf("secret.mp4"))!!.private)

        // Nothing selected: everything.
        assertEquals(6, item.tree(emptySet()).fileCount)
        // Metadata files are tagged "original" by archive.org but must not count as originals.
        assertEquals(setOf("ep1.mp4", "secret.mp4"), item.files.filter { it.visible(setOf(FileSource.Original)) }.map { it.path.last() }.toSet())
        assertEquals(
            setOf("item1_archive.torrent", "item1_meta.xml", "__ia_thumb.jpg"),
            item.files.filter { it.visible(setOf(FileSource.Metadata)) }.map { it.name }.toSet(),
        )
        assertEquals(listOf("ep1.ia.mp4"), item.files.filter { it.visible(setOf(FileSource.Derivative)) }.map { it.path.last() })
        assertEquals(5, item.tree(setOf(FileSource.Original, FileSource.Metadata)).fileCount)

        assertEquals(
            "https://archive.org/download/item1/Season%201/ep1.mp4",
            item.downloadUrl(item.fileAt(listOf("Season 1", "ep1.mp4"))!!),
        )
    }

    @Test
    fun missingItem() {
        assertThrows(IOException::class.java) { ArchiveApi.parse("nope", JSONObject("{}")) }
    }
}
