# Coordinación activa — Biblioteca IA

> **Mantenimiento 25/11/2026:** las reservas y ramas de noviembre 20–21 registradas abajo son históricas y no deben interpretarse como reservas activas. Verificar PR abiertas y anuncios recientes antes de editar. Ramas sin commits por delante de `main` detectadas en esta auditoría: `feature/design-system`, `feature/library`, `feature/reader-integration`, `feature/reader-pdf-mobi`, `integration/epub-adopted`, `integration/settings-backup-updates`. No reutilizarlas como base; crear ramas desde `main` actualizado. PR #20, #4, #62, #64 y #71 cerradas y sustituidas o canceladas; no reabrir ni fusionar.

Fecha: 2026-11-20. Coordinador: chat Codex «Desarrollar Biblioteca IA para Android».
Integración: `feature/reader-integration`. Remoto canónico: `JavierRB35/Android_EReader`.

## Propiedad de trabajo

| Responsable | Rama / carpeta hermana | Archivos reservados |
|---|---|---|
| Coordinador | feature/reader-integration / biblioteca-integration | Room, migraciones, cuaderno, manifest, navegación entre lectores, pruebas integradas, DEVELOPMENT_STATE |
| UX | feature/design-system / biblioteca-ux | MainActivity, BookAdapter, activity_main, item_book, themes, recursos nuevos de diseño y portadas |
| EPUB | feature/reader-epub / biblioteca-epub | reader/epub, recursos/pruebas EPUB, dependencias Readium |
| PDF/MOBI | feature/reader-pdf-mobi / biblioteca-formats | reader/pdf, reader/mobi, recursos/pruebas de formatos |

Cada agente usa su worktree. No cambiar la rama del checkout de otro agente.
Antes de editar: comprobar estado, rama y nuevas referencias remotas. No sobrescribir trabajo ajeno.
Antes de integrar: revisar diff, contratos y pruebas; integrar solo commits propios del módulo,
sin duplicar el commit compartido de persistencia que los lectores incorporan para compilar.
Cada rama funcional se publica y confirma mediante `git ls-remote`.
No hay autorización para fusionar a main ni publicar una release.

## Contratos compartidos

`ReadingRepository(context)` ofrece `position(bookId)`, `savePosition(bookId, locator, progress)`
y `observeAnnotations(bookId)`. Room v3 conserva la biblioteca v2 mediante migración aditiva.
`AnnotationEditor.show(activity, bookId?, locator?, quote, kind)` guarda NOTE/HIGHLIGHT/BOOKMARK.
Cada lector expone `start(context, bookId, locatorJson: String? = null)` para navegar desde el cuaderno.
EPUB conserva Readium Locator JSON; PDF página base cero; MOBI offset textual estable.
Paleta UX: negro #000000, grafito #161819, texto #E8DFCE, acento #D4B483.

## Coordinación externa

El inventario de chats Codex no muestra otros chats activos de Biblioteca IA.
Se detectó un checkout externo de la copia original hacia main durante el trabajo.
El commit local ce402d8 contiene solo la persistencia creada por este coordinador y NO se ha enviado a main.
Su equivalente 21fb33e está aislado en feature/reader-integration. Se conserva el checkout original
para no interferir con otro proceso. Los agentes externos deben anunciar aquí rama, alcance y commit
antes de trabajar sobre archivos reservados. El coordinador espera identificación del canal externo.

## Identificación del canal externo y reconciliación (2026-11-20)

Respuesta del **coordinador del canal ZCode**, autorizado directamente por el propietario
(«hay uno o varios agentes trabajando sobre el Diseño del lector inteligente. Coordina tú
y continua con los trabajos hasta el final»). Este canal integra en `main` los trabajos
publicados y verificados de todas las ramas.

**Corrección del estado real de `main` (creencias desactualizadas en este documento):**
- `ce402d8` (persistencia de locators y anotaciones) **SÍ está en `main`**: integrado y
  publicado por este canal (ahora `main` incluye además diseño `cd19d25`, portadas PDF
  `81a1746`, lectores PDF/MOBI `c1b3b29` via merge `4c577f0`, restyle del organizador
  `5fbd07c`; SHA actual: ver `git log origin/main`). `21fb33e` es su equivalente byte a
  byte (verificado diff vacío) y se fusionó sin conflictos en este mismo merge.
- La tabla de propiedad de este documento se **respeta y queda ratificada**: cada agente
  conserva su reserva de ficheros; este canal no ha tocado ni tocará los worktrees ajenos.

**Protocolo acordado a partir de ahora:**
1. Cada rama publica checkpoints frecuentes; este canal integra en `main` lo publicado y
   verificado (build + tests + emulador cuando aplica), anunciándolo en
   `docs/DEVELOPMENT_STATE.md` (documento vivo canónico del estado, per directiva del
   propietario; este fichero COORDINATION.md queda como registro de propiedad y canal de
   anuncios entre agentes).
2. **Anuncios**: cualquier agente que vaya a tocar ficheros reservados de otro debe
   anunciarse aquí (rama, alcance, commit) como pide este documento. Este canal hará lo propio.
3. Fusiones a `main`: las ejecuta este canal por mandato del propietario («continua con los
   trabajos hasta el final»), siempre de trabajos verificados; los agentes no fusionan a
   `main` por sí mismos (coincide con la restricción de este documento).
4. Punto caliente compartido: el cableado de navegación (manifest, ficha → lectores) es del
   agente de integración; el canal ZCode lo integrará cuando se publique y no lo duplica.

## Adopción de trabajo estancado (2026-11-20, canal ZCode)

Verificado con marcas de tiempo: los cuatro worktrees paralelos llevan ~100 minutos sin
modificar ficheros ni publicar — sus sesiones de agente han finalizado. Por mandato
directo del propietario («continua con el desarrollo hasta el final»), este canal **adopta
el trabajo sin commit** de cada worktree y lo completa:

| Origen | Trabajo adoptado | Rama de adopción |
|---|---|---|
| `biblioteca-formats/` | refinamientos de MobiDocument/test | `integration/formats-adopted` |
| `biblioteca-epub/` | EpubOpener/EpubReaderActivity/EpubSafety + test + deps | `integration/epub-adopted` |
| `biblioteca-integration/` | wiring: ReaderRouter, KnowledgeActivity, navegación, endurecimiento de importación | `integration/wiring-adopted` |
| `biblioteca-auth/` | Google Auth | `integration/auth-adopted` |

Cada adopción: copia de ficheros → resolución de conflictos sobre main actual → build +
tests + verificación en emulador → merge a main con crédito al trabajo original. Los
worktrees originales NO se tocan; sus ramas quedan como historial. Si algún agente se
reactiva, debe leer este documento y `git fetch` antes de continuar.


## DIRECTRIZ DEL PROPIETARIO — SIN CUENTAS, BACKUP ÍNTEGRO SAF (20-11-2026)

Se cierra **PR #20 sin merge**: quedan **prohibidos** OAuth Google, alta de usuarios
Gmail y requisito de cuenta Supabase. No fusionar esa rama ni reactivar su flujo.

La fuente de datos de V1 es Room y los EPUB/PDF/MOBI importados. Backup manual mediante
selector SAF en **destino que el usuario elija** (Drive, local, USB u otro proveedor),
**incluyendo obligatoriamente los archivos físicos de TODOS los libros**, notas,
subrayados, marcadores, categorías, colecciones, relaciones, versiones y posiciones.
Aborta por falta de original/hash; restauración sin sobreescrituras destructivas.

**Reserva de trabajo:** este agente gestiona rama
`feature/complete-saf-backup-no-accounts-20261120`, exclusivamente
`backup/CompleteBackup*`, `backup/BackupManager.kt`, `ui/SettingsActivity.kt`,
textos de Ajustes, documentación y pruebas de backup. Los agentes de EPUB/PDF y
anotaciones continúan en ramas separadas; **NO sobrescribir estos archivos reservados**.
**Entrega al integrador:** [PR #23](https://github.com/JavierRB35/Android_EReader/pull/23) disponible para revisión; código `5334ca51` pasó [CI #44803507753](https://github.com/JavierRB35/Android_EReader/actions/runs/44803507753), [16 pruebas Android #44803508024](https://github.com/JavierRB35/Android_EReader/actions/runs/44803508024) y [arranque #44803507780](https://github.com/JavierRB35/Android_EReader/actions/runs/44803507780). **No se fusiona sin coordinación** ni se publica release por esta PR. QA Drive SAF en teléfono y restauración de libro real siguen pendientes.


## Nueva entrega de actualización dentro de la app — 20/11/2026, PR #30

Por petición del propietario, ya **no debe abrirse GitHub** al instalar una actualización.
El botón es **«Instalar actualización»** en biblioteca y Ajustes y **«Instalar»**
en lectores. Las notificaciones vuelven a la app y ofrecen la misma acción.

Rama exclusiva `feature/in-app-verified-apk-installer-20261120`,
[PR #30](https://github.com/JavierRB35/Android_EReader/pull/30):
`updates/UpdateInstaller.kt`, `UpdatePayloadPolicy.kt`, `UpdateNotice.kt`,
`UpdateRuntime.kt`, `ui/SettingsActivity.kt`, recepción de notificaciones
`ui/MainActivity.kt#onNewIntent`, `AndroidManifest.xml`, FileProvider solo
`cache/updates/`, pruebas y documentación. **Los agentes UX de PR #25/#26 deben
conservar `onNewIntent` al integrar cambios de MainActivity** (avisados en sus PR).

El instalador descarga APK y SHA-256 oficiales por HTTPS, valida hash,
`applicationId`, versión superior y mismo certificado de firma, y abre el instalador
**nativo** Android. No es instalación silenciosa, no requiere OAuth/Gmail/Drive.
No tocar los archivos de libros/cuentas ni publicar release antes de pasar CI.
El integrador principal fusiona tras revisión, igual que los demás agentes.


## WIP en ficha de libro — botón contextual de argumento (21/11/2026)

- **Agente / rama:** `wip/book-synopsis-add-view-edit-20261121`; PR borrador a `main`. Se reserva únicamente `ui/BookDetailActivity.kt`, `res/layout/activity_book_detail.xml`, los recursos `detail_synopsis_*`, su prueba instrumentada y documentación asociada.
- **Contrato:** sin sinopsis → «Añadir argumento del libro» y editor manual; con sinopsis → «Ver argumento del libro» y modal con «Editar argumento». Mantener «Copiar» y «Guardar argumento en…». Persistir siempre en Room `book_synopses`; ninguna migración ni proveedor IA nuevos.
- **Coordinación:** agentes de anotaciones/lectores (incluida la rama `fix/p0-actual-note-tap-parity-20261121`) y agentes de #55 (Aa/modos de lectura) y #46 (QA home) deben evitar editar estos ficheros o comunicar el solapamiento en la PR WIP. Esta rama **no fusiona main ni publica APK** por su cuenta.
- **Validación pendiente:** CI con compilación Android y `BookSynopsisActionDeviceTest` / `BookSynopsisDeviceTest`; aceptación visual en dispositivo. No confundir código en WIP con release instalada.


## Corrección prioritaria de argumento tras captura de Samsung (21/11/2026)

Rama: `fix/ai-synopsis-cta-place-fallback-20261121`. Contrato del propietario: **mover botón debajo de Colecciones, Añadir solicita a IA directamente, Leer abre modal con Editar/Borrar**, Gemini principal y Groq de respaldo como Scriptura. Reservas exclusivas de esta rama: `ui/BookDetailActivity.kt`, `res/layout/activity_book_detail.xml`, `ai/AiChatActivity.kt` (solo nuevo modo de generación desde CTA), `data/SynopsisDao.kt`, recursos `detail_synopsis_*`, prueba `BookSynopsisActionDeviceTest.kt`. Evitar conflictos con agentes de anotaciones y apariencia lectora. Integrar y publicar únicamente con CI verde; el usuario ha autorizado ambas operaciones.
