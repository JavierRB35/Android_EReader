package com.javierrb35.bibliotecaia.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.ai.AiClients
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookClassificationEntity
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.BookSynopsisEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas de regresión del motor de catalogación automática (#142):
 * conservación de argumentos y ediciones, duplicados, cancelación implícita
 * (idempotencia y estados), metadatos del archivo y marcado de revisión.
 */
@RunWith(AndroidJUnit4::class)
class AutoCatalogEngineTest {

    private fun db(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    private fun book(id: String, localPath: String = "", format: BookFormat = BookFormat.EPUB) =
        BookEntity(
            id = id, title = "Libro $id", authors = "Autor/a",
            format = format, fileName = "libro.epub", localPath = localPath,
            contentHash = id.padStart(64, '0').take(64),
            sizeBytes = 10, addedAt = 1)

    /** EPUB sintético con dc:description y dc:subject: el material de Calibre real. */
    private fun richEpub(context: Context, description: String?, subjects: List<String>): File {
        val file = File.createTempFile("catalog-engine-", ".epub", context.cacheDir)
        val subjectsXml = subjects.joinToString("") {
            "<dc:subject>${it}</dc:subject>"
        }
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="BookId">urn:uuid:test</dc:identifier>
                <dc:title>Libro de metadatos</dc:title>
                <dc:creator>Autor/a</dc:creator>
                $subjectsXml
                ${if (description != null) "<dc:description>$description</dc:description>" else ""}
              </metadata>
              <manifest>
                <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>""".trimIndent()
        val container = """<?xml version="1.0" encoding="UTF-8"?>
          <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
              <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
          </container>""".trimIndent()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(name: String, data: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", container)
            entry("OEBPS/content.opf", opf)
            entry("OEBPS/c1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Capítulo de prueba.</p></body></html>")
        }
        return file
    }

    private fun aiAnswer(categories: String, tags: String, review: String = "OK") =
        AiClients.Answer(
            "Síntesis breve del libro basada en el argumento disponible.\n" +
                "CATEGORIAS_BIBLIOTECAIA: $categories\n" +
                "ETIQUETAS_BIBLIOTECAIA: $tags\n" +
                "REVISION_BIBLIOTECAIA: $review",
            "gemini", "gemini-3.6-flash")

    @Test fun metadataDescriptionFillsEmptySynopsisWithoutAi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = db(context)
        try {
            val id = UUID.randomUUID().toString()
            val epub = richEpub(context, "Un imperio se desmorona y una ciencia lo predice.",
                listOf("Novela de ciencia ficción"))
            db.bookDao().insert(book(id, epub.absolutePath))
            val outcome = AutoCatalogEngine(db).process(book(id, epub.absolutePath), epub, ask = null)
            assertEquals(AutoCatalogEngine.Outcome.PROCESSED, outcome)
            // El argumento procede de los metadatos del propio archivo (#142).
            assertEquals("Un imperio se desmorona y una ciencia lo predice.",
                db.synopsisDao().byBook(id)?.text)
            assertEquals("calibre", db.synopsisDao().byBook(id)?.provider)
            // Sin IA la organización queda pendiente de reintento.
            assertEquals(BookClassificationEntity.STATUS_PENDING,
                db.classificationDao().byBook(id)?.status)
            assertEquals(BookClassificationEntity.BASIS_METADATA,
                db.classificationDao().byBook(id)?.basis)
        } finally { db.close() }
    }

    @Test fun existingUserSynopsisAndAssignmentsAreNeverOverwritten() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = db(context)
        try {
            val id = UUID.randomUUID().toString()
            val epub = richEpub(context, "Descripción que NO debe sustituir al argumento.", emptyList())
            db.bookDao().insert(book(id, epub.absolutePath))
            db.synopsisDao().save(BookSynopsisEntity(
                id, "Argumento manual del usuario", "manual", 200, false))
            // El usuario ya organizó el libro por su cuenta.
            val category = com.javierrb35.bibliotecaia.data.CategoryEntity(
                UUID.randomUUID().toString(), "categoría manual", null, 0)
            db.categoryDao().insert(category)
            db.refsDao().addBookCategory(
                com.javierrb35.bibliotecaia.data.BookCategoryCrossRef(id, category.id))
            val tag = TagEntity(UUID.randomUUID().toString(), "etiqueta manual")
            db.tagDao().insert(tag)
            db.refsDao().addBookTag(
                com.javierrb35.bibliotecaia.data.BookTagCrossRef(id, tag.id))
            var called = false
            val ask: suspend (String, List<String>) -> AiClients.Answer = { _, _ ->
                called = true; aiAnswer("Novela", "intrusa")
            }
            val outcome = AutoCatalogEngine(db).process(book(id, epub.absolutePath), epub, ask)
            assertEquals(AutoCatalogEngine.Outcome.PROCESSED, outcome)
            assertFalse("Una ficha completa no debe gastar una llamada IA", called)
            assertEquals("Argumento manual del usuario", db.synopsisDao().byBook(id)?.text)
            assertEquals(listOf("categoría manual"), db.refsDao().categoryNamesForBook(id))
            assertEquals(listOf("etiqueta manual"), db.refsDao().tagNamesForBook(id))
            assertEquals(BookClassificationEntity.STATUS_CLASSIFIED,
                db.classificationDao().byBook(id)?.status)
            assertEquals(BookClassificationEntity.BASIS_EXISTING,
                db.classificationDao().byBook(id)?.basis)
        } finally { db.close() }
    }

    @Test fun aiClassifiesFromRecoveredSynopsisWithoutLocalCopyOrDownloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = db(context)
        try {
            val id = UUID.randomUUID().toString()
            // Sin copia local y sin original disponible: solo el argumento importado.
            db.bookDao().insert(book(id))
            // La base en memoria no siembra las categorías iniciales: vocabulary real.
            db.categoryDao().insert(com.javierrb35.bibliotecaia.data.CategoryEntity(
                UUID.randomUUID().toString(), "Ciencia ficción", null, 0))
            db.synopsisDao().save(BookSynopsisEntity(
                id, "Un imperio galáctico se desmorona; la psicohistoria predice la era oscura.",
                "calibre", 300, false))
            var asked: String? = null
            var evidence: List<String>? = null
            val ask: suspend (String, List<String>) -> AiClients.Answer = { question, fragments ->
                asked = question; evidence = fragments; aiAnswer("Ciencia ficción", "psicohistoria")
            }
            val outcome = AutoCatalogEngine(db).process(book(id), original = null, ask = ask)
            assertEquals(AutoCatalogEngine.Outcome.PROCESSED, outcome)
            // La evidencia es el argumento acotado, nunca el libro completo.
            assertEquals(listOf(db.synopsisDao().byBook(id)!!.text.take(2_000)), evidence)
            assertTrue(asked!!.contains("Ciencia ficción"))
            assertEquals(listOf("Ciencia ficción"), db.refsDao().categoryNamesForBook(id))
            assertEquals(listOf("psicohistoria"), db.refsDao().tagNamesForBook(id))
            assertEquals(BookClassificationEntity.STATUS_CLASSIFIED,
                db.classificationDao().byBook(id)?.status)
            assertEquals(BookClassificationEntity.BASIS_AI,
                db.classificationDao().byBook(id)?.basis)
            assertEquals("gemini", db.classificationDao().byBook(id)?.provider)
            // El argumento importado se conserva: la IA no lo reescribió.
            assertTrue(db.synopsisDao().byBook(id)!!.text.startsWith("Un imperio galáctico"))
        } finally { db.close() }
    }

    @Test fun dubiousClassificationIsMarkedForReviewAndKeepsPending() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = db(context)
        try {
            val id = UUID.randomUUID().toString()
            db.bookDao().insert(book(id))
            db.synopsisDao().save(BookSynopsisEntity(
                id, "Argumento ambiguo sin género claro.", "manual", 400, false))
            val ask: suspend (String, List<String>) -> AiClients.Answer = { _, _ ->
                aiAnswer("Otros", "general", review = "DUDOSO")
            }
            AutoCatalogEngine(db).process(book(id), original = null, ask = ask)
            assertEquals(BookClassificationEntity.STATUS_REVIEW,
                db.classificationDao().byBook(id)?.status)
        } finally { db.close() }
    }

    @Test fun idempotencySecondRunSkipsClassifiedSheets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = db(context)
        try {
            val id = UUID.randomUUID().toString()
            db.bookDao().insert(book(id))
            db.synopsisDao().save(BookSynopsisEntity(
                id, "Argumento completo del libro.", "gemini", 500, false))
            val ask: suspend (String, List<String>) -> AiClients.Answer = { _, _ ->
                aiAnswer("Novela", "narrativa")
            }
            val engine = AutoCatalogEngine(db)
            assertEquals(AutoCatalogEngine.Outcome.PROCESSED, engine.process(book(id), null, ask))
            var called = false
            val askAgain: suspend (String, List<String>) -> AiClients.Answer = { _, _ ->
                called = true; aiAnswer("Historia", "otra")
            }
            // Una ficha CLASSIFIED no se reprocesa: repetir la catalogación no duplica nada.
            assertEquals(AutoCatalogEngine.Outcome.SKIPPED, engine.process(book(id), null, askAgain))
            assertFalse(called)
            assertEquals(listOf("Novela"), db.refsDao().categoryNamesForBook(id))
        } finally { db.close() }
    }

    @Test fun pendingSheetWithoutAnyInformationIsRetriedLater() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = db(context)
        try {
            val id = UUID.randomUUID().toString()
            db.bookDao().insert(book(id))
            // Sin argumento, sin original y sin IA: queda PENDING y se reintenta.
            assertEquals(AutoCatalogEngine.Outcome.PROCESSED,
                AutoCatalogEngine(db).process(book(id), original = null, ask = null))
            assertEquals(BookClassificationEntity.STATUS_PENDING,
                db.classificationDao().byBook(id)?.status)
            assertEquals(BookClassificationEntity.BASIS_NONE,
                db.classificationDao().byBook(id)?.basis)
            // Al llegar la IA, la ficha PENDING se procesa con las materias del archivo.
            val epub = richEpub(context, null, listOf("Filosofía"))
            val ask: suspend (String, List<String>) -> AiClients.Answer = { _, _ ->
                aiAnswer("Filosofía", "ensayo")
            }
            assertEquals(AutoCatalogEngine.Outcome.PROCESSED,
                AutoCatalogEngine(db).process(book(id, epub.absolutePath), epub, ask))
            assertEquals(listOf("Filosofía"), db.refsDao().categoryNamesForBook(id))
            assertEquals(BookClassificationEntity.STATUS_CLASSIFIED,
                db.classificationDao().byBook(id)?.status)
        } finally { db.close() }
    }

    @Test fun aiSynopsisIsGeneratedOnlyFromLocalFragmentsAndNeverForPdf() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = db(context)
        try {
            // PDF histórico: sin generación IA de argumento.
            val pdfId = UUID.randomUUID().toString()
            db.bookDao().insert(book(pdfId, format = BookFormat.PDF))
            var pdfAsked = false
            val pdfAsk: suspend (String, List<String>) -> AiClients.Answer = { _, _ ->
                pdfAsked = true; aiAnswer("Referencia", "consulta")
            }
            AutoCatalogEngine(db).process(book(pdfId, format = BookFormat.PDF), null, pdfAsk)
            assertFalse(pdfAsked)

            // EPUB sin argumento ni original: sin copia local no hay generación.
            val remoteId = UUID.randomUUID().toString()
            db.bookDao().insert(book(remoteId))
            var remoteAsked = false
            val remoteAsk: suspend (String, List<String>) -> AiClients.Answer = { _, _ ->
                remoteAsked = true; aiAnswer("Novela", "etiqueta")
            }
            AutoCatalogEngine(db).process(book(remoteId), original = null, ask = remoteAsk)
            assertFalse("Sin copia local no se genera un argumento IA", remoteAsked)
            assertEquals(BookClassificationEntity.STATUS_PENDING,
                db.classificationDao().byBook(remoteId)?.status)
        } finally { db.close() }
    }

    @Test fun explicitClassificationUsesSavedArgumentAndAddsGenreWithoutOverwritingOrDuplicating() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = db(context)
        try {
            val id = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            db.bookDao().insert(book(id))
            db.bookDao().insert(book(other))
            val saved = BookSynopsisEntity(id, "Una detective investiga un crimen en Madrid.", "calibre", 400, false)
            db.synopsisDao().save(saved)
            val manual = com.javierrb35.bibliotecaia.data.CategoryEntity(
                UUID.randomUUID().toString(), "Mis favoritos", null, 0)
            val genre = com.javierrb35.bibliotecaia.data.CategoryEntity(
                UUID.randomUUID().toString(), "Novela negra", null, 1)
            db.categoryDao().insert(manual)
            db.categoryDao().insert(genre)
            db.refsDao().addBookCategory(com.javierrb35.bibliotecaia.data.BookCategoryCrossRef(id, manual.id))
            val tag = TagEntity(UUID.randomUUID().toString(), "Manual")
            db.tagDao().insert(tag)
            db.refsDao().addBookTag(com.javierrb35.bibliotecaia.data.BookTagCrossRef(id, tag.id))
            val engine = AutoCatalogEngine(db)
            engine.process(book(id), null, null)
            assertEquals(BookClassificationEntity.STATUS_CLASSIFIED, db.classificationDao().byBook(id)?.status)
            var calls = 0
            val ask: suspend (String, List<String>) -> AiClients.Answer = { _, evidence ->
                calls++
                assertEquals(listOf(saved.text), evidence)
                aiAnswer("Novela negra", "no sobrescribir")
            }
            repeat(2) {
                assertEquals(AutoCatalogEngine.Outcome.PROCESSED, engine.process(book(id), null, ask, explicit = true))
            }
            assertEquals(2, calls)
            assertEquals(setOf("Mis favoritos", "Novela negra"), db.refsDao().categoryNamesForBook(id).toSet())
            assertEquals(2, db.refsDao().categoryIdsForBook(id).size)
            assertEquals(listOf("Manual"), db.refsDao().tagNamesForBook(id))
            assertEquals(saved, db.synopsisDao().byBook(id))
            assertTrue(db.refsDao().categoryIdsForBook(other).isEmpty())
            engine.process(book(id), null, { _, _ -> aiAnswer("Fantasía", "otra", "DUDOSO") }, explicit = true)
            assertEquals(BookClassificationEntity.STATUS_REVIEW, db.classificationDao().byBook(id)?.status)
            assertEquals(setOf("Mis favoritos", "Novela negra"), db.refsDao().categoryNamesForBook(id).toSet())
        } finally { db.close() }
    }

    @Test fun singleBookRequestIsBackwardCompatible() {
        val old = AutoCatalogQueue.Request.decode("""{"all":false,"ids":["book"]}""")
        assertFalse(old.explicit)
        val explicit = AutoCatalogQueue.Request(false, listOf("book"), true)
        assertEquals(explicit, AutoCatalogQueue.Request.decode(explicit.encode()))
    }


    @Test fun explicitSubmissionSurvivesCancelledScreenWaiter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bookId = UUID.randomUUID().toString()
        val submission = AutoCatalogQueue.submitExplicit(context, bookId)
        val screen = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Default)
        val waiter = screen.launch { submission.await() }
        waiter.cancel()
        waiter.join()
        val workId = kotlinx.coroutines.withTimeout(10000) { submission.await() }
        val manager = androidx.work.WorkManager.getInstance(context)
        val info = manager.getWorkInfoById(workId).get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(info.tags.contains(AutoCatalogQueue.explicitTag(bookId)))
    }

}
