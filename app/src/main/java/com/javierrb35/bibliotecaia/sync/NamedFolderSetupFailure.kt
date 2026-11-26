package com.javierrb35.bibliotecaia.sync

/** Report the failing operation, not the state of an unrelated saved grant.
 * Keep provider details local; do not include them in analytics or release notes. */
internal class NamedFolderSetupFailure(val stage: Stage, cause: Exception) :
    IllegalStateException(cause.message, cause) {
    enum class Stage(val label: String, val advice: String) {
        FOLDER_ACCESS("Acceso a la carpeta seleccionada",
            "No se ha verificado el acceso completo de lectura y escritura. Selecciona de nuevo la carpeta de Drive. Un archivo individual no concede acceso a la carpeta."),
        LEGACY_PROGRESS("Recuperación de avances anteriores",
            "La carpeta nueva ha superado la prueba de acceso. Falta recuperar los avances guardados anteriormente. Comprueba que su ubicación anterior sigue accesible."),
        TARGET_CATALOGUE("Lectura del catálogo de destino",
            "La carpeta permite lectura y escritura, pero no se ha podido leer su catálogo o contiene archivos del formato antiguo. No borres archivos."),
        LEGACY_LIBRARY("Recuperación de biblioteca anterior",
            "La carpeta nueva ha superado la prueba de acceso, pero no se han recuperado todos los datos u originales anteriores. Conservamos la conexión anterior; comprueba su acceso."),
        SYNC("Sincronización de libros y datos",
            "El acceso inicial funciona, pero no se ha completado la sincronización. Reintenta sin borrar archivos."),
        VERIFY("Verificación de originales",
            "No se han verificado todos los originales. No se activará la nueva conexión sin comprobarlos."),
        SAVE("Guardado de conexión",
            "No se ha completado el guardado de la configuración. Comprueba de nuevo su estado antes de cambiar datos.")
    }

    fun userMessage(): String = stage.advice + "\n\nEtapa: " + stage.label +
        "\nDetalle: " + (cause?.message?.take(600)?.takeIf { it.isNotBlank() }
            ?: cause?.javaClass?.simpleName ?: "Error no especificado")
}
