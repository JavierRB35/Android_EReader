# Paridad funcional de anotaciones: Scriptura Android → BibliotecaAI

**Referencia inspeccionada:** `JavierRB35/Scriptura` (`main` `352f374792e4ee80515c31c7f9e1746b2a51096b`), principalmente
`app/src/main/java/com/scriptura/app/MainActivity.java`, bloques
`1683–1741`, `1805–1857`, `2818–2860`, y
`docs/android-epub-study-annotations.md`.

## Contrato de interacción comprobado en Scriptura

| Gesto / estado | Scriptura Android | Adaptación a BibliotecaAI |
|---|---|---|
| Pulsar brevemente texto marcado | Abre el detalle de anotación vinculado al rango exacto | `MarkedTextTap` en EPUB nativo, MOBI, texto OCR; decoración Readium / toque en PDF bitmap llevan a `SavedAnnotationSheet` |
| Varias marcas superpuestas | En la Biblia ofrece un selector de anotaciones | Selector de notas/subrayados en los tres formatos, también para geometría PDF superpuesta |
| Detalle | Cita literal íntegra y texto íntegro en diálogo desplazable y seleccionable | Vista compartida sin recortes `take(900)`/`take(1200)`, secciones «Fragmento seleccionado» y «Tu anotación» |
| Editar | Conserva referencia/cita, actualiza nota y repinta | Reutiliza `AnnotationEditor.edit` y observación Room existente; no migra esquema ni edita EPUB/PDF/MOBI |
| Eliminar | Confirmación explícita y retirada de marca | Confirmación antes de mover a papelera; marcas se actualizan desde `observeAnnotations(bookId)` |
| Pulsación larga/arrastre | Android gestiona selección nativa sin que el toque de marca la consuma | Detector solo maneja `ACTION_UP` breve e inmóvil; devuelve `false` para `DOWN`, largos y desplazamientos |
| Accesibilidad sin toque | Lista de anotaciones bajo el texto o biblioteca de anotaciones | Se conserva «Mis subrayados y anotaciones» en EPUB y «Conocimiento» para todos; el detalle usa texto seleccionable |

## Reglas de integridad

- Se muestran solo anotaciones existentes (`NOTE` o `HIGHLIGHT`) no eliminadas y con rango coincidente.
- Las anotaciones vinculadas se distinguen del mero subrayado; cualquier tipo de marca conserva su identificador y no se pierde si hay varias en el mismo fragmento.
- Un localizador inválido o una cita que no coincide no se traslada a otro fragmento por suposición.
- No se modifica el archivo original, ni el catálogo/relaciones, ni ZIP de backup ni las reglas BYOK.
- La actualización no elimina datos ni exige migración de Room.

## Diferencias legítimas de producto que no deben copiarse

- Scriptura ancla versículos y puede mostrar atlas bíblico/mapas/enlaces; BibliotecaAI ancla EPUB al texto o al localizador Readium, PDF a página/geometría OCR y MOBI a desplazamiento del texto. No inventar versículos en libros generales.
- La nota de biblioteca no es una interpretación IA, y una explicación BYOK **no se guarda automáticamente** como anotación sin autorización expresa.
- En Readium la activación de anotaciones depende de las decoraciones disponibles; las marcas nativas UTF-16 no siempre tienen localizador DOM equivalente.
- PDF escaneado y digital sigue usando el panel OCR para selección de texto; el bitmap no admite selección por arrastre. El toque breve sobre marcas existentes sí abre la nota.

## Evidencia y aceptación pendiente

La batería instrumentada `android-scriptura-annotation-parity.yml` valida el gesto corto frente a pulsación larga/arrastre, colisiones de múltiples marcas PDF y la apertura real del detalle desde MOBI. Los demás flujos previos de lectura EPUB/PDF/MOBI siguen cubiertos por `android-reader-e2e-v01.yml` y `android-emulator-reader.yml`.

**Antes de declarar aceptado en Samsung:** sobre una actualización firmada sin desinstalar, anotar una cita, tocar la marca, ver la cita y la nota completas, editar, eliminar, cerrar/reabrir y comprobar la marca; repetir en EPUB nativo, Readium, MOBI y PDF/OCR. Probar dos marcas en el mismo fragmento y seleccionar por pulsación larga. Se mantiene #24 abierto hasta QA físico.
