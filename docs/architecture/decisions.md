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

## ADR-007 — Favorito como columna ortogonal y relaciones sin FK (2026-11-20)

- **Contexto:** §7.1 lista «Favoritos» como estante junto a los estados de lectura; si fuera un estado pisaría el estado real (En lectura, etc.).
- **Decisión:** (a) `favorite` es columna booleana propia de `books` añadida en la **migración explícita v1→v2** (`ALTER TABLE ... ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0`), verificada en real sobre datos existentes; (b) las tablas N:M no declaran claves foráneas con CASCADE; la limpieza de referencias al eliminar categorías/etiquetas/colecciones se hace con `@Transaction` lógica en los DAOs (borrar refs primero, luego la entidad).
- **Motivación:** favorito ortogonal al estado; migración no destructiva (§15.7); evitar riesgo de migración de FKs en la V1 local manteniendo la misma integridad garantizada en código. Revisar al diseñar el modelo Supabase multiusuario.


## Decisión vigente 2026-11-20 — Backup completo sin cuentas (ADR-008 revisada)

- **Sin OAuth, sin registro Gmail ni usuario Google/Supabase en Biblioteca IA.** Cancelada PR #20.
- Toda copia de seguridad debe empaquetar los archivos **originales** de todos los libros
  EPUB/PDF/MOBI importados, junto al catálogo y todas las marcas/notas/versiones/posiciones.
- SAF de Android presenta Drive u otros proveedores **elegidos** por el usuario: la app
  no se autentica en Drive ni solicita acceso global a sus carpetas.
- ZIP verificado mediante SHA-256 por original antes de exportar/restaurar, con límites
  anti-bombas y preservación del contenido local. JSON sin binarios = formato heredado.
- Prevalece sobre decisiones y prompts anteriores con sincronización/identidad.
