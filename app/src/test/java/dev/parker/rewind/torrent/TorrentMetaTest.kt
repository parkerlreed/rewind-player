package dev.parker.rewind.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentMetaTest {
    private fun s(v: String) = "${v.toByteArray().size}:$v"

    @Test
    fun singleFile() {
        val t = "d4:infod6:lengthi1234e4:name${s("movie.mkv")}12:piece lengthi16384e6:pieces0:ee"
        val meta = TorrentMeta.parse(t.toByteArray())
        assertEquals("movie.mkv", meta.name)
        assertTrue(meta.singleFile)
        assertEquals(1234L, meta.totalSize)
        assertEquals(listOf("movie.mkv"), (meta.root.children.single() as TorrentNode.File).path)
    }

    @Test
    fun multiFileBuildsTreeAndSkipsPadFiles() {
        val files = "l" +
            "d6:lengthi10e4:pathl${s("Season 1")}${s("ep1.mkv")}ee" +
            "d4:attr1:p6:lengthi6e4:pathl${s(".pad")}${s("6")}ee" +
            "d6:lengthi20e4:pathl${s("Season 1")}${s("ep2.mkv")}ee" +
            "d6:lengthi5e4:pathl${s("readme.txt")}ee" +
            "e"
        val t = "d4:infod5:files${files}4:name${s("Show")}12:piece lengthi16384e6:pieces0:ee"
        val meta = TorrentMeta.parse(t.toByteArray())
        assertEquals("Show", meta.name)
        assertEquals(35L, meta.totalSize)
        assertEquals(3, meta.fileCount)
        // Directories sort before files.
        assertEquals(listOf("Season 1", "readme.txt"), meta.root.children.map { it.name })
        val season = meta.dirAt(listOf("Season 1"))
        assertNotNull(season)
        assertEquals(listOf("ep1.mkv", "ep2.mkv"), season!!.children.map { it.name })
        assertEquals(30L, season.size)
    }

    @Test
    fun webSeeds() {
        val info = "4:infod6:lengthi1e4:name${s("a")}12:piece lengthi16384e6:pieces0:e"
        val list = TorrentMeta.parse("d${info}8:url-listl${s("https://example.com/")}${s("udp://nope")}ee".toByteArray())
        assertEquals(listOf("https://example.com/"), list.webSeeds)
        val single = TorrentMeta.parse("d${info}8:url-list${s("http://example.com/a")}e".toByteArray())
        assertEquals(listOf("http://example.com/a"), single.webSeeds)
    }

    @Test
    fun v2FileTree() {
        fun leaf(len: Int) = "d0:d6:lengthi${len}e11:pieces root0:ee"
        val tree = "d${s("a")}d${s("b.bin")}${leaf(7)}e${s("c.bin")}${leaf(3)}e"
        val t = "d4:infod9:file tree${tree}12:meta versioni2e4:name${s("V2")}12:piece lengthi16384eee"
        val meta = TorrentMeta.parse(t.toByteArray())
        assertEquals(10L, meta.totalSize)
        assertEquals(listOf("a", "b.bin"), (meta.dirAt(listOf("a"))!!.children.single()).path)
    }
}
