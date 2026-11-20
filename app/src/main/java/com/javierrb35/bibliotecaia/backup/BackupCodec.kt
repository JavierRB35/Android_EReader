package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.AnnotationVersionEntity
import com.javierrb35.bibliotecaia.data.BookCategoryCrossRef
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.CollectionBookCrossRef
import com.javierrb35.bibliotecaia.data.CollectionEntity
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import com.javierrb35.bibliotecaia.data.ReadingStatus
import com.javierrb35.bibliotecaia.data.TagEntity
import org.json.JSONArray
import org.json.JSONObject

/** Instantánea completa de la biblioteca lista para serializar (§16.3). */
data class LibrarySnapshot(
    val books: List<BookEntity>,
    val categories: List<CategoryEntity>,
    val tags: List<TagEntity>,
    val collections: List<CollectionEntity>,
    val bookCategories: List<BookCategoryCrossRef>,
    val bookTags: List<BookTagCrossRef>,
    val collectionBooks: List<CollectionBookCrossRef>,
    val annotations: List<AnnotationEntity>,
    val versions: List<AnnotationVersionEntity>,
    val positions: List<ReadingPositionEntity>,
)

object BackupCodec {

    const val FORMAT_VERSION = 1
    private const val APP_ID = "biblioteca-ia"

    fun toJson(snapshot: LibrarySnapshot, appVersion: String, exportedAt: Long): String {
        val root = JSONObject()
        root.put(
            "manifest",
            JSONObject()
                .put("app", APP_ID)
                .put("formatVersion", FORMAT_VERSION)
                .put("appVersion", appVersion)
                .put("exportedAt", exportedAt)
                .put("counts", JSONObject()
                    .put("books", snapshot.books.size)
                    .put("annotations", snapshot.annotations.size)
                    .put("versions", snapshot.versions.size)
                    .put("positions", snapshot.positions.size))
        )
        root.put("books", JSONArray().apply { snapshot.books.forEach { put(bookJson(it)) } })
        root.put("categories", JSONArray().apply { snapshot.categories.forEach { put(categoryJson(it)) } })
        root.put("tags", JSONArray().apply { snapshot.tags.forEach { put(tagJson(it)) } })
        root.put("collections", JSONArray().apply { snapshot.collections.forEach { put(collectionJson(it)) } })
        root.put("bookCategories", JSONArray().apply { snapshot.bookCategories.forEach { put(refJson(it.bookId, it.categoryId)) } })
        root.put("bookTags", JSONArray().apply { snapshot.bookTags.forEach { put(refJson(it.bookId, it.tagId)) } })
        root.put("collectionBooks", JSONArray().apply { snapshot.collectionBooks.forEach { put(refJson(it.collectionId, it.bookId)) } })
        root.put("annotations", JSONArray().apply { snapshot.annotations.forEach { put(annotationJson(it)) } })
        root.put("annotationVersions", JSONArray().apply { snapshot.versions.forEach { put(versionJson(it)) } })
        root.put("readingPositions", JSONArray().apply { snapshot.positions.forEach { put(positionJson(it)) } })
        return root.toString(2)
    }

    private fun bookJson(b: BookEntity): JSONObject = JSONObject()
        .put("id", b.id)
        .put("title", b.title)
        .put("authors", b.authors ?: JSONObject.NULL)
        .put("format", b.format.name)
        .put("file_name", b.fileName)
        .put("content_hash", b.contentHash)
        .put("size_bytes", b.sizeBytes)
        .put("added_at", b.addedAt)
        .put("last_opened_at", b.lastOpenedAt ?: JSONObject.NULL)
        .put("reading_status", b.readingStatus.name)
        .put("favorite", b.favorite)
        .put("progress", b.progress.toDouble())

    private fun categoryJson(c: CategoryEntity): JSONObject = JSONObject()
        .put("id", c.id).put("name", c.name)
        .put("parent_id", c.parentId ?: JSONObject.NULL).put("sort_order", c.sortOrder)

    private fun tagJson(t: TagEntity): JSONObject = JSONObject().put("id", t.id).put("name", t.name)

    private fun collectionJson(c: CollectionEntity): JSONObject =
        JSONObject().put("id", c.id).put("name", c.name).put("created_at", c.createdAt)

    private fun refJson(first: String, second: String): JSONObject =
        JSONObject().put("first", first).put("second", second)

    private fun annotationJson(a: AnnotationEntity): JSONObject = JSONObject()
        .put("id", a.id)
        .put("book_id", a.bookId ?: JSONObject.NULL)
        .put("locator", a.locator ?: JSONObject.NULL)
        .put("quote", a.quote)
        .put("body", a.body)
        .put("kind", a.kind)
        .put("color", a.color)
        .put("created_at", a.createdAt)
        .put("updated_at", a.updatedAt)
        .put("deleted_at", a.deletedAt ?: JSONObject.NULL)

    private fun versionJson(v: AnnotationVersionEntity): JSONObject = JSONObject()
        .put("id", v.id).put("annotation_id", v.annotationId)
        .put("body", v.body).put("color", v.color).put("saved_at", v.savedAt)

    private fun positionJson(p: ReadingPositionEntity): JSONObject = JSONObject()
        .put("book_id", p.bookId).put("locator", p.locator)
        .put("progress", p.progress.toDouble()).put("updated_at", p.updatedAt)

    /** Deserialización estricta (Issue #10.4): valida manifiesto, secciones y recuentos. */
    fun fromJson(json: String): LibrarySnapshot {
        val root = try { JSONObject(json) } catch (e: Exception) {
            throw IllegalArgumentException("El archivo no es una copia válida de Biblioteca IA.")
        }
        val manifest = root.optJSONObject("manifest")
            ?: throw IllegalArgumentException("El archivo no es una copia válida de Biblioteca IA.")
        if (manifest.optString("app") != APP_ID) {
            throw IllegalArgumentException("La copia pertenece a otra aplicación.")
        }
        if (!manifest.has("formatVersion") || !manifest.has("exportedAt")) {
            throw IllegalArgumentException("La copia está incompleta (manifiesto sin versión o fecha).")
        }
        val format = manifest.optInt("formatVersion", -1)
        if (format !in 1..FORMAT_VERSION) {
            throw IllegalArgumentException("La copia usa un formato no compatible (versión $format).")
        }
        listOf("books", "categories", "annotations").forEach { key ->
            if (!root.has(key) || root.optJSONArray(key) == null) {
                throw IllegalArgumentException("La copia está incompleta: falta la sección $key.")
            }
        }
        val counts = manifest.optJSONObject("counts")
        if (counts != null) {
            val expectedBooks = counts.optInt("books", -1)
            if (expectedBooks >= 0 && root.optJSONArray("books")?.length() != expectedBooks) {
                throw IllegalArgumentException("La copia está incompleta (libros declarados: $expectedBooks).")
            }
            val expectedAnnotations = counts.optInt("annotations", -1)
            if (expectedAnnotations >= 0 && root.optJSONArray("annotations")?.length() != expectedAnnotations) {
                throw IllegalArgumentException("La copia está incompleta (anotaciones declaradas: $expectedAnnotations).")
            }
        }
        return LibrarySnapshot(
            books = root.optJSONArray("books").mapList { bookFrom(it) },
            categories = root.optJSONArray("categories").mapList { categoryFrom(it) },
            tags = root.optJSONArray("tags").mapList { tagFrom(it) },
            collections = root.optJSONArray("collections").mapList { collectionFrom(it) },
            bookCategories = root.optJSONArray("bookCategories").mapList { refPair(it) }
                .map { BookCategoryCrossRef(it.first, it.second) },
            bookTags = root.optJSONArray("bookTags").mapList { refPair(it) }
                .map { BookTagCrossRef(it.first, it.second) },
            collectionBooks = root.optJSONArray("collectionBooks").mapList { refPair(it) }
                .map { CollectionBookCrossRef(it.first, it.second) },
            annotations = root.optJSONArray("annotations").mapList { annotationFrom(it) },
            versions = root.optJSONArray("annotationVersions").mapList { versionFrom(it) },
            positions = root.optJSONArray("readingPositions").mapList { positionFrom(it) },
        )
    }

    private fun <T> JSONArray?.mapList(transform: (JSONObject) -> T): List<T> {
        this ?: return emptyList()
        return List(length()) { i -> transform(optJSONObject(i) ?: JSONObject()) }
    }

    private fun safePathSegment(value: String, label: String): String {
        require(value.isNotBlank() && value.length <= 255 &&
            value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) {
            "Copia dañada: $label contiene una ruta o nombre no permitido."
        }
        return value
    }

    private fun bookFrom(o: JSONObject): BookEntity = BookEntity(
        id = safePathSegment(o.getString("id"), "identificador del libro"),
        title = o.getString("title"),
        contentHash = o.getString("content_hash").also { require(it.isNotBlank()) { "Libro sin hash en la copia." } },
        authors = o.optString("authors").ifEmpty { null },
        format = runCatching { BookFormat.valueOf(o.getString("format")) }.getOrDefault(BookFormat.EPUB),
        fileName = safePathSegment(o.optString("file_name", "libro"), "nombre del libro"),
        localPath = "", // se reconstruye en la restauración; revinculación por hash (ADR-008)
        sizeBytes = o.optLong("size_bytes", 0),
        addedAt = o.optLong("added_at", System.currentTimeMillis()),
        lastOpenedAt = if (o.isNull("last_opened_at")) null else o.optLong("last_opened_at"),
        readingStatus = runCatching { ReadingStatus.valueOf(o.getString("reading_status")) }
            .getOrDefault(ReadingStatus.PENDING),
        favorite = o.optBoolean("favorite", false),
        progress = o.optDouble("progress", 0.0).toFloat(),
    )

    private fun categoryFrom(o: JSONObject): CategoryEntity = CategoryEntity(
        id = o.getString("id"), name = o.getString("name"),
        parentId = if (o.isNull("parent_id")) null else o.optString("parent_id"),
        sortOrder = o.optInt("sort_order", 0),
    )

    private fun tagFrom(o: JSONObject): TagEntity = TagEntity(id = o.getString("id"), name = o.getString("name"))

    private fun collectionFrom(o: JSONObject): CollectionEntity = CollectionEntity(
        id = o.getString("id"), name = o.getString("name"), createdAt = o.optLong("created_at", 0),
    )

    private fun refPair(o: JSONObject): Pair<String, String> =
        Pair(o.getString("first"), o.getString("second"))

    private fun annotationFrom(o: JSONObject): AnnotationEntity = AnnotationEntity(
        id = o.getString("id"),
        bookId = if (o.isNull("book_id")) null else o.optString("book_id"),
        locator = if (o.isNull("locator")) null else o.optString("locator"),
        quote = o.optString("quote"),
        body = o.optString("body"),
        kind = o.optString("kind", "NOTE"),
        color = o.optInt("color", 0),
        createdAt = o.optLong("created_at", System.currentTimeMillis()),
        updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
        deletedAt = if (o.isNull("deleted_at")) null else o.optLong("deleted_at"),
    )

    private fun versionFrom(o: JSONObject): AnnotationVersionEntity = AnnotationVersionEntity(
        id = o.getString("id"),
        annotationId = o.getString("annotation_id"),
        body = o.optString("body"),
        color = o.optInt("color", 0),
        savedAt = o.optLong("saved_at", 0),
    )

    private fun positionFrom(o: JSONObject): ReadingPositionEntity = ReadingPositionEntity(
        bookId = o.getString("book_id"),
        locator = o.getString("locator"),
        progress = o.optDouble("progress", 0.0).toFloat(),
        updatedAt = o.optLong("updated_at", 0),
    )
}
