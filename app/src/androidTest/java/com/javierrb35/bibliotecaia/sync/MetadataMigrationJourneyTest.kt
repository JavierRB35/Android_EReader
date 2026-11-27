package com.javierrb35.bibliotecaia.sync

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.backup.BackupCodec
import com.javierrb35.bibliotecaia.backup.CompleteBackupArchive
import com.javierrb35.bibliotecaia.backup.LibrarySnapshot
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.BookSynopsisEntity
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regresión permanente del incidente #155: un ZIP del migrador con metadatos
 * reales debe reparar las fichas ya importadas con title="epub" y
 * authors="Desktop" sin duplicar originales, sin reinstalar copias locales,
 * conservando notas, posiciones, etiquetas y ediciones del usuario, y
 * publicando las portadas incluidas sin reabrir originales remotos. Además,
 * la reparación automática completa los metadatos leyendo los originales
 * verificados una sola vez por hash, con convergencia idempotente A/B.
 */
@RunWith(AndroidJUnit4::class)
class MetadataMigrationJourneyTest {

    /** Almacén plano de prueba con la misma interface de producción. */
    private class FlatStore(private val dir: File) : SharedStore {
        override val namedOriginals = true
        override fun list() = dir.listFiles().orEmpty()
            .filter { it.isFile }
            .map { SharedDocumentStore.Item(it.name, Uri.fromFile(it), it.length()) }
        override fun open(item: SharedDocumentStore.Item): InputStream =
            File(item.uri.path!!).inputStream()
        override fun create(name: String, mime: String, source: InputStream, hash: String,
                            size: Long): SharedDocumentStore.Item {
            val file = File(dir, name)
            require(!file.exists()) { "Nunca se sobrescribe un documento existente." }
            source.use { input -> file.outputStream().use { input.copyTo(it) } }
            require(file.length() == size && SharedDocumentStore.hash(file.readBytes()) == hash)
            return SharedDocumentStore.Item(name, Uri.fromFile(file), file.length())
        }
    }

    private fun originalWithOpf(folder: File, name: String, title: String, creator: String,
                                subject: String, description: String): File {
        val file = File(folder, name)
        val cover = jpegBytes()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(entryName: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry("META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray())
            entry("book.opf", ("""<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata>""" +
                "<dc:title>$title</dc:title><dc:creator>$creator</dc:creator>" +
                "<dc:subject>$subject</dc:subject><dc:description>$description</dc:description>" +
                "</metadata><manifest>" +
                "<item id=\"cover\" properties=\"cover-image\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>" +
                "</manifest></package>").toByteArray())
            entry("cover.jpg", cover)
            entry("text.html", "Contenido de $title".toByteArray())
        }
        return file
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(24, 32, android.graphics.Bitmap.Config.ARGB_8888)
        return try { requireNotNull(com.javierrb35.bibliotecaia.ui.BookCoverLoader.jpeg(bitmap)) }
        finally { bitmap.recycle() }
    }

    /** ZIP con la forma del migrador v2: catálogo con metadatos reales,
     * originales y portadas por hash (issue #155). */
    private fun migratorZip(books: List<BookEntity>, tags: List<TagEntity>,
                            bookTags: List<BookTagCrossRef>,
                            synopses: List<BookSynopsisEntity>, covers: Map<String, ByteArray>): ByteArray {
        val json = BackupCodec.toJson(LibrarySnapshot(
            books = books, categories = emptyList(), tags = tags, collections = emptyList(),
            bookCategories = emptyList(), bookTags = bookTags, collectionBooks = emptyList(),
            annotations = emptyList(), versions = emptyList(), positions = emptyList(),
            synopses = synopses), "CalibreMigrator-2", 1)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(entryName: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("biblioteca.json", json.toByteArray(Charsets.UTF_8))
            books.forEach { entry(CompleteBackupArchive.entryName(it.id), File(it.localPath).readBytes()) }
            covers.forEach { (hash, bytes) -> entry("covers/" + hash + ".jpg", bytes) }
        }
        return output.toByteArray()
    }

    @Test fun migratorZipRepairsPlaceholdersWithoutDuplicatesAndPublishesCovers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "migrate-155-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val stage = File(root, "stage").apply { mkdirs() }
        val files = File(root, "private").apply { mkdirs() }
        val store = FlatStore(folder)
        // "Biblioteca compartida" ya publicada por la importación defectuosa:
        // los originales están en la carpeta central con sus nombres.
        val real1 = originalWithOpf(folder, "uno.epub", "La Sombra del Viento", "Ana Ruiz", "Aventura", "Resumen de La Sombra")
        val real2 = originalWithOpf(folder, "dos.epub", "El Faro", "Bo Carre", "Misterio", "Resumen de El Faro")
        val real3 = originalWithOpf(folder, "tres.epub", "Nudo", "Celia Paz", "Poesía", "Resumen de Nudo")
        val originals = listOf(real1, real2, real3)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            // Fichas importadas por el ZIP defectuoso: placeholders para todos.
            val books = originals.mapIndexed { index, file ->
                BookEntity(UUID.randomUUID().toString(), "epub", "Desktop", BookFormat.EPUB,
                    file.name, "", SharedDocumentStore.hash(file.readBytes()), file.length(), (index + 1).toLong())
            }
            books.forEach { db.bookDao().insert(it) }
            // Datos del usuario que la reparación debe conservar intactos.
            db.bookDao().update(books[2].copy(title = "Nudo (edición del usuario)", authors = "Celia Paz"))
            db.readingDao().savePosition(ReadingPositionEntity(books[0].id, "chapter:5", .4f, 20L))
            db.synopsisDao().save(BookSynopsisEntity(books[0].id, "Argumento escrito por el usuario",
                "gemini", 50L, true))
            // Otro origen (migrador corregido) genera identificadores distintos
            // para los mismos originales, con metadatos reales.
            val incomingBooks = books.mapIndexed { index, local ->
                local.copy(id = "zip-" + index, localPath = originals[index].absolutePath,
                    title = when (index) { 0 -> "La Sombra del Viento"; 1 -> "El Faro"; else -> "Nudo" },
                    authors = when (index) { 0 -> "Ana Ruiz"; 1 -> "Bo Carre"; else -> "Celia Paz" })
            }
            val repairTags = listOf(TagEntity("tag-a", "Aventura"), TagEntity("tag-b", "Misterio"))
            val synopses = incomingBooks.take(2).mapIndexed { index, book ->
                BookSynopsisEntity(book.id, "Sinopsis del catálogo " + index, "calibre", 5L, false)
            }
            val cover = jpegBytes()
            val zip = migratorZip(incomingBooks, repairTags,
                repairTags.mapIndexed { index, tag -> BookTagCrossRef(incomingBooks[index].id, tag.id) },
                synopses, covers = incomingBooks.associate { it.contentHash to cover })
            val content = CompleteBackupArchive.read(zip.inputStream(), stage)
            assertEquals(incomingBooks.size, content.coverFiles.size)

            val result = SharedLibrary.migrateZipStore(store, db, "a".repeat(32), content, files)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(3, result.hashes.size)

            // Sin duplicados: tres fichas antes y después de la importación.
            val after = db.backupDao().booksAll()
            assertEquals(3, after.size)
            // Placeholders reparados con los metadatos del catálogo.
            assertEquals("La Sombra del Viento", after.first { it.contentHash == books[0].contentHash }.title)
            assertEquals("Ana Ruiz", after.first { it.contentHash == books[0].contentHash }.authors)
            assertEquals("El Faro", after.first { it.contentHash == books[1].contentHash }.title)
            // La edición del usuario no se sobrescribe con el catálogo.
            assertEquals("Nudo (edición del usuario)", after.first { it.contentHash == books[2].contentHash }.title)
            // Etiquetas del catálogo añadidas de forma aditiva.
            val names = db.refsDao().tagNamesForBook(books[0].id)
            assertTrue(names.toString(), names.contains("Aventura"))
            // Posición y sinopsis del usuario intactas; el argumento importado
            // solo completa huecos (nunca reemplaza texto existente).
            assertEquals("chapter:5", db.readingDao().position(books[0].id)?.locator)
            assertEquals("Argumento escrito por el usuario", db.synopsisDao().byBook(books[0].id)!!.text)
            // Las fichas 1 y 2 no tenían sinopsis: el catálogo completa la de la ficha 1.
            assertNotNull(db.synopsisDao().byBook(books[1].id))
            // Portadas publicadas desde el ZIP sin reabrir originales remotos.
            incomingBooks.forEach { book ->
                assertNotNull("Portada publicada para " + book.contentHash.take(8),
                    SharedCoverArt.read(store, store.list(), book.contentHash))
            }
            // Reimportación idempotente del mismo ZIP: ni duplicados ni regresiones.
            val retry = SharedLibrary.migrateZipStore(store, db, "a".repeat(32), content, files)
            assertTrue(retry.errors.toString(), retry.errors.isEmpty())
            assertEquals(3, db.backupDao().booksAll().size)
            assertEquals("La Sombra del Viento", db.backupDao().booksAll().first { it.contentHash == books[0].contentHash }.title)
            assertEquals("Nudo (edición del usuario)", db.backupDao().booksAll().first { it.contentHash == books[2].contentHash }.title)
            assertEquals("Argumento escrito por el usuario", db.synopsisDao().byBook(books[0].id)!!.text)
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun repairCatalogBatchFixesExistingPlaceholdersFromOriginalsAndConverges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "repair-155-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val store = FlatStore(folder)
        val real1 = originalWithOpf(folder, "uno.epub", "La Sombra del Viento", "Ana Ruiz", "Aventura", "Resumen interno de La Sombra")
        val real2 = originalWithOpf(folder, "dos.epub", "El Faro", "Bo Carre", "Misterio", "Resumen interno de El Faro")
        val real3 = originalWithOpf(folder, "tres.epub", "Nudo", "Celia Paz", "Poesía", "Resumen interno de Nudo")
        val prefs = context.getSharedPreferences("catalog_repair_v1_test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val peer = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val books = listOf(real1, real2, real3).mapIndexed { index, file ->
                BookEntity(UUID.randomUUID().toString(), "epub", "Desktop", BookFormat.EPUB,
                    file.name, "", SharedDocumentStore.hash(file.readBytes()), file.length(), (index + 1).toLong())
            }
            books.forEach { db.bookDao().insert(it) }
            // Edición del usuario en una ficha: debe sobrevivir a la reparación.
            db.bookDao().update(books[2].copy(title = "Nudo (edición del usuario)"))
            val remaining = SharedLibrary.repairCatalogBatchStore(store, db, prefs, cache)
            val after = db.backupDao().booksAll().associateBy { it.contentHash }
            assertEquals("La Sombra del Viento", after.getValue(books[0].contentHash).title)
            assertEquals("Ana Ruiz", after.getValue(books[0].contentHash).authors)
            assertEquals("El Faro", after.getValue(books[1].contentHash).title)
            assertEquals("Nudo (edición del usuario)", after.getValue(books[2].contentHash).title)
            assertTrue(db.refsDao().tagNamesForBook(books[0].id).contains("Aventura"))
            assertEquals("Resumen interno de La Sombra", db.synopsisDao().byBook(books[0].id)!!.text)
            assertNotNull(SharedCoverArt.read(store, store.list(), books[0].contentHash))
            assertEquals(0, remaining)

            // Idempotencia: repetir el lote no cambia nada.
            assertEquals(0, SharedLibrary.repairCatalogBatchStore(store, db, prefs, cache))
            assertEquals("La Sombra del Viento", db.backupDao().booksAll().first { it.contentHash == books[0].contentHash }.title)

            // Convergencia A/B: el peer tiene los mismos placeholders y la misma
            // edición del usuario, y los mismos originales en la carpeta central;
            // su reparación llega al mismo estado.
            books.forEachIndexed { index, b ->
                val edited = if (index == 2) b.copy(title = "Nudo (edición del usuario)") else b
                peer.bookDao().insert(edited.copy(id = b.id + "-peer"))
            }
            SharedLibrary.repairCatalogBatchStore(store, peer, prefs, cache)
            val peerAfter = peer.backupDao().booksAll().associateBy { it.contentHash }
            assertEquals(after.getValue(books[0].contentHash).title, peerAfter.getValue(books[0].contentHash).title)
            assertEquals(after.getValue(books[1].contentHash).authors, peerAfter.getValue(books[1].contentHash).authors)
            assertEquals("Nudo (edición del usuario)", peerAfter.getValue(books[2].contentHash).title)
        } finally { db.close(); peer.close(); root.deleteRecursively() }
    }
}
