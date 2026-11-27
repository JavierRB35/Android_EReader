# Incidencia identidad de contenedor
Capturas del propietario: dos ZIP de 16,61 MB con nombres de modelos diferentes, 0 vinculados y error de propietario del archivo activo del móvil. No se conocen los IDs internos ni los bytes del ZIP real. La igualdad de tamaño no demuestra igualdad de contenido.
- Se identifica una carrera real en la inicialización de deviceId entre UI y WorkManager; se serializa.
- Se muestran los prefijos de identidad esperada y real al detectar un archivo ajeno. No se adopta ni se cambia su propietario.
- Ante el error histórico o nuevo, la acción principal es Reparar conexión. Crea un archivo con sufijo único, rechaza reutilizar un URI conocido o un documento con contenido. Conserva los archivos anteriores y las conexiones; admite el archivo anterior como fuente de solo lectura si se valida y su propietario es distinto.
- Los pares rechazados se conservan en el historial de la conexión anterior. No se garantiza recuperación si el proveedor no los permite leer. Vincular el archivo nuevo en el segundo dispositivo.
- Prueba instrumentada de propietario incorrecto, creación sobre archivo ocupado rechazada, reparación en archivo nuevo, lectura de original de antiguo sin tocar sus bytes.
- No se ha comprobado todavía el caso físico de Drive; el error puede deberse también a un documento equivocado o a bytes devueltos por el proveedor.
