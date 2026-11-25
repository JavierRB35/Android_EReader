# Registro vivo de regresiones — BibliotecaAI

No confundir una corrección escrita con una verificación realizada. Antes de fusionar cambios relacionados, comprobar los casos de esta tabla y los documentos enlazados. Las suites JVM y startup smoke se ejecutan sobre cada PR/main y son requisitos de la publicación automática. No retirar las pruebas existentes ni falsear resultados.

| Incidencia | Causa/contrato | Prueba permanente | Estado de verificación |
|---|---|---|---|
| [#95](https://github.com/JavierRB35/Android_EReader/issues/95) — Drive ausente en tablet | Selección de árbol no equivale a selección de documento; ofrecer ruta de archivo directa, cancelar vuelve a elección; nunca saltar la elección explícita por conexión previa | SharedSettingsJourneyDeviceTest: cloudFirstUsesCreateDocumentAndCancelReturnsToChoice, folderIsSeparateAndCancelReturnsToChoice | Cambio propuesto, CI y tablet física pendientes; versión afectada exacta por confirmar |
| Original ausente / ZIP recuperable | No sustituir copia buena si faltan originales; conservar recuperación | CompleteBackupIntegrityInstrumentedTest, CompleteBackupRestoreInstrumentedTest, SingleFileBackupInstrumentedTest | Ver evidencia de cada release; Drive físico pendiente |
| Biblioteca vaciada / libros borrados resurgen | Distinguir retirada local, baja definitiva y nuevo catálogo; preservar originales y lápidas | StorageFolderRebuildTest, BookDeletionJourneyTest, SharedFileStoreJourneyTest | Reinicio completo desde cero todavía pendiente; no equivale a traslado |
| Notas al tocar marcas | Rango exacto y modal guardado; no interferir con selección | SavedAnnotationModalDeviceTest, ScripturaMarkTapDeviceTest | Ver CI de cada cambio y Samsung físico |
| [#94](https://github.com/JavierRB35/Android_EReader/pull/94) — selección multipantalla | Arrastre al borde, conserva selección y no cambia capítulo | SelectionEdgeScrollDeviceTest, SelectionEdgeSpeedTest (en PR #94, todavía no integradas) | CI pendiente; Readium y límites de bloque por verificar |

## Cierre de incidencias

Registrar PR, SHA exacto, links de Actions, versión publicada y resultado de prueba física si aplica. Reabrir si el síntoma reaparece; reproducir primero y reforzar el test. No declarar verificado Drive real por un emulador con proveedor simulado.

## Pendiente crítico: empezar de cero

El cambio de ubicación conserva los datos locales. Un reinicio completo requiere una operación independiente, confirmada, que detenga trabajos de sincronización y backup, desvincule todos los URI/peers e historial de conexión, vacíe catálogo y datos derivados de libros sin tocar claves IA ni archivos remotos, y cree una nueva identidad/ubicación sin restauración implícita. Debe ser recuperable ante interrupción, con pruebas de dos dispositivos y respaldo previo. No implementar como borrado parcial de preferencias ni como cambio de carpeta; no hay reset destructivo en esta PR.
