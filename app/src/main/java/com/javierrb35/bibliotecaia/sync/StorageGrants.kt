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
