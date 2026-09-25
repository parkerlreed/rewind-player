package dev.parker.rewind.torrent

import java.io.File

/** A .torrent file's contents as a browsable tree. Parsed without libtorrent so browsing never needs the engine. */
class TorrentMeta(
    val name: String,
    /** True when the torrent holds a single file with no directory of its own. */
    val singleFile: Boolean,
    val root: TorrentNode.Dir,
    /** BEP 19 web seeds (`url-list`). libtorrent4j drops these when adding a torrent, so they're re-added by hand. */
    val webSeeds: List<String> = emptyList(),
) {
    val totalSize: Long get() = root.size
    val fileCount: Int get() = root.fileCount

    /** Walk from the root down [segments]; null if the path doesn't exist. */
    fun dirAt(segments: List<String>): TorrentNode.Dir? {
        var dir = root
        for (s in segments) {
            dir = dir.children.firstOrNull { it is TorrentNode.Dir && it.name == s } as? TorrentNode.Dir ?: return null
        }
        return dir
    }

    companion object {
        fun parse(file: File): TorrentMeta = parse(file.readBytes(), file.nameWithoutExtension)

        @Suppress("UNCHECKED_CAST")
        fun parse(data: ByteArray, fallbackName: String = "torrent"): TorrentMeta {
            val top = Bencode.decode(data) as? Map<String, Any>
                ?: throw Bencode.ParseException("Torrent is not a dictionary")
            val info = top["info"] as? Map<String, Any>
                ?: throw Bencode.ParseException("Torrent has no info dictionary")

            val name = (info.str("name.utf-8") ?: info.str("name"))?.takeIf { it.isNotBlank() } ?: fallbackName
            val entries = ArrayList<Pair<List<String>, Long>>()
            val v1Files = info["files"] as? List<Any>
            val v2Tree = info["file tree"] as? Map<String, Any>

            val singleFile = when {
                // v1 (and hybrid, whose v1 list is authoritative for layout).
                v1Files != null -> {
                    for (f in v1Files) {
                        val fd = f as? Map<String, Any> ?: continue
                        val attr = fd.str("attr") ?: ""
                        val path = ((fd["path.utf-8"] ?: fd["path"]) as? List<Any>)
                            ?.map { String(it as ByteArray, Charsets.UTF_8) } ?: continue
                        if ('p' in attr || path.lastOrNull()?.startsWith(".pad") == true) continue
                        entries += path to ((fd["length"] as? Long) ?: 0L)
                    }
                    false
                }
                v2Tree != null -> {
                    walkV2(v2Tree, emptyList(), entries)
                    // A v2 single-file torrent is a tree with one entry named after the torrent.
                    entries.size == 1 && entries[0].first == listOf(name)
                }
                else -> {
                    entries += listOf(name) to ((info["length"] as? Long) ?: 0L)
                    true
                }
            }

            // url-list is either a single string or a list of strings.
            val webSeeds = when (val u = top["url-list"]) {
                is ByteArray -> listOf(String(u, Charsets.UTF_8))
                is List<*> -> u.mapNotNull { (it as? ByteArray)?.let { b -> String(b, Charsets.UTF_8) } }
                else -> emptyList()
            }.filter { it.startsWith("http://") || it.startsWith("https://") }

            return TorrentMeta(name, singleFile, buildTree(entries), webSeeds)
        }

        @Suppress("UNCHECKED_CAST")
        private fun walkV2(node: Map<String, Any>, prefix: List<String>, out: MutableList<Pair<List<String>, Long>>) {
            for ((key, value) in node) {
                val child = value as? Map<String, Any> ?: continue
                val leaf = child[""] as? Map<String, Any>
                if (leaf != null) {
                    out += (prefix + key) to ((leaf["length"] as? Long) ?: 0L)
                } else {
                    walkV2(child, prefix + key, out)
                }
            }
        }

        /** Group flat (path, size) entries into a sorted folder tree. Also used for archive.org items. */
        fun buildTree(entries: List<Pair<List<String>, Long>>): TorrentNode.Dir {
            class Builder(val name: String, val path: List<String>) {
                val dirs = LinkedHashMap<String, Builder>()
                val files = ArrayList<TorrentNode.File>()
                fun build(): TorrentNode.Dir = TorrentNode.Dir(
                    name, path,
                    dirs.values.map { it.build() }.sortedBy { it.name.lowercase() } +
                        files.sortedBy { it.name.lowercase() },
                )
            }

            val root = Builder("", emptyList())
            for ((path, size) in entries) {
                if (path.isEmpty()) continue
                var b = root
                for (seg in path.dropLast(1)) {
                    b = b.dirs.getOrPut(seg) { Builder(seg, b.path + seg) }
                }
                b.files += TorrentNode.File(path.last(), path, size)
            }
            return root.build()
        }

        private fun Map<String, Any>.str(key: String) = (this[key] as? ByteArray)?.let { String(it, Charsets.UTF_8) }
    }
}

sealed interface TorrentNode {
    val name: String
    /** Path inside the torrent, excluding the torrent's own name. */
    val path: List<String>
    val size: Long

    class Dir(override val name: String, override val path: List<String>, val children: List<TorrentNode>) : TorrentNode {
        override val size: Long = children.sumOf { it.size }
        val fileCount: Int = children.sumOf { if (it is Dir) it.fileCount else 1 }
    }

    class File(override val name: String, override val path: List<String>, override val size: Long) : TorrentNode
}
