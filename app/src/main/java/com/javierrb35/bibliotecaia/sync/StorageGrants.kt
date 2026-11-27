package com.javierrb35.bibliotecaia.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** The system validates the actual descendant. Never infer folder permission from a file URI. */
internal object StorageGrants {
    fun allows(context: Context, uri: Uri, write: Boolean): Boolean {
        if (uri.scheme != "content") return false
        val tree = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && (!write || permission.isWritePermission) &&
                (permission.uri == uri || (tree != null && permission.uri.authority == uri.authority &&
                    permission.uri.pathSegments.size == 2 && permission.uri.pathSegments.firstOrNull() == "tree" &&
                    runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull() == tree))
        }
    }

    fun belongs(uri: Uri?, tree: Uri): Boolean = uri != null &&
        uri.authority == tree.authority &&
        runCatching { DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree) }.getOrDefault(false)

    /** The child list is authoritative only after a successful complete query.
     * Compare document IDs, not display names or the old persisted grant. */
    fun listed(uri: Uri?, tree: Uri, items: List<SharedDocumentStore.Item>): SharedDocumentStore.Item? {
        if (uri == null || uri.authority != tree.authority) return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return items.singleOrNull {
            runCatching { DocumentsContract.getDocumentId(it.uri) == id }.getOrDefault(false)
        }
    }

    /** Archive a rollback locally instead of discarding it when its remote
     * destination is confirmed absent. Do not touch any remote file. */
    fun archiveRollback(file: java.io.File) {
        if (!file.exists()) return
        val dir = java.io.File(file.parentFile, "storage-recovery-history")
        check(dir.isDirectory || dir.mkdirs()) { "No se pudo conservar la copia anterior." }
        check(file.renameTo(java.io.File(dir, java.util.UUID.randomUUID().toString() + "-" + file.name))) {
            "No se pudo conservar la copia anterior."
        }
    }

    fun create(context: Context, tree: Uri, mime: String, name: String): Uri {
        require(allows(context, tree, true)) { "Falta acceso a la carpeta elegida." }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val created = DocumentsContract.createDocument(context.contentResolver, root, mime, name)
            ?: error("La ubicación no permite crear archivos automáticamente.")
        val child = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created))
        require(allows(context, child, true))
        return child
    }
}
