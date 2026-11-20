# Copia completa de BibliotecaAI — paridad funcional con Scriptura para Android

Referencia verificada: `JavierRB35/Scriptura`, `ScripturaBackup.java` y
`MainActivity.java` (rama `main`, 20/11/2026).

## Contrato funcional y UX

1. En Ajustes aparecen **tres acciones directas**, sin confundir «actualizar»
   con «crear»: **Crear copia ahora**, **Elegir ubicación del archivo**,
   **Restaurar desde un archivo**.
2. Si no hay destino, «Crear copia ahora» abre las mismas dos opciones SAF
   de Scriptura: crear archivo en una ubicación y seleccionar un archivo ya
   existente. Elegir destino dispara inmediatamente una copia completa.
3. Se conserva el URI con permisos persistentes. Copias manuales y automáticas
   **sustituyen el contenido del mismo ZIP**, sin generar archivos con fechas.
4. Al abrir la biblioteca, si existe destino, se calcula una huella estable
   del estado de libros, notas, historial, posiciones y originales. Se realiza
   una nueva copia **solo si el estado cambió** respecto a la última copia
   verificada; tras importar un original se solicita otro chequeo.
5. Ajustes muestra **última copia verificada**, ausencia de destino, error de
   escritura y posibilidad de recuperar el ZIP anterior si falla el proveedor.
6. Se reutiliza `CompleteBackupManager` para generar originales
   EPUB/PDF/MOBI, metadatos, verificar SHA-256 de escritura y recuperar copia
   anterior. El ZIP no incluye secretos BYOK y no está cifrado; se informa al
   usuario. No hay Gmail, OAuth ni una integración directa con Drive.
7. Restaurar y exportar comparten exclusión mutua dentro de
   `BackupRoutine` para evitar que una copia automática coincida con una
   restauración iniciada en Ajustes.

## Diferencias deliberadas respecto de Scriptura

Scriptura guarda también ajustes y DB SQLite propias. BibliotecaAI guarda
los **originales importados y todas las entidades Room del modelo portátil**;
el fichero de destino se llama `BibliotecaIA-completa.zip`, nunca
`Scriptura.backup`. Una actualización de Scriptura no constituye una copia
de seguridad compatible con BibliotecaAI.

## QA necesario

- Verificar en Samsung «Crear copia ahora» sin destino, elegir memoria local
  y Drive; tras cerrar y abrir debe existir **un único ZIP**, con fecha de
  última copia visible; cuando los datos no han cambiado no se reescribe.
- Importar un libro, anotar y reabrir la biblioteca: verificar que se actualiza
  el mismo ZIP y que se pueden recuperar notas, versiones y originales.
- Probar permiso SAF revocado, pérdida de red, falta de espacio, ZIP dañado y
  restauración en otro teléfono sin sobrescribir datos previos.
- La automatización en arranque es **best effort**: Android puede detener el
  proceso en segundo plano, y Google Drive no garantiza una escritura atómica
  de un DocumentsProvider remoto. No declarar aceptación física sin ensayos.
