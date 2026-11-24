package com.javierrb35.bibliotecaia.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** No shared mutable file. Each publication has a new name; incomplete files
 * are never advertised by a verified receipt. No remote delete or overwrite. */
internal interface SharedStore {
    fun list(): List<SharedDocumentStore.Item>
    fun open(item: SharedDocumentStore.Item): InputStream
    fun create(name: String, mime: String, source: InputStream, hash: String, size: Long): SharedDocumentStore.Item
    fun verify(item: SharedDocumentStore.Item, hash: String, size: Long): Boolean =
        open(item).use { SharedDocumentStore.digest(it, size) } == (hash to size)
    fun extract(item: SharedDocumentStore.Item, file: File, hash: String, size: Long) {
        open(item).use { source ->
            file.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= size)
                    sink.write(buffer, 0, count)
                }
            }
        }
        require(file.inputStream().use { SharedDocumentStore.digest(it, size) } == (hash to size))
    }
}

internal class SharedDocumentStore(context: Context, private val tree: Uri) : SharedStore {
    private val resolver = context.contentResolver
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree,
        DocumentsContract.getTreeDocumentId(tree))
    data class Item(val name: String, val uri: Uri, val size: Long)
    override fun list(): List<Item> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
            DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE)
        return resolver.query(children, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Item(c.getString(1),
                    DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    if (c.isNull(2)) -1L else c.getLong(2)))
            }
        } ?: throw IOException("El proveedor no permite listar esta carpeta.")
    }
    override fun open(item: Item): InputStream = resolver.openInputStream(item.uri)
        ?: throw IOException("No se puede leer " + item.name)
    override fun create(name: String, mime: String, source: InputStream, hash: String, size: Long): Item {
        val uri = DocumentsContract.createDocument(resolver, root, mime, name)
            ?: throw IOException("El proveedor no permite crear documentos.")
        // Never truncate any existing document. This URI is newly created.
        resolver.openOutputStream(uri, "wt")?.use { out ->
            source.use { it.copyTo(out, 64 * 1024) }
        } ?: throw IOException("No se puede escribir el documento nuevo.")
        val actual = resolver.openInputStream(uri)?.use { digest(it, size) }
            ?: throw IOException("No se puede verificar el documento nuevo.")
        require(actual == (hash to size)) { "El proveedor no devolvió los bytes guardados." }
        val item = list().firstOrNull { it.uri == uri || it.name == name }
            ?: throw IOException("El documento escrito no aparece todavía en la carpeta.")
        require(item.name == name) { "El proveedor cambió el nombre del documento." }
        return item
    }
    override fun verify(item: Item, hash: String, size: Long): Boolean =
        open(item).use { digest(it, size) } == (hash to size)
    override fun extract(item: Item, file: File, hash: String, size: Long) {
        val md = MessageDigest.getInstance("SHA-256")
        var count = 0L
        open(item).use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    count += n
                    require(count <= size) { "Original central demasiado grande." }
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        require(count == size && hex(md.digest()) == hash) { "Original central incompleto o dañado." }
    }
    fun probe() {
        val bytes = UUID.randomUUID().toString().toByteArray()
        val name = "bia-probe-" + UUID.randomUUID() + ".txt"
        val item = create(name, "text/plain", bytes.inputStream(), hash(bytes), bytes.size.toLong())
        require(verify(item, hash(bytes), bytes.size.toLong()))
        // Only delete our own temporary probe, never a published object.
        runCatching { DocumentsContract.deleteDocument(resolver, item.uri) }
    }
    companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun digest(input: InputStream, limit: Long): Pair<String, Long> {
            val md = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                count += n
                require(count <= limit) { "Documento mayor que el tamaño esperado." }
                md.update(buf, 0, n)
            }
            return hex(md.digest()) to count
        }
    }
}
