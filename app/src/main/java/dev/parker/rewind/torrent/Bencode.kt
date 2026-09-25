package dev.parker.rewind.torrent

/** Minimal bencode decoder. Byte strings stay as [ByteArray] because torrent pieces are binary. */
object Bencode {
    class ParseException(message: String) : Exception(message)

    fun decode(data: ByteArray): Any = Parser(data).run {
        val value = next()
        if (pos != data.size) throw ParseException("Trailing data at offset $pos")
        value
    }

    private class Parser(val data: ByteArray) {
        var pos = 0

        fun next(): Any {
            if (pos >= data.size) throw ParseException("Unexpected end of data")
            return when (val c = data[pos].toInt().toChar()) {
                'i' -> readInt()
                'l' -> readList()
                'd' -> readDict()
                in '0'..'9' -> readBytes()
                else -> throw ParseException("Unexpected '$c' at offset $pos")
            }
        }

        private fun readInt(): Long {
            pos++ // 'i'
            val end = indexOf('e')
            val value = String(data, pos, end - pos, Charsets.US_ASCII).toLongOrNull()
                ?: throw ParseException("Bad integer at offset $pos")
            pos = end + 1
            return value
        }

        private fun readBytes(): ByteArray {
            val colon = indexOf(':')
            val len = String(data, pos, colon - pos, Charsets.US_ASCII).toIntOrNull()
                ?: throw ParseException("Bad string length at offset $pos")
            val start = colon + 1
            if (len < 0 || start + len > data.size) throw ParseException("String overruns data at offset $pos")
            pos = start + len
            return data.copyOfRange(start, start + len)
        }

        private fun readList(): List<Any> {
            pos++ // 'l'
            val list = ArrayList<Any>()
            while (peek() != 'e') list.add(next())
            pos++
            return list
        }

        private fun readDict(): Map<String, Any> {
            pos++ // 'd'
            val map = LinkedHashMap<String, Any>()
            while (peek() != 'e') {
                val key = String(readBytes(), Charsets.UTF_8)
                map[key] = next()
            }
            pos++
            return map
        }

        private fun peek(): Char {
            if (pos >= data.size) throw ParseException("Unexpected end of data")
            return data[pos].toInt().toChar()
        }

        private fun indexOf(c: Char): Int {
            var i = pos
            while (i < data.size) {
                if (data[i].toInt().toChar() == c) return i
                i++
            }
            throw ParseException("Missing '$c' after offset $pos")
        }
    }
}
