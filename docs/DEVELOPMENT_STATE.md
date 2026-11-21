# DEVELOPMENT_STATE — Estado vivo del desarrollo

> **Regla de continuidad:** todo agente debe (1) leer este documento al iniciar, (2) actualizarlo y hacer push al terminar cada sesión o unidad funcional verificable. La continuidad se reconstruye desde este documento + Git, nunca desde conversaciones previas. El «último commit» de una rama es, por definición, el commit que porta este documento.

**Última actualización:** 2026-11-21 (biblioteca premium compacta en rama de trabajo; otras tareas conservadas)
**Fase actual:** Núcleo funcional 1.0 operativo sin registro: lectura EPUB/PDF/MOBI, cuaderno, backup/restauración con versiones, revinculación por hash, IA BYOK (Gemini/Groq) con asistente guiado y prueba real. Pendiente: verificación IA con claves del propietario, OCR, accesibilidad, AndroidIDE.
**Remoto canónico:** `https://github.com/JavierRB35/Android_EReader.git`

## REVISIÓN DE HOME PREMIUM — 21/11/2026

- En `feature/compact-reading-premium-library-20261121` se reduce la tarjeta de reanudación a acceso de 56 dp mínimo (miniatura 32×44 dp), se compactan cabecera/controles y tarjetas de la rejilla, y la cuadrícula pasa a ser el modo predeterminado solo para instalaciones sin preferencia previa.
- Material Components 1.12.0 / Material 3 ya está integrado; se reutiliza en lugar de añadir otro framework pesado. No se cambian motores de lectura, IA, copias de seguridad ni navegación fija.
- Prueba focalizada `CompactLibraryHomeDeviceTest`; documentación `docs/ux-premium-compact-20261121.md`. Pendiente compilar en CI e inspeccionar renderizado Android antes de fusionar y publicar. No confundir con APK v0.1.35.

## DIRECTRIZ NUEVA Y ESTADO — 20/11/2026

- **Sin cuentas:** el propietario prohíbe OAuth, registro con Gmail y acceso mediante
  usuario Google/Supabase. [PR #20](https://github.com/JavierRB35/Android_EReader/pull/20)
  cerrada **SIN MERGE**. La pantalla de cuenta se retira de la navegación y el manifest
  en la nueva rama de backup; no reactivar la autenticación heredada.
- **Cambio obligatorio de backup:** formato principal ZIP completo con **todos los archivos
  originales EPUB/PDF/MOBI** más catálogo, subrayados, marcadores, notas/versiones,
  categorías, etiquetas, colecciones, relaciones y posiciones. Selector SAF permite
  elegir Drive, local u otro proveedor SIN API Drive ni credenciales de Biblioteca IA.
- **Desarrollo en curso — NO INTEGRADO:** rama `feature/complete-saf-backup-no-accounts-20261120`
  modifica exportación/restauración ZIP streaming, comprobación SHA-256, staging seguro,
  reubicación de originales y asignación atómica de rutas Room. Importación JSON previa
  queda heredada con advertencia de ausencia de libros.
- **Evidencia automatizada APROBADA en la rama:** [quality gate #44803507753](https://github.com/JavierRB35/Android_EReader/actions/runs/44803507753) SUCCESS (JVM, compilaciones debug/release sin firmar y APK AndroidTest), [emulador #44803508024](https://github.com/JavierRB35/Android_EReader/actions/runs/44803508024) SUCCESS con **16/16 pruebas instrumentadas**, incluido ZIP completo → originales → Room/revinculación y los tests de lectura; [arranque Android #44803507780](https://github.com/JavierRB35/Android_EReader/actions/runs/44803507780) SUCCESS.
- **Evidencia todavía pendiente:** restaurar en teléfono físico (Drive como proveedor SAF y destino local), espacio insuficiente/interrupción, reinstalación/móvil nuevo y marcas visibles en libros importados. No etiquetar la versión 1.0 como completa; [PR #23](https://github.com/JavierRB35/Android_EReader/pull/23) LISTA PARA REVISIÓN, sin merge.
- Otros agentes continúan con anotaciones y lectura en ramas separadas; revisar
  `docs/COORDINATION.md` e Issues #3/#10 antes de integrar.



## Entrega verificada posterior a 71a89c4 — IA de lectura EPUB/MOBI

- ✅ **PR #13 fusionada en `main` (`3d50bb3`, 2026-11-20)** — `ai/BookTextSource.kt`: manifest/spine EPUB toleran orden de atributos y comillas simples/dobles; el ZIP del EPUB se lee con límite efectivo de 2 MB por entrada incluso si el tamaño declarado no es fiable; las rutas no canónicas de rootfile se rechazan; sin texto falso ante fallo de parseo.
- ✅ **MOBI → RAG:** se elimina HTML/scripts de los fragmentos enviados al modelo y se aplican límites antes de recuperar contexto.
- ✅ **Pruebas JVM nuevas (5)** en `ai/BookTextSourceTest.kt`: spine/atributos, entrada inflada, ruta de rootfile, anti-spoilers y texto MOBI; **CI del head `a9a31d0` verde** [Actions 44780253575](https://github.com/JavierRB35/Android_EReader/actions/runs/44780253575).
- ⚠️ **No confundir con paridad 1.0 completada**: PDF sigue sin selección de capa textual/OCR; las acciones de EPUB/MOBI requieren aceptación E2E en emulador/teléfono tras cambiar fuente, reabrir y restaurar. No se han verificado claves de usuario ni firmado APK definitivo.
- **Siguiente hito P0 recomendado:** texto seleccionable y anclado en PDF (capa digital/OCR local), repintado de subrayados + entrada contextual a IA, con pruebas E2E de #8 y sin publicar release.


---

## ⚠️ Coordinación multiagente activa (leer antes de tocar nada)

Proyecto organizado en **worktrees paralelos** (uno = un agente = una rama):

| Worktree | Rama | Área | Estado 2026-11-20 |
|---|---|---|---|
| `biblioteca-ux/` | `feature/design-system` | **Diseño — agente abandonó; trabajo INTEGRADO** (`cd19d25` + restyle organizador + PdfCoverSource por el canal ZCode) | Cerrado |
| `biblioteca-epub/` | `feature/reader-epub` | Motor EPUB | **ADOPTADO** → `integration/epub-adopted` → main (`789c163`): Readium 3.0.3, EpubOpener/Safety/Reader, 6 tests seguridad + test instrumentado VERDE en emulador |
| `biblioteca-formats/` | `feature/reader-pdf-mobi` | PDF + MOBI | **INTEGRADO** (`4c577f0`) + refinamiento PalmDOC **ADOPTADO** (`2ea9980`) |
| `biblioteca-integration/` | `feature/reader-integration` | Wiring (chat Codex) | **ADOPTADO** → `integration/wiring-adopted` → main (`3ab0159`): ficha→«Abrir lector»→ReaderRouter, navegación 3 espacios, KnowledgeActivity, BoundedZipInputStream; **verificado en emulador leyendo el Quijote real con Readium** |
| `biblioteca-auth/` | `feature/google-auth` | Google Auth | **ADOPTADO** → `integration/auth-adopted` → main (`35120b3`): auth/ (Credential Manager, SecureSessionStore), AccountActivity registrada, guardia de clave pública anón en build.gradle, docs/setup/google-cloud.md; 58/58 tests |
| `Androdi_EReader/` | `main` | Coordinador canal ZCode | Integra, verifica y publica; adopciones anunciadas en COORDINATION.md |

**Reglas:** 1 worktree = 1 agente = 1 rama; no tocar el worktree ajeno ni cambiar su rama; no duplicar investigaciones; push frecuentes en la propia rama; fusiones a `main` solo de trabajos verificados y coordinados. El push a `main` puede chocar con otros coordinadores: `git fetch` + merge antes de push.

---

## Estado del proyecto

- **Incremento 0 — Auditoría y base: COMPLETADO** (en `main`).
- **Incremento 1 — Biblioteca local: COMPLETADO** (importación real EPUB/PDF/MOBI, catálogo con búsqueda/estantes/cuadrícula, organizador, ficha, favoritos, Room con migraciones explícitas 1→2).
- **Incremento 2 (lectura) en curso:**
  - ✅ **Sistema de diseño Material 3 INTEGRADO en `main`** (`cd19d25`): tema oscuro con tokens en `res/values/design.xml`, biblioteca editorial con portadas EPUB reales (EPUB2 `meta name=cover` / EPUB3 `cover-image`) con extracción acotada y segura (anti-traversal, límites de tamaño, sin red), cuadrícula adaptativa 2–6 columnas, estados vacíos diferenciados, importación anti doble-toque. 8 tests focalizados de portadas.
  - ✅ **Locators de lectura y anotaciones versionadas INTEGRADOS en `main`** (`ce402d8`): tablas `reading_positions`, `annotations`, `annotation_versions` con **migración 2→3 no destructiva verificada sobre datos reales** en emulador (solo `CREATE TABLE IF NOT EXISTS`; el libro y sus datos sobrevivieron). El esquema v3 (`app/schemas/.../3.json`) queda ahora commiteado por el coordinador (omisión del autor original).
  - ⏳ Motores de lectura EPUB/PDF/MOBI: en desarrollo por los agentes de sus ramas (ver tabla).
  - ⏳ Google Auth + Drive: en desarrollo (rama `feature/google-auth`).
- **Verificación integrada final (main @ adopción auth):** BUILD SUCCESSFUL; **58/58 tests JVM** (9 formatos + 6 metadatos + 8 portadas + 3 límites import + 21 MobiDocument + 6 EpubSafety + 5 AuthPolicy) + **1/1 test instrumentado** (EpubOpeningTest: Readium abre EPUB real con búsqueda de localizadores estables). Emulador: catálogo→ficha→**«Abrir lector»→Readium leyendo el Quijote real** (Capítulo 1 renderizado, OLED, progreso 0%, marcador), navegación 3 espacios con Conocimiento operativo, sin crashes.
- Etiqueta honesta: **Verificado localmente (escritorio + emulador x86_64)**. Sin verificación con servicio: Google Auth real (requiere config del propietario). AndroidIDE ARM64 en teléfono: **Pendiente**.

## Revisión visual del diseño (tarea del agente de diseño, completada por el coordinador)

El agente de diseño dejó pendiente la «revisión visual real… hasta disponer de dispositivo/emulador». El coordinador la ejecutó en emulador: biblioteca Material 3 verificada conforme a `docs/design-system.md` (fondo OLED, superficies grafito, acento cálido, ficha tipográfica sin imágenes ficticias, jerarquía limpia). Pendiente de esa lista: TalkBack, fuente ampliada, tablet y compilación AndroidIDE.

## Plan de diseño asumido por el coordinador (el agente de diseño abandonó)

1. ✅ **Extractor de portada PDF** (`PdfCoverSource`): primera página vía `android.graphics.pdf.PdfRenderer`, sin dependencias, muestreo máx. 360×540 y límite 20.000 px. **Fix con evidencia de logcat**: PdfRenderer exige ARGB_8888 («Unsupported pixel format» con RGB_565). **Verificado en emulador con 2 PDFs reales** (Edge print-to-pdf y PDF artesanal con xref propio): portadas renderizadas; fallback tipográfico intacto para EPUB sin portada.
2. Portada MOBI: bloqueada hasta que el agente de formats defina el extractor de texto («SIN PORTADA» aceptable de momento).
3. Restyle a tokens de `design.xml`: ✅ `OrganizeActivity` + `activity_organize.xml` (título serif, cabeceras de sección en acento, filas con `bia_text`/`bia_text_secondary` vía recursos). `BookDetailActivity` sigue **BLOQUEADO** (el agente de integración lo modifica en su worktree).
4. Accesibilidad (TalkBack, fuente ampliada) y tablet: tras integrar los lectores.
5. El «leer» del catálogo apuntará al motor cuando el agente de integración cablee las actividades aisladas (`reader/` llegó a `main` sin registro en Manifest — trabajo suyo en curso).

## Distribución (Incremento 10 adelantado)

✅ **Workflow de releases firmados integrado** (`d41d5eb`, revisado): `.github/workflows/release-apk.yml` — solo se activa con tags `vX.Y.Z` que sean ancestros de `main` y coincidan con `versionName`; build en 2 jobs con privilegios mínimos; firma vía secretos (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); zipalign + apksigner verify + checksum SHA256; idempotente. **Prerrequisitos pendientes (acción exclusiva del propietario):** crear el keystore de release (nunca en repo) y configurar los 4 secretos en GitHub — **verificado con `gh secret list` (2026-11-20): aún sin configurar**; sin ellos, un push de tag `v*` falla de forma segura en la firma sin publicar nada. **No etiquetar versiones hasta que los incrementos estén revisados.**

## Estado funcional hacia la 1.0 (sesión de finalización)

**Integrado y verificado en `main` (68/68 tests JVM + instrumentado + emulador):**

| Incremento | Contenido | Estado |
|---|---|---|
| 1 — Biblioteca local | Catálogo Material 3 (búsqueda, estantes, grid/lista, portadas EPUB/PDF), organizador (categorías/etiquetas/colecciones), ficha editable, favoritos | ✅ COMPLETO |
| 2 — Lectura | EPUB vía Readium 3.0.3 (offline, sanitizado), PDF con render en proceso aislado, MOBI acotado con rechazo de DRM/PalmDOC; posiciones y progreso persistentes; marcadores y anotaciones desde el lector (versionadas) | ✅ NÚCLEO COMPLETO |
| 4 — Conocimiento | Cuaderno global (KnowledgeActivity): lista, búsqueda, nueva nota, editar con historial de versiones, papelera/restaurar, abrir origen, exportar a Markdown | ✅ COMPLETO |
| 9 — Protección (núcleo) | BackupCodec JSON con 6 tests, restauración **aditiva** por hash con recuento honesto, posiciones solo si más recientes; exportar/restaurar vía SAF desde Ajustes | ✅ NÚCLEO (sin copia de archivos originales ni copias periódicas) |
| 10 — Distribución | Workflow releases firmados + docs de firma + comprobador de actualizaciones (GitHub Releases público, instalación manual) | ✅ PARCIAL (falta: keystore/secretos verificados en un tag real, firma de producción) |
| §18.4 Ajustes | Pantalla Ajustes con diagnóstico real de servicios, acceso a cuenta, acerca de | ✅ COMPLETO |

**Bloqueados por credenciales/servicios del propietario (adaptadores no verificables sin ellos):**

| Incremento | Bloqueo | Acción del propietario |
|---|---|---|
| 5-6 — Sync + multiusuario | **REVISIÓN DEL PROPIETARIO INTEGRADA**: sus 3 hallazgos corregidos vía PR #5 (fusionado `929b08b`, repo alineado con el esquema endurecido `initial_schema_hardened` YA DESPLEGADO: append-only real en versiones, `user_id default auth.uid()`, FKs compuestas de tenant en 10 relaciones, API solo autenticada). **Bug de pérdida corregido** (`c2884ac`): el motor JAMÁS descarta la cola por errores HTTP — fallo registrado en la entrada (intentos+error), aislamiento fila a fila cuando un lote falla, 4xx permanente se conserva para revisión. 77/77 tests. Pendiente: propietario aplica claves en `local.properties` + OAuth Google (punto B) para pruebas con sesión real |
| 5 — Google Auth real | Google Cloud sin configurar (OAuth) | Seguir `docs/setup/google-cloud.md` (proyecto, OAuth, SHA-1 de firma, Web Client ID) y proveedor Google en Supabase |
| 7-8 — IA (Gemini/Groq) | ~~Sin claves~~ **IMPLEMENTADO (BYOK)**: AiKeyStore cifrado (Keystore AES-GCM), Gemini + Groq con errores diferenciados, asistente guiado con PRUEBA REAL, chat con RAG por capítulos, referencias [n], anti-spoilers por progreso, historial JSON. **Pendiente:** verificación con claves reales del propietario (asistente→Guardar y probar) y análisis de imágenes |
| 3 — OCR | PDFs escaneados de prueba | ML Kit (evaluar tamaño en AndroidIDE) |

**Recuperación de originales (ADR-008):** OriginalLinker vincula por SHA-256 (nunca por nombre) con copia local para offline; la ficha detecta archivo ausente y ofrece «Vincular archivo original…» vía SAF (Drive o cualquier origen); sin duplicar fichas. 4 tests de revinculación. Backup incluye ahora **versiones de anotaciones** (export+restore).

**Pendientes técnicas propias del código:** OCR de PDFs escaneados (ML Kit — evaluación de tamaño en AndroidIDE pendiente), accesibilidad (TalkBack, fuente ampliada), tablet, portada MOBI, copia de archivos originales dentro del backup (hoy: metadatos + guía de revinculación), copias periódicas.

**Verificación obligatoria pendiente en el teléfono (propietario):** `sh ./build-on-phone.sh` en AndroidIDE-Rv2. Nota operativa: Readium aumenta la memoria de la fusión dex (en escritorio se resolvió con `-Dorg.gradle.jvmargs=-Xmx3g` por línea de comandos); si el teléfono se queda sin memoria, evaluar JAVA_OPTS en `build-on-phone.sh` con precaución (la prohibición de `-Xmx2048m` en `gradle.properties` sigue vigente).

## Problemas abiertos / bloqueos

- Ninguno bloqueante para `main`.
- Los commits `c1b3b29` (formats) y `655dbda` (integration) comparten mensaje pero distinto SHA: el agente de integración ya está absorbiendo el trabajo de formats; al fusionar, revisar duplicación.
- Los agentes activos aún no han hecho push de sus ramas: la integración de los lectores espera a sus commits públicos.

## Siguiente acción concreta

1. ✅ `PdfCoverSource` publicado (`81a1746`); ✅ `feature/reader-pdf-mobi` integrado (`4c577f0`); ✅ restyle del organizador publicado (`5fbd07c`); ✅ checkpoint de `feature/reader-integration` integrado (`70484ee`) con su `docs/COORDINATION.md` y **reconciliación de coordinación publicada** (identificación del canal ZCode, corrección del estado real de main, protocolo de anuncios y fusiones). 44/44 tests tras cada merge.
2. Vigilar pushes de `feature/reader-epub` (EPUB staged: EpubOpener/EpubReaderActivity/EpubSafety + test instrumentado), `feature/reader-integration` (wiring: ReaderRouter, KnowledgeActivity, navegación 3 espacios §9.1, endurecimiento de importación con BoundedZipInputStream), `feature/google-auth` (10 ficheros en curso). **Conflicto anticipado ya analizado:** su wiring edita MainActivity/activity_main desde base pre-diseño → al integrar, aplicar su cableado funcional sobre las versiones de diseño de main. Puntos calientes: `Daos.kt`, `AppDatabase.kt`, `AndroidManifest.xml`, `BookDetailActivity.kt`.
3. Los lectores integrados (`reader/`) están **aislados** (sin registro en Manifest): el cableado en ficha/catálogo lo completa el agente de integración; el coordinador lo verificará al llegar a `main`.
4. Actualizar este documento en cada integración. AndroidIDE-Rv2 en teléfono sigue pendiente de disponibilidad.


## Nueva funcionalidad en revisión — instalación sin navegar a GitHub (20/11/2026)

- **Petición del propietario:** reemplazar los botones que llevaban a GitHub por
  **«Instalar actualización»** y descarga en la propia Biblioteca IA, como Scriptura.
- [PR #30](https://github.com/JavierRB35/Android_EReader/pull/30), rama
  `feature/in-app-verified-apk-installer-20261120`: descarga y comprueba la APK
  firmada y el fichero SHA-256, paquetes y certificado, y abre instalador nativo
  de Android con autorización del usuario; FileProvider restringido a caché
  `updates/`, notificaciones ya no llevan a GitHub. Sin cuentas ni OAuth.
- **En revisión, NO integrada a main.** CI Android y tests de URL/hash/FileProvider
  pendientes. Prueba física necesaria de descargas, permiso instalar desde esta
  fuente, confirmar/rechazar instalación y preservar libros/notas. No declarar
  instalación silenciosa ni QA física realizada.

## Ficha compacta de libro y organización desde la propia pantalla (PR #25 · 20/11/2026)

- Rama: `feature/compact-book-detail-inline-organize-20261120`; PR [#25](https://github.com/JavierRB35/Android_EReader/pull/25). Basada en `2ef2041`; los cambios de backup ZIP SAF de PR #23, integrados durante el desarrollo, se conservan al fusionar.
- Problema de UX constatado en las capturas Samsung del propietario: campos presentados en filas verticales y segunda tanda de botones repetidos, obligando a desplazarse para llegar a «Editar datos», categorías, etiquetas, colecciones y estado.
- Nueva distribución OLED en `activity_book_detail.xml`: título y autor acotados; formato, tamaño y fecha en una franja; estado/favorito compactos; tres filas de organización seleccionables; «Abrir lector», «Consultar IA» y «Editar datos» visibles en la zona inferior. La pantalla normal **no requiere scroll**; el `ScrollView` solo es reserva para pantallas muy pequeñas o fuentes de accesibilidad aumentadas.
- `BookOrganizationEditor.kt`: diálogo desde la propia ficha para marcar o desmarcar elementos existentes o crear+asignar un elemento nuevo. Categoría raíz, etiqueta y colección se insertan y enlazan al libro en **una transacción Room**; los nombres iguales sin distinguir mayúsculas se reutilizan, desasignar no elimina elementos del catálogo y no altera otros libros. Sin cambios de esquema ni backup.
- Al tocar el estado se abre selector explícito de estado; favorito, IA, edición de metadatos, apertura de lector y revinculación de original por hash se conservan.
- Evidencia: [Android UI instrumentado #44805098468](https://github.com/JavierRB35/Android_EReader/actions/runs/44805098468) **SUCCESS, 2/2**, probado con viewport reducido a `900×1900 px` y sin desplazamiento vertical; [quality gate #44805098643](https://github.com/JavierRB35/Android_EReader/actions/runs/44805098643) **SUCCESS**; [OCR/lector emulador #44805098464](https://github.com/JavierRB35/Android_EReader/actions/runs/44805098464) **SUCCESS**. Smoke de arranque separado en curso al documentar.
- Limitación: no se ha verificado el diseño aún en el Samsung físico ni a escalas de fuente extremas. No distribuir APK ni afirmar aceptación de dispositivo hasta confirmar.
