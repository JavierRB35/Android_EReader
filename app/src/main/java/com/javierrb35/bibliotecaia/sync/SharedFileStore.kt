package com.javierrb35.bibliotecaia.sync

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * File-only SAF fallback (e.g. providers that expose document pickers but no
 * tree picker). Every device owns a DISTINCT container; peers are read-only.
 * A sync writes its own container at most once, after staging and validation.
 * The portable backup ZIP is a separate file/format. No remote deletion.
 */
internal interface SharedContainerAccess {
    fun requireGrant(uri: Uri, write: Boolean)
    fun input(uri: Uri): InputStream
    fun output(uri: Uri): java.io.OutputStream
}
private class SafContainerAccess(context: Context) : SharedContainerAccess {
    private val resolver = context.contentResolver
    override fun requireGrant(uri: Uri, write: Boolean) {
        require(resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && (!write || it.isWritePermission)
        }) { "Falta permiso persistente para el contenedor." }
    }
    override fun input(uri: Uri) = resolver.openInputStream(uri)
        ?: throw IOException("Contenedor inaccesible.")
    override fun output(uri: Uri) = resolver.openOutputStream(uri, "wt")
        ?: throw IOException("No se puede escribir el contenedor propio.")
}
internal class SharedFileStore(
    cacheDir: File, filesDir: File, private val access: SharedContainerAccess,
    private val own: Uri, peers: List<Uri>, private val owner: String
) : SharedStore {
    constructor(context: Context, own: Uri, peers: List<Uri>, owner: String) :
        this(context.cacheDir, context.filesDir, SafContainerAccess(context), own, peers, owner)
    companion object {
        private const val OWNER = "bia-owner.txt"
        private const val MAX = 4L * 1024 * 1024 * 1024
        private const val MAX_OBJECT = 512L * 1024 * 1024
        private val NAME = Regex("bia-(book-[a-f0-9]{64}-[a-f0-9-]{36}\\.bin|state-[a-f0-9]{32}-[0-9]{1,19}-[a-f0-9]{64}-[a-f0-9-]{36}\\.json)")
        private val ID = Regex("[a-f0-9]{32}")
    }
    private val temp = File(cacheDir, "bia-container-" + UUID.randomUUID()).apply { check(mkdirs()) }
    private val recovery = File(filesDir, "bia-container-recovery-" +
        SharedDocumentStore.hash(own.toString().toByteArray()).take(24))
    private val old = File(temp, "own.zip")
    private val rollback = File(recovery, "previous.zip")
    private val pending = File(recovery, "pending.json")
    private val sources = linkedMapOf<Uri, Source>()
    private val staged = linkedMapOf<String, Source>()
    private val archives = mutableListOf<ZipFile>()
    private val errors = mutableListOf<String>()
    override val readErrors: List<String> get() = errors.toList()
    private var ownZip: ZipFile? = null
    private var initialized = false
    private data class Source(val item: SharedDocumentStore.Item, val open: () -> InputStream)
    init {
        try {
            require(ID.matches(owner))
            access.requireGrant(own, true)
            copyUri(own, old)
            recoverIfNeeded()
            if (old.length() > 0) {
                ownZip = load(old, owner)
                initialized = true
            }
            val seenOwners = hashSetOf(owner)
            for (peer in peers.distinct()) {
                try {
                    require(peer != own)
                    access.requireGrant(peer, false)
                    val file = File(temp, "peer-" + UUID.randomUUID() + ".zip")
                    copyUri(peer, file)
                    val zip = ZipFile(file)
                    try {
                        val id = ownerOf(zip)
                        require(id !in seenOwners) { "Contenedor duplicado o perteneciente a este dispositivo." }
                        validate(zip)
                        seenOwners.add(id)
                        register(zip)
                    } catch (e: Exception) { zip.close(); throw e }
                } catch (e: Exception) { errors.add("Contenedor de otro dispositivo: " + (e.message ?: "inaccesible")) }
            }
        } catch (e: Exception) { close(); throw e }
    }
     private fun copyBounded(input: InputStream, target: File, max: Long) {
        input.use { source ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= max) { "Contenedor demasiado grande." }
                    out.write(buffer, 0, n)
                }
            }
        }
    }
    private fun copyUri(uri: Uri, target: File) {
        copyBounded(access.input(uri), target, MAX)
    }
    private fun identity(file: File) = file.inputStream().use { SharedDocumentStore.digest(it, MAX).first }
    private fun ownerOf(zip: ZipFile): String {
        val entry = zip.getEntry(OWNER) ?: error("No es un contenedor de biblioteca compartida.")
        require(entry.size == 32L)
        val id = zip.getInputStream(entry).use { stream ->
            val bytes = ByteArray(32)
            java.io.DataInputStream(stream).readFully(bytes)
            require(stream.read() == -1)
            bytes.toString(Charsets.UTF_8)
        }
        require(ID.matches(id))
        return id
    }
    private fun validate(zip: ZipFile) {
        val names = hashSetOf<String>()
        var total = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            require(!entry.isDirectory && names.add(entry.name) &&
                (entry.name == OWNER || NAME.matches(entry.name)) &&
                entry.size in 1L..MAX_OBJECT) { "Entrada inválida en contenedor." }
            total += entry.size
            require(total <= MAX && names.size <= 50000) { "Contenedor fuera de límites." }
        }
        ownerOf(zip)
    }
    private fun register(zip: ZipFile) {
        archives.add(zip)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name == OWNER) continue
            val uri = Uri.parse("content://biblioteca.internal/" + UUID.randomUUID())
            val item = SharedDocumentStore.Item(entry.name, uri, entry.size)
            sources[uri] = Source(item) { zip.getInputStream(entry) }
        }
    }
    private fun load(file: File, expectedOwner: String): ZipFile {
        val zip = ZipFile(file)
        try {
            validate(zip)
            require(ownerOf(zip) == expectedOwner) { "El archivo pertenece a otro dispositivo. No se sobrescribirá." }
            register(zip)
            return zip
        } catch (e: Exception) { zip.close(); throw e }
    }
    override fun list() = sources.values.map { it.item }
    override fun open(item: SharedDocumentStore.Item): InputStream =
        sources[item.uri]?.open?.invoke() ?: throw IOException("Objeto no encontrado.")
    override fun create(name: String, mime: String, source: InputStream, hash: String, size: Long): SharedDocumentStore.Item {
        require(NAME.matches(name) && size in 1L..MAX_OBJECT && list().none { it.name == name })
        val file = File(temp, "object-" + UUID.randomUUID())
        copyBounded(source, file, size)
        require(file.inputStream().use { SharedDocumentStore.digest(it, size) } == (hash to size))
        val item = SharedDocumentStore.Item(name, Uri.fromFile(file), size)
        val value = Source(item) { file.inputStream() }
        sources[item.uri] = value
        staged[name] = value
        return item
    }
    private fun writeUri(file: File) {
        val out = access.output(own)
        out.use { sink -> file.inputStream().use { it.copyTo(sink, 64 * 1024) }; sink.flush() }
    }
    private fun clearRecovery() {
        check(!pending.exists() || pending.delete())
        rollback.delete()
        recovery.delete()
    }
    private fun recoverIfNeeded() {
        if (!pending.isFile) return
        val marker = JSONObject(pending.readText())
        require(marker.getString("uri") == own.toString() && marker.getString("owner") == owner)
        val before = marker.getString("previous")
        val after = marker.getString("next")
        require(rollback.isFile && identity(rollback) == before) { "Copia de emergencia dañada." }
        val actual = identity(old)
        if (actual == before || actual == after) { clearRecovery(); return }
        // A valid but unexpected archive could be an independent edit. Never overwrite it.
        val valid = runCatching { ZipFile(old).use { validate(it); ownerOf(it) } }.getOrNull()
        require(valid == null) { "Contenedor modificado externamente; recuperación detenida." }
        writeUri(rollback)
        copyUri(own, old)
        require(identity(old) == before) { "No se pudo recuperar el contenedor anterior." }
        clearRecovery()
    }
    override fun commit() {
        if (initialized && staged.isEmpty()) return
        val next = File(temp, "next-" + UUID.randomUUID() + ".zip")
        ZipOutputStream(next.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(OWNER))
            zip.write(owner.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            ownZip?.let { original ->
                val entries = original.entries().toList()
                // Only compact our own metadata history. Original objects remain
                // immutable and all previous original receipts stay in the new state.
                val oldStates = entries.filter { it.name.startsWith("bia-state-" + owner + "-") }
                    .sortedByDescending { it.name.removePrefix("bia-state-" + owner + "-").substringBefore("-").toLongOrNull() ?: 0L }
                val retained = oldStates.take(2).map { it.name }.toSet()
                val compact = staged.keys.any { it.startsWith("bia-state-" + owner + "-") }
                for (e in entries) {
                    if (e.name == OWNER || (compact && e in oldStates && e.name !in retained)) continue
                    zip.putNextEntry(ZipEntry(e.name))
                    original.getInputStream(e).use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
            for ((name, source) in staged) {
                zip.putNextEntry(ZipEntry(name))
                source.open().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
        }
        require(next.length() <= MAX)
        ZipFile(next).use { validate(it); require(ownerOf(it) == owner) }
        val before = identity(old)
        val after = identity(next)
        val current = File(temp, "current-" + UUID.randomUUID() + ".zip")
        copyUri(own, current)
        require(identity(current) == before) { "El contenedor propio cambió; no se sobrescribirá." }
        check(recovery.isDirectory || recovery.mkdirs())
        require(!pending.exists())
        old.copyTo(rollback, overwrite = true)
        require(identity(rollback) == before)
        val marker = File(recovery, "pending.tmp")
        marker.writeText(JSONObject().put("uri", own.toString()).put("owner", owner)
            .put("previous", before).put("next", after).toString())
        check(marker.renameTo(pending)) { "No se pudo registrar la recuperación." }
        // Pending recovery survives process death. The caller must not report
        // success or evict a local original until this independent read-back passes.
        writeUri(next)
        copyUri(own, current)
        require(identity(current) == after) { "No se ha verificado el contenedor escrito." }
        clearRecovery()
        initialized = true
    }
    override fun close() {
        archives.forEach { runCatching { it.close() } }
        temp.deleteRecursively()
    }
}
