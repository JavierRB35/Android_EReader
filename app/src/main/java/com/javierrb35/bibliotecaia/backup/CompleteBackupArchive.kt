package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.BookEntity
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable offline backup ZIP: full JSON snapshot and every original EPUB/PDF/MOBI.
 * Streaming and bounded. No account or Drive API.
 */
object CompleteBackupArchive {
    private const val METADATA = "biblioteca.json"
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024
    private const val MAX_BOOK_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_BOOKS = 10_000
    private val HASH = Regex("[a-f0-9]{64}")
    private val SEGMENT = Regex("[A-Za-z0-9_-]{1,128}")

    data class Contents(val json: String, val staged: Map<String, File>, val bytes: Long,
                        val missingHashes: Set<String> = emptySet()) {
        val bookCount: Int get() = staged.size
    }

    // Missing originals are explicit, not silently tolerated ZIP corruption.
    fun withMissingOriginals(json: String, missingHashes: Set<String>): String {
        val root = JSONObject(json)
        root.getJSONObject("manifest").put("missingOriginals", org.json.JSONArray(missingHashes.sorted()))
        return root.toString()
    }

    private fun missingOriginals(json: String, books: List<BookEntity>): Set<String> {
        val manifest = JSONObject(json).getJSONObject("manifest")
        if (!manifest.has("missingOriginals")) return emptySet()
        val array = manifest.getJSONArray("missingOriginals")
        require(array.length() <= books.size)
        val hashes = (0 until array.length()).map { array.getString(it) }
        require(hashes.distinct().size == hashes.size && books.map { it.contentHash }.containsAll(hashes)) {
            "La lista de originales ausentes no coincide con el catálogo."
        }
        return hashes.toSet()
    }

    fun write(output: OutputStream, json: String, books: List<BookEntity>): Long {
        validateCompleteMetadata(json)
        val snapshot = BackupCodec.fromJson(json)
        val missing = missingOriginals(json, snapshot.books)
        val included = snapshot.books.filter { it.contentHash !in missing }
        require(included.map { it.id to it.contentHash }.toSet() ==
            books.map { it.id to it.contentHash }.toSet() && included.size == books.size) {
            "La biblioteca cambió durante la preparación de la copia."
        }
        require(books.size <= MAX_BOOKS) { "Demasiados libros para una sola copia." }
        val metadata = json.toByteArray(Charsets.UTF_8)
        require(metadata.size in 1..MAX_JSON_BYTES) { "Los metadatos superan el límite de seguridad." }
        var total = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(METADATA))
            zip.write(metadata)
            zip.closeEntry()
            val buffer = ByteArray(64 * 1024)
            for (book in books.sortedBy { it.id }) {
                validateBook(book)
                val source = File(book.localPath)
                require(source.isFile && source.length() == book.sizeBytes) {
                    "Falta el original o cambió su tamaño: " + book.title.take(80) + ". La copia se cancela."
                }
                zip.putNextEntry(ZipEntry(entryName(book.id)))
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                source.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        count += n
                        total += n
                        require(count <= MAX_BOOK_BYTES && total <= MAX_TOTAL_BYTES) {
                            "Los libros superan el tamaño máximo admitido para una copia."
                        }
                        digest.update(buffer, 0, n)
                        zip.write(buffer, 0, n)
                    }
                }
                require(count == book.sizeBytes && hex(digest.digest()) == book.contentHash) {
                    "El archivo de " + book.title.take(80) + " no coincide con el importado."
                }
                zip.closeEntry()
            }
            zip.finish()
        }
        return total
    }

    /** Extract only declared originals into a private staging directory. */
    fun read(input: InputStream, stageDir: File, wantedHashes: Set<String>? = null): Contents {
        require(stageDir.isDirectory) { "No existe el espacio temporal de restauración." }
        var json: String? = null
        var expected: Map<String, BookEntity>? = null
        var missing: Set<String> = emptySet()
        val staged = linkedMapOf<String, File>()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        val seen = hashSetOf<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(seen.add(entry.name) && !entry.isDirectory) {
                    "La copia contiene entradas duplicadas o inválidas."
                }
                if (json == null) {
                    require(entry.name != "bia-owner.txt") {
                        "Has seleccionado un archivo de biblioteca compartida, no una copia de seguridad. Usa Biblioteca compartida en Ajustes."
                    }
                    require(entry.name == METADATA) {
                        "Este ZIP no es una copia de seguridad válida de BibliotecaAI. El archivo no se modificará."
                    }
                    val data = java.io.ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        require(data.size() <= MAX_JSON_BYTES - n) {
                            "El manifiesto supera el límite de seguridad."
                        }
                        data.write(buffer, 0, n)
                    }
                    json = data.toString(Charsets.UTF_8.name())
                    validateCompleteMetadata(json)
                    val books = BackupCodec.fromJson(json).books
                    require(books.size <= MAX_BOOKS) { "La copia contiene demasiados libros." }
                    books.forEach(::validateBook)
                    require(books.map { it.id }.distinct().size == books.size) { "La copia repite identificadores de libros." }
                    missing = missingOriginals(json, books)
                    expected = books.filter { it.contentHash !in missing }.associateBy { entryName(it.id) }
                } else {
                    val book = expected?.get(entry.name)
                        ?: throw IllegalArgumentException("La copia contiene archivos inesperados.")
                    require(entry.name !in staged.keys) { "Libro duplicado en copia." }
                    val target = File(stageDir, book.id + ".bin").canonicalFile
                    require(target.parentFile == stageDir.canonicalFile && !target.exists()) {
                        "Ruta no permitida en la copia."
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val keep = wantedHashes == null || book.contentHash in wantedHashes
                    val output = if (keep) target.outputStream() else null
                    output.use { sink ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            size += n
                            total += n
                            require(size <= MAX_BOOK_BYTES && total <= MAX_TOTAL_BYTES &&
                                size <= book.sizeBytes) { "La copia contiene un archivo sobredimensionado." }
                            digest.update(buffer, 0, n)
                            sink?.write(buffer, 0, n)
                        }
                    }
                    require(size == book.sizeBytes && hex(digest.digest()) == book.contentHash) {
                        "La copia contiene un libro incompleto o modificado."
                    }
                    if (keep) staged[entry.name] = target
                }
                zip.closeEntry()
            }
        }
        val metadata = json ?: throw IllegalArgumentException("No es una copia completa de Biblioteca IA.")
        require(expected!!.keys == seen - METADATA) {
            "Faltan originales: el archivo no es una copia completa."
        }
        return Contents(metadata, staged, total, missing)
    }

    /** Legacy JSON import is allowed separately; a ZIP must preserve ALL sections. */
    private fun validateCompleteMetadata(json: String) {
        val root = JSONObject(json)
        val keys = listOf("books", "categories", "tags", "collections",
            "bookCategories", "bookTags", "collectionBooks", "annotations",
            "annotationVersions", "readingPositions")
        require(keys.all { root.optJSONArray(it) != null }) {
            "El ZIP no contiene todas las secciones de la biblioteca."
        }
        val counts = root.optJSONObject("manifest")?.optJSONObject("counts")
        require(counts != null &&
            counts.optInt("books", -1) == root.getJSONArray("books").length() &&
            counts.optInt("annotations", -1) == root.getJSONArray("annotations").length() &&
            counts.optInt("versions", -1) == root.getJSONArray("annotationVersions").length() &&
            counts.optInt("positions", -1) == root.getJSONArray("readingPositions").length()
        ) { "El manifiesto de la copia completa no coincide con sus datos." }
    }

    fun entryName(id: String) = "books/" + id + ".bin"

    private fun validateBook(book: BookEntity) {
        require(SEGMENT.matches(book.id) &&
            book.fileName.isNotBlank() && book.fileName.length <= 255 &&
            book.fileName != "." && book.fileName != ".." &&
            book.fileName.none { it == '/' || it == '\\' || it.code < 32 } &&
            HASH.matches(book.contentHash) &&
            book.sizeBytes in 1..MAX_BOOK_BYTES) {
            "La copia contiene un libro con identificador, ruta, hash o tamaño inválido."
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
