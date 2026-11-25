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
internal interface SharedStore : java.io.Closeable {
    val readErrors: List<String> get() = emptyList()
    // Opt-in for the new folder layout; legacy ZIP and existing folders remain readable.
    val namedOriginals: Boolean get() = false
    fun commit() {}
    override fun close() {}
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

internal class SharedDocumentStore(context: Context, private val tree: Uri,
    override val namedOriginals: Boolean = false) : SharedStore {
    private val resolver = context.contentResolver
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree,
        DocumentsContract.getTreeDocumentId(tree))
    data class Item(val name: String, val uri: Uri, val size: Long, val directory: Boolean = false)
    private val technicalName = "_Sincronizacion"
    private fun childrenOf(parent: Uri): List<Item> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
            DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE)
        return resolver.query(children, columns, null, null, null)?.use { c ->
            // A cloud provider may return an empty placeholder while loading.
            // Never treat this as proof that a previously saved file was deleted.
            if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                !c.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank())
                throw IOException("La carpeta aún no está disponible. Comprueba la conexión y vuelve a intentarlo.")
            buildList {
                while (c.moveToNext()) add(Item(c.getString(1),
                    DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    if (c.isNull(2)) -1L else c.getLong(2), c.getString(3) == Document.MIME_TYPE_DIR))
            }
        } ?: throw IOException("El proveedor no permite listar esta carpeta.")
    }
    private fun technicalFolder(create: Boolean): Uri? {
        val before = childrenOf(root)
        val candidates = before.filter { it.name.equals(technicalName, ignoreCase = true) }
        require(candidates.size <= 1 && candidates.all { it.directory && it.name == technicalName }) {
            "La carpeta técnica es ambigua; no se modificará."
        }
        candidates.singleOrNull()?.let { return it.uri }
        if (!create) return null
        val created = DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, technicalName)
            ?: throw IOException("El proveedor no permite crear la carpeta técnica.")
        require(before.none { it.uri == created }) { "El proveedor devolvió una carpeta existente." }
        val matching = childrenOf(root).filter { it.name.equals(technicalName, ignoreCase = true) }
        require(matching.size == 1 && matching.single().directory &&
            matching.single().name == technicalName && matching.single().uri == created) {
            "No se pudo verificar una única carpeta técnica; no se escribirán metadatos."
        }
        return created
    }
    override fun list(): List<Item> {
        val files = childrenOf(root).filterNot { it.directory }
        // Preserve the legacy catalogue at root; never move or delete it.
        // New metadata is isolated; unrelated nested files are not scanned.
        val metadata = if (namedOriginals) technicalFolder(false)?.let { folder ->
            childrenOf(folder).filter { !it.directory && it.name.startsWith("bia-state-") }
        }.orEmpty() else emptyList()
        return files + metadata
    }
    override fun open(item: Item): InputStream = resolver.openInputStream(item.uri)
        ?: throw IOException("No se puede leer " + item.name)
    override fun create(name: String, mime: String, source: InputStream, hash: String, size: Long): Item {
        val parent = if (namedOriginals && name.startsWith("bia-state-"))
            requireNotNull(technicalFolder(true)) else root
        val before = childrenOf(parent)
        require(before.none { it.name.equals(name, ignoreCase = true) }) {
            "Ya existe un documento con ese nombre; no se sobrescribirá."
        }
        val uri = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("El proveedor no permite crear documentos.")
        // Check the returned identity BEFORE opening a writable stream.
        require(before.none { it.uri == uri }) {
            "El proveedor devolvió un documento existente; no se sobrescribirá."
        }
        // Do not assume provider create/list is an atomic distributed reservation.
        resolver.openOutputStream(uri, "wt")?.use { out ->
            source.use { it.copyTo(out, 64 * 1024) }
        } ?: throw IOException("No se puede escribir el documento nuevo.")
        val actual = resolver.openInputStream(uri)?.use { digest(it, size) }
            ?: throw IOException("No se puede verificar el documento nuevo.")
        require(actual == (hash to size)) { "El proveedor no devolvió los bytes guardados." }
        val item = childrenOf(parent).singleOrNull { it.uri == uri && it.name == name && !it.directory }
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
        val bytes = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
        val name = "bia-probe-" + UUID.randomUUID() + ".txt"
        val before = list()
        require(before.none { it.name == name }) { "El archivo de prueba ya existe; vuelve a intentarlo." }
        val created = DocumentsContract.createDocument(resolver, root, "text/plain", name)
            ?: throw IOException("El proveedor no permite crear archivos en esta carpeta.")
        // A provider must not return an existing document. Never write to or
        // delete a known pre-existing URI, even if the requested name is new.
        require(before.none { it.uri == created }) { "El proveedor devolvió un archivo existente. No se ha modificado." }
        var verified = false
        try {
            resolver.openOutputStream(created, "wt")?.use { it.write(bytes) }
                ?: throw IOException("No se puede escribir en esta carpeta.")
            val item = list().singleOrNull { it.uri == created && it.name == name }
                ?: throw IOException("El archivo de prueba no aparece con su identidad y nombre originales.")
            require(verify(item, hash(bytes), bytes.size.toLong())) { "No coinciden los bytes leídos y escritos." }
            verified = true
        } finally {
            // Cleanup is limited to the URI created by this very probe, including
            // incomplete writes. Do not search by name or delete other files.
            val removed = runCatching { DocumentsContract.deleteDocument(resolver, created) }.getOrDefault(false)
            if (verified && !removed) throw IOException("Lectura y escritura correctas, pero no se pudo retirar el archivo temporal de prueba.")
        }
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
