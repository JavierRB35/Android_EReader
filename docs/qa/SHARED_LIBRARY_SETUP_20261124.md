# Configuración de biblioteca compartida
Incidencia #72, PR #76. Prioridad UX: una acción principal contextual. Sin OAuth ni cuenta propia.
- Sin conexión: Configurar biblioteca > Google Drive u otra nube (selección de archivo), opción de reutilizar un archivo propio o carpeta avanzada.
- Archivo propio sin pares: Añadir otro dispositivo. Se explica la vinculación recíproca y que hay que elegir el archivo del otro equipo. En Drive usar el mismo directorio y nombres sugeridos distintos.
- Con pares: Sincronizar ahora, Añadir otro dispositivo, opciones. Estado real de permisos, fecha de comprobación, nombre de archivo cuando el proveedor lo facilita. Falta de permiso: Reparar conexión.
- Cambiar conexión y abrir anterior no borran datos ni archivos. No se migra automáticamente un original que solo existe en la conexión anterior; se conserva acceso por historial. Importación explícita del respaldo.
- La copia de seguridad tiene sección independiente; se rechaza escribir como respaldo sobre el archivo de biblioteca propio, vinculado o histórico conocido por URI. No se garantiza identidad de documentos con URIs de proveedores distintos.
- El sistema de JSON anterior se oculta bajo configuración avanzada, sin borrar ajustes.
- Las pruebas de interfaz comprueban acceso inicial al asistente y a copia de seguridad. La prueba física de Drive sigue pendiente.
