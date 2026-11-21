# P0 — Notas visibles sin modal al tocar el texto (reapertura 21-11-2026)

## Hecho observado y alcance

El usuario confirma que el problema **persiste después de v0.1.56**, pese a que las pruebas automatizadas de aquella PR pasaron. Por tanto, no se considera aceptada la corrección anterior. Esta revisión no promete validación en un Samsung físico sin una prueba real del usuario.

Comparación directa:
- **Scriptura Android** usa el texto y los rangos verificados persistidos para identificar una nota y abre un diálogo al tocarla. No requiere un `AnnotationHitSpan` transitorio.
- **BibliotecaAI ≤v0.1.38** tenía una resolución semejante para EPUB nativo y MOBI.
- **BibliotecaAI v0.1.40** introdujo dependencia de spans de toque transitorios; v0.1.56 restauró los rangos persistidos, pero siguió escuchando `TextView.setOnTouchListener`, cuya secuencia de eventos puede ser consumida/cancelada por la selección nativa o interceptada por el contenedor según el fabricante y la posición.
- La prueba anterior cubría toques inyectados en `TextView` y `ScrollView` y pruebas por ventana, pero no simulaba explícitamente texto extenso desplazado con el hijo seleccionable consumiendo la secuencia. El éxito en emulador **no prueba** igualdad en Samsung.

## Causas y riesgos verificados mediante código

1. **Propiedad incorrecta del gesto**. En EPUB nativo, MOBI y transcripción OCR, el detector seguía asociado al hijo `TextView`; con la selección de Android activada, la secuencia de eventos que recibe el hijo no está garantizada. Una marca puede estar correctamente pintada en la pantalla y anclada en Room, sin que el callback de nota llegue a ejecutarse.
2. **Menú de selección dependiente del instante**. `DefinitionUi.ensureViewSavedMark()` ocultaba por completo «Ver anotación» si `getSelectionStart/End()` todavía no devolvía el rango marcado **en el momento de crear ActionMode**. Android y las personalizaciones de fabricante pueden establecer la selección a continuación; el botón no se añadía si no se volvía a preparar el menú.
3. **Dos sistemas EPUB diferentes**. La representación `nativeText` es un desplazamiento UTF-16 normalizado, no un rango DOM-CFI. `EpubAnnotationMarks.forReadium` excluye deliberadamente esos anclajes de la maquetación Readium para evitar destacar texto equivocado. La vía táctil nativa arreglada **no** soluciona la correspondencia visual de las notas entre ambos motores. Hay una ruta explícita desde la selección Readium a «Ver anotación» solo cuando puede verificarse de modo inequívoco la cita. No crear una falsa decoración ni declarar paridad intermotor hasta resolver la conversión de anclajes sin ambigüedad.
4. **Casos no cubiertos por diagnóstico remoto**. No hay trazas del teléfono/archivo afectado. Una nota cuya cita o anclaje ya no coincide con el libro no es una marca activa verificada. Esta revisión no atribuye por anticipado todos los casos al mismo origen.

## Corrección de esta rama

- Nuevo `SavedMarkScrollView`: recoge DOWN/MOVE/UP en el **despachador del contenedor**, incluso cuando `TextView` consume o cancela su propia selección. Sólo activa el modal tras un toque breve sin desplazamiento, con ambas coordenadas dentro de una línea real y sobre un rango verificado que también pinta la marca.
- La invocación del diálogo ocurre **después** de `super.dispatchTouchEvent(ACTION_UP)`, por lo que no compite con el cierre de la secuencia táctil del hijo.
- No consulta texto aproximado ni deduce citas por similitud. Apertura filtrada por identificación y rango persistido, sin duplicar notas superpuestas.
- EPUB nativo, MOBI y transcripción OCR de PDF comparten el nuevo despacho. La selección prolongada, copiar, pegar, subrayar, navegación y scroll siguen delegados a Android.
- «Ver anotación» se incorpora siempre al menú contextual cuando existe un gestor de notas; el solapamiento se verifica al **pulsar la acción**, no al construir el menú. No se abre otra nota si el texto seleccionado no está marcado.
- El modal sigue mostrando el cuerpo completo y acciones de editar/eliminar; ningún cambio destructivo al almacenamiento ni a los libros.

## Pruebas y criterio de aceptación

- Prueba instrumental nueva: nota pintada, texto largo desplazado, `AnnotationHitSpan` ausente, toque sobre el texto real a través del ScrollView seleccionable, modal con el **cuerpo completo**, cierre correcto.
- Prueba de menú: `ActionMode` se construye **antes** de establecer selección; luego «Ver anotación» debe abrir exactamente la nota seleccionada.
- Mantener los flujos de lectura completos EPUB/MOBI, PDF/OCR y la regresión de selección Android.
- Verificar CI en el commit final. **Aceptación definitiva**: instalar la versión publicada en el Samsung afectado, pulsar exactamente una nota vieja y una nueva, tanto en lectura nativa como donde corresponda, comprobar modal y scroll. Sin esa evidencia, el estado es «corrección publicada / pendiente de validación en dispositivo», nunca «problema resuelto».
