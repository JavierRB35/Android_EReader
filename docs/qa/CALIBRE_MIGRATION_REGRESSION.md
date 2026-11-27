# Regresión: ZIP Calibre no incorpora libros (26/11/2026)

El propietario informa que seleccionar el ZIP no restaura libros. En main 5dad7975, `SettingsActivity.onCreate` solo ofrece Recuperar una copia, que llama a BackupRoutine.restore (local); la opción antigua showSharedOptions que llamaba a SharedLibrary.migrateZip no se construye en pantalla. Tampoco tenía confirmación de éxito. Esto acredita la regresión de accesibilidad, no demuestra la causa exacta de un ZIP concreto no inspeccionado.

Corrección: opción independiente y visible en Ajustes; comprobar carpeta única y permiso de escritura; selector ZIP, progreso, resultado total verificado o error, sin acceso a vistas antiguas no inicializadas. Mantener recuperación local independiente y no cambiar originales ni configuración. No modificar SharedLibrary mientras #129 está en validación.

Pruebas permanentes en startup smoke existente: SettingsBackupJourneyDeviceTest.firstBackupAndDestinationChoiceAreExplicitlyReachable (ambas rutas visibles y aviso de configuración); SingleFileBackupInstrumentedTest.calibreLegacyZipWithVerifiedOriginalIsRestoredWithoutDuplicates (layout Python, original y metadatos, reimportación sin duplicidad en Room local). No equivale a migración SAF real. Pendiente CI, APK, importación de Migracion-001.zip real en carpeta Drive y lectura en segundo dispositivo; verificar recuento, bytes y reintento.
