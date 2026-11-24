# Biblioteca central compartida — fase 1 (experimental)

Base: 9442160e69b0493436b38127e68d966e37b8a049. Incidencia #72. Sin OAuth ni servidor propio.

- Carpeta SAF con prueba efectiva de listar/crear/leer, permiso persistente. Si Drive no expone una carpeta, no activar ni simular éxito. El selector de archivo ZIP no equivale a permiso sobre una carpeta.
- Originales individuales inmutables, nombre hash + UUID. Catálogos JSON inmutables con digest y recibos de originales verificados; archivos parciales sin recibo no forman parte del catálogo. Dos dispositivos no sobrescriben el mismo documento. Descargar y retirar solo tras SHA-256 y tamaño. No se eliminan originales remotos.
- Migración explícita de ZIP, sin sobrescribirlo ni descargar todo al espacio de libros. ZIP sigue independiente; exportación recupera originales ausentes desde carpeta.
- Worker persistente y sincronización antes de lectura. Merge de libros por hash, posiciones por timestamp, anotaciones incluidas bajas por updatedAt, argumentos por generatedAt. Los cambios de organización siguen siendo aditivos; falta protocolo completo para sus ediciones/bajas, gestión de relojes desalineados, compactación del almacenamiento histórico y UX definitiva. Se lee solo el último estado válido por dispositivo; el historial se conserva.
- La carpeta no implica que Drive remoto haya propagado inmediatamente una escritura local. Prueba real obligatoria.
- No fusionar ni publicar como solución final hasta validar con dos instalaciones y proveedor real. No retirar originales de producción durante pruebas.

## Aceptación pendiente
A/B: importaciones simultáneas, búsqueda, descarga individual, notas/ediciones/bajas, posición hacia delante y atrás, proceso cerrado antes de publicar, desconexión, recuperación, ZIP previo intacto y backup nuevo completo. Verificar también proveedor sin permiso de carpeta y carpeta con datos parciales.
