# P0 — La nota guardada no se abre al tocar el subrayado

**21/11/2026 · BibliotecaAI Android · análisis de fuente contrastado con Scriptura para Android**

## Contrato exigido

En EPUB nativo, MOBI y el texto OCR del PDF, un toque breve sobre una NOTE
pintada tiene que abrir directamente un modal con el texto íntegro de la nota
ya guardada. Una pulsación larga debe conservar el menú Android de selección,
incluida «Ver anotación» cuando la selección intersecte un rango válido.
El modal debe funcionar después de guardar, cerrar/reabrir, editar y actualizar
la APK conservando los datos. No se debe crear otra nota ni editar el libro.

## Histórico reconstruido, no inferido

- **Scriptura/main** `MainActivity.java::applyStudyMarks` (líneas ~1683–1741):
  usa los rangos persistidos `AnnotationAnchors.inDisplay` tanto para
  pintar como para localizar el toque, y abre `showStudyAnnotation`
  inmediatamente durante `ACTION_UP`. No depende de `AnnotationHitSpan`.
- **BibliotecaAI v0.1.26 y v0.1.38**: los lectores EPUB nativo y MOBI
  interrogaban `nativeMarkRange`/`validatedMarkOffset` al tocar. El
  helper `MarkedTextTap` abría el modal inmediatamente, sin `post()`.
  El usuario confirma que una versión anterior funcionó, aunque no se ha
  identificado documentalmente su número.
- **v0.1.40 / commit d02eaf2**: se introdujo una dependencia nueva del
  `AnnotationHitSpan` temporal pintado por TextView y se cambió el
  reconocedor: interceptación del desplazamiento, seguimiento de gestos y
  `text.post { onMarks }`. Aunque la anotación de Room y el subrayado visible
  sigan existiendo, el toque puede quedar sin destino si ese span temporal
  falta o el padre intercepta el gesto. Es una regresión estructural
  verificable por comparación de fuentes, no una demostración de que sea
  la única causa física en Samsung.
- **v0.1.54 / PR #57**: se añadió «Ver anotación» al menú de selección y
  se corrigieron los subrayados con texto de nota. Se mantuvo la detección
  de toque dependiente de `AnnotationHitSpan`. Las pruebas sintéticas con
  dicho span presente no descartaban el fallo comunicado.

## Corrección P0

1. EPUB nativo y MOBI: tanto el toque corto como la selección larga usan
   los **mismos localizadores persistidos, validados** que la pintura,
   comprobando edición, capítulo/página y coincidencia textual. El
   `AnnotationHitSpan` puede permanecer como adorno y ayuda técnica, pero
   ya NO es requisito funcional para abrir la nota.
2. `MarkedTextTap` conserva el destino captado durante `ACTION_DOWN`
   dentro del margen normal del dedo y abre el modal de modo inmediato,
   igual que Scriptura. Los gestos largos, arrastres y selección Android
   siguen sin consumirse. Se reserva `ScrollView` solamente cuando
   el inicio del toque cae sobre un rango real guardado.
3. EPUB Readium: comprobar el decorado **antes** de resolver un toque del
   borde como cambio de página; conservar el borde estrecho anterior
   si no se encuentra una nota.
4. PDF/OCR: el menú «Ver anotación» y el toque apuntan a los mismos
   rangos OCR validados y actualmente pintados. No se reconstruye
   geometría antigua cuando faltan los datos originales.

## Pruebas de regresión que deben pasar antes de integrar

- `EpubReadingJourneyDeviceTest`: se crea y guarda una nota en un EPUB
  **real** y se espera la pintura; se eliminan los `AnnotationHitSpan`
  temporales sin tocar el subrayado visible ni Room. Se inyecta el toque
  a través del **ScrollView real**; el modal debe mostrar `Tu anotación`
  y el cuerpo guardado. La prueba original que pasaba con el span presente
  no detectaba esta regresión.
- `MobiReadingJourneyDeviceTest`: mismo recorrido para MOBI con
  localizador absoluto, conservación de la nota y reapertura del libro.
- `ScripturaMarkTapDeviceTest`: toque corto frente a pulsación larga
  y arrastre; conservación de selección.
- Mantener `Android quality gate`, `Android startup smoke`,
  render de EPUB, OCR y editor de anotaciones. No mezclar con cambios IA
  ni migraciones de base de datos.

## Límites de evidencia y aceptación

Los tests de emulador Android prueban el código y reproducen esta clase
de fallo. **No equivalen a prueba táctil con el Samsung real, ni prueban
todos los EPUB comerciales/ediciones y todas las anotaciones antiguas**.
Solo se puede afirmar `resuelto en dispositivo` después de instalar la
APK nueva SIN desinstalar, abrir el libro afectado, tocar una marca de NOTE
existente y comprobar el cuerpo íntegro en el modal. Si falla todavía,
recoger formato del libro (EPUB nativo, EPUB maquetación, MOBI o PDF),
captura y estado de la nota desde «Conocimiento» para investigar el
localizador concreto sin exponer datos del libro ni borrar la biblioteca.
