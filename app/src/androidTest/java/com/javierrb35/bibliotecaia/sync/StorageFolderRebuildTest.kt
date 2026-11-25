package com.javierrb35.bibliotecaia.sync

import android.provider.DocumentsContract
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageFolderRebuildTest {
    @Test fun staleGrantDoesNotMakeDeletedChildExist() {
        val tree = DocumentsContract.buildTreeDocumentUri("test.provider", "folder")
        val old = DocumentsContract.buildDocumentUriUsingTree(tree, "old-position")
        val replacement = DocumentsContract.buildDocumentUriUsingTree(tree, "new-position")
        assertTrue(StorageGrants.belongs(old, tree))
        assertNull(StorageGrants.listed(old, tree, emptyList()))
        val items = listOf(SharedDocumentStore.Item("BibliotecaIA-lectura-a.json", replacement, 10))
        assertNull(StorageGrants.listed(old, tree, items))
        assertEquals(replacement, StorageGrants.listed(replacement, tree, items)?.uri)
        val bare = DocumentsContract.buildDocumentUri("test.provider", "new-position")
        assertEquals(replacement, StorageGrants.listed(bare, tree, items)?.uri)
        assertNull(StorageGrants.listed(Uri.parse("content://other/document/new-position"), tree, items))
    }

    @Test fun abandonedRollbackIsPreservedLocally() {
        val root = java.nio.file.Files.createTempDirectory(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "rollback-").toFile()
        try {
            val old = File(root, "previous.zip").apply { writeText("previous contents") }
            StorageGrants.archiveRollback(old)
            assertFalse(old.exists())
            assertEquals("previous contents", File(root, "storage-recovery-history").listFiles()!!.single().readText())
        } finally { root.deleteRecursively() }
    }
}
