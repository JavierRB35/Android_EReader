package com.javierrb35.bibliotecaia.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecureSessionStoreTest {
    private lateinit var directory: File
    private lateinit var store: SecureSessionStore
    private lateinit var alias: String
    private val project = "https://abcdefghijklmnopqrst.supabase.co"
    private fun session() = AccountSession("d4719550-aea6-4f5c-904e-615dc4b775e6", "synthetic@example.invalid", "synthetic-access-token", "synthetic-refresh-token", 2000000000, project)

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "auth-storage-test-${UUID.randomUUID()}").apply { mkdirs() }
        alias = "biblioteca.test.${UUID.randomUUID()}"
        store = SecureSessionStore(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        }, alias)
    }
    @After fun cleanup() {
        directory.listFiles()?.forEach { it.delete() }; directory.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    @Test fun roundTripPersistsEncryptedCredentials() {
        store.save(session())
        val restored = store.load(project)!!
        assertEquals(session().userId, restored.userId)
        assertEquals(session().refreshToken, restored.refreshToken)
        val raw = File(directory, "biblioteca-account-session.v1").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("synthetic-access-token")); assertFalse(raw.contains("synthetic-refresh-token"))
    }
    @Test fun tamperingFailsClosed() {
        store.save(session())
        val file = File(directory, "biblioteca-account-session.v1")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        try { store.load(project); fail("Tampered ciphertext accepted") } catch (e: AccountFailure) {
            assertEquals(AccountFailure.Reason.SECURE_STORAGE, e.reason)
        }
    }
    @Test fun projectMismatchCannotReuseSession() {
        store.save(session())
        try { store.load("https://otherprojectabcdefghi.supabase.co"); fail("Cross-project session accepted") } catch (e: AccountFailure) {
            assertEquals(AccountFailure.Reason.SECURE_STORAGE, e.reason)
        }
    }
    @Test fun signOutDeletesOnlyAccountFile() {
        val unrelated = File(directory, "library-marker").apply { writeText("keep") }
        store.save(session()); store.clear()
        assertNull(store.load(project)); assertEquals("keep", unrelated.readText())
    }
}
