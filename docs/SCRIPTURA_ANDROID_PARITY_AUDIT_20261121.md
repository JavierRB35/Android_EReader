# Auditoría de paridad funcional · Scriptura Android → BibliotecaAI

**Fecha:** 21/11/2026  
**Fuente de verdad Scriptura:** [JavierRB35/Scriptura](https://github.com/JavierRB35/Scriptura), `main` **`840258ef21311be7e1e3993079167c3f5454c7f4`**.  
**Destino:** [JavierRB35/Android_EReader](https://github.com/JavierRB35/Android_EReader), base auditada **`3a10cec7e082628c7c33110a9818edf5971f4ca3`**.  
**Método:** comparación directa de fuentes Java/Kotlin, README y pruebas JVM/instrumentadas de ambos repositorios; no extrapolar un botón, color, permiso, comportamiento o proveedor no presente en el código. **No se ha validado todavía la paridad completa en el Samsung del propietario.**

## 0. Criterio rector de adaptación

Scriptura tiene un lector **bíblico (73 libros, versículos y orden canónico)** y una **biblioteca independiente de seis obras extracanonicas** (`LibraryCatalog`). La presente aplicación es una biblioteca de **N libros importados por usuario**, de tres formatos (`EPUB`, `PDF`, `MOBI`). Por tanto:

- Igualar el **comportamiento** de lectura, selección, anotación, conservación de posición, navegación y consultas, no trasplantar números de versículo a documentos que no los tienen.
- Mantener los archivos originales **inmutables**, guardando posiciones y anotaciones en Room, referenciadas por `bookId` y localizadores del formato/edición. La identidad de libro y el localizador reemplazan a `book+chapter+verse`.
- Mantener **consentimiento** ante transmisión de fragmentos a un proveedor externo. Una clave cifrada no da permiso implícito para un proveedor secundario.
- **No inventar** atlas bíblicos, mapas, notas editoriales de Navarra, calendario litúrgico, numeración de versículos ni el orden de libros para un PDF/MOBI cualquiera.
- No considerar la compilación o un emulador como prueba suficiente de un toque en marca, lectura con desplazamiento real ni instalación sobre el Samsung.

## 1. Arquitectura funcional examinada

| Eje | Scriptura (código fuente) | BibliotecaAI (código fuente) | Diagnóstico |
|---|---|---|---|
| Fuentes y estructura | `BibleStore` (pasajes), `LibraryCatalog` (obra/sección) | `BookImporter`, `Models`, `ReaderRouter`, `BookTextSource` | **Implementado adaptado**: múltiples originales. No convertir libros genéricos en Biblia |
| Capas de lectura | `MainActivity.readingPreview` y `libraryReader` | `EpubReaderActivity`, `PdfReaderActivity`, `MobiReaderActivity` | **Tres motores distintos**: deben compartir contrato de experiencia sin falsificar paginación |
| Localizador | `AnnotationAnchors`, `LibraryTextAnchors`, `ReadingProgress` | `ReadingRepository`, `NativeEpubAnchors`, `SelectionAnchors`, `PdfOcrAnchors` | **Parcial**: UI real debe verificar reanclaje y exactitud por edición |
| Nota y subrayado | `StudyAnnotations`, `applyStudyMarks`, `showStudyAnnotation` | `AnnotationEditor`, `MarkedTextTap`, `SavedAnnotationSheet`, `KnowledgeActivity` | **Parcial**: existe persistencia, diálogo y toque, falta Samsung y filtros completos |
| Selección nativa | `DefinitionUi`, callbacks de acción, Copiar/Compartir/Definir/Anotar/Subrayar/IA | `study.DefinitionUi`, callbacks propios EPUB/MOBI/PDF OCR | **Parcial**: verificar menú de Android, varias palabras, acciones nativas sin anularlas |
| Voz | `ReaderSpeech` (Android TTS por bloques, continuidad entre capítulos) | `reader/speech/ReaderSpeech.java` enlazado desde `EpubReaderActivity`, `MobiReaderActivity` y `PdfReaderActivity` | **Implementado en código**: EPUB avanza por capítulos; MOBI narra el texto disponible; PDF solo OCR de la página. Pendiente escucha efectiva en dispositivo y accesibilidad |
| Diseño lector | `DisplayModes` normal/cómodo/OLED/sistema; `Aa`, acciones compactas, hojas contextuales | Configuración EPUB y controles PDF/MOBI heterogéneos; `PremiumBottomBar` en biblioteca | **Parcial**: unificar jerarquía, densidad, tipografía, accesibilidad y acciones entre formatos |
| IA Gemini/Groq | `AiRouter`, `GeminiClient`, `GroqClient`, `AiRecoverableException` | `AiClients`, `AiFallbackRouter`, `AiFallbackConsent`, `AiKeyStore`, `AiChatActivity` | **Integrado en `main`**: respaldos selectivos y consentimiento; falta verificar con claves reales y en móvil |
| IA con selección | `interpretSelectedFragment`, diálogo de respuesta y `Añadir al texto` | `AiChatActivity` en modo interpretación, indicador y `Guardar en mis anotaciones` | **Implementado parcialmente**; conservar lectura/scroll y distinguir proveedor |
| IA general | `askBibleQuestion` con citas verificadas contra EPUB local | `BookTextSource.retrieve`, chat por libro, historial local | **Parcial**: reforzar referencias y deep links verificables por fragmento |
| Argumento/sinopsis | Scriptura cachea interpretaciones y reflexiones por pasaje/obra | `BookOverview`, `BookSynopsisRepository`, `BookDetailActivity` | **Ya existe**: argumento por libro, etiquetas solo si vacío, acceso, copia y exportación; no sobrescribir |
| Organización conocimiento | `AnnotationLibrary`, `AnnotationExport`, `BibleQuestionHistory` | `KnowledgeActivity`, `AnnotationVersionEntity`, papelera y exportación MD | **Parcial**: agrupación por libro, enlace directo a fragmento y distinción entre tipos |
| Backup y restauración | `ScripturaBackup`: archivo seleccionado SAF, guardado/copia/recuperación | `BackupRoutine`, `CompleteBackupManager`, `BackupCodec` | **Adaptado**: incluye múltiples originales; QA de ZIP/corrupción/SAF y actualización conserva datos |
| Actualización | `UpdateChecker`, `UpdateInstaller`, firma mantenida | `updates/*` + workflow firmado | **Implementado**, comprobar instalación in situ; no borrar datos |

## 2. Port prioritario: respaldo de Gemini a Groq

Fuente exacta:
- `Scriptura/AiRouter.java`: Gemini es primario y Groq recibe **una única** petición de respaldo si `AiRecoverableException` y `fallbackEnabled`.
- `Scriptura/GeminiClient.java`: recuperables **429**, **5xx** y **SocketTimeoutException**; no reenvía 401/403, respuestas bloqueadas ni errores de configuración.
- `Scriptura/GroqClient.java`: `fallbackEnabled` requiere una clave y autorización revocable; guardar una clave nueva o eliminarla desactiva el respaldo; el propio cliente revalida la autorización antes de enviar. Usa `api.groq.com` y modelo `openai/gpt-oss-120b`.
- `Scriptura/AiRouterTest.java`: verifica éxito Gemini, 429, consentimiento, fallo no recuperable y doble fallo.
- **Precisión de nombre:** el proveedor real es **Groq** (GroqCloud), no **Grok** (xAI). No añadir xAI.

Adaptación integrada en `main` y comprobada por inspección de fuente (no certificación de servicio remoto):
1. `AiFallbackRouter` con secuencia Gemini → Groq **a lo sumo una vez** para cuota, servidor 5xx o timeout. 400/401/403/404, bloqueo, errores de parseo y fallos genéricos de red **no** deben activar un proveedor distinto.
2. `AiFallbackConsent` y control explícito en `AiSetupActivity`: permiso revocable, desactivado de forma predeterminada; reemplazar/eliminar la clave Groq lo revoca.
3. `AiChatActivity` hace uso de la misma ruta para **interpretación de selección**, **preguntas libres** y **argumento del libro completo**; el historial solo se guarda una vez tras éxito. La misma pregunta, fragmentos y contexto van al proveedor elegido.
4. A diferencia de la consulta bíblica de Scriptura que no transmite historial, **el chat actual de BibliotecaAI incluye contexto conversacional reciente**. El consentimiento debe mencionarlo explícitamente; no afirmar que Groq recibe solo el fragmento.
5. Cuando solo existe clave Groq y no Gemini se conserva el modo directo Groq anterior, porque quitarlo supondría perder funcionalidad.
6. Cuando ambos proveedores fallan se muestra error y posibilidad de reintento humano; **no garantizar respuestas si no hay red, cuotas o claves válidas**. No cambiar proveedores infinitamente.

**Selector de modelo ya integrado:** BibliotecaAI incluye `AiModelSettings` y `AiSetupActivity.chooseGeminiModel()`; el identificador inicial `gemini-3.6-flash` y `openai/gpt-oss-20b` constan en la documentación oficial de Google y Groq (consulta 21/11/2026). El control valida sintaxis, no garantiza que la clave del usuario tenga acceso: usar «Guardar y probar» con la clave real.

## 3. Lectura y selección: equivalencias obligatorias

### 3.1 Locators seguros por libro y edición

- Scriptura `AnnotationAnchors.fromSelection` acepta selección exacta en el pasaje actual, con inicio/fin de versículo; `inDisplay` valida coincidencia y evita reubicar una marca en ocurrencia ambigua.
- Biblioteca debe validar **identidad de edición** y **anclaje real** antes de pintar: EPUB CFI/posición de sección; MOBI offset absoluto; PDF página y cajas OCR. Si un archivo cambia y el texto no coincide, conservar la anotación en Conocimiento pero **no pintarla en otro fragmento**.
- Mantener la selección nativa de Android (Copiar, Compartir, Seleccionar todo, Definir, pegar en campos editables), pulsación larga y selección multipalabra. Un toque corto inmóvil sobre marca abre anotación, el scroll diagonal **no cambia página**.
- Si hay superposición, mostrar selector; si hay una única nota escrita, preferir su detalle. Deben ser visibles tanto nota como subrayado y funcionar los menús de editar/eliminar con confirmación y recuperar origen.
- En PDF sin OCR disponible, no presentar un botón que simule haber detectado un texto o subrayado inexistente.

### 3.2 Estructura de pantalla

| Comportamiento Scriptura | Criterio de BibliotecaAI | Estado |
|---|---|---|
| Texto protagonista con acciones compactas; herramientas de segundo nivel plegadas | Menús EPUB/PDF/MOBI compactos; no cubrir área de lectura con barras largas | Parcial |
| `Aa` junto al texto + vista previa y tamaño inmediato | Ajustes de tipografía/espaciado y tema en EPUB y MOBI; en PDF solo zoom/contraste de contenedor, sin alterar PDF | Parcial |
| Modos normal, lectura cómoda, OLED y sistema | Reutilizar paleta por lector; persistir la preferencia por formato, respetar tema Android | Parcial |
| Restaurar scroll en misma lectura; saltos explícitos prevalecen | `bookId + locator + versión` por formato; regreso exacto desde Conocimiento/IA | Parcial |
| Navegar capítulos contiguos y, en la Biblia, siguiente libro del canon | EPUB: índice y capítulos de su EPUB; MOBI: estructura real si existe; PDF: páginas | Implementado parcial; no inventar canon de biblioteca |
| `ReaderSpeech` TTS local y continuidad automática | EPUB y MOBI: botones Escuchar/Detener y narración por contenido; PDF: texto OCR si existe. No narra el PDF escaneado sin texto reconocido | Código integrado, QA auditiva en teléfono pendiente |
| Diálogo o hoja de IA sin alterar el texto ni la posición | Mostrar espera animada y resultado, guardar opcionalmente y retornar al mismo localizador | Parcial |
| Iconos vectoriales y barra con mínimo para letra grande | Accesibilidad, objetivos táctiles >=48dp, contraste y fuentes aumentadas | Parcial; evidencia Samsung necesaria |

### 3.3 Recepción de información

- Scriptura `DefinitionUi` conserva el menú Android, busca varias palabras en Wikipedia/Wikcionario, ofrece fuentes locales donde proceda y separa la explicación IA.
- En Biblioteca, `study.DefinitionUi` y `WordMeaning` ya existen; revisar qué opciones se aplican efectivamente a EPUB nativo, EPUB Readium y MOBI/PDF OCR. Mantener significado de frase y no reducir a una palabra.
- Respuestas IA fundamentadas en **fragmentos efectivamente extraídos**. La instrucción del modelo NO es evidencia por sí sola. La respuesta “No aparece en el libro” puede ir acompañada de contexto externo **claramente distinguido**, nunca atribuido al libro.
- Para consultas de la biblioteca global, no interpolar entre libros sin un índice o corpus recuperado explícitamente: Scriptura verifica las citas de su EPUB local antes de ligar versículos; la biblioteca debe verificar `bookId + fragmento + localizador`.

## 4. Anotaciones, conocimiento y argumento

- Tipos actuales de BibliotecaAI: `NOTE`, `HIGHLIGHT`, `BOOKMARK`; `AnnotationEditor` edita, versiona, envía a papelera sin borrar originales, `SavedAnnotationSheet` abre texto completo. Scriptura distingue `NOTE`, `UNDERLINE`, `AI`, `WEB`, `MAP`, `ATLAS`; **no crear artificialmente mapas/atlas si no existen fuentes geográficas de la obra**.
- **Brecha P1 real:** las interpretaciones de Biblioteca se guardan hoy como `NOTE` con prefijo de proveedor, no como `AI` distinguible; plantear tipo semántico compatible con notas existentes, sin perder ni duplicar históricos. Agregar pruebas de migración antes de desplegar.
- La biblioteca de conocimiento debe poder agrupar por libro, tipo y fecha, mostrar un extracto; seleccionar una anotación abre detalle/edición, y «Ir al origen» salta al localizador comprobado. La exportación ha de contener obra, cita, nota y localizador legible; omitir referencias técnicas en UI de lectura.
- **Argumento por libro ya implementado**: pregunta vacía desde ficha abre síntesis basada en capítulos extraídos; `BookOverview` separa las etiquetas; `BookSynopsisRepository` solo asigna etiquetas si el libro no tiene otras; `BookDetailActivity` permite **leer, copiar y exportar** argumento persistido sin nueva consulta. Añadir fallover sin perder estas transacciones. No guardar un argumento basado en fragmentos insuficientes ni publicar etiquetas sin evidencia.
- Scriptura también guarda historial de preguntas con fuentes; Biblioteca guarda `ai_history/<bookId>/historial.json`, pero debe validar que las fuentes listadas se puedan abrir y que no se hagan públicas/compartan al consultar a otro proveedor sin consentimiento.

## 5. QA obligatoria antes de afirmar paridad

**Bloque A · IA:**
- Gemini responde → 1 petición, nada sale a Groq.
- Gemini 429, 503 y timeout → Groq exactamente una vez **solo con clave y permiso**; preserva pregunta, historia y fragmentos acotados.
- Gemini 400/401/403/404, fallo de parseo o bloqueo de seguridad → error directo, sin envío Groq.
- Groq 429, clave inválida o servidor caído → mensaje de ambos proveedores, ningún bucle.
- Revocar consentimiento mientras Gemini responde → Groq no recibe la petición.
- Cambiar/eliminar clave Groq → respaldo se desactiva y requiere nueva autorización.
- Probar por separado interpretación EPUB/PDF/MOBI, pregunta libre y argumento; el argumento se guarda una vez, con proveedor real, y mantiene etiquetas existentes.
- Verificar con **claves propias en dispositivo**: CI usa mocks y no acredita cuotas reales.

**Bloque B · Lectura y conocimiento por formato (3 × cada caso):**
- Importar original, abrir, avanzar, salir y volver al mismo fragmento.
- Seleccionar 1 palabra, expresión de varias palabras y párrafo; comprobar Copiar/Compartir/Definir/Anotar/Subrayar/IA.
- Crear nota y subrayado, ver color/subrayado correcto, tocar marca, abrir texto completo, editar, eliminar y cancelar; scroll suave/diagonal intacto.
- Varias notas solapadas, componer sin perder selección, acciones de Android disponibles, cambiar orientación/fuentes.
- Conocimiento → Ir al origen, abrir sin Internet, exportar y recuperar backup/ZIP sin perder archivos, locators ni anotaciones.

**Bloque C · Experiencia visual:**
- Comparar capturas de Scriptura y Biblioteca en el mismo Android: lector en primer plano, jerarquía de navegación, modo cómodo/OLED, espaciados y tipografía.
- Fuente a escala 1.3/1.5, pantallas compactas, TalkBack, área de toque 48dp, teclado y Back; barras no tapan contenido.
- Revisar originales EPUB largo, PDF escaneado con OCR y PDF sin OCR, MOBI con y sin índice; no presentar controles ficticios.

**Estado de implantación (21/11/2026):** el respaldo Gemini/Groq y la narración están en `main`. La rama `feature/scriptura-ai-source-parity-retry-20261121` añade numeración unívoca de las fuentes entre ambos proveedores y la UI, la persistencia del listado de fuentes con la sinopsis y la opción de reintentar consultas manuales sin duplicar turnos fallidos. PR #55 (apariencia `Aa`/sistema) y PR #46 (capturas de QA) continúan siendo trabajo independiente. La compilación, pruebas CI, comprobación de claves del usuario y comparación Samsung siguen siendo puertas de aceptación; no declarar «paridad completa» sin ellas.


## 6. Brecha de integridad identificada y corregida en rama de revisión IA

En la implementación previa de BibliotecaAI, `BookTextSource.retrieve()` podía devolver
capítulos no consecutivos (p. ej. 7 y 23), mientras `AiClients.contextBlock()`
los numeraba 1 y 2 para el modelo. La interfaz mostraba 7 y 23,
por lo que una cita **[1]** de Gemini/Groq no se podía conciliar con la lista visible.
Una cita seleccionada añadida al principio desplazaba todavía más los números.

La solución crea `AiSourceReferences` como único contrato de referencias:
1. `[1]`, `[2]`… designan **el orden de evidencia en la petición**, con independencia del identificador interno de capítulo/página;
2. ambas IA reciben exactamente los mismos fragmentos y el mismo orden;
3. las referencias visibles coinciden con los números que puede citar el modelo;
4. la sinopsis persistida incluye su leyenda de fuentes para lectura posterior sin IA;
5. un error no guarda un turno ficticio en el historial; aparece «Reintentar consulta» y el borrador original se restituye.

**Límite explícito:** esto alinea referencias y mejora trazabilidad, pero no certifica que el modelo haya citado realmente todas ellas ni crea hipervínculos verificables al lugar concreto del libro. El salto desde cada cita al fragmento original exige un trabajo adicional de locators por formato. Los errores de proveedor continúan siendo posibles si ambos servicios están indisponibles.
