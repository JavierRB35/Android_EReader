package com.javierrb35.bibliotecaia.sync

import kotlinx.coroutines.CancellationException

/** Immutable derived thumbnails, never evidence of an original. */
internal object SharedCoverArt {
    const val MAX_BYTES = 512 * 1024
    private val hash = Regex("[a-f0-9]{64}")
    private val pattern = Regex("bia-cover-v1-([a-f0-9]{64})-([a-f0-9]{64})\\.jpg")
    fun isName(name: String) = pattern.matches(name)
    fun bookHash(name: String): String? = pattern.matchEntire(name)?.groupValues?.get(1)
    fun isJpeg(bytes: ByteArray) = bytes.size in 4..MAX_BYTES &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()

    fun read(store: SharedStore, items: List<SharedDocumentStore.Item>, book: String): ByteArray? {
        if (!hash.matches(book)) return null
        for (item in items) {
            val match = pattern.matchEntire(item.name) ?: continue
            if (match.groupValues[1] != book || item.size > MAX_BYTES || item.directory) continue
            try {
                val bytes = store.open(item).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= MAX_BYTES)
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                if (isJpeg(bytes) && SharedDocumentStore.hash(bytes) == match.groupValues[2]) return bytes
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A corrupt thumbnail must not block the book. */ }
        }
        return null
    }

    fun publish(store: SharedStore, book: String, bytes: ByteArray,
                items: List<SharedDocumentStore.Item> = store.list()): Boolean {
        if (!store.namedOriginals || !hash.matches(book) || !isJpeg(bytes)) return false
        if (read(store, items, book) != null) return false
        val digest = SharedDocumentStore.hash(bytes)
        val name = "bia-cover-v1-$book-$digest.jpg"
        if (items.any { it.name == name }) return false // Never overwrite a provider document.
        store.create(name, "image/jpeg", bytes.inputStream(), digest, bytes.size.toLong())
        return true
    }
}
