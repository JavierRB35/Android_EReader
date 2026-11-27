# Consulta IA: argumento del libro y etiquetas automáticas

**Fecha:** 21/11/2026 · Implementación: `feature/blank-book-ai-synopsis-autotags-20261121`

## Contrato de producto

- Desde la ficha de un libro → «Consultar IA» (sin cita seleccionada), **dejar la pregunta vacía y pulsar Consultar** prepara un argumento/sinopsis del libro. Con texto escrito se conserva el comportamiento de consulta convencional. «Interpretar con IA» sobre un pasaje seleccionado no se modifica.
- La sinopsis se fundamenta en fragmentos de la **edición importada**; no inventa un argumento completo si el lector solo dispone de una muestra. La opción **Evitar spoilers** se respeta: sin progreso, solo se entrega un breve fragmento inicial y se solicitará una sinopsis introductoria. Para un argumento con información de toda la obra, el usuario debe desactivar «Evitar spoilers».
- La selección de fragmentos de una sinopsis abarca principio, desarrollo y final con spoilers permitidos (máximo cinco fragmentos acotados, nunca se transmite el archivo completo). PDF solo utiliza páginas con OCR almacenado. Si no hay contenido extraíble, **no se finge** una sinopsis ni se guardan etiquetas.
- Tras una respuesta válida, se guarda automáticamente una sinopsis **por libro** en Room `book_synopses` (migración no destructiva v4→v5), accesible sin conexión y sin volver a llamar al proveedor.
- En la **ficha del libro, inmediatamente debajo del título y autor**, aparece una fila de 44dp: «Añadir argumento del libro» si no existe (editor manual) o «Ver argumento del libro» si ya existe (modal seleccionable y desplazable con «Editar argumento»).
- Desde ese diálogo se puede **Editar**, **Copiar** o **Guardar argumento en…** (texto UTF-8, extensión `.txt`) mediante Android `CreateDocument`. El usuario elige almacenamiento interno, Drive o cualquier proveedor SAF compatible, sin OAuth y sin transferencias de archivos decididas por la app.
- Si el libro **no tiene etiquetas asociadas** en el momento de guardar la sinopsis, la IA propone entre 3 y 5 y solo se aceptan nombres saneados y seguros. Se reutilizan etiquetas globales existentes por nombre, ignorando mayúsculas/minúsculas. Si el libro ya tiene alguna etiqueta (incluso si se añadió durante la consulta), se respetan íntegramente y no se añaden ni borran etiquetas.
- La nueva versión del manifiesto de copia de seguridad (v2) incluye `bookSynopses`. Restauración aditiva: conserva sinopsis locales y asigna las del ZIP solo cuando faltan. **Las copias v1 anteriores siguen restaurándose**.

## Pruebas y aceptación

1. `BookOverviewTest`: prompt de sinopsis, filtro de spoilers y parseo del pie de etiquetas sin mostrarlo en la respuesta.
2. `BookTextSourceTest`: muestreo representativo, sin spoilers en progreso cero, cobertura del argumento.
3. `BookSynopsisDeviceTest`: guardado Room, etiquetas solo en libros vacíos, reconsulta sin sobreescritura, JSON compatible con copia v1, acceso real en ficha del libro.
4. `BookDetailOrganizationDeviceTest`: evitar que el acceso a argumentos rompa la edición y disposición de categorías, etiquetas y colecciones.
5. `BookSynopsisActionDeviceTest`: desde libro nuevo, introducir argumento manual, comprobar persistencia y cambio del botón, visualizar, editar y volver a visualizar sin claves de IA.
6. QA de integración: generar sinopsis del libro real con una clave válida, salir y volver a entrar, exportar TXT a SAF, comprobar contenido y reabrir tras restaurar ZIP.

**La ampliación añadir/ver/editar está aislada en la rama WIP `wip/book-synopsis-add-view-edit-20261121`. No se declara disponible en APK ni probado físicamente hasta que el CI y el flujo de publicación se completen**. No se modifica la IA por selección ni se incorpora búsqueda externa falsa.


## Rectificación de producto por captura Samsung del 21/11/2026

- Ficha: botón **después de Colecciones**, antes de los botones fijos inferiores. Se mantiene una única acción contextual sin duplicación superior.
- Sin argumento: **Añadir argumento** inicia una consulta IA automáticamente, sin una pregunta ni pulsación adicionales. Para un argumento del libro completo se usan los fragmentos de distintas partes (desactivando filtro de spoilers expresamente en ese lanzamiento); se exponen los límites cuando el texto no está disponible.
- Con argumento: **Leer argumento** abre el modal de lectura, desde el que se puede **Editar** y **Borrar** (con confirmación). Se conservan copiar/exportar. Borrar afecta solo al argumento; no borra el libro, notas, etiquetas ni colecciones.
- Respaldo Gemini → Groq: ya implementado mediante `AiFallbackRouter`; cuota, errores temporales o timeout disparan un único respaldo cuando Groq está configurado y autorizado, igual que `Scriptura/AiRouter.java`. No se envía automáticamente a un tercero sin consentimiento ni se reintenta un rechazo de permisos.
- Criterios nuevos: prueba instrumentada para comprobar posición del botón, lanzamiento del modo IA, edición, cancelación del borrado y borrado confirmado; regresión de ficha sin scroll, guardado de argumentos y respaldo.
