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

## #95 — diagnóstico físico v0.1.181 (26/11)
Selección de documento Drive lograda; selección/comprobación posterior falla con aviso genérico. No confundir progreso histórico inaccesible con fallo del proveedor. Prueba destino antes de migrar y diagnóstico por etapas; NamedFolderSetupFailureTest y SharedSettingsJourneyDeviceTest.returnedDocumentCannotBeAcceptedAsTree. CI y físico pendientes; no cerrar.

## #126 — carátulas y navegación (26/11)
No registrar ausencia de portada por original remoto no descargado. Miniaturas JPEG acotadas, verificadas por hash y separadas de originales, caché local persistente y recuperación A/B. Navegar desde índice listado/Room sin leer originales; hash real obligatorio antes de aceptar descargas, publicación, migración y evicción. SharedLibraryJourneyTest: localCoverCanBeLoadedAfterRemoteOnlyBookWithoutRestart, sharedThumbnailIsReusableWithoutOpeningOriginalAndRejectsCorruption, browseCatalogueDoesNotOpenOriginalStreams. CI y mediciones físicas pendientes. MOBI sin extractor; generación progresiva de carátulas históricas.

## #131 — selección múltiple de libros
B12 descarga, B13 guardado, B14 retirada, B16 cancelación/fallos parciales: LibrarySelectionTest (identidad/filtrado, cancelación y errores), LibraryHomeUxDeviceTest (sombreado y reciclaje sin casillas en ambas vistas), controles de SharedLibraryJourneyTest existentes. Pruebas físicas A/B y lotes reales pendientes. B15 eliminación global recuperable pendiente: no activar uso masivo de BookDeletion.apply, que borra conocimiento. No cerrar #131 ni declarar validado hasta acreditar pruebas.

- CI inicial HEAD 2f75d05: JVM/compilación y anotaciones verdes. Startup [45829915706](https://github.com/JavierRB35/Android_EReader/actions/runs/45829915706) 39/40, lector/OCR [45829915632](https://github.com/JavierRB35/Android_EReader/actions/runs/45829915632) 93/94. Único fallo común: fixture de LibraryHomeUxDeviceTest sin LayoutManager al inflar item_book. Corregida preparación con GridLayoutManager/LinearLayoutManager y reinicio de selección entre modos; se mantienen todas las aserciones. Nueva CI pendiente; no confundir fallo de fixture con prueba funcional superada.
