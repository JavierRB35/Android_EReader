package com.javierrb35.bibliotecaia.updates

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The installer can read ONLY the private updates cache, not the user's books,
 * annotations, or any other application-private data.
 */
@RunWith(AndroidJUnit4::class)
class UpdateFileProviderInstrumentedTest {
    @Test fun onlyVerifiedUpdatesDirectoryIsExposedViaContentUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = context.packageName + ".updates.fileprovider"
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val fakeUpdate = File(folder, "test-release.apk")
        val userBook = File(context.filesDir, "sensitive-book.epub")
        try {
            fakeUpdate.writeBytes(byteArrayOf(1, 2, 3))
            userBook.writeText("personal notes and books must not be exposed")
            val uri = FileProvider.getUriForFile(context, authority, fakeUpdate)
            assertEquals("content", uri.scheme)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, userBook)
            }
        } finally {
            fakeUpdate.delete()
            userBook.delete()
        }
    }
}
