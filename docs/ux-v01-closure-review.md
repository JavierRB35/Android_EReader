# BibliotecaAI v0.1 — revisión final de usabilidad (UX/UI)

**Fecha:** 20-11-2026 · **Auditoría del código:** main `51f4d9e`, PR #25 (ficha) y PR #26 (biblioteca).  
**Alcance:** inspección estática de UI, recorrido y pruebas disponibles. **No se ha probado un Samsung físico en esta revisión.**  
**Coordinación:** issue #24. Los cambios visuales se entregan en PR #25 y #26; los lectores y backup permanecen reservados a sus agentes.

## Resultado verificable

- **Biblioteca (PR #26):** cabecera reducida, acceso directo real a la última lectura, importación visible sin fila inferior extra, búsqueda/filtros restaurables, vista adaptable 1–6 columnas, mensajes vacíos contextualizados y limpiar filtros. CI: quality gate, startup y lector/emulador **SUCCESS** sobre commit de código `200c115`; archivo específico `LibraryHomeUxDeviceTest` incluido en AndroidTest.
- **Ficha (PR #25):** compactación de metadatos, acciones de leer/IA/editar, selector explícito de estado, tres diálogos de creación/selección/desvinculación de categorías/etiquetas/colecciones en el mismo libro mediante transacción Room. Los cuatro workflows de la entrega `09a878e` terminaron **SUCCESS**, incluido test de UI en emulador a 900×1900 px. En el cierre se ha añadido `BookDetailUnassignDeviceTest` para cubrir la desasignación real por diálogo y se han ampliado a 48 dp los objetivos táctiles de estado, favorito y revinculación. **Requiere comprobar las nuevas CI del HEAD final**; no extrapolar la prueba de un tamaño a todos los dispositivos.
- **Compatibilidad de PRs:** la biblioteca y la ficha se han editado en conjuntos de archivos distintos, salvo documentación propia. PR #25 no altera motor, backup ni esquema; PR #26 tampoco.

## Matriz de pantallas

| Pantalla / flujo | Revisión de código | Incidencia UX | Decisión |
|---|---|---|---|
| Biblioteca | `MainActivity`, `activity_main`, `BookAdapter` | Cabecera sobredimensionada y último libro no destacado en el diseño anterior | **Corregido en PR #26**; comprobar rotación, 320–360 dp y fuente 200 % en dispositivo |
| Importación | `MainActivity`, `BookImporter` invocado por SAF | Era demasiado prominente el botón inferior; feedback ya existe (estado “Importando” y progreso) | **Corregida jerarquía en #26**; verificar importación, cancelar/errores y conservación de datos |
| Ficha | `BookDetailActivity`, `activity_book_detail` y `BookOrganizationEditor` en PR #25 | Metadatos y asignaciones ocupaban más de un viewport; gestión necesitaba desplazarse a Organizar | **Corregido en #25**; prueba de desasignación y mínimos táctiles añadidos; verificar fuente ampliada |
| Organizar | `OrganizeActivity` | Sigue siendo la gestión general de estantes, pero ya no es necesaria para clasificar un libro | **Sin rediseño**; mantener para organización global; inspección visual pendiente |
| EPUB | `EpubReaderActivity` | Dos filas superiores + otra inferior; acciones secundarias en barra horizontal parcialmente oculta | **P1 lectura**: propuesta en #28, no tocar `reader/` hasta cerrar selección y marcas |
| PDF / OCR | `PdfReaderActivity`, `PdfOcrStudy` | Barras + Nota y OCR a ancho completo restan área de página; selección OCR y repintado tienen implementación | **P1 densidad, P0 prueba real de marcas**; coordinar con #8 y #28 |
| MOBI | `MobiReaderActivity` | Botones nativos y tipografía de barras no coherentes con EPUB/PDF; hay selección/subrayado y posición | **P1 consistencia, P0 prueba real de marca y retorno**; #8 |
| Conocimiento | `KnowledgeActivity`, `ReadingRepository`, `ReaderRouter` | «Abrir origen» es cuarta opción en diálogo; la lista de notas no identifica siempre a qué libro pertenece | **P1 descubrir origen**; navegación con `note.bookId` y `note.locator` existe, pero falta prueba dispositivo de vuelta al fragmento. Archivo reservado al integrador |
| Editor de anotación | `AnnotationEditor` | Guardar y editar muestran feedback, preservan texto al fallar; el marcado visible depende del lector | **P0 E2E de selección → guardar → marca → reabrir**; pruebas de editor aisladas no prueban visibilidad en libro |
| Asistente IA / configuración | `AiChatActivity`, `AiSetupActivity` | Campo de pregunta después del historial obliga a buscarlo al crecer la conversación; la configuración presenta ayuda de ambos proveedores a la vez | **P1**; conservar flujo BYOK, consentimiento de envío y respuestas reales. No rediseñar antes de P0 |
| Ajustes / backup | `SettingsActivity` + backup completo vía SAF | Información clara de restauración, pero operación larga tras elegir destino no presenta progreso persistente; navegación larga pero justificable | **P1 feedback**, **P0 QA** de ZIP original completo en proveedor Drive/local, cancelación y restauración segura; no modificar backup |
| Navegación general | Barra Biblioteca/Leer/Conocimiento y rutas de retorno | Navegar a fragmento desde Conocimiento exige acción no evidente | **P1** accesibilidad/visibilidad de «Ir al texto»; no crear una pantalla nueva |

## Acciones esenciales y confirmación de funcionalidad

**Se ha comprobado por código** que los controles de biblioteca ejecutan importación SAF, apertura de ficha, vista lista/cuadrícula, navegación a Organizar/Ajustes y retomar lectura por `ReaderRouter`. La ficha enlaza Leer, IA, favorito, estado, edición y tres diálogos de taxonomía con escritura real. Conocimiento enlaza edición, versiones, papelera, exportación y apertura al origen por localizador. El lector EPUB usa decoraciones Readium o marcas nativas, PDF reconstruye zonas de marca desde OCR y MOBI repinta anotaciones por localizador. **Ver el método y pasar CI no demuestra que cada marca sea visible o pulsable en el Samsung.**

## Bloqueantes P0 para aceptar v0.1

1. **#16:** importar, abrir y leer EPUB/PDF/MOBI en el Samsung que registró crash; recabar `logcat` si reaparece. No borrar datos de usuario.
2. **#8:** seleccionar, guardar anotación/subrayado, verlo sobre el texto, tocarlo, editarlo y reabrir el libro; repetir en EPUB nativo/Readium, PDF OCR y MOBI. Confirmar navegación Conocimiento → fragmento real y posiciones distintas en dos libros.
3. **#10:** exportar ZIP íntegro vía SAF a Drive (como DocumentsProvider) y destino local, restaurar originales EPUB/PDF/MOBI y metadatos en dispositivo de prueba sin pérdida; confirmar casos cancelación, archivo corrupto, falta de espacio y ausencia de OAuth.
4. **PR #25/#26 + integrador:** integrar en orden coordinado tras comprobar CI verde del HEAD y ejecutar smoke conjunto; verificar accesibilidad, legibilidad, controles 48 dp en normal/200 %, títulos largos, y flujo completo antes de publicar.
5. **Distribución:** firma autorizada, `versionCode` creciente, actualizar APK sin borrar datos y distinguir release automática de versión aceptada.

## Ajustes P1 y P2 aplazados deliberadamente

- **P1:** reducir cromado en lectores; exponer «Ir al texto» directamente en Conocimiento; mantener el campo de pregunta y botón IA accesibles con historial largo; feedback de backup largo y configuración IA por proveedor activo.
- **P2:** homogeneizar botones nativos / Material3, microespaciados y mensajes menores una vez superada QA funcional.

## QA física mínima reproducible

En móvil de tamaño habitual y otro estrecho: probar modo vertical, giro, teclado visible, tema OLED, fuente del sistema normal y 200 %, TalkBack, título y autor muy largos, colección vacía, resultados sin coincidencias, nota larga, importar archivo y cancelar, estado/favorito, clasificación y retirada desde ficha. Repetir **Conocimiento → Abrir origen** para EPUB/PDF/MOBI con subrayado persistente. Probar ZIP completa y actualización con la misma firma en el dispositivo destinatario.

**Criterio de cierre:** PRs revisadas + checks verdes sobre SHAs finales, evidencia visual/funcional en dispositivo y documentación en #24. Sin prueba física no etiquetar “Samsung verificado” ni cerrar los P0.
