package com.javierrb35.bibliotecaia.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.backup.BackupRoutine
import com.javierrb35.bibliotecaia.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Only Android-granted tree URIs are eligible. Never infer a sibling from
 * a single-document grant. The provider additionally enforces ancestry. */
internal object CloudSetupFolder {
    private const val PREFS = "cloud_setup_folder"
    fun permitted(context: Context, uri: Uri, write: Boolean): Boolean {
        return context.contentResolver.persistedUriPermissions.any { grant ->
            if (!grant.isReadPermission || (write && !grant.isWritePermission)) false
            else if (grant.uri == uri) true
            else runCatching {
                DocumentsContract.isTreeUri(grant.uri) && DocumentsContract.isTreeUri(uri) &&
                    grant.uri.authority == uri.authority &&
                    DocumentsContract.getTreeDocumentId(grant.uri) == DocumentsContract.getTreeDocumentId(uri)
            }.getOrDefault(false)
        }
    }

    fun tree(context: Context): Uri? {
        val chosen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tree", null)?.let(Uri::parse)
        return (chosen ?: SharedLibrary.uri(context)?.takeIf { !SharedLibrary.fileMode(context) })
            ?.takeIf { DocumentsContract.isTreeUri(it) && permitted(context, it, true) }
    }

    fun remember(context: Context, tree: Uri) {
        require(DocumentsContract.isTreeUri(tree) && permitted(context, tree, true))
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tree", tree.toString()).commit())
    }

    fun create(context: Context, tree: Uri, name: String, mime: String): Uri {
        require(permitted(context, tree, true))
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val raw = DocumentsContract.createDocument(context.contentResolver, root, mime, name)
            ?: throw IOException("La ubicación no permite crear archivos automáticamente.")
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(raw))
    }

    fun deviceLabel(context: Context): String =
        android.os.Build.MODEL.replace(Regex("[^a-zA-Z0-9_-]+"), "-").trim('-').take(28).ifBlank { "Android" } +
            "-" + ReadingPositionSafSync.deviceId(context).take(8)

    fun backupName(context: Context) = "BibliotecaIA-copia-" + deviceLabel(context) + ".zip"

    suspend fun prepareBackup(context: Context, database: AppDatabase, tree: Uri): BackupRoutine.Result? =
        withContext(Dispatchers.IO) {
            val state = BackupRoutine.status(context)
            if (state.uri != null) return@withContext null
            require(!state.recoveryPending) { "Hay una copia anterior pendiente de recuperación." }
            val uri = create(context, tree, backupName(context), "application/zip")
            BackupRoutine.save(context, database, BuildConfig.VERSION_NAME, force = true,
                chosenUri = uri, replaceExisting = false)
        }
}
