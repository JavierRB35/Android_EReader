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
}
