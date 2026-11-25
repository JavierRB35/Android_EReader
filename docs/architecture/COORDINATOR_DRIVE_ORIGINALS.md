# Drive dedicado — originales únicos (Agente 3 / #109)

## Autorización pendiente
Para Mi unidad de una cuenta personal no sirve una cuenta de servicio que cree archivos: no tiene cuota propia. Elegir OAuth de servidor con permiso mínimo `https://www.googleapis.com/auth/drive.file`, acceso offline, carpeta existente BibliotecaAI seleccionada explícitamente por el propietario (orden #103; no crear otra). No login Google en Android. No inferir acceso a los hijos de una carpeta preexistente; cualquier migración de archivos existentes requiere consentimiento individual/legítimo o importación explícita. No usar el token del conector ChatGPT como credencial de la aplicación.

Acción mínima del propietario cuando exista el flujo administrativo autenticado: abrir la autorización web de BibliotecaAI, elegir su cuenta Drive y consentir el acceso por archivo; el propietario selecciona la carpeta existente con Google Picker; el servidor verifica acceso y capacidad de añadir hijos y, posteriormente, un original sintético. No pegar tokens en chat ni GitHub. Precondiciones técnicas: proyecto Google Cloud con Drive API habilitada, cliente OAuth web con callback HTTPS exacto, state/PKCE, acceso offline y almacenamiento de secretos exclusivo de backend. Si el proyecto externo está en Testing, el refresh token puede expirar a los siete días; no considerarlo producción. No hay credencial ni callback operativo acreditados todavía. El agente 1 debe coordinar el punto administrativo seguro y el gestor de secretos, sin publicar una ruta administrativa abierta. No solicitar acceso completo a Drive.

## Diseño de originales
Google Drive v3 permite preasignar IDs de archivos binarios mediante files.generateIds. Repetir create con mismo ID devuelve 409 cuando el original ya existe, sin crear otro archivo. Una reserva transaccional debe persistir antes de subir: UNIQUE(library_id, sha256), UNIQUE(file_id), tamaño y carpeta autorizada inmutables. Agente 1: implementar el puerto registry.reserve/markVerified en la capa privada, con autenticación y transacciones; resolver el libraryId/folderId desde datos autorizados, nunca confiar en IDs suministrados por Android. Un ID generado pero no seleccionado no se usa; tras caída nunca generar una nueva reserva para el mismo hash. 409 no significa éxito: verificar bytes reales SHA-256, tamaño, carpeta, nombre, versión antes/después. La confirmación central también requiere CAS sobre reserva. Los originales definitivos son inmutables, llamados <sha256>.bin; la extensión real se mantiene en metadatos/ZIP. La aplicación solo usa el ID reservado, no búsqueda por nombre ni actualización de un original corrupto. Ante permisos revocados o conflicto, mantener reserva y bloquear publicación/evicción; no borrar ni sustituir automáticamente.

`originals.mjs` implementa servicio puro y adaptador HTTPS Drive v3 create-only con token inyectado y sin listar, editar permisos, actualizar ni borrar. Fuente privada e inmutable, límite 512 MiB, verificación streaming, sin extracción ni escritura de datos reales. El adaptador rechaza redirecciones, comprueba URL de sesión, no registra tokens ni URL. El timeout de PUT queda como estado incierto: reintentar posteriormente con la misma reserva, verificar contenido. No es todavía prueba de Drive real, persistencia ni integración con gateway. La prueba concurrente usa un doble que modela el contrato documentado, no PostgreSQL ni Drive físico.

## ZIP mutable
La creación idempotente por ID resuelve originales, no CAS de reemplazo de un ZIP. No se ha encontrado garantía explícita en files.update para escrituras concurrentes de media con If-Match. No activar reemplazo antes de prueba real de dos escritores, timeout y recuperación. Mantener último válido y evitar expiración de lock con transferencia en vuelo. Revisiones fijadas pueden proteger recuperación, pero no equivalen a exclusión.

Fuentes oficiales:
- https://developers.google.com/workspace/drive/api/guides/create-file
- https://developers.google.com/workspace/drive/api/guides/manage-uploads
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- https://developers.google.com/workspace/drive/api/guides/about-shareddrives
- https://developers.google.com/identity/protocols/oauth2/web-server
- https://developers.google.com/identity/protocols/oauth2
- https://developers.google.com/workspace/drive/api/reference/rest/v3/files/update

## Primitivas OAuth de servidor (incremento no desplegado)

`backup/drive_oauth.mjs` limita la autorización a drive.file, solicita acceso offline, genera state aleatorio y PKCE S256, consume el estado de un solo uso por un puerto de sesión administrativa autenticada, intercambia código en el endpoint HTTPS fijo de Google y verifica scope y refresh token. El proveedor de access token usa cache y renovación single-flight, sin fallback ni scope ampliado. `verifySelectedDriveFolder` solo consulta metadatos de la carpeta seleccionada y exige canAddChildren. No acredita escritura real ni acceso a hijos históricos.

Faltan ruta administrativa autenticada, persistencia cifrada y consumo atómico del estado ligado a la sesión, consentimiento real del propietario, configuración Google Cloud (Drive API, Picker, cliente OAuth web y callback exacto), gestor de secretos de servidor y vinculación administrativa del folderId en SQL. No exponer refresh token, client secret o verifier a Android, a Picker ni a logs. Picker usa solo access token de corta duración en la sesión administrativa. No publicar ruta abierta ni solicitar credenciales por chat. Las pruebas unitarias son sintéticas y no sustituyen prueba física. Si se deniega consentimiento o el alcance no permite seleccionar la carpeta existente, detener provisión sin crear ni modificar otra carpeta.
