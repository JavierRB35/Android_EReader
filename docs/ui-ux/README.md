# BibliotecaAI — evolución editorial UI/UX (WIP 0.1)
Base examinada: main 225503cc679e6ccb560f6bda3092e0d4765f9dfa; release oficial consultada v0.1.215. Capturas del propietario de esa versión, no incorporadas al repositorio público. Estado: propuesta inicial pendiente de aprobación, NO implementación. Aplican AGENTS.md, docs/protocol/ai-development-protocol.md, #103 y #136. Conocimiento está reservado a #152 / PR #153; no tocar sus archivos ni anticipar funcionalidades no integradas. Este documento amplía docs/design-system.md y docs/ux-premium-compact-20261121.md sin sustituirlos hasta aprobación.

## 1 Auditoría
| ID | Pantalla | Evidencia y causa | Prioridad | Corrección objetivo |
|---|---|---|---|---|
| UX-001 | EPUB | Grandes huecos entre palabras; readerPreferences usa Readium con publisherStyles=false, lineHeight=1.55 y pageMargins=1.2. Causa exacta de justificación no demostrada. | P1 | Contrastar maquetación y selección precisa; alineación izquierda como candidata de presentación solo si preserva formato relevante, preferencias y localizadores. |
| UX-002 | Conocimiento | Fragmentos cortado; tres botones con peso igual y altura fija 46dp en onCreate. | P1 | Ancho intrínseco, alto mínimo 48dp y scroll horizontal accesible; coordinar #153. |
| UX-003 | Conocimiento | Botón dorado sin icono visible; onCreate usa texto Unicode ⚙. Causa de invisibilidad no confirmada. | P1 | Reutilizar vector ic_ux_settings con nombre accesible. |
| UX-004 | Biblioteca | Cabecera truncada; activity_main.xml txtTitle maxLines=1 y dos iconos de 48dp. | P2 | Título de hasta 2 líneas y ancho flexible; sin reducción automática de letra. |
| UX-005 | Biblioteca | Dos tarjetas visibles; item_book_list.xml portada 78x112, padding 12, margen 5. | P2 | Portada 60x88, separación 12, padding 8, filas mínimo 104; expansión por texto. |
| UX-006 | Navegación | Barra 82dp y margen exterior; premium_bottom_navigation.xml. | P2 | Altura mínima 64, iconos 24, etiquetas 12sp; crecer en fuente ampliada. |
| UX-007 | Ficha | Gran espacio antes de acciones; layout con fillViewport y altura interna match_parent. | P2 | Contenido continuo y acciones accesibles; revisar distribución elástica, sin eliminar acciones. |
| UX-008 | Diálogos | Fondos gris y púrpura frente a paleta cálida; mezcla MaterialAlertDialogBuilder y AppCompat AlertDialog en SettingsActivity. | P2 | Overlay coherente con tema; conservar botones, cancelación y confirmaciones. |
| UX-009 | Ajustes | Controles grandes repetidos, jerarquía débil. | P2 | Una acción destacada por bloque, filas secundarias textuales de 48dp mínimo, texto multilínea. |
| UX-010 | Ficha | Acción retirar local visualmente similar a acciones normales. | P2 | Diferenciar retirar local de eliminar definitivamente (texto explícito + confirmación existente). |
| UX-011 | Lector | Cabecera doble y botones 42dp en createLayout; acciones fuera de vista horizontal. | P2 | Fila de título 48dp y fila de acciones 48dp, sin reducir áreas táctiles; scroll visible y foco. |
| UX-012 | Biblioteca | Portada ausente con metadato epub/Desktop. | P2 visual | Fallback tipográfico y rótulo explícito; calidad de metadatos y portadas reales fuera de alcance. |
| UX-013 | Ajustes | Texto acerca de cita PDF; #136 retira lectura/importación PDF y OCR. | P2 | Alinear etiqueta con EPUB/MOBI, sin eliminar originales históricos. |

## 2 Mapa técnico e inventario inicial
Kotlin/XML Views, Material Components 1.12.0, AppCompat 1.7.0, Readium 3.0.3, SDK 26–34; UI mixta XML y vistas programáticas. Sin nuevas dependencias. Prefijo de clases: app/src/main/java/com/javierrb35/bibliotecaia/. Recursos: app/src/main/res/. Manifest declara diez activities. No es todavía un inventario exhaustivo de todos los diálogos, acciones condicionales, estados ni gestos.

| SCR | Clase/archivo | ACC/estados que preservar | Representación objetivo |
|---|---|---|---|
| 001 | ui/SplashActivity, activity_splash.xml | arranque y transición actual | marca existente |
| 002 | ui/MainActivity, activity_main.xml, BookAdapter, item_book_list.xml, item_book.xml | catálogo local/compartido setCentralMode, continuar, búsqueda y sugerencias renderSearchSuggestions, seis estantes, filtros showSearchFilters, lista/rejilla toggleViewMode, organizar/ajustes, seleccionar todo/cerrar renderSelectionChrome, descarga/guardado/retirada/borrado y confirmaciones confirmBatchDeletion/Removal, variantes de edición, importación openImportPicker, faltantes, progreso/detener/error/resultado | cabecera flexible, filas compactas, acciones actuales; selección sustituye cabecera como hoy |
| 003 | ui/BookDetailActivity, activity_book_detail.xml | show, promptReadingStatus, toggleFavorite, promptEditMetadata, promptAssignment, openSynopsisAction, editSynopsis, showSavedSynopsis, openAi, descarga/vinculación/retirada/borrado, lector, confirmaciones y errores | ficha continua, metadatos, organización y acciones |
| 004 | reader/epub/EpubReaderActivity | createLayout, índice showContents, search, settings, concentración toggleReadingControls, notas showAnnotations, voz toggleSpeech, selección precisa/maquetación setNativeMode, marcar, selección contextual/definición/IA, tema y tipografía, scroll/páginas, progreso; apertura/error, gestos, localizadores, guardado | barra compacta y texto protagonista, controles existentes sin agregar gestos |
| 005 | reader/mobi/MobiReaderActivity | volver, búsqueda, letra +/- y cuatro temas settings, voz, concentración, notas, selección, progreso, apertura/error | misma gramática visual sin añadir controles exclusivos EPUB |
| 006 | ui/KnowledgeActivity | pestañas existentes, búsqueda, nueva nota, exportación Markdown, filtro por libro, papelera/restauración, acciones y versiones actions/showVersions, editor AnnotationEditor; cambios #152 solo tras integrar y actualizar inventario | coordinación con PR #153, no definir nuevo comportamiento |
| 007 | ui/OrganizeActivity, activity_organize.xml | altas categorías/etiquetas/colecciones, renombrar, mover, jerarquía, eliminar confirmDelete; observar existentes y estados vacíos | filas con separadores y diálogo de opciones |
| 008 | ui/SettingsActivity, StorageSetupWizard | configurar/comprobar/resolver, comprobar acceso, importar Calibre, recuperar copia, opciones condicionales de conexión, permisos SAF, cancelaciones, estado catálogo/originales, IA, comprobar actualizaciones, avisos, instalación, acerca de | bloques compactos sin modificar diagnósticos ni rutas |
| 009 | ai/AiSetupActivity | Gemini/Groq, guardar/probar/eliminar clave, modelo Gemini, estado y consentimiento fallback | tarjetas de proveedor, campos protegidos, feedback |
| 010 | ai/AiChatActivity | consulta, interpretación automática, reintento, configurar si falta clave, historial, guardar interpretación, proveedor, respuestas/errores | mensajes discretos y campo existente |
| 011 | ui/AnnotationEditor, reader/annotations/SavedAnnotationSheet | creación/edición, texto guardado, nota asociada y acciones contextuales | diálogo coherente, preservar selección y resultados |

## 3 Decisiones y tokens
DEC-001 mantener identidad editorial y paleta existente, evitar nueva familia de fuentes o librería. DEC-002 distinguir borde decorativo de borde imprescindible para identificar controles. Valores sRGB, opacidad 100%; contraste calculado sobre fondo/superficie/superficie alta.
| Token | Hex | Contraste sobre los tres fondos | Uso |
|---|---|---|---|
| bia_background | #080807 | — | app |
| bia_surface | #1B1A18 | — | panel |
| bia_surface_high | #28241F | — | selección/superficie |
| bia_text | #F4E8D7 | 16.57/14.38/12.75 | principal |
| bia_text_secondary | #B8B0A6 | 9.35/8.11/7.19 | secundario |
| bia_accent | #E9C282 | 11.93/10.35/9.18 | primario, texto de selección |
| bia_outline | #49443D | 2.08/1.80/1.60 | decorativo, nunca única frontera funcional |
| outline_interactive (propuesto) | #777168 | 4.15/3.60/3.19 | borde necesario y foco; foco también 2dp dorado |
| error (propuesto) | #F2A39B | 10.01/8.69/7.70 | error con texto |
| success (propuesto) | #A6CCA8 | 11.28/9.79/8.68 | confirmación con texto |
Acción primaria: acento con texto #080807 (11.93:1); secundaria texto principal, superficie o borde interactivo. Pulsado ripple nativo; seleccionado dorado con check o etiqueta, foco visible. Deshabilitado mantiene semántica y no parece activo; no aplicar opacidad al contenido informativo. Temas de lector: preservar colores de palette()/applySettings existentes por motor, no extrapolar tema global ni alterar preferencias.
DEC-003 tipografía del sistema serif para títulos editoriales 24sp/30sp, 400; sans-serif para título de libro 16sp/22sp, 600; sección 16sp/22sp, 600; cuerpo 14sp/20sp, 400; acción 14sp/20sp, 500; meta 12sp/17sp; navegación 12sp/16sp. Sin fuente embebida; Android resuelve serif/sans-serif. Sin reducir sp por falta de ancho. Para lector EPUB conservar fontScale .75–2, familia y lineHeight 1.55; nativeText 20sp*fontScale; MOBI 14–36sp. Verificar composición real antes de cambiar márgenes o justificación.
DEC-004 dimensiones en dp: base 4/8/12/16/24/32, margen pantalla 16 (12 a 320), radio panel 12, campo 12, botón 10, divisor 1. Alto táctil mínimo 48, contenido wrap_content; botones pueden crecer y saltar línea. Cuerpo de lector horizontal 16–24 según ancho, sujeto a preservación de localizadores; el valor para Readium debe calibrarse en su unidad propia, no confundir con dp.

## 4 Componentes y contrato acotado
| CMP | Medidas nominales (360dp) y reglas | Referencia |
|---|---|---|
| 001 cabecera | 48 mínimo, padding 16, iconos 24 dentro de 48, título 24/30 hasta 2 líneas; altura flexible | activity_main.xml, KnowledgeActivity |
| 002 navegación | 64 mínimo, cuatro columnas de ancho igual, iconos 24, etiqueta 12, importación 32 dentro de 48; etiquetas hasta dos líneas y alto flexible | premium_bottom_navigation.xml, PremiumBottomBar |
| 003 botón | min 48, padding H12 V8, radio10; una línea si cabe, si no wrap | MaterialButton existente |
| 004 búsqueda | 48 mínimo, icono 24, clear existente, padding12; hint accesible y sin recorte | txtSearch, searchSuggestions |
| 005 filtros | ancho intrínseco, min48 táctil, separación4; scroll horizontal con contenido desplazable, foco visible | chipShelves, showSearchFilters |
| 006 fila libro | margen horizontal 16, portada 60x88 fitCenter, padding 8, separación 12; alto mínimo104, título máximo 2 líneas en normal; detalle ofrece completo; ampliar si fuente grande. Formato/descarga/progreso visibles | item_book_list.xml, BookAdapter |
| 007 rejilla | conservar regla actual columnas 1–6 y elección persistente, mínimo legible; portada fitCenter, no recortar; texto y estados visibles | item_book.xml, applyViewMode |
| 008 ficha | título 24/30, autor14, metadata12, estado/favorito mínimo48; tres filas organización 56 mínimo, acción argumento48, acciones lector/IA/editar/más 48; scroll natural sin hueco forzado | activity_book_detail.xml |
| 009 lector | cabecera título 48 mínimo, fila horizontal 48 mínimo, footer48 mínimo; zona restante peso1; no solapar contenido; concentración conserva ocultación actual | EpubReaderActivity.createLayout, MobiReaderActivity |
| 010 diálogo | margen ventana 24, máximo 480 ancho, padding 20, título 20/26, fila 48 mínimo, botones 48, contenido scrollable; superficie #1B1A18 y texto principal | builders Material/AppCompat |
| 011 organización | secciones 16sp, fila 48 mínimo, indentación 16 por nivel y límite según ancho, nombre completo accesible | OrganizeActivity |
| 012 ajustes | título 24, secciones 16, bloque con padding12, filas min48, división 1dp; acción principal de configuración conserva texto dinámico; acciones restantes visibles | SettingsActivity.onCreate/refreshUnifiedStatus |
| 013 estado | texto14/20, icono vector cuando disponible, recuento real, botón detener/cerrar según estado, no color único | observeBatchQueue, importBooks |
| 014 conocimiento | pestañas intrínsecas, búsqueda min48, estado vacío legible, controles secundarios según base integrada #152; no imponer cambios a PR activa | KnowledgeActivity.onCreate |
| 015 IA | campos wrap 48, clave protegida, acciones guardado/prueba/borrado existentes, estado texto; chat burbujas max ancho útil, cuerpo 14/20 | AiSetupActivity, AiChatActivity |
Recursos: reutilizar ic_read, ic_library, ic_note, ic_ux_organize, ic_ux_settings, ic_ux_grid, ic_ux_list, ic_premium_search en res/drawable (vectores existentes, revisar procedencia/licencia antes de redistribuir). Sustituir glifos ambiguos por vector equivalente solo si no cambia acción. Sin portadas ficticias. Mockup usa símbolos y datos sintéticos, no material privado.

## 5 Validación y matriz de conservación
La tabla SCR es matriz inicial de equivalencia, no certificación exhaustiva. Para cada acción, comprobar mismo callback, disponibilidad, cancelación/confirmación, destino y resultado persistido; comparar controles y estados condicionales antes/después. Prohibido alterar Room, SAF, originales, backup, posiciones, IDs, permisos, selección, gestos, formatos, claves, preferencias. No cambiar EPUB/MOBI por motivos estéticos. Separar defectos de datos. No introducir PDF/OCR, cuenta, OAuth o servicio adicional.
Pruebas: anchuras 320/360/411/480dp, escala 1.0/1.3/2.0 o máxima, teclado, navegación gestual/botones, orientaciones soportadas, TalkBack y foco; lector OLED/oscuro/sepia/claro y EPUB maquetación/nativo/MOBI. Títulos largos, sin portada, 484+ libros sintéticos, filtro vacío, operación activa/cancelada/error, selección múltiple, nota y restauración, claves ausentes. Comparar medidas ±2dp, tokens exactos, sin recorte involuntario ni solapamiento, mínimo táctil 48dp, texto normal 4.5:1, grande 3:1, componentes necesarios 3:1. Validación funcional por desarrollador con pruebas proporcionales; usar existentes CompactLibraryHomeDeviceTest y LibraryHomeUxDeviceTest si siguen presentes. Validación física posterior en dispositivo, no acreditada.
Secuencia: aprobar gramática y prototipos → biblioteca y navegación → ficha → lector (verificar posiciones antes/después) → ajustes/organización/IA → sincronizar propuesta Conocimiento tras #153. Validar cada incremento; no fusionar WIP ni publicar APK por documentación. Estado 0.1: auditoría inicial, mapa de 10 activities + editor, propuesta medible; falta inventario acción por acción con todos los estados, mockups completos y adaptativos, confirmación de exactitud en implementación y aprobación del coordinador.