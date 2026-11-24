# Copia de seguridad: originales no disponibles y ZIP incorrecto
- El error de manifiesto indica que se seleccionó un ZIP que no cumple el formato de respaldo. Si comienza con bia-owner.txt es un contenedor de sincronización. No se sobrescribe.
- Un error de lectura del proveedor se comunica con instrucciones de reparar acceso o crear respaldo nuevo; se preserva el anterior. No se puede identificar la causa exacta de StorageFileLoadException sin reproducir Drive.
- Un original ausente de dispositivo, respaldo anterior y biblioteca compartida comprobada ya no bloquea una copia: se declara en manifest.missingOriginals, se guardan todos los metadatos y se distinguen originales disponibles. No se acepta un ZIP truncado o con entradas faltantes no declaradas.
- La restauración conserva notas y metadatos aunque falte un original declarado. La descarga selectiva y el índice no anuncian originales ausentes. La migración no intenta extraerlos.
- Una biblioteca inaccesible no se interpreta como vacía. Se conserva el respaldo previo y se informa del error.
- Pruebas unitarias del formato, validación estricta previa y regresión de originales ausentes. Pendiente comprobación física con el proveedor de la incidencia.
