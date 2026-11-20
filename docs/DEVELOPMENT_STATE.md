# DEVELOPMENT_STATE — Estado vivo del desarrollo

> **Regla de continuidad:** todo agente debe (1) leer este documento al iniciar, (2) actualizarlo y hacer push al terminar cada sesión o unidad funcional verificable. La continuidad se reconstruye desde este documento + Git, nunca desde conversaciones previas. El «último commit» de una rama es, por definición, el commit que porta este documento.

**Última actualización:** 2026-11-20 (coordinación multiagente)
**Fase actual:** Incremento 1 — Biblioteca local: **COMPLETADO y fusionado a `main`** · Incremento 2 en curso por agentes paralelos
**Remoto canónico:** `https://github.com/JavierRB35/Android_EReader.git` (renombrado desde `Androdi_EReader`; usar la URL canónica)

---

## ⚠️ Coordinación multiagente activa (leer antes de tocar nada)

El proyecto está organizado en **worktrees paralelos** (uno = un agente = una rama), todos creados desde `main` @ `b2e95df`:

| Worktree (raíz compartida del proyecto) | Rama | Área asignada | Estado observado 2026-11-20 |
|---|---|---|---|
| `Androdi_EReader/` | `main` (el agente coordinador/bootstrap trabajó aquí) | Integración y coordinación; trabajos terminados se fusionan aquí | Libre; **contiene ficheros sin commit ajenos** (ver nota) |
| `biblioteca-epub/` | `feature/reader-epub` | Motor EPUB (Readium; verificar compatibilidad AGP 8.7.3/Gradle 8.9/SDK 34/Kotlin 2.0.21 antes de fijar versión) | `app/build.gradle` modificado (en curso) |
| `biblioteca-formats/` | `feature/reader-pdf-mobi` | PDF + MOBI + OCR | paquete `reader/` nuevo (en curso) |
| `biblioteca-ux/` | `feature/design-system` | Sistema de diseño/portadas | layouts + `BookCoverLoader.kt`, `EpubCoverSource.kt` (en curso) |
| `biblioteca-integration/` | `feature/reader-integration` | Integración entre áreas | Creado recién; limpio |

**Reglas de coordinación:**
1. Cada agente trabaja SOLO en su worktree/rama. Nunca cambiar de rama ni ejecutar operaciones git en el worktree de otro.
2. No duplicar investigaciones (p. ej. la compatibilidad de Readium es del agente EPUB).
3. **Nota crítica:** en el worktree `Androdi_EReader/` hay cambios SIN COMMIT que no son del coordinador (`data/ReadingDao.kt`, `data/ReadingModels.kt`, `AppDatabase.kt` modificado): pertenecen presumiblemente al agente de integración. **No borrar ni commitear**: si el agente los reclama, migrarlos; si el propietario confirma que son huérfanos, eliminar con autorización.
4. Al terminar cada unidad funcional: push en la rama propia + actualizar este documento (desde el propio worktree, no desde el de otro).
5. Fusión a `main`: solo incrementos verificados y sin conflictos con otras ramas; coordinar antes de fusionar si varias ramas tocan los mismos ficheros.

---

## Estado del proyecto

- **Incremento 0 — Auditoría y base: COMPLETADO** (en `main`).
- **Incremento 1 — Biblioteca local: COMPLETADO** (rebanadas 1 y 2; `feature/library` fusionada a `main` tras verificación).
- Biblioteca utilizable offline: importación real (EPUB/PDF/MOBI), catálogo con búsqueda y estantes, categorías/etiquetas/colecciones, ficha editable básica, favoritos, esquema Room v2 con migración explícita.

## Funcionalidades implementadas (Incremento 1)

| Elemento | Estado |
|---|---|
| Room v2 con migración explícita 1→2 (`favorite INTEGER NOT NULL DEFAULT 0`); esquemas versionados en `app/schemas/` | **Implementado** |
| Importación real vía SAF: hash SHA-256 en streaming, detección por contenido (mimetype EPUB / `%PDF-` / `BOOKMOBI`), duplicados por hash, metadatos OPF/PDB | **Implementado** |
| Catálogo: búsqueda (título/autor/categoría/etiqueta/colección con JOINs), estantes (Todos/Favoritos/Pendientes/En lectura/Terminados/Abandonados), cuadrícula/lista persistente | **Implementado** |
| Organizador: categorías con subcategorías (crear/renombrar/mover/eliminar sin borrar libros), etiquetas, colecciones (crear/renombrar/eliminar/abrir) | **Implementado** |
| Ficha: favorito ortogonal, edición manual título/autor, asignaciones N:M (categorías/etiquetas/colecciones), ciclo de estado | **Implementado** |
| Semilla de 17 categorías iniciales (§7.3) | **Implementado** |

## Evidencias de verificación

| Verificación | Resultado | Etiqueta honesta |
|---|---|---|
| `gradlew :app:assembleDebug :app:testDebugUnitTest` (JDK 17) | **BUILD SUCCESSFUL**, 15/15 tests JVM | **Verificado localmente** |
| **Migración v1→v2 en real**: `install -r` sobre instalación con datos; app abre sin crash y el libro/estado sobreviven | Correcto | **Verificado localmente (emulador)** |
| Estante «En lectura» filtra; estante «Favoritos» muestra el libro con ★ en catálogo | Correcto | **Verificado localmente (emulador)** |
| Organizador: 17 categorías visibles; creación de etiqueta «Clasicos» y colección «Verano 2026» | Correcto | **Verificado localmente (emulador)** |
| Ficha: Categorías=Novela, Etiquetas=Clasicos, Colecciones=Verano 2026, favorito ortogonal (botón «Quitar de favoritos») | Correcto | **Verificado localmente (emulador)** |
| Búsqueda «Clasicos» localiza por **etiqueta** (JOIN) y combina con estante Favoritos; cuadrícula 2 columnas | Correcto | **Verificado localmente (emulador)** |
| Importación/duplicados (rebanada 1): evidencias anteriores siguen vigentes | Correcto | **Verificado localmente (emulador)** |
| Compilación en AndroidIDE-Rv2 (teléfono ARM64) | — | **Pendiente** (`sh ./build-on-phone.sh`) |

**Bugs capturados y corregidos durante el incremento:**
1. Xerces cierra el stream recibido en `DocumentBuilder.parse` → al parsear desde un `ZipInputStream` compartido, el recorrido ZIP moría («Stream closed»). Fix + test de regresión (rebanada 1).
2. TextViews creados por código heredaban `textColorPrimary` del modo día (negro) → invisibles sobre fondo OLED. Fix: colores explícitos en `OrganizeActivity` (rebanada 2).

## Limitaciones conocidas (declaradas)

- No hay lectura de contenido aún (EPUB/PDF/MOBI abren solo en ficha): Incremento 2.
- MOBI soporta importación/validación/metadatos mínimos (nombre PDB); la conversión/lectura completa es del Incremento 3. No se ha probado con un MOBI real todavía.
- Búsqueda de texto completo dentro del libro: llega con los motores (§7.5).
- Papelera/backup: Incremento 9 (por eso no hay borrado de libros aún).
- Sin prueba con MOBI/EPUB reales de gran tamaño ni PDF escaneado (OCR: Incremento 3).

## Siguiente acción concreta (Incremento 2 — Motor EPUB)

1. **Verificación técnica temprana** de Readium Kotlin Toolkit (ADR-005): versiones compatibles con AGP 8.7.3/Gradle 8.9/SDK 34/minSdk 26/teléfono; evaluar tamaño y memoria con `-Xmx768m`.
2. Si es compatible: abrir EPUB, navegar capítulos, guardar posición; si no, documentar alternativas antes de decidir.
3. Compilar en teléfono (AndroidIDE) para validar el entorno completo y registrar resultado aquí.

## Roadmap restante

Ver `docs/architecture/overview.md` (incrementos 2–10) y `docs/product/master-prompt.md` §22.
