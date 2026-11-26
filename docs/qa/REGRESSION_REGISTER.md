# Registro vivo de regresiones — BibliotecaAI

No confundir una corrección escrita con una verificación realizada. Antes de fusionar cambios relacionados, comprobar los casos de esta tabla y los documentos enlazados. Las suites JVM y startup smoke se ejecutan sobre cada PR/main y son requisitos de la publicación automática. No retirar las pruebas existentes ni falsear resultados.

| Incidencia | Causa/contrato | Prueba permanente | Estado de verificación |
|---|---|---|---|
| [#95](https://github.com/JavierRB35/Android_EReader/issues/95) — Drive ausente en tablet v0.1.179 | #96 (9019da52/v0.1.127) abrió documento ZIP; #119 (8e5993e) retiró ruta. Recuperar búsqueda de proveedor con OPEN_DOCUMENT y reintento OPEN_DOCUMENT_TREE con hint; nunca aceptar documento como árbol, persistir R/W exactos, no crear ZIP. [Contrato](../architecture/GOOGLE_DRIVE_ACCESS.md) | SharedSettingsJourneyDeviceTest: folderOnlyAndCancelReturnsToChoice, driveSearchUsesOpenDocumentAndCancelReturnsToChoice, driveDocumentIsOnlyNavigationHintAndNeverActivatesLibrary, sharedFolderCheckOpensTreeWithoutChangingExistingConnection; SharedLibraryJourneyTest, SharedWriteAccessTest | Implementado en rama; CI y Drive real A/B pendientes; acceso completo no acreditado |
| Original ausente / ZIP recuperable | No sustituir copia buena si faltan originales; conservar recuperación | CompleteBackupIntegrityInstrumentedTest, CompleteBackupRestoreInstrumentedTest, SingleFileBackupInstrumentedTest | Ver evidencia de cada release; Drive físico pendiente |
| Biblioteca vaciada / libros borrados resurgen | Distinguir retirada local, baja definitiva y nuevo catálogo; preservar originales y lápidas | StorageFolderRebuildTest, BookDeletionJourneyTest, SharedFileStoreJourneyTest | Reinicio completo desde cero todavía pendiente; no equivale a traslado |
| Notas al tocar marcas | Rango exacto y modal guardado; no interferir con selección | SavedAnnotationModalDeviceTest, ScripturaMarkTapDeviceTest | Ver CI de cada cambio y Samsung físico |
| [#94](https://github.com/JavierRB35/Android_EReader/pull/94) — selección multipantalla | Arrastre al borde, conserva selección y no cambia capítulo | SelectionEdgeScrollDeviceTest, SelectionEdgeSpeedTest (en PR #94, todavía no integradas) | CI pendiente; Readium y límites de bloque por verificar |

| PR #100 / #99 — pérdida de foco en Ajustes | Cuatro fallos repetibles Espresso, ventana base sin foco; posible interferencia de consulta de releases real en debug. Aislar con variante QA existente y archivar diagnóstico si vuelve a fallar | Se mantienen SharedSettingsJourneyDeviceTest (3 casos) y SettingsBackupJourneyDeviceTest (1 caso), suite startup completa | [Evidencia de fallo](https://github.com/JavierRB35/Android_EReader/actions/runs/45614799099); hipótesis y corrección de aislamiento pendientes de CI |
| Configuración IA ausente y claves mezcladas | Ajustes enlaza IA; campos/guardado/estado independientes, Groq predeterminado para nuevos ajustes, conserva opt-out y cifrado | AiSetupJourneyDeviceTest + AiFallbackRouterTest | Implementación en rama; CI y prueba real pendientes |
| Actualizaciones publicadas no detectadas | No consumir intervalo en Splash/cancelación; caché al navegar, aviso transitorio no consume diálogo; distinguir error de versión actual | UpdateCheckerVersionTest (clasificación HTTP y metadatos), CI startup | Implementación en rama, comprobación física pendiente |
| #103 — biblioteca preparada pero importación exige backup | Selector, recepción y publicación no deben depender de ZIP; permiso SAF persistente e identidad válidos; relectura antes de retirar staging; offline conserva originales | SharedWriteAccessTest, BatchImportTest, SharedLibraryJourneyTest.centralImportWithoutBackupRetainsOfflineOriginalAndReconcilesOnSecondDevice | Implementado en rama; CI y Drive real A/B pendientes |

## Cierre de incidencias

Registrar PR, SHA exacto, links de Actions, versión publicada y resultado de prueba física si aplica. Reabrir si el síntoma reaparece; reproducir primero y reforzar el test. No declarar verificado Drive real por un emulador con proveedor simulado.

## Pendiente crítico: empezar de cero

El cambio de ubicación conserva los datos locales. Un reinicio completo requiere una operación independiente, confirmada, que detenga trabajos de sincronización y backup, desvincule todos los URI/peers e historial de conexión, vacíe catálogo y datos derivados de libros sin tocar claves IA ni archivos remotos, y cree una nueva identidad/ubicación sin restauración implícita. Debe ser recuperable ante interrupción, con pruebas de dos dispositivos y respaldo previo. No implementar como borrado parcial de preferencias ni como cambio de carpeta; no hay reset destructivo en esta PR.
