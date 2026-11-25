package com.javierrb35.bibliotecaia.importer

import com.javierrb35.bibliotecaia.data.BookEntity
import kotlinx.coroutines.CancellationException

/** Sequential bounded import. An individual bad file does not abort the rest.
 * No original is evicted until the complete remote publication AND backup succeed. */
internal object BatchImport {
    data class Result(val imported: List<BookEntity>, val failures: List<String>, val centralError: String?)
    suspend fun <T> run(
        items: List<T>, central: Boolean,
        importOne: suspend (T) -> BookEntity,
        progress: (Int, Int) -> Unit = { _, _ -> },
        publishAndBackup: suspend (List<BookEntity>) -> Unit,
        removeStaging: suspend (List<BookEntity>) -> Unit
    ): Result {
        val imported = mutableListOf<BookEntity>()
        val failures = mutableListOf<String>()
        val unique = items.distinct()
        unique.forEachIndexed { index, item ->
            try { imported.add(importOne(item)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures.add(e.message ?: "Archivo no válido o inaccesible") }
            progress(index + 1, unique.size)
        }
        var centralError: String? = null
        if (central && imported.isNotEmpty()) {
            try {
                publishAndBackup(imported)
                removeStaging(imported)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { centralError = e.message ?: "No se ha podido verificar la biblioteca central." }
        }
        return Result(imported, failures, centralError)
    }
}
