package com.javierrb35.bibliotecaia.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Núcleo puro de la sincronización (§15.5): decisión de conflictos sin Android,
 * verificable con pruebas de datos sintéticos.
 *
 * Reglas acordadas:
 * · Nunca sobrescritura silenciosa de contenido de usuario (anotaciones: INSERT OR
 *   IGNORE por id; el texto ya presente en otro dispositivo se conserva).
 * · Posiciones de lectura: gana la más reciente por updatedAt (sin retrocesos).
 * · Borrados por lápida: una fila con deleted_at no revive; su contenido no se fusiona.
 */
object SyncPolicy {

    /** Decide el resultado de un conflicto de posición de lectura. */
    fun resolvePosition(localUpdatedAt: Long, remoteUpdatedAt: Long): Winner =
        when {
            remoteUpdatedAt > localUpdatedAt -> Winner.REMOTE
            remoteUpdatedAt < localUpdatedAt -> Winner.LOCAL
            else -> Winner.LOCAL // empate: conserva lo local, sin retroceso
        }

    /** ¿Debe aplicarse una anotación remota sobre el almacenamiento local? */
    fun applyRemoteAnnotation(existsLocally: Boolean, remoteDeleted: Boolean): Boolean =
        !existsLocally && !remoteDeleted

    /** ¿Debe marcarse como borrada (lápida) una anotación local por orden remoto? */
    fun shouldTombstoneLocal(existsLocally: Boolean, remoteDeleted: Boolean): Boolean =
        existsLocally && remoteDeleted

    /** Serializa un lote para upsert PostgREST conservando el orden de envío. */
    fun batch(rows: List<JSONObject>): JSONArray = JSONArray().apply { rows.forEach(::put) }

    /** Filtra filas remotas que el dispositivo aún no conoce por id. */
    fun unknownIds(localIds: Set<String>, remoteRows: List<JSONObject>, idKey: String): List<JSONObject> =
        remoteRows.filter { it.optString(idKey).isNotEmpty() && it.optString(idKey) !in localIds }

    enum class Winner { LOCAL, REMOTE }

    /** Clasificación de un fallo HTTP de sincronización para decidir el trato de la entrada. */
    enum class FailureKind(val label: String, val retryable: Boolean) {
        PERMANENT("error permanente", false),
        RETRYABLE("reintenable", true),
    }

    /** 4xx (salvo 408/429): la petición volverá a fallar igual; 5xx/408/429/red: reintento. */
    fun classifyFailure(status: Int): FailureKind = when (status) {
        408, 429 -> FailureKind.RETRYABLE
        in 500..599 -> FailureKind.RETRYABLE
        in 400..499 -> FailureKind.PERMANENT
        else -> FailureKind.RETRYABLE
    }
}

