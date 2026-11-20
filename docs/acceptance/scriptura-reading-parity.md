# Contrato de paridad de selección y estudio — Scriptura Android → Biblioteca IA 1.0

**Estado:** especificación comparativa basada en código fuente real, **NO** certificación de funcionamiento de Biblioteca IA. Fecha 2026-11-20. Prioridad **P0 / bloqueante 1.0**. Encargo del propietario: definición Wikipedia y otras fuentes, subrayado, selección de texto y anotaciones deben comportarse **igual que en Scriptura para Android**.

## Fuente de verdad funcional (pinned)

Scriptura Android, commit `352f374792e4ee80515c31c7f9e1746b2a51096b`:
- [DefinitionUi.java](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/main/java/com/scriptura/app/DefinitionUi.java): menú `ActionMode` y panel de definición/fuentes.
- [WordMeaning.java](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/main/java/com/scriptura/app/WordMeaning.java): validación, glosario y enlaces.
- [MainActivity.java](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/main/java/com/scriptura/app/MainActivity.java): `addDefinitionAction` ~línea 1418, `interpretSelectedFragment` ~1515, `applyStudyMarks` ~1683; `attachLibrarySelection` ~2781, `applyLibraryMarks` ~2818, `interpretLibrary` ~2966.
- [LibraryTextAnchors.java](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/main/java/com/scriptura/app/LibraryTextAnchors.java), [AnnotationAnchors.java](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/main/java/com/scriptura/app/AnnotationAnchors.java), [StudyAnnotations.java](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/main/java/com/scriptura/app/StudyAnnotations.java): anclajes y persistencia.
- Tests de referencia: [WordMeaningTest](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/test/java/com/scriptura/app/WordMeaningTest.java), [LibraryTextAnchorsTest](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/test/java/com/scriptura/app/LibraryTextAnchorsTest.java), [AnnotationAnchorsTest](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/test/java/com/scriptura/app/AnnotationAnchorsTest.java), [GeminiSelectionTest](https://github.com/JavierRB35/Scriptura/blob/352f374792e4ee80515c31c7f9e1746b2a51096b/app/src/test/java/com/scriptura/app/GeminiSelectionTest.java).

**Paridad de comportamiento y experiencia, no copia ciega de código o semántica bíblica.** Biblioteca IA lee libros generales EPUB, PDF y MOBI. No inventar versículos, interpretación doctrinal ni glosarios católicos para un ensayo/novela; adaptar el anclaje según formato mientras se preservan las acciones y sus garantías.

## 1. Contrato único del menú de texto

Pulsación larga / selección válida, en **EPUB, PDF con capa de texto y MOBI**, abre menú contextual nativo, con los mismos verbos:
- Preservar **Copiar / Compartir / Seleccionar todo** del sistema Android. No llamar indiscriminadamente a `menu.clear()`; no consumir acciones desconocidas ni cancelar la selección antes de pasar el rango.
- **Definir** — acceso prioritario y visible como en Scriptura.
- **Anotar** — editor de nota ligada a cita textual y posición del libro.
- **Subrayar** — guardar y pintar un subrayado distinguible sobre la selección exacta.
- **Interpretar con IA** — acción contextual sobre el fragmento, con el asistente BYOK.
- Mantener selección y localizador al mostrar diálogo, navegar a fuente o consultar IA. Cerrar/cancelar vuelve al punto de lectura, sin mover el libro ni crear nota vacía.
- Acción sobre texto repetido debe preservar el **anclaje del lugar seleccionado**, no escoger una ocurrencia arbitraria por coincidencia textual.

## 2. «Definir» idéntico al flujo de Scriptura

El usuario selecciona **una sola palabra**. Validación equivalente a `WordMeaning.oneWord`: letras Unicode, diacríticos y apóstrofo interno; rechazar espacios, puntuación/URL maliciosa, vacío y selecciones mayores de **45 code points**. Normalizar mayúsculas/acentos para coincidencia en glosario, **no borrar acentos** en búsquedas externas.

Panel «**Definición y fuentes**»:
1. Palabra elegida, explicación local **si existe una entrada apropiada**; si no existe, mostrar la misma advertencia honesta de ausencia de glosario, no inventar definición.
2. «**Wikipedia · Buscar «palabra»**» con URL de búsqueda oficial `https://es.wikipedia.org/wiki/Especial:Buscar?search=<término UTF-8 percent-encoded>`.
3. «**Wikcionario · Buscar «palabra»**» con `https://es.wiktionary.org/wiki/Especial:Buscar?search=<término UTF-8 percent-encoded>`.
4. Cuando **exista artículo exacto comprobado** en una fuente especializada, puede aparecer otro enlace específico (Scriptura: Enciclopedia Católica para Fariseos, Saduceos, Apóstoles, Pecado). No fabricar URLs para términos no comprobados ni presentar páginas religiosas como fuente prioritaria para libros generales.
5. «**Explicar esta palabra en su contexto con IA**» si hay clave válida de usuario. La explicación se ancla al libro y pasaje actuales; no al término aislado.
6. Los enlaces abren navegador mediante `Intent.ACTION_VIEW` HTTPS; solo transmiten la **palabra seleccionada**, no el libro, notas, historial ni claves. Si no hay app de navegador o conexión, informar de fallo sin perder la posición. Mostrar claramente que es un **servicio externo**.
7. Diálogo legible y desplazable en móvil, accesible en TalkBack/tamaño de fuente aumentado.

**Regresiones trasladables del original:** `Salvación → Salvaci%C3%B3n`, `apóstoles → ap%C3%B3stoles`; bloquear `la gracia`, `gracia,` y `fariseo?redirect=https://evil.test`. Conservar las acciones nativas copiar/compartir/seleccionar todo.

## 3. Subrayados y anotaciones

- **Subrayar** actúa sobre rango arbitrario válido, no solo palabra o página. En Scriptura `UnderlineSpan` se reconstituye desde ancla+cita al reabrir; notas/interpretaciones muestran además fondo resaltado con contraste según tema. Biblioteca IA debe reproducir el efecto visual sin confundirlo con selección temporal.
- **Anotar** presenta la cita exacta, campo de reflexión y acciones reales guardar/editar/eliminar con confirmación. Persistencia y reapertura desde cuaderno global; tocar un subrayado debe mostrar su ficha y permitir quitarlo sin borrar texto del libro.
- Usar una referencia estable: `bookId` + hash de edición + localizador real + rango/cita (y, si hace falta, coordenadas o posición de carácter). **No** asociar únicamente título/número de página/posición visual cambiante.
- Al cambiar fuente, tema, tamaño, orientación, cerrar/reabrir o recuperar backup/archivo de Drive, el rango sigue correcto. Si cambió la edición o el texto deja de coincidir, mostrar «anclaje no recuperable» y **no pintar otro fragmento idéntico**.
- La reversión/cancelación no debe modificar lectura ni guardar registros fantasma. Debe conservarse el historial de versiones en backups (véase Issue #10).

## 4. Interpretación con IA

- Al escoger «**Interpretar con IA**», tomar la **cita exacta**, su anclaje, el título/obra y **contexto próximo acotado**. La selección en Scriptura admite hasta **1.200 caracteres** (lector bíblico y Biblioteca). Aplicar límite documentado con mensaje claro; no mandar libro completo.
- BYOK: Gemini principal, Groq solo si el usuario autorizó el respaldo. Pedir clave antes de enviar, sin perder cita ni selección; confirmar qué fragmento se compartirá (consentimiento por sesión opcional, al estilo Scriptura).
- Presentar **referencia del libro y cita seleccionada**, respuesta y acciones **Cerrar**, **Preguntar más**, **Añadir al texto / Guardar interpretación**. La respuesta es generada, distinguirla de texto original y nota propia; asociarla a la cita y su localizador.
- No mover la lectura al abrir/cerrar diálogo, ni borrar anotaciones existentes. Guardar solo tras acción explícita. Mostrar errores de red/cuota/modelo/credencial. Impedir que un texto del libro dicte instrucciones privilegiadas (prompt injection).
- Para libros generales, el análisis se adapta al género y a las fuentes verificables; **no** arrastrar la instrucción de teología católica de Scriptura a libros no religiosos. Indicar límites de precisión, referencias reales e incertidumbre.

## 5. Particularidades obligatorias por formato

| Formato | Selección y representación | Anclaje y restauración |
|---|---|---|
| **EPUB** | Leer selección mediante `Readium.currentSelection`, conservar acciones nativas, añadir Definir/Anotar/Subrayar/Interpretar IA; decorar subrayados persistentes | Readium Locator + cita + rango/versión de edición; cambiar tamaño de fuente sin desanclar |
| **PDF con texto** | El lector actual usa bitmap: debe existir **capa de selección de texto real**, extracción y correspondencia texto ↔ página/coordenadas; no presentar botones falsos | Página + offsets/cuadriláteros + cita + hash; anotar y representar sobre página original |
| **PDF escaneado** | OCR real si está implementado y probado; seleccionar texto OCR anclado a página y mostrar precisión/posibles errores | Página + coordenadas OCR, cita y hash; en fallos, mensaje honesto y opción de nota por página |
| **MOBI** | `TextView` selección nativa; añadir Definir/Subrayar/Interpretar IA al menú existente, y repintar subrayados | Offset global + rango exacto y cita + hash. Restaurar dentro del fragmento/scroll, no solo al principio del chunk |

El **mismo flujo de usuario** debe funcionar en todos los formatos que tengan texto seleccionable. El alcance de PDF escaneado se declarará pendiente si no hay OCR verdadero, y se bloqueará la afirmación de paridad completa.

## 6. Estado observado en Biblioteca IA `main=d2a9cec` (sin afirmar pruebas en dispositivo)

- [EpubReaderActivity.kt](https://github.com/JavierRB35/Android_EReader/blob/d2a9cec920f8bbe4a14be4afa76ae280761e964c/app/src/main/java/com/javierrb35/bibliotecaia/reader/epub/EpubReaderActivity.kt): `selectionCallback.onCreateActionMode` hace `menu?.clear()` y añade solo `Anotar`; guarda HIGHLIGHT con `AnnotationEditor` y lo repinta mediante `Decoration.Style.Highlight`. **No Definir/Wikipedia/Wikcionario/Interpretar IA**; no preserva acciones nativas.
- [MobiReaderActivity.kt](https://github.com/JavierRB35/Android_EReader/blob/d2a9cec920f8bbe4a14be4afa76ae280761e964c/app/src/main/java/com/javierrb35/bibliotecaia/reader/mobi/MobiReaderActivity.kt): `TextView` seleccionable; añade solo «Anotar». **No Definir/Subrayar visual persistente/Interpretar IA**.
- [PdfReaderActivity.kt](https://github.com/JavierRB35/Android_EReader/blob/d2a9cec920f8bbe4a14be4afa76ae280761e964c/app/src/main/java/com/javierrb35/bibliotecaia/reader/pdf/PdfReaderActivity.kt): PDF bitmap con nota por página; **sin selección textual ni subrayado**.
- [AnnotationEditor.kt](https://github.com/JavierRB35/Android_EReader/blob/d2a9cec920f8bbe4a14be4afa76ae280761e964c/app/src/main/java/com/javierrb35/bibliotecaia/ui/AnnotationEditor.kt): editor de anotación existente sin búsqueda de definiciones ni envío a IA.

La existencia de `ReadingRepository` y `AnnotationEntity` **no prueba** que la selección/UX del usuario funcione de punta a punta.

## 7. Pruebas de aceptación P0 (automáticas + Android real)

**No aprobar 1.0 sin evidencia de cada escenario aplicable por formato:**

1. Seleccionar `Salvación` → menú nativo con Copiar/Compartir/Seleccionar todo + Definir/Anotar/Subrayar/Interpretar IA; Wikipedia/Wikcionario preservan tilde y abren página de búsqueda real.
2. Seleccionar `la gracia` → «Definir» rechaza múltiples palabras, **pero** Subrayar/Anotar/Interpretar IA sí aceptan frase.
3. Seleccionar `gracia,` → Definir rechaza puntuación; entrada con acento y mayúsculas se valida sin alterar término.
4. Cortar red o no tener navegador → diálogo y posición conservados, sin crash; las definiciones locales (si existentes) continúan funcionando.
5. Subrayar un fragmento con dos ocurrencias idénticas → después de cerrar, reabrir y cambiar tipografía permanece subrayada **solo la ocurrencia elegida**.
6. Tocar subrayado → ficha, editar nota o quitar subrayado; deshacer no altera original. Cuaderno puede navegar al punto exacto.
7. Anotar selección con signos Unicode, varias líneas y fin de capítulo → cita completa, persistencia y lectura retomada, no selección arbitraria.
8. «Interpretar con IA» sobre frase de hasta 1.200 caracteres → muestra cita+referencia, consentimiento, respuesta real con clave del usuario, Guardar/Preguntar más; falla con mensaje comprensible si clave inválida/cuota/red. Sin clave abre configuración guiada.
9. Confirmar con registros/verificación de peticiones que solo se envían cita/contexto acotado, no archivos completos ni claves a fuentes externas no elegidas; Wikipedia/Wikcionario solo reciben término.
10. Cancelar Definir/Anotar/IA y pulsar Atrás → **misma posición y mismo libro**. Reabrir app conserva subrayados y notas.
11. Cambiar dispositivo → restaurar backup desde Drive SAF, revincular el original por SHA-256, comprobar el mismo subrayado, la misma anotación y su historial.
12. Ejecutar los escenarios en EPUB y MOBI. En PDF textual validar correspondencia selección ↔ página/coordenadas; en PDF escaneado validar OCR real y anclaje o registrar limitación **no aceptada** para paridad total.
13. TalkBack y fuente grande: acciones anunciadas claramente, áreas táctiles correctas, diálogo navegable.
14. Repetir pruebas tras cambiar tema OLED/sepia/claro y rotar pantalla. No degradar navegación o copia/compartir nativas.

**Coordinación:** agente local implementa `app/src/` y tests instrumentados. ChatGPT remoto audita y mantiene CI/firma en PR #7/#9; este documento NO cambia código Android. Relacionado con [Issue #8](https://github.com/JavierRB35/Android_EReader/issues/8) (selección/IA) e [Issue #10](https://github.com/JavierRB35/Android_EReader/issues/10) (backup).
