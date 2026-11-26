package com.javierrb35.bibliotecaia.ui

import kotlinx.coroutines.CancellationException

/** Stable identifiers, never adapter positions. Hidden items cannot remain selected. */
internal class LibrarySelection {
    private val selected = linkedSetOf<String>()
    val ids: Set<String> get() = selected.toSet()
    val active get() = selected.isNotEmpty()
    fun toggle(id: String) { if (!selected.remove(id)) selected.add(id) }
    fun clear() = selected.clear()
    fun retain(visible: Collection<String>) { selected.retainAll(visible.toSet()) }
    fun toggleAll(visible: Collection<String>) {
        if (visible.isNotEmpty() && selected.containsAll(visible)) selected.clear()
        else { selected.clear(); selected.addAll(visible) }
    }
}

internal enum class LibraryBatchAction(val label: String) {
    DOWNLOAD("Descargar al dispositivo"), SAVE("Guardar en biblioteca"), REMOVE("Retirar del dispositivo")
}

internal object LibraryBatch {
    data class Result(val completed: Int, val failures: List<String>, val pending: Int)
    /** Cancel between books, not during an integrity-sensitive file operation. */
    suspend fun <T> run(items: List<T>, cancelled: () -> Boolean,
                        progress: (Int, Int) -> Unit, describe: (T) -> String = { "" },
                        action: suspend (T) -> Unit): Result {
        var done = 0
        var completed = 0
        val failures = mutableListOf<String>()
        for (item in items) {
            if (cancelled()) break
            try {
                action(item)
                completed++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val name = describe(item)
                failures.add((if (name.isBlank()) "" else "$name: ") +
                    (e.message ?: "No se pudo completar el libro."))
            }
            done++
            progress(done, items.size)
        }
        return Result(completed, failures, items.size - done)
    }
}
