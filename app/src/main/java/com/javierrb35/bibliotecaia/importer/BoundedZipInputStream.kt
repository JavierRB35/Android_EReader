package com.javierrb35.bibliotecaia.importer

import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Metadata scans also inflate skipped entries; bound every read, including closeEntry drains. */
internal class BoundedZipInputStream(input: InputStream) : ZipInputStream(input) {
    private var inflated = 0L
    private var entries = 0
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(bytes, offset, length)
        if (count > 0) inflated += count
        if (inflated > 256L * 1024 * 1024) throw IOException("EPUB descomprimido demasiado grande")
        return count
    }
    override fun getNextEntry(): ZipEntry? {
        val entry = super.getNextEntry()
        if (entry != null && ++entries > 10000) throw IOException("Demasiados recursos EPUB")
        return entry
    }
    fun readEntry(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (out.size() + count > limit) throw IOException("Recurso EPUB demasiado grande")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
