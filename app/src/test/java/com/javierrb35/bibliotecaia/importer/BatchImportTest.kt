package com.javierrb35.bibliotecaia.importer

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchImportTest {
    private fun book(id: String) = BookEntity(id, id, null, BookFormat.PDF, "$id.pdf", "/tmp/$id.pdf", id, 10L, 1L)

    @Test fun centralPublishesAndBacksUpOnceBeforeRemovingOnlySuccessfulImports() = runBlocking {
        val events = mutableListOf<String>()
        val result = BatchImport.run(listOf("a", "bad", "b", "a"), true,
            importOne = { if (it == "bad") error("format") else book(it) },
            publishAndBackup = { events.add("backup:" + it.joinToString { b -> b.id }) },
            removeStaging = { events.add("evict:" + it.joinToString { b -> b.id }) })
        assertEquals(listOf("a", "b"), result.imported.map { it.id })
        assertEquals(listOf("format"), result.failures)
        assertEquals(listOf("backup:a, b", "evict:a, b"), events)
        assertNull(result.centralError)
    }

    @Test fun deviceRetainsOriginalsAndBackupFailureNeverEvicts() = runBlocking {
        var removed = false
        val local = BatchImport.run(listOf("a", "b"), false, importOne = { book(it) },
            publishAndBackup = { error("local must not force cloud backup") },
            removeStaging = { removed = true })
        assertEquals(2, local.imported.size)
        assertNull(local.centralError)
        val failed = BatchImport.run(listOf("a"), true, importOne = { book(it) },
            publishAndBackup = { error("backup failed") }, removeStaging = { removed = true })
        assertEquals("backup failed", failed.centralError)
        assertFalse(removed)
    }
}
