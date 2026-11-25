package com.javierrb35.bibliotecaia.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * A SAF document is owned by ONE device. Other devices may read it, but never
 * modify it. This avoids concurrent writers to the same Google Drive file.
 * Files contain positions only: no book bytes, account details or API keys.
 */
data class ReadingPositionRecord(
    val contentHash: String,
    val locator: String,
    val progress: Float,
    val updatedAt: Long,
    val deviceId: String,
)

data class ReadingPositionDocument(
    val deviceId: String,
    val positions: List<ReadingPositionRecord>,
)

object ReadingPositionFileCodec {
    private const val APP = "biblioteca-ia-reading-sync"
    private const val VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_POSITIONS = 10_000
    private val HASH = Regex("[a-f0-9]{64}")
    private val DEVICE = Regex("[A-Za-z0-9_-]{8,64}")

    fun encode(document: ReadingPositionDocument): String {
        validateOwner(document.deviceId)
        require(document.positions.size <= MAX_POSITIONS)
        val positions = JSONArray()
        for (record in document.positions.sortedBy { it.contentHash }) {
            validate(record)
            positions.put(JSONObject().put("hash", record.contentHash)
                .put("locator", record.locator)
                .put("progress", record.progress.toDouble())
                .put("updatedAt", record.updatedAt)
                .put("source", record.deviceId))
        }
        return JSONObject().put("app", APP).put("version", VERSION)
            .put("owner", document.deviceId).put("positions", positions).toString()
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun decode(json: String): ReadingPositionDocument {
        require(json.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES) {
            "El archivo de posiciones está vacío o excede el tamaño permitido."
        }
        val root = try { JSONObject(json) } catch (_: Exception) {
            throw IllegalArgumentException("El archivo no es un estado de lectura válido.")
        }
        require(root.optString("app") == APP && root.optInt("version", -1) == VERSION) {
            "El archivo no corresponde a la sincronización de BibliotecaAI."
        }
        val owner = root.getString("owner").also(::validateOwner)
        val array = root.getJSONArray("positions")
        require(array.length() <= MAX_POSITIONS) { "Demasiadas posiciones en el archivo." }
        val seen = HashSet<String>()
        val records = ArrayList<ReadingPositionRecord>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val record = ReadingPositionRecord(
                item.getString("hash"), item.getString("locator"),
                item.getDouble("progress").toFloat(), item.getLong("updatedAt"),
                item.getString("source")
            )
            validate(record)
            require(seen.add(record.contentHash)) { "Libro duplicado en sincronización." }
            records.add(record)
        }
        return ReadingPositionDocument(owner, records)
    }

    /**
     * The timestamp is the real local movement's timestamp, not the polling time.
     * Locator/progress and then device ID resolve exact timestamp ties deterministically; clock skew/offline concurrent
     * movements remain an explicit limitation of serverless synchronization.
     */
    fun newest(records: Iterable<ReadingPositionRecord>): Map<String, ReadingPositionRecord> {
        val result = linkedMapOf<String, ReadingPositionRecord>()
        for (record in records) {
            validate(record)
            val previous = result[record.contentHash]
            if (previous == null || record.updatedAt > previous.updatedAt ||
                (record.updatedAt == previous.updatedAt &&
                    (record.locator > previous.locator ||
                        (record.locator == previous.locator &&
                            (record.progress > previous.progress ||
                                (record.progress == previous.progress && record.deviceId > previous.deviceId)))))) {
                result[record.contentHash] = record
            }
        }
        return result
    }

    private fun validateOwner(id: String) {
        require(DEVICE.matches(id)) { "Identificador de dispositivo no válido." }
    }

    private fun validate(record: ReadingPositionRecord) {
        require(HASH.matches(record.contentHash) &&
            record.locator.isNotBlank() && record.locator.length <= 65_536 &&
            record.progress.isFinite() && record.progress in 0f..1f &&
            record.updatedAt > 0L) { "Posición de lectura no válida." }
        validateOwner(record.deviceId)
    }
}
