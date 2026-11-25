package com.javierrb35.bibliotecaia.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.reader.epub.EpubReaderActivity
import com.javierrb35.bibliotecaia.reader.pdf.PdfReaderActivity
import com.javierrb35.bibliotecaia.reader.mobi.MobiReaderActivity
import kotlinx.coroutines.launch

object ReaderRouter {
    fun open(activity: AppCompatActivity, bookId: String, locator: String? = null) {
        activity.lifecycleScope.launch {
            val db = AppDatabase.get(activity)
            try {
                val checked = com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync
                    .refreshForRead(activity.applicationContext, db)
                if (!checked) Toast.makeText(activity,
                    "No se pudo comprobar la posición de todos los dispositivos. Se abrirá la última posición disponible.",
                    Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(activity)) {
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.recordFailure(activity.applicationContext, e)
                    Toast.makeText(activity, "No se pudo actualizar la posición desde la biblioteca. Lectura sin conexión.",
                        Toast.LENGTH_LONG).show()
                }
            }
            val book = db.bookDao().findById(bookId)
            if (book == null) {
                Toast.makeText(activity, "No se encuentra el libro de origen", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!com.javierrb35.bibliotecaia.backup.CentralCatalogue.local(book)) {
                BookDetailActivity.start(activity, book.id)
                return@launch
            }
            when (book.format) {
                BookFormat.EPUB -> EpubReaderActivity.start(activity, bookId, locator)
                BookFormat.PDF -> PdfReaderActivity.start(activity, bookId, locator)
                BookFormat.MOBI -> MobiReaderActivity.start(activity, bookId, locator)
            }
        }
    }
}
