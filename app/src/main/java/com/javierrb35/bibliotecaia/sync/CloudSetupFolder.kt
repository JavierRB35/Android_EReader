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

    private val containerName = Regex("^BibliotecaIA-(?!copia-|completa|lectura-).+-[a-f0-9]{8}(?:-[a-f0-9]{8})?(?: \\(\\d+\\))?\\.zip$",
        RegexOption.IGNORE_CASE)

    /** Preserve the transport already present in the chosen folder. A new
     * installation must not accidentally create a folder-mode library beside
     * another device's file-mode library. Never infer ownership from a name. */
    suspend fun prepareLibrary(context: Context, database: AppDatabase, tree: Uri) =
        withContext(Dispatchers.IO) {
            if (!SharedLibrary.configured(context)) {
                val candidates = SharedDocumentStore(context, tree).use { it.list() }
                    .filter { containerName.matches(it.name) }
                if (candidates.isEmpty()) {
                    val result = SharedLibrary.connect(context, database, tree)
                    require(result.errors.isEmpty()) { result.errors.joinToString(" · ") }
                } else {
                    val id = ReadingPositionSafSync.deviceId(context)
                    val matches = candidates.filter { it.name.contains(id.take(8), ignoreCase = true) }
                    require(matches.size <= 1) { "Hay varios archivos de este dispositivo. Revisa la conexión." }
                    val own = matches.singleOrNull()?.uri ?: create(context, tree,
                        "BibliotecaIA-" + deviceLabel(context) + ".zip", "application/zip")
                    val result = SharedLibrary.connectFile(context, database, own, newDocument = matches.isEmpty())
                    require(result.errors.isEmpty()) { result.errors.joinToString(" · ") }
                }
            }
            if (SharedLibrary.fileMode(context)) {
                val own = SharedLibrary.uri(context)
                val linked = SharedLibrary.linkedFiles(context)
                val candidates = SharedDocumentStore(context, tree).use { it.list() }
                    .filter { containerName.matches(it.name) && it.uri != own &&
                        !it.name.contains(ReadingPositionSafSync.deviceId(context).take(8), ignoreCase = true) }
                for (candidate in candidates.take(10)) {
                    // File-only links previously granted through a picker can
                    // have a different URI for the same document. Compare IDs.
                    fun same(a: Uri, b: Uri): Boolean = a == b ||
                        (a.authority == b.authority && runCatching {
                            DocumentsContract.getDocumentId(a) == DocumentsContract.getDocumentId(b)
                        }.getOrDefault(false))
                    if (linked.any { same(it, candidate.uri) }) continue
                    try { SharedLibrary.addFilePeer(context, database, candidate.uri) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { /* Owner validation failed; do not link it. */ }
                }
            }
        }

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
