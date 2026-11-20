package com.javierrb35.bibliotecaia.reader.pdf

import android.content.Context
import java.io.File

/** Private, edition-bound OCR cache; the JSON is authoritative for marks. */
object PdfOcrStore {
    private fun directory(context: Context, bookId: String): File? {
        if (!Regex("""[A-Za-z0-9_-]{1,128}""").matches(bookId)) return null
        return File(context.filesDir, "ocr/$bookId")
    }

    fun load(context: Context, bookId: String, page: Int, hash: String): PdfOcrSnapshot? {
        if (page < 0) return null
        val file = File(directory(context, bookId) ?: return null, "page-" + (page + 1) + ".json")
        if (!file.isFile || file.length() !in 1L..450_000L) return null
        return runCatching {
            PdfOcrGeometry.decode(file.readText(Charsets.UTF_8), hash)
        }.getOrNull()
    }

    fun save(context: Context, bookId: String, page: Int, snapshot: PdfOcrSnapshot): Boolean {
        if (page < 0 || !PdfOcrGeometry.valid(snapshot)) return false
        val dir = directory(context, bookId) ?: return false
        return runCatching {
            check(dir.isDirectory || dir.mkdirs())
            val base = "page-" + (page + 1)
            val jsonFile = File(dir, "$base.json")
            val tmpJson = File(dir, "$base.json.tmp")
            tmpJson.writeText(PdfOcrGeometry.encode(snapshot), Charsets.UTF_8)
            check(tmpJson.renameTo(jsonFile)) { "No se pudo conservar el mapa OCR" }
            // The plaintext is only used by the bounded RAG retrieval layer.
            val textFile = File(dir, "$base.txt")
            val tmpText = File(dir, "$base.txt.tmp")
            tmpText.writeText(snapshot.text, Charsets.UTF_8)
            check(tmpText.renameTo(textFile)) { "No se pudo conservar la transcripción" }
            true
        }.getOrDefault(false)
    }
}
