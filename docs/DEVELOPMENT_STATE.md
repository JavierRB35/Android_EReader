# DEVELOPMENT_STATE — Estado vivo del desarrollo

> **Regla de continuidad:** todo agente debe (1) leer este documento al iniciar, (2) actualizarlo y hacer push al terminar cada sesión o unidad funcional verificable. La continuidad se reconstruye desde este documento + Git, nunca desde conversaciones previas. El «último commit» de una rama es, por definición, el commit que porta este documento.

**Última actualización:** 2026-11-20
**Fase actual:** Incremento 1 — Biblioteca local (rebanada 1 completa y verificada; rebanada 2 pendiente)
**Rama de trabajo:** `feature/library` (basada en `main` @ `3ac7fb2`; fusionar a `main` solo tras completar el incremento o con autorización)
**Remoto canónico:** `https://github.com/JavierRB35/Android_EReader.git` — el propietario renombró el repositorio desde `Androdi_EReader` (detectado en el push inicial; la URL antigua redirige). Usar siempre la URL canónica.

---

## Estado del proyecto

- **Incremento 0 — Auditoría y base: COMPLETADO** (en `main`): Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21, pantalla base OLED, documentación completa (protocolo, prompt maestro, requisitos, ADRs).
- **Incremento 1 — Biblioteca local: rebanada 1 COMPLETADA**: modelo Room v1, importación real EPUB/PDF/MOBI, catálogo persistente, ficha básica con ciclo de estado.

## Funcionalidades implementadas

| Elemento | Estado |
|---|---|
| Estructura Gradle/wrapper/script AndroidIDE (Inc. 0) | **Implementado** |
| Modelo Room v1: books/categories/tags/collections + relaciones N:M, esquema versionado en `app/schemas/` | **Implementado** |
| Semilla de 17 categorías iniciales (§7.3) en `onCreate` de la BD | **Implementado** |
| Importación real vía SAF: copia interna + hash SHA-256 en streaming + detección por contenido (mimetype EPUB / `%PDF-` / `BOOKMOBI`) + duplicados por hash + metadatos (OPF y PDB) | **Implementado** |
| Catálogo (Flow + repeatOnLifecycle), estado vacío, ficha básica, ciclo de estado de lectura | **Implementado** |
| Protección XXE en parsers XML (DTD deshabilitada) | **Implementado** |

## Evidencias de verificación

| Verificación | Resultado | Etiqueta honesta |
|---|---|---|
| `sh ./gradlew :app:assembleDebug :app:testDebugUnitTest` (JDK 17, escritorio) | **BUILD SUCCESSFUL** | **Verificado localmente** |
| 15 tests JVM (formatos, hash, parsers EPUB/MOBI, anti-XXE) | **15/15 en verde** | **Verificado localmente** |
| Importación real de EPUB sintético válido vía SAF en emulador: catálogo muestra «Don Quijote de la Mancha — Miguel de Cervantes» (metadatos del OPF real) | Correcto | **Verificado localmente (emulador)** |
| Reimportación del mismo archivo | Rechazada: el catálogo mantiene 1 solo ejemplar (hash + índice único) | **Verificado localmente (emulador)** |
| Ficha: metadatos completos y cambio de estado Pendiente→En lectura persistido en Room | Correcto | **Verificado localmente (emulador)** |
| Archivo copiado a `files/books/<uuid>/don-quijote.epub`; BD `biblioteca_ia.db` creada (WAL) | Confirmado vía `adb shell run-as` | **Verificado localmente (emulador)** |
| Compilación en AndroidIDE-Rv2 (teléfono ARM64) | — | **Pendiente** (requiere dispositivo físico; comando: `sh ./build-on-phone.sh`) |

**Bug capturado y corregido con test de regresión:** el parser DOM (Xerces) cierra el stream que recibe; al parsear `container.xml` directamente desde el `ZipInputStream` compartido, el recorrido ZIP moría con `IOException: Stream closed`. Corrección: copiar la entrada a memoria antes de parsear (`MetadataParsers.kt`).

## Problemas abiertos / bloqueos

- Ninguno bloqueante.
- El selector SAF muestra también archivos no admitidos (los filtra el detector por contenido con mensaje claro). Mejora opcional: filter MIME adicional.
- Botón deshabilitado casi invisible sobre negro (nota de Inc. 0) — resuelto de facto al habilitar el botón de importación.

## Pendientes del Incremento 1 (rebanada 2)

1. UI de gestión: categorías (crear/renombrar/reordenar/subcategorías), etiquetas y colecciones; asignación múltiple a libros (§7.3–7.4).
2. Ficha editable manualmente (§7.2) y favoritos accesibles.
3. Búsqueda por título/autor (§7.5 parcial; búsqueda de texto completo llega con los motores).
4. Vista cuadrícula/lista y adaptación tablet (§7.1).
5. Tras completar: fusionar `feature/library` a `main` con autorización y evidencia.

## Roadmap restante

Ver `docs/architecture/overview.md` (incrementos 2–10) y `docs/product/master-prompt.md` §22. Próximo hito técnico temprano: **prueba de compatibilidad MOBI** y verificación de versiones de Readium para EPUB (ADR-005) antes del Incremento 2.

## Siguiente acción concreta

1. Rebanada 2 del Incremento 1 en `feature/library`: pantallas de gestión de categorías/etiquetas/colecciones + búsqueda básica + grid/lista.
2. Ejecutar `build-on-phone.sh` en AndroidIDE cuando haya dispositivo disponible y registrar el resultado aquí (obligatorio antes de declarar el entorno validado).
