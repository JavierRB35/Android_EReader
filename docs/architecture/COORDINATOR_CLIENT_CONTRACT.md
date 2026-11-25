# Contrato Android ↔ coordinador privado — #103

Incremento aislado sobre las funciones SQL de #99 (001/002). **Propuesta de contrato de gateway, no API desplegada**. El código Kotlin no llama a la red ni modifica el estado real; no toca el asistente, Room, archivos o sincronización actual. El agente del gateway puede adaptar este contrato mediante acuerdo en #103.

## Solicitudes y respuestas

JSON UTF-8, HTTPS exclusivamente, identidad estable libraryId/deviceId en cuerpo y token aleatorio de 256 bits como Authorization: Bearer (nunca en URL, logs, backups ni cuerpo). El cliente crea identidad una vez, la persiste cifrada ANTES de enviar create/join, y reintenta con exactamente la misma identidad y operación. Crear y vincular son mutuamente excluyentes. Los códigos de vinculación de un solo uso se transfieren de forma segura, caducan según servidor; no se registran ni exponen. La capa de transporte futura debe impedir redirecciones, limitar respuestas/tiempos, clasificar errores sin divulgar cuerpos, autenticar y aplicar límites de tasa. No usar token service-role ni publicar SQL privado.

| Operación | Campos adicionales a libraryId/deviceId | Resultado SQL |
|---|---|---|
| create | ninguno | libraryId, deviceId |
| issuePairing | ninguno | libraryId, code, expiresAt |
| join | code | libraryId, deviceId, replayed |
| apply | operationId, kind, entityId, expectedRevision, action, payload | status applied/conflict, revision, replayed/deleted |
| pull | after, limit (1..100) | libraryId, head, cursor, items (kind, entity_id, revision, deleted, payload) |
| revoke | targetDeviceId | respuesta de éxito sin contenido, autorización de revocación por definir con backend |

El gateway traduce a los parámetros SQL privados; no se compromete ruta HTTP ni proveedor hasta acuerdo. Al responder create/join, verificar misma identidad; el codec rechaza respuestas cruzadas, tipos incorrectos, hashes inválidos, páginas desordenadas, saltos de cursor incompatibles con el contrato SQL, revisiones no crecientes, y conserva lápidas. Un conflicto no confirma la cola ni autoriza sobrescribir. Persistir toda página+cursor en una transacción Room; congelar payload y operationId antes de enviar. Los límites de payload coinciden con SQL para JSON compacto (servidor aplica su límite canónico, que puede ser más restrictivo); respuesta máxima 32 MiB, transporte debe limitar antes de leer en memoria. Los errores del codec no muestran contenido privado.

## Validación y pendientes

Tests JVM sintéticos de identidad compartida, formato de credencial, pertenencia, reintentos, mutabilidad, conflicto, paginación, lápidas, cursores, límites y datos malformados. No se ejecuta ningún backend real. CI pendiente. Pendientes de integración: endpoint autenticado/rate limited, almacenamiento cifrado no exportable con recuperación explícita (no generar otra identidad si se pierde la clave), cola y mapeo Room, resolución de conflictos, originales, escritor único, copia, migración y pruebas reales A/B. En particular, una identidad de libro por hash NO acredita que sus bytes existan. No publicar este incremento como biblioteca única operativa.

## Transporte e integridad — incremento siguiente
Cliente HTTPS explícitamente configurado (rutas por operación, no endpoint inventado), sin redirects, token Bearer, timeout, límites de bytes, UTF-8 estricto, errores tipados sin cuerpo ni causa privada, sin retry automático. Ejecutar I/O fuera de UI. Para los conflictos de entidad el gateway debe entregar JSON de resultado en HTTP 2xx; HTTP 409 es fallo de transporte/protocolo que conserva cola. Revoke espera HTTP 204 sin cuerpo. Se añade verificación read-only de bytes originales mediante SHA-256 y tamaño con lectura streaming y pruebas de archivos sintéticos. No prueba permisos o atomicidad de Drive. Comprobación remota de solo lectura 25/11: núcleo bia_coord existente; tabla pairings y pull_authenticated no desplegados, cero Edge Functions. Falta endpoint real y credenciales/cola Room y flujo de almacenamiento; no activar cliente. Los tests de transporte usan doble controlado y no afirman una conexión de red real.
