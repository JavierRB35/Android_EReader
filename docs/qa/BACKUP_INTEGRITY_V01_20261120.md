# QA de integridad y recuperación — BibliotecaAI v0.1 (20-11-2026)

**Estado: pendiente de aceptación física, no certificada para datos reales.** Coordinación [#24](https://github.com/JavierRB35/Android_EReader/issues/24). Referencias #3, #10, #23, #32, #34, #35 y #36.

## Cronología verificada y alcance
- Auditoría original: `main@ca3083a9b9c73d33dd254f093963da44895860b8` (archivo único todavía no integrado en esa instantánea).
- **Actualización:** [PR #34](https://github.com/JavierRB35/Android_EReader/pull/34) **fusionada** en `93937abe0c09d8d7c3975df604fbd5a4bc5df2b3`. El código actual crea `BibliotecaIA-completa.zip`, conserva un URI SAF persistente, verifica la copia escrita por SHA-256, intenta recuperar el ZIP anterior y conserva la copia anterior en almacenamiento privado si falla el proveedor. El problema de nombre con marca temporal indicado en el informe original **ya tiene corrección integrada**; no tratarlo como fallo pendiente sin reproducirlo.
- El exportador también **corrige en main** la lectura de rutas originales: usa `database.backupDao().booksAll()`, no las rutas eliminadas intencionadamente al deserializar JSON. Esto aborda el bloqueo reproducible [#35](https://github.com/JavierRB35/Android_EReader/issues/35).
- **[PR #32](https://github.com/JavierRB35/Android_EReader/pull/32) sigue abierta/conflictiva** en el momento de actualizar este documento. Aporta mejoras adicionales de revinculación de ficheros existentes corruptos, validación estricta del formato y regresión end-to-end. Sus resultados [quality](https://github.com/JavierRB35/Android_EReader/actions/runs/44809083806) y [emulador](https://github.com/JavierRB35/Android_EReader/actions/runs/44809083811) son históricos **sobre HEAD de su rama `8fc495d8541bdfeeb0e0bf357647ef25ff4ba1f2`**, no sobre main reciente.
- La API SAF de proveedores externos **no ofrece atomicidad universal** al sobrescribir; no se ha verificado la recuperación en Drive real. La corrección de #34 mitiga el riesgo, pero no lo elimina sin ensayos de dispositivo y proveedor.

## Evidencia de código y pruebas existentes
- `CompleteBackupArchive` genera `biblioteca.json` y los originales `books/<id>.bin` con SHA-256 y tamaño. Rechaza ZIP incompletos, entradas extrañas y duplicados; límites: 512 MiB por libro, 4 GiB de originales, 16 MiB JSON y 10.000 libros.
- `BackupCodec` incorpora libros, categorías, etiquetas, colecciones, relaciones, anotaciones (notas/subrayados), versiones y posiciones. El ZIP **no está cifrado** y no incorpora expresamente secretos BYOK, contraseñas ni tokens mediante el esquema de serialización. Verificar el archivo real generado y la advertencia de UI.
- `BackupManager.restoreJson` aplica una transacción Room e inserciones aditivas. `CompleteBackupManager.restoreFrom` coloca originales antes del commit y elimina ficheros nuevos si falla. La ejecución previa de tests de ZIP/Room no sustituye ensayos con un proveedor SAF real.
- Los checks de la PR #34 sobre `31dfa107` figuran verdes en la coordinación #24, pero **no prueban Samsung/Drive ni el SHA final de main**. La publicación automática exige éxito del quality gate y startup smoke sobre exactamente el mismo SHA de main. Solo publicar cuando la Release nueva conste con APK firmada y SHA-256, no por el mero éxito de un job con `skip`.

## Matriz de regresión y bloqueos
| Caso | Estado | Evidencia / bloqueo |
| --- | --- | --- |
| ZIP EPUB/PDF/MOBI + metadatos | Parcial | Estructura en código; prueba de los tres formatos en PR #32, aún sin integrar |
| SHA-256 y tamaños de cada original | Parcial | JVM histórica; verificar ZIP real en Samsung |
| Exportación SAF repetida al mismo archivo | Corrección integrada, **sin QA física** | PR #34; comprobar ausencia de `(1).zip` en Drive/local |
| Restaurar biblioteca vacía y reabrir tres libros | Pendiente | Samsung o Android de prueba; sin rutas anteriores |
| Restaurar aditivamente con datos existentes, notas y posiciones | Parcial | Room instrumentada; ensayo integral físico pendiente |
| ZIP truncado, hash alterado, ausencia de original | Parcial | JVM histórica; corrupción real del destino sin mutar datos |
| Cancelar, denegar permisos, cortar Internet, espacio bajo, interrumpir | Pendiente | SAF real y conservación del respaldo previo |
| Backup libre de secretos y aviso de ZIP sin cifrar | Parcial | Serialización revisada, comprobar ZIP/UI en dispositivo |
| Actualización APK conservando biblioteca | Pendiente | Instalar sobre versión existente con misma firma, versionCode superior |
| PR #32 reparación de originales corruptos/formato desconocido | **Pendiente de integrar** | No asumir presente en main |

## Pasos de prueba manual reproducible
1. Solo en un **dispositivo/perfil de prueba**, importar EPUB/PDF/MOBI lícitos y registrar tamaño/SHA-256, anotaciones, versiones, categorías, etiquetas, colecciones y dos posiciones diferentes. Nunca subir libros o claves privadas a incidencias públicas.
2. Ajustes → Exportar copia completa → escoger almacenamiento local; comprobar ZIP. Repetir exportación y verificar que se **sustituye el mismo documento**, sin segundo archivo. Hacer idéntico ensayo mediante Google Drive instalado como proveedor del selector SAF, sin OAuth de la app.
3. Restaurar en biblioteca de prueba vacía; desconectar red, abrir los tres libros, revisar SHA y anotaciones visibles. Después restaurar sobre biblioteca con datos previos e importar dos veces: sin borrados ni duplicados.
4. Sobre copias **desechables**, truncar ZIP, alterar bytes, eliminar original, cancelar, denegar acceso, cortar conectividad, simular poco espacio e interrumpir durante la escritura. Comparar inventario antes/después; comprobar recuperación desde copia local de emergencia.
5. Instalar APK actualizada **encima** de la existente, sin desinstalar y con el mismo certificado: inspeccionar libros, notas, posiciones, categorías y colecciones. Guardar logcat/versión/resultado.
6. Registrar cada resultado como PASS/FAIL/BLOQUEADO con identificador de prueba, SHA del commit, versión del APK, dispositivo/proveedor y enlace de evidencia. **No marcar como superadas pruebas no ejecutadas.**

## Decisión de QA
Sin certificación de backup/restore completo sobre Samsung/Drive. Es admisible publicar una **compilación de desarrollo identificada como no aceptada** si sus gates verificables pasan, pero no anunciar v0.1 estable o recomendar usarla como única custodia de datos privados hasta la aceptación física. No fusionar PR #32 sin resolver conflictos y repetir CI.
