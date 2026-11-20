# DEVELOPMENT_STATE — Estado vivo del desarrollo

> **Regla de continuidad:** todo agente debe (1) leer este documento al iniciar, (2) actualizarlo y hacer push al terminar cada sesión o unidad funcional verificable. La continuidad se reconstruye desde este documento + Git, nunca desde conversaciones previas. El «último commit» de una rama es, por definición, el commit que porta este documento.

**Última actualización:** 2026-11-20
**Fase actual:** Incremento 0 — Auditoría y base (completado y publicado en `main`)
**Rama de trabajo:** `main` (bootstrap inicial; el trabajo funcional posterior se realiza en ramas `feature/<área>`)

---

## Estado del proyecto

- Repositorio remoto creado desde **cero** (estaba completamente vacío: sin `main`, sin commits).
- Aplicación base **compilada y ejecutándose**: pantalla «Biblioteca» con tema OLED-first y estado vacío.

## Funcionalidades implementadas

| Elemento | Estado |
|---|---|
| Estructura Gradle (settings / root / app), wrapper Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21 | **Implementado** |
| Gradle wrapper con distribución `gradle-8.9-bin.zip` (services.gradle.org) | **Implementado** |
| `build-on-phone.sh` para AndroidIDE-Rv2 (JAVA_OPTS validados, check de APK, copia a Descargas) | **Implementado** |
| MainActivity: pantalla «Biblioteca» con estado vacío y tema negro puro (texto cálido, barras negras) | **Implementado** |
| Documentación: protocolo obligatorio, prompt maestro, requisitos, ADRs, AGENTS.md | **Implementado** |

## Evidencias de verificación

| Verificación | Resultado | Etiqueta honesta |
|---|---|---|
| `sh ./gradlew assembleDebug --no-daemon --max-workers=1` (JDK 17, escritorio) | **BUILD SUCCESSFUL** (33 tareas, APK 5,6 MB) 2026-11-20 | **Verificado localmente** |
| Instalación del APK en emulador Android (x86_64) + arranque de `com.javierrb35.bibliotecaia` | Instalación correcta; app abre; pantalla «Biblioteca» con estado vacío y fondo negro | **Verificado localmente (emulador)** |
| Compilación en AndroidIDE-Rv2 (teléfono ARM64, `sh ./build-on-phone.sh`) | — | **Pendiente** (requiere el dispositivo físico) |
| Pruebas unitarias | No creadas: el Incremento 0 no contiene lógica (protocolo §9: no crear tests triviales) | N/A |

> **Nota:** no afirmar nunca «compilado en AndroidIDE» hasta ejecutarlo en el teléfono. Etiquetado según Prompt Maestro §23.

## Decisiones técnicas vigentes

Ver `docs/architecture/decisions.md`. Resumen: Kotlin + XML Views (no Compose, ADR-002); package/applicationId estable `com.javierrb35.bibliotecaia` (ADR-003); bootstrap publicado directamente en `main` por ser repo vacío, trabajo posterior en `feature/*` (ADR-004); Readium Kotlin Toolkit como candidato EPUB pendiente de compatibilidad (ADR-005).

## Problemas abiertos / bloqueos

- Ninguno bloqueante.
- Observación menor: el botón deshabilitado «Importar libro» apenas es visible sobre fondo negro (alpha de estado disabled de MaterialButton). Se revisará al activarlo en el Incremento 1.

## Próximas tareas (Incremento 1 — Biblioteca local)

1. Crear rama `feature/library`.
2. Modelo Room (Book, Category, Tag, Collection + relaciones; schema v1).
3. Importación real vía SAF (ACTION_OPEN_DOCUMENT): copia a almacenamiento interno de la app, hash SHA-256, detección de duplicados, metadatos iniciales.
4. Catálogo con persistencia (lista/vacío), ficha básica.
5. Activar el botón «Importar libro».
6. Verificación: compilación local + instalación en emulador + prueba de importación con EPUB/PDF sintético; actualizar este documento y hacer push.

## Plan general de incrementos

Ver `docs/architecture/overview.md` (roadmap 0–10) y `docs/product/master-prompt.md` §22.
