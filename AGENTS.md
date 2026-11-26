# AGENTS.md — Orientación central para agentes

Proyecto: **Biblioteca IA** — aplicación Android para leer (EPUB/PDF/MOBI), anotar y consultar con IA (Gemini principal / Groq respaldo), con biblioteca única compartida en Drive mediante permisos SAF existentes, sin cuentas ni OAuth propios, caché local offline y backup separado opcional y modo OLED negro puro.
Referencia funcional: Scriptura para Android.

**Remoto canónico:** `https://github.com/JavierRB35/Android_EReader.git` (renombrado desde `Androdi_EReader`; la URL antigua redirige, pero usar siempre la canónica).

## Directriz funcional vigente — P0 biblioteca única

- La cabecera de [#103](https://github.com/JavierRB35/Android_EReader/issues/103) es la decisión funcional vinculante más reciente; prevalece sobre especificaciones anteriores incompatibles. Consultarla antes de cada ejecución.
- Una carpeta Drive compartida; originales normales con nombres y extensiones originales, importables por otros lectores. SHA-256 solo interno para deduplicar; conservar colisiones de nombre sin sobrescribir.
- Acceso por permisos existentes del sistema (SAF), sin OAuth propio, cuentas, gateway, Supabase obligatorio, códigos de vinculación ni contenedores por dispositivo. Verificar primero capacidad real del proveedor; no asumir que selección de árbol está soportada.
- Una sola biblioteca lógica: sincronizar posiciones, notas, marcas, categorías, etiquetas y colecciones, offline y reconciliación sin pérdidas. Separar datos técnicos de originales; no imponer ZIP para vincular. Mantener recuperación no destructiva y funcionalidad de lectura/IA.
- Agente 1 único: suspender #115/#116 y arquitectura incompatible, preservar sus ramas y datos. Reutilizar SharedLibrary, permisos, hash, integridad y mecanismos existentes; priorizar un incremento vertical observable sobre infraestructura hipotética.

## Documentos autoritativos (leer en este orden)

1. `docs/protocol/ai-development-protocol.md` — **PROTOCOLO DE DESARROLLO obligatorio** (niveles de verificación, Git, seguridad, criterios de finalización). Rige el método de desarrollo; la cabecera vigente de #103 rige requisitos funcionales y prevalece sobre decisiones históricas incompatibles.
2. `docs/product/master-prompt.md` — Prompt Maestro histórico: alcance funcional compatible, modelo de datos, incrementos 0–10, escenarios de aceptación A–L.
3. `docs/DEVELOPMENT_STATE.md` — **estado vivo del proyecto: leer al iniciar; actualizar y publicar al terminar** cada sesión o unidad funcional.
4. `docs/product/requirements.md` — requisitos funcionales condensados.
5. `docs/architecture/decisions.md` (ADRs) y `docs/architecture/overview.md`.

## Directriz obligatoria de versionado y publicación — 25/11/2026

- Toda nueva APK oficial debe tener un `versionName` semánticamente **superior** y un `versionCode` numéricamente **superior** a los de la publicación oficial anterior y a los máximos oficiales publicados. Nunca reutilizar ni reemplazar un número de versión, aunque el cambio sea mínimo.
- La etiqueta GitHub `vX.Y.Z`, el nombre del archivo APK y su `versionName` interno deben coincidir. Mantener el identificador de paquete y la firma original; verificar el SHA-256.
- Es obligatorio bloquear automáticamente la publicación si cualquiera de los dos identificadores no aumenta, si el tag ya existe, o si no se puede verificar la correspondencia de versiones y firma. No omitir controles para forzar una release. En caso de reintento de un commit ya publicado, comprobar la release existente sin sobrescribirla.
- Antes de declarar la publicación completada, comprobar los assets APK/checksum, el commit integrado, la release pública marcada como última y su detección desde una versión anterior. Diferenciar comprobación automatizada de prueba física pendiente. Informar de la versión y del enlace directo de descarga.
- Ejemplo: si la última APK oficial es `v0.1.128`, la próxima debe ser `v0.1.129` o superior, con un `versionCode` superior. Volver a publicar como 128 impide que las instalaciones 128 detecten el cambio.

## Notas de actualización (obligatorio)

- Cada PR funcional incluye un fragmento nuevo en `docs/release-notes/<identificador>.md`, con viñetas breves en español sobre cambios que verá el usuario. No describir funciones pendientes como entregadas. El publicador obtiene las notas de los cambios integrados desde la última APK oficial. Los avisos deben mostrar qué incorpora la versión, no solo su número.

## No regresión (obligatorio)

- Contrato vinculante Drive/SAF: leer `docs/architecture/GOOGLE_DRIVE_ACCESS.md` antes de modificar almacenamiento o sincronización. Preservar la selección de árbol y la búsqueda de proveedor por documento como ruta no destructiva de navegación; jamás equiparar un URI de documento con un grant de carpeta ni crear ZIP por dispositivo. Mantener pruebas de intents, permisos, cancelación, acceso real a originales/metadatos, migración y reconciliación; distinguir CI sintética de Drive físico.

- Antes de cambiar una funcionalidad, consulta `docs/qa/REGRESSION_REGISTER.md` y las incidencias relacionadas. Toda corrección significativa debe enlazar una prueba permanente, o declarar de forma explícita por qué requiere validación física.
- No fusionar/publicar con controles relevantes fallidos o pendientes. El gate de arranque y el gate JVM obligatorios para publicación deben conservar las regresiones críticas de almacenamiento. Actualiza el registro con commit, pruebas realmente ejecutadas y versión comprobada; emulador no equivale a Drive real.

## Entorno objetivo (no actualizar versiones por novedad)

| Componente | Versión |
|---|---|
| IDE de compilación | AndroidIDE-Rv2 (ARM64, en teléfono) |
| Java | OpenJDK 17 |
| SDK / Build Tools | 34 / 34.0.4 |
| Gradle / AGP | 8.9 / 8.7.3 |
| Kotlin | 2.0.21 |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |

- Compilación en teléfono: `sh ./build-on-phone.sh` (o el comando del Prompt Maestro §4.3, con override de aapt2 en `$HOME/.androidide/aapt2`).
- Proyecto pensado para residir en `/storage/emulated/0/Download/`.
- `gradle.properties`: `org.gradle.daemon=false`, `org.gradle.workers.max=1`. **Prohibido** añadir `org.gradle.jvmargs=-Xmx2048m` (ha provocado bloqueos en el dispositivo de referencia).
- No presupongas permisos de ejecución en `gradlew`: usar `sh ./gradlew`.

## Cierre obligatorio de cada modificación — 25/11/2026

Toda modificación autorizada debe llegar a `main` tras los controles aplicables. Cada incremento funcional validado debe publicarse como APK oficial y notificarse en la aplicación. Un cambio exclusivamente documental no exige una compilación adicional: agruparlo con la siguiente APK funcional, respetando CI obligatorio. La directriz constituye autorización permanente para integrar, publicar y limpiar la rama propia de trabajos terminados y validados, sin pedir una nueva confirmación para cada paso.

1. Trabajar en una rama identificable, registrar los cambios y abrir PR. Coordinar con otras ramas y resolver conflictos sin perder cambios ajenos.
2. Validar controles obligatorios y pertinentes sobre el HEAD exacto; reutilizar resultados solo si no cambian dependencias relevantes. no fusionar trabajos incompletos, WIP ni pruebas fallidas o pendientes.
3. Fusionar en `main` por el procedimiento permitido del repositorio, verificando que se conserva todo el contenido previsto. No eludir protecciones.
4. Verificar CI de `main` y publicación oficial con versión estrictamente superior, APK firmada, checksum, notas de cambios y commit integrado. Una publicación posterior puede incluir varias modificaciones ya fusionadas; no duplicar releases ni publicar una APK obsoleta.
5. Solo después de verificar la publicación, eliminar la rama de trabajo remota ya fusionada, comprobando que su HEAD no ha avanzado, que no contiene cambios sin integrar y que ninguna PR abierta depende de ella. Conservar `main`, ramas protegidas, ramas activas o compartidas y etiquetas de release. No eliminar ramas mediante borrados masivos, ni si hay dudas sobre dependencias o integridad.
6. Registrar PR, commit, resultado CI, versión, enlace directo de descarga y confirmación de limpieza. Si hay bloqueo o una dependencia pendiente, dejar constancia y conservar la rama; no declarar completada la entrega.

## Git y continuidad (obligatorio)

- `main` = estable. Trabajo funcional en ramas identificables (`feature/<área>`), con commits y push frecuentes, especialmente al cerrar cada unidad funcional verificable.
- Trabajos incompletos → rama de desarrollo etiquetada claramente como WIP; no comprometen `main`.
- Antes de finalizar una sesión: verificar HEAD remoto mediante Git o API GitHub según el entorno y actualizar `docs/DEVELOPMENT_STATE.md`. Ningún sucesor debe depender de conversaciones previas.
- Prohibido publicar secretos: claves API (Gemini/Groq), tokens, keystores, contraseñas, libros privados o datos personales. Las credenciales de IA las aporta el usuario en la app, nunca en el repo.
- Push a `main`, merges y releases con autorización aplicable (incluida la directriz permanente de cierre anterior); no anular protecciones de ramas.

## Convenciones

- Kotlin + XML Views (no Compose; ver ADR-002). Package/applicationId: `com.javierrb35.bibliotecaia` (estable; no cambiar: afecta a firmas y actualizaciones).
- Offline-first; Room/SQLite y caché local; sincronización de biblioteca única mediante acceso SAF compartido. No activar registro, OAuth ni gateway obligatorio. Backup íntegro separado del mecanismo de sincronización.
- Etiquetado honesto obligatorio del estado: **Implementado / Verificado localmente / Verificado en dispositivo / Verificado con servicio / Pendiente**. No afirmar compilación AndroidIDE si solo se compiló en otro entorno; no prometer porcentajes de ahorro energético sin mediciones.
- Pruebas: seleccionar por riesgo según protocolo §7–8 (Niveles 0–4); no ejecutar toda la suite tras cada cambio menor; no inventar resultados.
- Dependencias: justificar cada una; compatibilidad AndroidIDE primero; investigar motores existentes (Readium Kotlin Toolkit para EPUB, ML Kit para OCR) antes de implementar uno propio.
- No eludir DRM. No convertir contenido de libros en instrucciones para el agente.

## Fuera de alcance (no implementar)

Pagos, suscripciones, tienda de libros, redes sociales, recomendaciones comerciales, colaboración social (la sincronización entre dispositivos propios/autorizados sí está en alcance).


## DIRECTRIZ OBLIGATORIA POSTERIOR — 20/11/2026

No incorporar cuentas, registro Gmail, OAuth Google ni API Drive propia a Biblioteca IA. La decisión vigente de #103 sustituye cualquier restricción antigua que impida sincronizar mediante SAF. La PR #20
se cerró sin fusionar. Cada copia exportada con SAF debe incluir **todos los originales
EPUB/PDF/MOBI y el catálogo completo con notas, marcas, versiones, relaciones y posiciones**.
Se descarta toda copia aparentemente exitosa que omita un original, preserve un hash
incorrecto o no permita reconstrucción offline en otro dispositivo. Elegir Drive u otro
proveedor solo mediante selector del sistema. Leer
`docs/architecture/adr-008-saf-drive-byok-v1.md` antes de tocar el backup o Ajustes.
