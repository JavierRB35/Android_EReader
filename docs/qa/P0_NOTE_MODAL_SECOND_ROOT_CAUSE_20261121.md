# P0 — Regresión persistente del modal de notas en Samsung · auditoría de segunda capa

**Fecha:** 21/11/2026. **Evidencia:** la persona usuaria indica que v0.1.56 no abre
la anotación desde el texto, pese a los controles verdes de Android 14 y 16.
La PR #60 añadió eventos táctiles por ventana, pero solo **pruebas**, no modificó
el comportamiento. Afirmar «solucionado en Samsung» a partir de estas pruebas
sería incorrecto.

## Hallazgos verificables del código, no suposiciones

1. **Dos motores EPUB y anotaciones incompatibles.**
   `EpubReaderActivity.kt` tiene modo `native_selection=true` (`TextView`)
   y `native_selection=false` (Readium). Los rangos nativos son offsets UTF-16.
   `EpubAnnotationMarks.forReadium` **excluye por diseño** cualquier anotación
   que incluya `nativeText`, pues ese offset no es un CFI. Resultado: cuando
   se cambia a Maquetación, faltaba la marca y el toque de una NOTE creada
   en el lector nativo. Cambiar de modo no debe hacer invisibles las notas.
2. **Acción contextual dependiente de la selección durante la creación de menú.**
   `DefinitionUi.ensureViewSavedMark` ocultaba «Ver anotación» si
   `getSelectionStart/End` aún no coincidían con un marcado en
   `onCreateActionMode`. Samsung/Android puede no volver a solicitar una
   preparación del menú al modificar el rango. No se puede garantizar que
   la opción reaparezca; exponerla siempre en un ActionMode con el handler
   y verificar al pulsar elimina esta dependencia.
3. **Superficie táctil exacta frente a dedo real.**
   `MarkedTextTap` en v0.1.56 exigía que el carácter más cercano a DOWN
   o UP estuviera dentro del intervalo, mientras que un subrayado puede ser
   fino y quedar entre caracteres. Los tests anteriores inyectaban siempre
   un toque en el centro calculado de un glifo. Se añade tolerancia delimitada
   por `ViewConfiguration.scaledTouchSlop` sobre los rangos ya verificados,
   sin resolver notas por texto repetido ni abrir notas distantes.
4. **Sin vía de lectura en el listado de notas.**
   `EpubReaderActivity.showAnnotations` ofrecía «Ir al texto»,
   «Editar nota» y «Eliminar», pero carecía de «Leer anotación».
   Si el motor táctil no respondía, el usuario no podía leer su nota
   desde el lector sin pasar por editarla. Se añade lectura modal directa,
   sin modificar el registro Room.

## Correcciones de esta rama

- `DefinitionUi.java`: «Ver nota» siempre accesible durante selección
  (con validación rigurosa del intervalo al pulsar); mantiene Copiar,
  Compartir, Seleccionar todo y el resto de opciones.
- `MarkedTextTap.kt`: reconocimiento robusto de una marca guardada dentro
  de un radio físico pequeño y sin interceptar scroll/long press.
- EPUB Maquetación: nuevo puente independiente sin CFI inventada:
  para cada capítulo se considera únicamente NOTE/HIGHLIGHT con locator
  `nativeText.href` igual al recurso actual y texto guardado con **una
  sola aparición** en el DOM XHTML. Se visualiza mediante CSS Highlight
  cuando el WebView lo admite y se detecta un tap con los rectángulos del
  mismo rango DOM. Si la cita es ambigua, NO se asocia a otro fragmento.
- EPUB «Notas»: «Leer anotación» abre `SavedAnnotationSheet` con el
  cuerpo íntegro. «Ir al texto» pasa a selección nativa para localizar
  correctamente el offset cuando la nota es de origen nativo.
- No se cambia esquema Room, datos, libros ni backups.

## Evidencia y limitaciones

- CI debe cubrir JVM, instalación/emulador y regresión de anotaciones.
- El nuevo puente de citas DOM puede no conseguir asociar fragmentos que
  hayan cambiado de edición, estén repetidos o que difieran en
  normalización XHTML. En tales casos se mantiene «Notas > Leer anotación».
- Los emuladores Android 14/16 **no equivalen** a verificación táctil sobre
  One UI en el Samsung del usuario. Esta validación sigue pendiente.
- Para separar falta de detección vs. anclaje incompatible en una incidencia
  concreta se necesita inspeccionar el formato del libro, modo actual,
  si la nota aparece en «Notas» y la versión exacta instalada. Nunca borrar
  o reinstalar desde cero los datos del usuario para diagnosticar.
