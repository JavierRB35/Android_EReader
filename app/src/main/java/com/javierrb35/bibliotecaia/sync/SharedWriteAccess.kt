package com.javierrb35.bibliotecaia.sync

/** A backup is an independent recovery feature, not a write permission.
 * This gate describes persisted access, not network/provider availability. */
enum class SharedWriteAccess(val message: String) {
    READY(""),
    NOT_CONNECTED("Selecciona la carpeta compartida en Ajustes. No necesitas una copia de seguridad independiente."),
    NO_WRITE_GRANT("La biblioteca no tiene permiso persistente de lectura y escritura. Selecciona de nuevo la carpeta con esos permisos."),
    NEEDS_REPAIR("La identidad de la conexión no coincide. Comprueba la biblioteca en Ajustes sin borrar tus libros.");

    companion object {
        fun evaluate(configured: Boolean, hasReadWriteGrant: Boolean, needsRepair: Boolean): SharedWriteAccess =
            when {
                !configured -> NOT_CONNECTED
                !hasReadWriteGrant -> NO_WRITE_GRANT
                needsRepair -> NEEDS_REPAIR
                else -> READY
            }
    }
}
