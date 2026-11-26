package com.javierrb35.bibliotecaia.ui

import java.io.File
import java.io.RandomAccessFile

/**
 * Read only explicitly identified MOBI cover/thumbnail records (EXTH 201/202).
 * Format reference: https://github.com/kovidgoyal/calibre/blob/master/format_docs/pdb/mobi.txt
 * No decompression of text, no DRM handling, no guessing from interior illustrations.
 */
object MobiCoverSource {
    private const val LIMIT = 8 * 1024 * 1024
    fun read(file: File): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                if (length < 94) return null
                input.seek(60)
                if (String(ByteArray(8).also(input::readFully), Charsets.US_ASCII) != "BOOKMOBI") return null
                input.seek(76)
                val count = input.readUnsignedShort()
                val tableEnd = 78L + count * 8L
                if (count < 2 || tableEnd > length) return null
                val offsets = LongArray(count + 1)
                repeat(count) { i ->
                    input.seek(78L + i * 8)
                    offsets[i] = input.readInt().toLong() and 0xffffffffL
                    if (offsets[i] < tableEnd || offsets[i] >= length ||
                        (i > 0 && offsets[i] <= offsets[i - 1])) return null
                }
                offsets[count] = length
                fun record(i: Int): ByteArray? {
                    if (i !in 0 until count) return null
                    val size = offsets[i + 1] - offsets[i]
                    if (size !in 1L..LIMIT.toLong()) return null
                    input.seek(offsets[i])
                    return ByteArray(size.toInt()).also(input::readFully)
                }
                val header = record(0) ?: return null
                fun number(at: Int): Long {
                    require(at >= 0 && at <= header.size - 4)
                    return (0..3).fold(0L) { result, i -> (result shl 8) or (header[at+i].toLong() and 255L) }
                }
                if (header.size < 132 || header[12].toInt() != 0 || header[13].toInt() != 0 ||
                    String(header, 16, 4, Charsets.US_ASCII) != "MOBI") return null
                val headerSize = number(20)
                if (headerSize < 116 || headerSize > header.size - 16L) return null
                val firstImage = number(108)
                if (firstImage !in 1L until count.toLong()) return null
                val exth = (16 + headerSize).toInt()
                if (exth > header.size - 12 || String(header, exth, 4, Charsets.US_ASCII) != "EXTH") return null
                val exthSize = number(exth + 4)
                val records = number(exth + 8)
                if (exthSize < 12 || exthSize > header.size - exth || records > (exthSize - 12) / 8) return null
                var at = exth + 12
                val end = exth + exthSize.toInt()
                var cover: Long? = null
                var thumb: Long? = null
                repeat(records.toInt()) {
                    if (at > end - 8) return null
                    val type = number(at)
                    val size = number(at + 4)
                    if (size < 8 || size > end - at) return null
                    if (size >= 12) {
                        if (type == 201L) cover = number(at + 8)
                        if (type == 202L) thumb = number(at + 8)
                    }
                    at += size.toInt()
                }
                for (offset in listOfNotNull(cover, thumb)) {
                    val index = firstImage + offset
                    if (index !in 1L until count.toLong()) continue
                    val bytes = record(index.toInt()) ?: continue
                    // Only supported raster signatures; BitmapFactory validates the image itself.
                    if (bytes.size >= 8 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) ||
                            bytes.take(8) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte(),
                                0x0d.toByte(), 0x0a.toByte(), 0x1a.toByte(), 0x0a.toByte()) ||
                            String(bytes, 0, 3, Charsets.US_ASCII) == "GIF")) return bytes
                }
                null
            }
        } catch (_: Exception) { null }
    }
}
