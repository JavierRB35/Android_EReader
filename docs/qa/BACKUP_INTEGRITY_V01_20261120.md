# QA de integridad y recuperación — BibliotecaAI v0.1 (20-11-2026)

**Estado: NO ACEPTADA hasta resolver #36 y completar pruebas físicas.** Inspección estática de `main` @ `ca3083a9b9c73d33dd254f093963da44895860b8`. PR #23 fusionada con merge `f87cc523adc23fbe6ed778f3c666fb6798eff465`. Coordinación #24. Referencias #3 y #10.

## Evidencia y límites
- Implementación: `CompleteBackupArchive` serializa `biblioteca.json` más `books/<id>.bin`, verifica bytes/tamaño/SHA-256, rechaza entradas inesperadas y duplicadas, limita bytes por original (512 MiB), JSON (16 MiB), originales (4 GiB total) y número de libros (10 000).
- `BackupCodec.toJson` exporta libros, categorías, etiquetas, colecciones, relaciones, anotaciones (incl. notas/subrayados), versiones y posiciones. El ZIP **no está cifrado**. No se detecta serialización explícita de claves BYOK, contraseñas ni tokens en el esquema del codec; falta inspección del ZIP generado en dispositivo.
- `CompleteBackupManager.restoreFrom` prepara originales en almacenamiento privado antes de llamar a `BackupManager.restoreJson` (transacción Room); ante error elimina solo archivos finales registrados como creados. El componente usa archivos temporales, verifica hash y no sustituye archivos diferentes. No equivale a una transacción entre el sistema de archivos y Room ni certifica tolerancia ante terminación abrupta.
- Pruebas existentes: `CompleteBackupArchiveTest` comprueba ZIP, SHA, originales ausentes/corruptos y estructura; `BackupRestoreInstrumentedTest` prueba conflictos, remapeos, transacción Room e idempotencia; `CompleteBackupRestoreInstrumentedTest` prueba combinación ZIP/Room, pero NO la entrada SAF real ni `CompleteBackupManager.restoreFrom`.
- Evidencia histórica declarada en PR #23: quality gate [44803507753](https://github.com/JavierRB35/Android_EReader/actions/runs/44803507753), emulador [44803508024](https://github.com/JavierRB35/Android_EReader/actions/runs/44803508024), startup [44803507780](https://github.com/JavierRB35/Android_EReader/actions/runs/44803507780), todos SUCCESS según PR. Corresponden a SHA funcional `5334ca51`, **no** al SHA auditado. No atribuirles aprobación de v0.1 final.
- **SHA probado en esta auditoría:** ninguno ejecutado por el agente (revisión de código solamente). **SHA inspeccionado:** `ca3083a9b9c73d33dd254f093963da44895860b8`. Los checks lanzados después de este SHA no sustituyen QA física.

## Matriz de regresión (PASS solo con ejecución y evidencia)
| Caso | Estado a 20/11 | Evidencia/acción pendiente |
| --- | --- | --- |
| Export ZIP completo con EPUB/PDF/MOBI y relaciones | Parcial (código + pruebas unitarias EPUB/PDF) | Añadir MOBI y SAF end-to-end |
| SHA-256, tamaño, original ausente, ZIP manipulado | Parcial (JUnit existente, ejecuciones históricas) | Ejecutar sobre SHA final y validar ZIP exportado |
| Restaurar biblioteca vacía y abrir 3 formatos sin ruta antigua | BLOQUEADO | Dispositivo/emulador: `restoreFrom` real + abrir tres lectores |
| Restauración aditiva y dos importaciones idénticas | Parcial (Room instrumentada en SHA anterior) | Repetir flujo completo con notas preexistentes y categorías/colecciones |
| Notas, subrayados, marcadores, versiones y posiciones por libro | Parcial (persistencia probada por componente) | Comparar recuentos y contenido antes/después; comprobar marcas visibles |
| SAF almacenamiento local y Google Drive como proveedor | BLOQUEADO | Samsung con Drive instalado; sin OAuth ni API Drive |
| Cancelación, corrupción, denegación, interrupción, espacio bajo | Parcial (rechazo ZIP por componente) | Casos de escritura/lectura SAF real e interrupciones; verificar copia previa |
| Backup único reemplazable sin pérdida del anterior | RIESGO P0 | #36: nombre timestamp + borrado en excepción del URI |
| ZIP sin secretos y aviso de no cifrado | Parcial (revisión del codec/UI) | Buscar secretos en ZIP real y comprobar aviso visible en móvil |
| Actualizar APK conservando Room, originales y marcas | BLOQUEADO | Instalar encima con mismo applicationId, certificado y versionCode superior |

## Procedimiento exacto de aceptación manual (no usar biblioteca personal como experimento)
1. En un Samsung **de prueba**, con Drive instalado, crear biblioteca con 1 EPUB, 1 PDF y 1 MOBI lícitos; registrar SHA-256/tamaño de cada original, dos posiciones distintas, etiquetas, categoría, colección, nota, subrayado, marcador y versión. Capturar recuentos, nunca secretos.
2. Ajustes → Exportar copia completa. Elegir **Archivos / Almacenamiento interno** mediante selector SAF. Comprobar ZIP, lista de entradas y hash/tamaño de cada original respecto al origen. Repetir con **Google Drive** desde el selector del sistema (sin autenticar BibliotecaAI). Confirmar que se puede recuperar el ZIP exportado. Verificar mensaje «ZIP no cifrado».
3. En perfil/dispositivo de prueba vacío, importar ZIP con Ajustes → Restaurar; desconectar red y abrir los tres libros. Verificar relación de notas, marcadores, subrayados, versión y posición tras cerrar y reabrir.
4. En otra biblioteca **de prueba** con libro diferente y notas propias, importar el mismo ZIP dos veces; comprobar que no desaparecen los datos previos, no se duplica ningún elemento y la unión contiene todo. Repetir usando un libro ya existente con idéntico SHA pero diferente ID.
5. Sobre copias **desechables** del ZIP: truncar, modificar un byte del original, eliminar un `books/*.bin`, introducir una entrada extra, cancelar selector, denegar acceso, cortar conectividad al proveedor, simular espacio lleno y forzar interrupción. Tras cada fallo, volver a abrir la base, comprobar recuentos, archivos y que la última copia válida permanece intacta.
6. Probar instalación de APK nueva **sin desinstalar**, misma firma y `versionCode` superior; abrir libros y anotaciones preexistentes. Guardar modelo Android, API, versión anterior/nueva, SHA APK, logs depurados y resultado PASS/FAIL.

## Bloqueantes
- #36: sustitución segura del único ZIP y pruebas end-to-end SAF/Drive/errores.
- Sin acceso desde este agente al Samsung, proveedor Drive real ni datos privados. Ningún caso físico figura como superado.
- CI sobre SHA final y regresión completa tras integración de otros agentes.
