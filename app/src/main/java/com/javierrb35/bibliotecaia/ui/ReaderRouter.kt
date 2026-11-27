package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.backup.CentralCatalogue
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.reader.epub.EpubReaderActivity
import com.javierrb35.bibliotecaia.reader.mobi.MobiReaderActivity
import com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync
import com.javierrb35.bibliotecaia.sync.SharedLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.WeakHashMap

object ReaderRouter {
    // Main-thread navigation guard: retain until the source leaves RESUMED.
    private val opening = WeakHashMap<AppCompatActivity, Boolean>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    fun open(activity: AppCompatActivity, bookId: String, locator: String? = null) {
        openLocal(activity, bookId, locator) { app ->
            ReadingPositionSafSync.refreshForRead(app, AppDatabase.get(app))
        }
    }

    // The refresh runs in an independent job; the reader reads Room,
    // never waits for a provider or the shared catalogue mutex.
    internal fun openLocal(activity: AppCompatActivity, bookId: String, locator: String? = null,
                           refresh: suspend (Context) -> Unit) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            opening.put(activity, true) != null) return
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                opening.remove(activity)
                owner.lifecycle.removeObserver(this)
            }
        }
        activity.lifecycle.addObserver(observer)
        activity.lifecycleScope.launch {
            var navigating = false
            try {
                val book = AppDatabase.get(activity).bookDao().findById(bookId)
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (book == null) {
                    Toast.makeText(activity, "No se encuentra el libro de origen", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (book.format == BookFormat.PDF) {
                    Toast.makeText(activity, "PDF histórico: conservado para recuperación, sin lector compatible.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (!CentralCatalogue.local(book)) {
                    if (activity is BookDetailActivity) {
                        Toast.makeText(activity, "Descarga el libro en este dispositivo para leerlo.", Toast.LENGTH_LONG).show()
                    } else {
                        BookDetailActivity.start(activity, book.id)
                        navigating = true
                    }
                    return@launch
                }
                when (book.format) {
                    BookFormat.EPUB -> EpubReaderActivity.start(activity, bookId, locator)
                    BookFormat.PDF -> Unit
                    BookFormat.MOBI -> MobiReaderActivity.start(activity, bookId, locator)
                }
                navigating = true
                // No lifecycle dependency on the screen that just launched the reader.
                refreshInBackground(activity.applicationContext, refresh)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!navigating) Toast.makeText(activity, e.message ?: "No se pudo abrir el libro.", Toast.LENGTH_LONG).show()
            } finally {
                if (!navigating) {
                    opening.remove(activity)
                    activity.lifecycle.removeObserver(observer)
                }
            }
        }
    }

    @Synchronized
    private fun refreshInBackground(context: Context, refresh: suspend (Context) -> Unit) {
        if (refreshJob?.isActive == true) return
        val app = context.applicationContext
        refreshJob = refreshScope.launch {
            try {
                // Existing reconciliation merges timestamps, not maximum progress.
                // Never navigate the already-open reader to a newly arriving position.
                refresh(app)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (SharedLibrary.configured(app)) SharedLibrary.recordFailure(app, e)
            }
        }
    }
}
