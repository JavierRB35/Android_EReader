package com.javierrb35.bibliotecaia.importer

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchImportTest {
    private fun book(id: String) = BookEntity(id, id, null, BookFormat.PDF, "$id.pdf", "/tmp/$id.pdf", id, 10L, 1L)

    @Test fun centralPublishesWithoutBackupBeforeRemovingOnlySuccessfulImports() = runBlocking {
        val events = mutableListOf<String>()
        val result = BatchImport.run(listOf("a", "bad", "b", "a"), true,
            importOne = { if (it == "bad") error("format") else book(it) },
            publishVerified = { events.add("publish:" + it.joinToString { b -> b.id }) },
            removeStaging = { events.add("evict:" + it.joinToString { b -> b.id }) })
        assertEquals(listOf("a", "b"), result.imported.map { it.id })
        assertEquals(listOf("format"), result.failures)
        assertEquals(listOf("publish:a, b", "evict:a, b"), events)
        assertNull(result.centralError)
    }

    @Test fun deviceRetainsOriginalsAndOfflinePublicationNeverEvicts() = runBlocking {
        var removed = false
        val local = BatchImport.run(listOf("a", "b"), false, importOne = { book(it) },
            publishVerified = { error("local must not force cloud publication") },
            removeStaging = { removed = true })
        assertEquals(2, local.imported.size)
        assertNull(local.centralError)
        val failed = BatchImport.run(listOf("a"), true, importOne = { book(it) },
            publishVerified = { error("provider offline") }, removeStaging = { removed = true })
        assertEquals("provider offline", failed.centralError)
        assertFalse(removed)
    }
}
