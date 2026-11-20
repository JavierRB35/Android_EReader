# DEVELOPMENT_STATE — Estado vivo del desarrollo

> **Regla de continuidad:** todo agente debe (1) leer este documento al iniciar, (2) actualizarlo y hacer push al terminar cada sesión o unidad funcional verificable. La continuidad se reconstruye desde este documento + Git, nunca desde conversaciones previas. El «último commit» de una rama es, por definición, el commit que porta este documento.

**Última actualización:** 2026-11-20 (integración del diseño + locators/anotaciones)
**Fase actual:** Incremento 1 COMPLETO · Incremento 2 en curso (lectores por agentes paralelos; diseño integrado y verificado)
**Remoto canónico:** `https://github.com/JavierRB35/Android_EReader.git`

---

## ⚠️ Coordinación multiagente activa (leer antes de tocar nada)

Proyecto organizado en **worktrees paralelos** (uno = un agente = una rama):

| Worktree | Rama | Área | Estado 2026-11-20 |
|---|---|---|---|
| `biblioteca-ux/` | `feature/design-system` | **Diseño — AGENTE ABANDONÓ el proyecto** | Terminó y publicó `cd19d25`. **INTEGRADO en `main` y verificado visualmente por el coordinador.** Sus tareas pendientes las retoma el coordinador (ver «Plan de diseño») |
| `biblioteca-epub/` | `feature/reader-epub` | Motor EPUB (Readium) | Activo; a punto de commitear (`EpubOpeningTest`, EPUB de prueba en `androidTest/assets`). Sin push aún |
| `biblioteca-formats/` | `feature/reader-pdf-mobi` | PDF + MOBI | Activo; commit local `c1b3b29` «isolated PDF viewer and bounded MOBI text reader» + ajustes en curso. Sin push aún |
| `biblioteca-integration/` | `feature/reader-integration` | Integración entre áreas | Activo; commit local `655dbda` (absorbe el trabajo de formats) + cableado de lectura (Manifest, Daos, ReadingRepository, BookDetailActivity) en curso. Sin push aún |
| `biblioteca-auth/` | `feature/google-auth` | Google Auth + Drive | Activo (dependencias y schema v3 en curso). Sin push aún |
| `Androdi_EReader/` | `main` | Coordinador: integra ramas verificadas y fusiona | El commit directo `ce402d8` (locators + anotaciones versionadas) se integró aquí |

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
- **Verificación integrada de `main` (dbc7b19 + merge de `1ee628e`):** BUILD SUCCESSFUL; **23/23 tests JVM** (9 formatos + 6 metadatos + 8 portadas); revisión visual en emulador correcta (biblioteca Material 3: encabezado editorial, chips con acento cálido, ficha tipográfica «SIN PORTADA», contador «1 libros», ★ favorito), datos preservados tras la migración.
- Etiqueta honesta: todo lo anterior es **Verificado localmente (escritorio + emulador x86_64)**. AndroidIDE ARM64 en teléfono sigue **Pendiente**.

## Revisión visual del diseño (tarea del agente de diseño, completada por el coordinador)

El agente de diseño dejó pendiente la «revisión visual real… hasta disponer de dispositivo/emulador». El coordinador la ejecutó en emulador: biblioteca Material 3 verificada conforme a `docs/design-system.md` (fondo OLED, superficies grafito, acento cálido, ficha tipográfica sin imágenes ficticias, jerarquía limpia). Pendiente de esa lista: TalkBack, fuente ampliada, tablet y compilación AndroidIDE.

## Plan de diseño asumido por el coordinador (el agente de diseño abandonó)

1. ✅ **Extractor de portada PDF** (`PdfCoverSource`): primera página vía `android.graphics.pdf.PdfRenderer`, sin dependencias, muestreo máx. 360×540 y límite 20.000 px. **Fix con evidencia de logcat**: PdfRenderer exige ARGB_8888 («Unsupported pixel format» con RGB_565). **Verificado en emulador con 2 PDFs reales** (Edge print-to-pdf y PDF artesanal con xref propio): portadas renderizadas; fallback tipográfico intacto para EPUB sin portada.
2. Portada MOBI: bloqueada hasta que el agente de formats defina el extractor de texto («SIN PORTADA» aceptable de momento).
3. Restyle a tokens de `design.xml`: ✅ `OrganizeActivity` + `activity_organize.xml` (título serif, cabeceras de sección en acento, filas con `bia_text`/`bia_text_secondary` vía recursos). `BookDetailActivity` sigue **BLOQUEADO** (el agente de integración lo modifica en su worktree).
4. Accesibilidad (TalkBack, fuente ampliada) y tablet: tras integrar los lectores.
5. El «leer» del catálogo apuntará al motor cuando el agente de integración cablee las actividades aisladas (`reader/` llegó a `main` sin registro en Manifest — trabajo suyo en curso).

## Problemas abiertos / bloqueos

- Ninguno bloqueante para `main`.
- Los commits `c1b3b29` (formats) y `655dbda` (integration) comparten mensaje pero distinto SHA: el agente de integración ya está absorbiendo el trabajo de formats; al fusionar, revisar duplicación.
- Los agentes activos aún no han hecho push de sus ramas: la integración de los lectores espera a sus commits públicos.

## Siguiente acción concreta

1. ✅ `PdfCoverSource` publicado (`81a1746`); ✅ `feature/reader-pdf-mobi` integrado en `main` (`4c577f0`) — su capa de datos era idéntica a la de `main` (diff vacío verificado); 44/44 tests JVM (incluye 21 de `MobiDocumentTest`).
2. Vigilar pushes de `feature/reader-epub`, `feature/reader-integration`, `feature/google-auth`; integrar en orden: lectores → integración → auth; puntos calientes compartidos: `Daos.kt`, `AppDatabase.kt`, `AndroidManifest.xml`, `BookDetailActivity.kt`.
3. Los lectores integrados (`reader/`) están **aislados** (sin registro en Manifest): el cableado en ficha/catálogo lo completa el agente de integración; el coordinador lo verificará al llegar a `main`.
4. Actualizar este documento en cada integración. AndroidIDE-Rv2 en teléfono sigue pendiente de disponibilidad.
