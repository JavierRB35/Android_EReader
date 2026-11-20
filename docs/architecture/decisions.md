# Decisiones técnicas (ADRs)

Formato breve: contexto → decisión → motivación. Añadir nuevas decisiones al final con fecha.

## ADR-001 — Versiones del entorno de compilación (2026-11-20)

- **Contexto:** objetivo obligatorio de compilación en AndroidIDE-Rv2 en teléfono ARM64 con memoria limitada.
- **Decisión:** Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 34, minSdk 26, JDK 17, `JAVA_OPTS=-Xmx768m -XX:MaxMetaspaceSize=256m` en el teléfono, daemon desactivado y `workers.max=1`.
- **Motivación:** entorno validado por el propietario (Prompt Maestro §4); Kotlin 2.0.21 es la versión segura probada con AGP 8.7.x sin requisitos de memoria excesivos. No subir versiones por novedad; cualquier cambio requiere prueba de compilación en el entorno objetivo.

## ADR-002 — Toolkit de interfaz: Kotlin + XML Views (no Compose) (2026-11-20)

- **Contexto:** la app necesita navegadores EPUB/PDF y control fino de temas de lectura.
- **Decisión:** Views XML + AndroidX/Material Components; sin Jetpack Compose en V1.
- **Motivación:** (a) los navegadores de Readium Kotlin Toolkit son basados en Views; (b) la compilación Compose es más pesada para el teléfono objetivo (-Xmx768m); (c) dependencias más estables y ligeras. Reevaluable en futuras versiones con evidencia.

## ADR-003 — Identidad de aplicación (2026-11-20)

- **Decisión:** namespace y applicationId `com.javierrb35.bibliotecaia`; versionCode creciente desde 1; versionName "0.1.0" en bootstrap.
- **Motivación:** identidad estable exigida para actualizaciones firmadas (Prompt Maestro §17.3). No cambiar applicationId jamás sin decisión explícita del propietario.

## ADR-004 — Bootstrap del repositorio vacío directamente en `main` (2026-11-20)

- **Contexto:** repositorio remoto sin ninguna referencia (sin `main`, sin commits).
- **Decisión:** el commit de bootstrap se publica directamente en `main` (crea la rama estable). Todo trabajo funcional posterior va en ramas `feature/<área>`.
- **Motivación:** un repo vacío no tiene nada que estabilizar; la continuidad remota (directiva del propietario) exige que `main` exista desde el primer momento.

## ADR-005 — Motor EPUB: Readium Kotlin Toolkit como candidato (2026-11-20)

- **Estado:** candidato no integrado aún.
- **Plan:** en el Incremento 2 verificar versiones compatibles con AGP 8.7.3/Gradle 8.9/SDK 34/minSdk 26, tamaño, búsqueda, localizadores y una compilación mínima antes de incorporarlo. Si falla la compatibilidad, evaluar alternativas documentando el motivo.

## ADR-006 — Git local del agente vs. remoto (2026-11-20)

- **Decisión:** los agentes no publican `local.properties` (ruta local del SDK), builds ni cachés (`build/`, `.gradle/`). Compilaciones locales de escritorio solo cuentan como «Verificado localmente», nunca como «Verificado en dispositivo/AndroidIDE».
- **Motivación:** reproducibilidad y honestidad de evidencias (Prompt Maestro §23).
