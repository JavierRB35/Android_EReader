package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.LruCache
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.sync.SharedCoverArt
import com.javierrb35.bibliotecaia.sync.SharedLibrary
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Remote-only does not mean a book has no cover. Never download a book from a view. */
object BookCoverLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val missingLocal = LruCache<String, Boolean>(256)
    private val diskLock = Any()
    private var writes = 0

    suspend fun load(context: Context, book: BookEntity): Bitmap? = withContext(Dispatchers.IO) {
        val hash = book.contentHash
        if (!hash.matches(Regex("[a-f0-9]{64}"))) return@withContext null
        cache.get(hash)?.let { return@withContext it }
        val app = context.applicationContext
        val dir = File(app.cacheDir, "book-covers-v1")
        val target = File(dir, "$hash.jpg")
        val saved = synchronized(diskLock) {
            runCatching {
                if (target.isFile && target.length() in 1L..SharedCoverArt.MAX_BYTES.toLong())
                    decode(target.readBytes())?.also { target.setLastModified(System.currentTimeMillis()) }
                else null
            }.getOrNull()
        }
        if (saved != null) {
            cache.put(hash, saved)
            return@withContext saved
        }
        val file = File(book.localPath)
        val localKey = "$hash:${file.absolutePath}:${file.length()}:${file.lastModified()}"
        val local = if (book.localPath.isNotBlank() && file.isFile && missingLocal.get(localKey) != true) {
            extract(book.format, file).also { if (it == null) missingLocal.put(localKey, true) }
        } else null
        val bitmap = local ?: try {
            SharedLibrary.readCover(app, hash)?.let(::decode)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        if (bitmap != null) {
            cache.put(hash, bitmap)
            val bytes = jpeg(bitmap)
            if (bytes != null) synchronized(diskLock) {
                runCatching {
                    check(dir.isDirectory || dir.mkdirs())
                    val atomic = AtomicFile(target)
                    val stream = atomic.startWrite()
                    try { stream.write(bytes); atomic.finishWrite(stream) }
                    catch (e: Exception) { atomic.failWrite(stream); throw e }
                    if (++writes % 32 == 0) {
                        val images = dir.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }
                        var total = images.sumOf { it.length() }
                        for (image in images.sortedBy { it.lastModified() }) {
                            if (total <= 64L * 1024 * 1024) break
                            val size = image.length()
                            if (image.delete()) total -= size
                        }
                    }
                }
            }
        }
        bitmap
    }

    internal fun clearMemoryCache() {
        cache.evictAll()
        missingLocal.evictAll()
    }

    internal fun extract(format: BookFormat, file: File): Bitmap? {
        if (!file.isFile) return null
        return when (format) {
            BookFormat.EPUB -> EpubCoverSource.read(file)?.let(::decode)
            BookFormat.PDF -> null // archival metadata, no PDF renderer
            BookFormat.MOBI -> MobiCoverSource.read(file)?.let(::decode)
        }
    }

    internal fun jpeg(bitmap: Bitmap): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)) return null
        return out.toByteArray().takeIf(SharedCoverArt::isJpeg)
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
