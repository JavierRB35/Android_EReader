# BibliotecaAI — lectura premium, anotaciones y backup (21-11-2026)

## Alcance del cambio
- El EPUB conserva sus dos modos: **maquetación Readium** y **selección precisa TextView**. Cabecera editorial compacta (marca y título real del libro), controles de lectura desplazables, contraste OLED y barra inferior con marcador/progreso. Ninguna acción existente se ha eliminado.
- Lectura de anotaciones: los marcados nativos ya usan `MarkedTextTap` y `SavedAnnotationSheet`. En Readium siguen activas las decoraciones interactivas del SDK; además, un toque corto puede localizar sus cajas de anotación en el propio DOM de Readium (±9 CSS px), verificar que el ID está guardado y mostrar el panel de nota, sin adivinar por una cita repetida. La pulsación prolongada y el arrastre no activan esta búsqueda; la selección nativa sigue funcionando.
- Gestos: se prioriza el **desplazamiento vertical** mediante migración local del valor `scroll` al primer arranque con esta versión, sin borrar posiciones ni anotaciones. `Aa → Cambiar a páginas` sigue habilitando la paginación. En ese modo, el cambio mediante toque se restringe al 10 % lateral (antes 30 %, mínimo 28 dp) y permanecen las flechas explícitas. Readium no se bifurca ni se modifica el libro.
- Prueba Android instrumental de IDs y cálculo de bordes, conversión de coordenadas, límite de tolerancia y rechazo de IDs desconocidos. No afirma reproducción táctil Samsung si no se ha ejecutado allí.

## Seguridad y confirmación de backup
La funcionalidad **sí está implementada** en `main`, pero **no se puede acreditar que una copia de la biblioteca personal del propietario se haya hecho** sin ver la pantalla Ajustes/ZIP real. Comportamiento contrastado:

1. `SettingsActivity` permite configurar un URI mediante selector Android (almacenamiento local o proveedor SAF Drive) y `Crear copia ahora`. Muestra último backup verificado/error y recuperación pendiente.
2. `BackupRoutine.refreshOnLibraryOpen()` se llama al reabrir la Biblioteca y al importar un libro; con URI con permisos persistentes, `save(force=false)` compara fingerprint de libros, notas, subrayados, versiones, posiciones y relaciones. Solo actualiza el ZIP al detectar diferencias. **No debe presumirse copia continua en tiempo real durante la lectura**.
3. `CompleteBackupManager.exportTo` usa rutas internas Room, verifica originales (formato/ruta), escribe temporal, incluye todos los EPUB/PDF/MOBI originales, coteja SHA-256/tamaños, protege ZIP anterior antes de sustituir, escribe vía SAF en modo truncar `wt`, relee destino y compara SHA-256. Ante fallo conserva o permite recuperar copia previa. El ZIP no está cifrado y no incluye claves BYOK del usuario.
4. `CompleteBackupManager.restoreFrom` valida el ZIP, restaura originales en ruta privada y aplica transacción Room. La recuperación con Drive real y restauración total en Samsung requiere comprobación externa.

## Prueba de aceptación física en Samsung (NO aprobada hasta ejecución real)
1. Instalar APK firmada nueva **encima**, sin borrar datos. Abrir el libro de la captura. Comprobar cabecera, progreso y botones existentes.
2. Pulsar **una vez** el texto «general Sanjurjo» (nota) y confirmar que se abre la nota guardada, con opción Editar/Cerrar/Eliminar según tipo. Probar un subrayado, un tramo repetido y un toque normal sin marca. Mantener una pulsación larga para comprobar selección habitual.
3. Deslizar 10 veces en vertical, algunas con desviación horizontal leve: no debe cambiar página; en `Aa → Cambiar a páginas` comprobar que las flechas pasan páginas y que un toque en el centro no lo hace.
4. Ajustes → Copia de seguridad: comprobar **Última copia verificada** con fecha real posterior a cambios, ubicación autorizada, ausencia de error y archivo `BibliotecaIA-completa.zip`. Inspeccionar el ZIP, originales y manifiesto, y restaurar en una biblioteca **de prueba**, no borrando la actual. Validar SHA-256 de los originales y notas, posiciones y apertura.
5. Registrar PASS/FAIL/BLOQUEADO, versión/APK, modelo Android y evidencia sin divulgar libros ni notas privadas. No declarar backup personal certificado desde CI solamente.

Referencia: coordinación #24, QA de backup v0.1 y pruebas de anotación existentes.
