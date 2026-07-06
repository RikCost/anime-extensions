package eu.kanade.tachiyomi.animeextension.ru.rutracker

import java.security.MessageDigest

/**
 * Minimal bencode reader for .torrent files. Extracts the info-hash (SHA-1 of the raw `info`
 * dictionary), the display name, the trackers and the file list — enough to build a magnet
 * link with per-file `&index=` selectors without depending on the runtime torrent helper.
 */
object RuTrackerTorrent {

    data class Entry(val index: Int, val path: String, val length: Long)

    data class Meta(
        val infoHashHex: String,
        val name: String,
        val trackers: List<String>,
        val files: List<Entry>,
    )

    fun parse(data: ByteArray): Meta {
        require(data.isNotEmpty() && data[0].toInt().toChar() == 'd') { "Not a bencoded torrent" }

        val reader = Reader(data)
        reader.pos = 1 // consume the top-level 'd'

        var announce: String? = null
        val announceList = mutableListOf<String>()
        var name = ""
        var infoHashHex = ""
        val entries = mutableListOf<Entry>()

        while (data[reader.pos].toInt().toChar() != 'e') {
            when (reader.readByteString().toString(Charsets.UTF_8)) {
                "announce" -> announce = (reader.readValue() as ByteArray).toString(Charsets.UTF_8)
                "announce-list" -> {
                    (reader.readValue() as? List<*>).orEmpty().forEach { tier ->
                        (tier as? List<*>).orEmpty().forEach {
                            announceList += (it as ByteArray).toString(Charsets.UTF_8)
                        }
                    }
                }
                "info" -> {
                    val start = reader.pos
                    val info = reader.readValue() as Map<*, *>
                    infoHashHex = sha1Hex(data.copyOfRange(start, reader.pos))
                    name = (info["name"] as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()

                    val files = info["files"]
                    if (files is List<*>) {
                        files.forEachIndexed { index, entry ->
                            val fields = entry as Map<*, *>
                            val length = fields["length"] as? Long ?: 0L
                            val path = (fields["path"] as? List<*>).orEmpty()
                                .joinToString("/") { (it as ByteArray).toString(Charsets.UTF_8) }
                            entries += Entry(index, path, length)
                        }
                    } else {
                        entries += Entry(0, name, info["length"] as? Long ?: 0L)
                    }
                }
                else -> reader.readValue() // skip unknown keys
            }
        }

        val trackers = (listOfNotNull(announce) + announceList)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return Meta(infoHashHex, name, trackers, entries)
    }

    private fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private class Reader(val data: ByteArray) {
        var pos = 0

        fun readValue(): Any = when (val c = data[pos].toInt().toChar()) {
            'i' -> {
                pos++
                readUntil('e').toLong()
            }
            'l' -> {
                pos++
                val list = ArrayList<Any>()
                while (data[pos].toInt().toChar() != 'e') list += readValue()
                pos++
                list
            }
            'd' -> {
                pos++
                val map = LinkedHashMap<String, Any>()
                while (data[pos].toInt().toChar() != 'e') {
                    val key = readByteString().toString(Charsets.UTF_8)
                    map[key] = readValue()
                }
                pos++
                map
            }
            in '0'..'9' -> readByteString()
            else -> error("Unexpected bencode marker '$c' at $pos")
        }

        fun readByteString(): ByteArray {
            val length = readUntil(':').toInt()
            val bytes = data.copyOfRange(pos, pos + length)
            pos += length
            return bytes
        }

        private fun readUntil(marker: Char): String {
            val start = pos
            while (data[pos].toInt().toChar() != marker) pos++
            val token = String(data, start, pos - start, Charsets.ISO_8859_1)
            pos++ // consume the marker
            return token
        }
    }
}
