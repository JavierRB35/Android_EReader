package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.AnnotationVersionEntity
import com.javierrb35.bibliotecaia.data.BookCategoryCrossRef
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.CollectionBookCrossRef
import com.javierrb35.bibliotecaia.data.CollectionEntity
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
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

/** Resultado de una restauración: nunca destructiva, siempre con recuento honesto. */
data class RestoreReport(
    val booksRestored: Int,
    val booksSkipped: Int,      // ya existían (mismo id o hash)
    val annotationsRestored: Int,
    val annotationsSkipped: Int,
    val categoriesRestored: Int,
    val tagsRestored: Int,
    val collectionsRestored: Int,
    val positionsUpdated: Int,
)

object BackupCodec {

    const val FORMAT_VERSION = 1
    private const val APP_ID = "biblioteca-ia"

    // ---------- Serialización ----------

    fun toJson(snapshot: LibrarySnapshot, appVersion: String, exportedAt: Long): String {
        val root = JSONObject()
        root.put(
            "manifest",
            JSONObject()
                .put("app", APP_ID)
                .put("formatVersion", FORMAT_VERSION)
                .put("appVersion", appVersion)
                .put("exportedAt", exportedAt)
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
        .put("fileName", b.fileName)
        .put("contentHash", b.contentHash)
        .put("sizeBytes", b.sizeBytes)
        .put("addedAt", b.addedAt)
        .put("lastOpenedAt", b.lastOpenedAt ?: JSONObject.NULL)
        .put("readingStatus", b.readingStatus.name)
        .put("favorite", b.favorite)
        .put("progress", b.progress.toDouble())
    // localPath no se exporta: es específico del dispositivo (§16.3)

    private fun categoryJson(c: CategoryEntity): JSONObject = JSONObject()
        .put("id", c.id).put("name", c.name)
        .put("parentId", c.parentId ?: JSONObject.NULL).put("sortOrder", c.sortOrder)

    private fun tagJson(t: TagEntity): JSONObject = JSONObject().put("id", t.id).put("name", t.name)

    private fun collectionJson(c: CollectionEntity): JSONObject =
        JSONObject().put("id", c.id).put("name", c.name).put("createdAt", c.createdAt)

    private fun refJson(first: String, second: String): JSONObject =
        JSONObject().put("first", first).put("second", second)

    private fun annotationJson(a: AnnotationEntity): JSONObject = JSONObject()
        .put("id", a.id)
        .put("bookId", a.bookId ?: JSONObject.NULL)
        .put("locator", a.locator ?: JSONObject.NULL)
        .put("quote", a.quote)
        .put("body", a.body)
        .put("kind", a.kind)
        .put("color", a.color)
        .put("createdAt", a.createdAt)
        .put("updatedAt", a.updatedAt)
        .put("deletedAt", a.deletedAt ?: JSONObject.NULL)

    private fun versionJson(v: AnnotationVersionEntity): JSONObject = JSONObject()
        .put("id", v.id).put("annotationId", v.annotationId)
        .put("body", v.body).put("color", v.color).put("savedAt", v.savedAt)

    private fun positionJson(p: ReadingPositionEntity): JSONObject = JSONObject()
        .put("bookId", p.bookId).put("locator", p.locator)
        .put("progress", p.progress).put("updatedAt", p.updatedAt)

    // ---------- Deserialización ----------

    /** Lanza IllegalArgumentException con mensaje claro si la copia no es válida. */
    fun fromJson(json: String): LibrarySnapshot {
        val root = try { JSONObject(json) } catch (e: Exception) {
            throw IllegalArgumentException("El archivo no es una copia válida de Biblioteca IA.")
        }
        val manifest = root.optJSONObject("manifest")
            ?: throw IllegalArgumentException("El archivo no es una copia válida de Biblioteca IA.")
        if (manifest.optString("app") != APP_ID) {
            throw IllegalArgumentException("La copia pertenece a otra aplicación.")
        }
        val format = manifest.optInt("formatVersion", -1)
        if (format !in 1..FORMAT_VERSION) {
            throw IllegalArgumentException("La copia usa un formato no compatible (versión $format).")
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

    private fun bookFrom(o: JSONObject): BookEntity = BookEntity(
        id = o.getString("id"),
        title = o.getString("title"),
        authors = o.optString("authors").ifEmpty { null },
        format = runCatching { com.javierrb35.bibliotecaia.data.BookFormat.valueOf(o.getString("format")) }
            .getOrDefault(com.javierrb35.bibliotecaia.data.BookFormat.EPUB),
        fileName = o.optString("fileName", "libro"),
        localPath = "", // se reconstruye en la restauración; el archivo debe existir o reimportarse
        contentHash = o.getString("contentHash"),
        sizeBytes = o.optLong("sizeBytes", 0),
        addedAt = o.optLong("addedAt", System.currentTimeMillis()),
        lastOpenedAt = if (o.isNull("lastOpenedAt")) null else o.optLong("lastOpenedAt"),
        readingStatus = runCatching { com.javierrb35.bibliotecaia.data.ReadingStatus.valueOf(o.getString("readingStatus")) }
            .getOrDefault(com.javierrb35.bibliotecaia.data.ReadingStatus.PENDING),
        favorite = o.optBoolean("favorite", false),
        progress = o.optDouble("progress", 0.0).toFloat(),
    )

    private fun categoryFrom(o: JSONObject): CategoryEntity = CategoryEntity(
        id = o.getString("id"), name = o.getString("name"),
        parentId = if (o.isNull("parentId")) null else o.optString("parentId"),
        sortOrder = o.optInt("sortOrder", 0),
    )

    private fun tagFrom(o: JSONObject): TagEntity = TagEntity(id = o.getString("id"), name = o.getString("name"))

    private fun collectionFrom(o: JSONObject): CollectionEntity = CollectionEntity(
        id = o.getString("id"), name = o.getString("name"), createdAt = o.optLong("createdAt", 0),
    )

    private fun refPair(o: JSONObject): Pair<String, String> =
        Pair(o.getString("first"), o.getString("second"))

    private fun annotationFrom(o: JSONObject): AnnotationEntity = AnnotationEntity(
        id = o.getString("id"),
        bookId = if (o.isNull("bookId")) null else o.optString("bookId"),
        locator = if (o.isNull("locator")) null else o.optString("locator"),
        quote = o.optString("quote"),
        body = o.optString("body"),
        kind = o.optString("kind", "NOTE"),
        color = o.optInt("color", 0),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
        deletedAt = if (o.isNull("deletedAt")) null else o.optLong("deletedAt"),
    )

    private fun versionFrom(o: JSONObject): AnnotationVersionEntity = AnnotationVersionEntity(
        id = o.getString("id"),
        annotationId = o.getString("annotationId"),
        body = o.optString("body"),
        color = o.optInt("color", 0),
        savedAt = o.optLong("savedAt", 0),
    )

    private fun positionFrom(o: JSONObject): ReadingPositionEntity = ReadingPositionEntity(
        bookId = o.getString("bookId"),
        locator = o.getString("locator"),
        progress = o.optDouble("progress", 0.0).toFloat(),
        updatedAt = o.optLong("updatedAt", 0),
    )
}
