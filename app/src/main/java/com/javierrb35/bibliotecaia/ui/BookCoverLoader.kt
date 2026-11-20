package com.javierrb35.bibliotecaia.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded in-memory thumbnails; original files are never modified. */
object BookCoverLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val missing = LruCache<String, Boolean>(256)
    private val mutex = Mutex()

    suspend fun load(book: BookEntity): Bitmap? = withContext(Dispatchers.IO) {
        if (book.format != BookFormat.EPUB) return@withContext null
        mutex.withLock {
            cache.get(book.contentHash)?.let { return@withLock it }
            if (missing.get(book.contentHash) == true) return@withLock null
            val bytes = EpubCoverSource.read(File(book.localPath))
            val bitmap = bytes?.let { decode(it) }
            if (bitmap == null) missing.put(book.contentHash, true)
            else cache.put(book.contentHash, bitmap)
            bitmap
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 20000 || bounds.outHeight > 20000) return null
        var sample = 1
        while (bounds.outWidth / sample > 360 || bounds.outHeight / sample > 540) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}
