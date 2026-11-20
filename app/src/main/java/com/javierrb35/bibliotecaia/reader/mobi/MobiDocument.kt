package com.javierrb35.bibliotecaia.reader.mobi

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Deliberately bounded MOBI 6 text reader. Never decrypts or uploads the original. */
object MobiDocument {
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    private const val MAX_RECORD_BYTES = 1024 * 1024

    class Unsupported(message: String) : IOException(message)

    fun readHtml(file: File): String = RandomAccessFile(file, "r").use { input ->
        fun invalid(): Nothing = throw IOException("El MOBI está incompleto o tiene una estructura no válida.")
        if (input.length() < 94) invalid()
        input.seek(60)
        val magic = ByteArray(8).also(input::readFully)
        if (!magic.contentEquals("BOOKMOBI".toByteArray(Charsets.US_ASCII))) invalid()
        input.seek(76)
        val count = input.readUnsignedShort()
        val tableEnd = 78L + count * 8L
        if (count < 2 || tableEnd > input.length()) invalid()
        val offsets = LongArray(count + 1)
        repeat(count) { i ->
            input.seek(78L + i * 8L)
            offsets[i] = input.readInt().toLong() and 0xffffffffL
            if (offsets[i] < tableEnd || offsets[i] >= input.length() ||
                (i > 0 && offsets[i] <= offsets[i - 1])) invalid()
        }
        offsets[count] = input.length()
        fun record(index: Int): ByteArray {
            val length = offsets[index + 1] - offsets[index]
            if (length !in 1..MAX_RECORD_BYTES.toLong()) invalid()
            input.seek(offsets[index])
            return ByteArray(length.toInt()).also(input::readFully)
        }
        val header = record(0)
        fun u16(at: Int): Int {
            if (at < 0 || at + 2 > header.size) invalid()
            return ((header[at].toInt() and 255) shl 8) or (header[at + 1].toInt() and 255)
        }
        fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        if (header.size < 16) invalid()
        if (u16(12) != 0) throw Unsupported("Este MOBI tiene DRM. No se modificará su protección.")
        if (header.size < 40 || String(header, 16, 4, Charsets.US_ASCII) != "MOBI") invalid()
        val compression = u16(0)
        val headerLength = u32(20)
        if (headerLength < 24 || headerLength > header.size - 16L) invalid()
        if (headerLength >= 164 && u32(172) != 0L)
            throw Unsupported("Este MOBI declara DRM. No se modificará su protección.")
        val version = u32(36)
        if (version !in 1L..6L) throw Unsupported("MOBI/KF8 no compatible todavía. Utiliza una edición EPUB sin DRM.")
        if (compression != 1 && compression != 2)
            throw Unsupported("La compresión HUFF/CDIC de este MOBI no es compatible todavía.")
        val charset = when (u32(28)) {
            65001L -> Charsets.UTF_8
            1252L -> charset("windows-1252")
            else -> throw Unsupported("La codificación de este MOBI no es compatible todavía.")
        }
        val textLength = u32(4)
        if (textLength !in 1..MAX_TEXT_BYTES.toLong())
            throw Unsupported("El texto MOBI supera el límite de lectura de 8 MiB o está vacío.")
        val textRecords = u16(8)
        val recordSize = u16(10)
        if (textRecords < 1 || textRecords >= count || recordSize !in 1..65535) invalid()
        val trailing = if (headerLength >= 228) u16(242) else 0
        val output = ByteArrayOutputStream(textLength.toInt())
        repeat(textRecords) { i ->
            val packed = stripTrailing(record(i + 1), trailing)
            val decoded = if (compression == 1) packed else palmDoc(packed, recordSize)
            if (decoded.size > recordSize || output.size().toLong() + decoded.size > MAX_TEXT_BYTES) invalid()
            output.write(decoded)
        }
        if (output.size().toLong() != textLength) invalid()
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    internal fun stripTrailing(record: ByteArray, flags: Int): ByteArray {
        var end = record.size
        repeat(Integer.bitCount(flags ushr 1)) {
            var size = 0
            var shift = 0
            var found = false
            for (i in end - 1 downTo maxOf(0, end - 4)) {
                val b = record[i].toInt() and 255
                size = size or ((b and 127) shl shift)
                shift += 7
                if (b and 128 != 0) { found = true; break }
            }
            if (!found || size <= 0 || size > end) throw IOException("Datos finales MOBI no válidos.")
            end -= size
        }
        if (flags and 1 != 0) {
            if (end == 0) throw IOException("Datos multibyte MOBI incompletos.")
            end -= (record[end - 1].toInt() and 3) + 1
            if (end < 0) throw IOException("Datos multibyte MOBI incompletos.")
        }
        return record.copyOf(end)
    }

    /** PalmDOC LZ77 with overlap, input bounds and an explicit expansion limit. */
    internal fun palmDoc(input: ByteArray, limit: Int): ByteArray {
        require(limit in 1..65535)
        val output = ByteArray(limit)
        var used = 0
        var cursor = 0
        fun emit(value: Byte) {
            if (used >= limit) throw IOException("El registro MOBI excede su tamaño declarado.")
            output[used++] = value
        }
        while (cursor < input.size) {
            val token = input[cursor++].toInt() and 255
            when {
                token in 1..8 -> {
                    if (cursor + token > input.size) throw IOException("Registro MOBI truncado.")
                    repeat(token) { emit(input[cursor++]) }
                }
                token < 128 -> emit(token.toByte())
                token >= 192 -> { emit(32); emit((token xor 128).toByte()) }
                else -> {
                    if (cursor == input.size) throw IOException("Referencia MOBI truncada.")
                    val pair = (token shl 8) or (input[cursor++].toInt() and 255)
                    val distance = (pair ushr 3) and 2047
                    val length = (pair and 7) + 3
                    if (distance == 0 || distance > used) throw IOException("Referencia MOBI fuera de rango.")
                    repeat(length) { emit(output[used - distance]) }
                }
            }
        }
        return output.copyOf(used)
    }
}
