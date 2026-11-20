# ADR-008 propuesta: BibliotecaIA V1 local, Drive mediante SAF y claves IA propias
Fecha: 2026-11-20. **Requisito del propietario** posterior al prompt maestro. Revisión/integración: coordinador de main.

## Decisión: experiencia sin cuentas

- No exigir registro ni login Google/Supabase para leer, importar, organizar, anotar, consultar IA con clave propia o hacer backups.
- El propietario confirma que **los originales EPUB, PDF y MOBI se guardan en Google Drive**, igual que en Nexo Android y Scriptura. Google Drive es el repositorio de originales elegido por el usuario, **no** la base Supabase ni un ZIP duplicado obligatorio. La app mantiene una copia local para lectura offline y enlaza con el original de Drive cuando el permiso y proveedor lo permitan.
- Las copias **de los datos de BibliotecaIA** —catálogo, etiquetas, colecciones, anotaciones y posiciones— se guardan/restauran con el selector de archivos Android (SAF), eligiendo el usuario Drive u otro destino. El flujo actual de `SettingsActivity` usa `ActivityResultContracts.CreateDocument` / `OpenDocument`; completar QA y explicaciones, no sustituir por API Drive.
- El permiso SAF autoriza documentos elegidos, **no acceso global automático** a Drive. Si el usuario importa un archivo almacenado en Drive, su URI podrá persistirse solo donde el proveedor y Android lo permitan; un URI de un teléfono NO se puede asumir válido tras reinstalar o cambiar de dispositivo. Restaurar la biblioteca debe permitir **volver a vincular los originales de Drive** por nombre/hash (SHA-256), con selección manual múltiple y un árbol de carpeta solo si el DocumentsProvider realmente admite ese árbol. Mostrar cuáles están disponibles y cuáles faltan, sin ocultar errores ni inventar sincronización.
- Si el usuario **ya tiene originales en Drive**, **no subir duplicados**. Si importa desde un archivo local y decide conservarlo en Drive, ofrecer exportación del original mediante selector del sistema y verificarla; no afirmar subida automática a carpeta sin prueba en Android real.
- El modo lectura offline requiere caché local de los libros importados; la caché no autoriza borrar originales ni conservar referencias rotas como archivos disponibles.
- Supabase existente `krxdcfyvksjhpohxxvcf` y sus 11 tablas quedan **conservados sin uso obligatorio** en V1. No borrar proyecto/migraciones ni desplegar sincronización automática; OAuth de Google/Supabase deja de ser bloqueo para V1. El motor SyncEngine requiere decisión separada sobre identidad y aislamiento antes de activarse.

## Copia de metadatos existente y restauración

`BackupManager.exportJson()` ya guarda registros de libros, categorías, etiquetas, colecciones, relaciones, anotaciones y posiciones, **no los binarios**; esta omisión es correcta **si los originales están íntegros en Drive**, pero hay que documentar que **una copia JSON sola NO recupera los libros**.

1. Guardar copia: usuario elige destino y nombre en selector (Drive si disponible). Guardar JSON, comprobar finalización y explicar qué protege; cancelar sin alterar datos.
2. Restaurar copia: seleccionar JSON, validar manifest/version/entradas, confirmar y restaurar aditivamente; presentar inventario de originales por resolver.
3. Recuperar originales: usuario selecciona en Drive uno o varios EPUB/PDF/MOBI; comparar SHA-256 del original con `BookEntity.contentHash`, vincular localmente sin duplicar fichas ni alterar anotaciones/locators; exigir consentimiento específico por archivo/carpeta cuando el proveedor lo requiera. Nunca confiar solo en nombre.
4. Caso de uso: móvil nuevo → importar JSON desde Drive → seleccionar libros originales en Drive → restaurar disponibilidad de lectura y notas sin perder datos.
5. Probar en un Android real con proveedor Google Drive: `CreateDocument` JSON, `OpenDocument` JSON, importación de EPUB/PDF/MOBI de Drive, reinicio, revocación, error de nube y reconexión. No afirmar acceso a carpetas si `ACTION_OPEN_DOCUMENT_TREE` no es soportado por ese proveedor.

## IA BYOK sin autenticación

En Ajustes → IA, crear asistente real: proveedor Gemini/Groq → enlace oficial para generar la propia clave → campo protegido → almacenamiento local cifrado por Android Keystore → prueba API real y errores diferenciados (red, clave, cuota, modelo) → eliminar/revocar. **Nunca** subir claves al repo, APK de distribución, copias de seguridad ni logs. Ser transparente sobre el riesgo residual de credenciales en el cliente y qué fragmentos del libro se envían al proveedor; no solicitar clave hasta el primer uso de IA. La funcionalidad del RAG y citas debe implementarse y probarse de forma independiente.

## Criterios de aceptación y alcance

- Offline sin login. Exportación/restauración JSON en Drive vía selector SAF con datos íntegros.
- Originales de Drive importados/relincados por hash y copiados a caché para leer offline; ausencia de originales claramente visible tras restauración, con recuperación guiada. Sin envío duplicado forzoso ni acceso total a Drive.
- Ninguna promesa de sincronización automática/multidispositivo: backups manuales pueden restaurar información, no resolver conflictos concurrentes.
- No cambiar applicationId, no borrar Supabase ni implementar OAuth solo para backup. Mantener la firma de APK y pruebas AndroidIDE ya previstas.
- El prompt maestro anterior exigía registro Google/Supabase/Drive API para la V1; **esta decisión posterior y explícita del propietario prevalece**. Integrar sus cambios editoriales en los documentos canónicos al cerrar PR; evitar instrucciones contradictorias.

Fuentes de referencia: [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files), [DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider).
