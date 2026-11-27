package com.javierrb35.bibliotecaia.study

import android.app.Activity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.data.ReadingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Captured before the selection is cleared. Never locate a repeated word by search. */
object DefinitionAnnotation {
    data class Target(val bookId: String, val locator: String, val quote: String) {
        init {
            require(bookId.isNotBlank() && locator.isNotBlank() && quote.isNotBlank())
        }
    }

    internal suspend fun persist(repository: ReadingRepository, target: Target, definition: String): String {
        require(definition.isNotBlank())
        // Same NOTE record, locator, visual mark and sync pipeline as manual notes.
        return repository.addAnnotation(target.bookId, target.locator, target.quote, definition, "NOTE")
    }

    fun interface Callback { fun done(error: String?) }

    @JvmStatic fun save(activity: Activity, target: Target, definition: String, callback: Callback) {
        val owner = activity as? LifecycleOwner
        if (owner == null) {
            callback.done("No se puede guardar la anotación desde esta pantalla.")
            return
        }
        owner.lifecycleScope.launch {
            try {
                persist(ReadingRepository(activity), target, definition)
                callback.done(null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                callback.done("No se pudo guardar la anotación. Puedes reintentar.")
            }
        }
    }
}
