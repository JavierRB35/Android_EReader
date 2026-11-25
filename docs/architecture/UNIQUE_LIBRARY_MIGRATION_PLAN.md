# Migración no destructiva — planificación de originales (#103)

Componente de planificación puro y reutilizable, sin activar migración ni escribir datos. A partir de inventarios completos de todos los dispositivos que van a migrar y un snapshot central consistente (libraryId/revisión), agrupa por SHA-256, retiene todos los identificadores de origen para mapear anotaciones, versiones, posiciones y relaciones, y conserva las lápidas. No usa títulos, nombres ni fechas del dispositivo como identidad ni prioridad. No acredita bytes a partir del catálogo.

- Original central verificado: reutilizar, no copiar.
- Nuevo hash con original local verificado: seleccionar un único candidato determinista; conservar todos los originales locales hasta confirmación remota y backup.
- Original central sin verificar/ausente/corrupto: inspeccionar o reparar mediante reserva transaccional, nunca crear otro archivo ciegamente.
- Baja más copia viva: conflicto explícito; no resucitar ni eliminar automáticamente.
- Tamaño incompatible, original ilegible o inventario ambiguo: bloquear la decisión y conservar evidencia.

El plan no es autorización para ejecutar migración. El ejecutor futuro debe verificar credenciales y misma libraryId, inmovilizar o reconciliar revisión CAS, verificar bytes mediante OriginalIntegrity de #106 y reserva central, capturar y remapear todas las entidades (incluidas notas sin libro, versiones, lápidas y relaciones), conservar ambas versiones ante conflicto de edición, verificar copia única y efectuar corte no destructivo. RestoreIdentityPlanner ya resuelve parte del mapeo legacy; no reemplazarlo sin pruebas. No marcar éxito hasta completar las fases de #103. Tests sintéticos de deduplicación, conflictos, integridad y determinismo; CI pendiente. Sin acceso real a Drive.
