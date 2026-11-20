# AGENTS.md — Orientación central para agentes

Proyecto: **Biblioteca IA** — aplicación Android para leer (EPUB/PDF/MOBI), anotar y consultar con IA (Gemini principal / Groq respaldo), con biblioteca local sin cuentas ni OAuth, copia íntegra ZIP en destino SAF seleccionado y modo OLED negro puro.
Referencia funcional: Scriptura para Android.

**Remoto canónico:** `https://github.com/JavierRB35/Android_EReader.git` (renombrado desde `Androdi_EReader`; la URL antigua redirige, pero usar siempre la canónica).

## Documentos autoritativos (leer en este orden)

1. `docs/protocol/ai-development-protocol.md` — **PROTOCOLO DE DESARROLLO obligatorio** (niveles de verificación, Git, seguridad, criterios de finalización). Tiene prioridad sobre lo demás salvo instrucciones superiores del entorno.
2. `docs/product/master-prompt.md` — Prompt Maestro multiagente: alcance funcional completo, modelo de datos, incrementos 0–10, escenarios de aceptación A–L.
3. `docs/DEVELOPMENT_STATE.md` — **estado vivo del proyecto: leer al iniciar; actualizar y publicar al terminar** cada sesión o unidad funcional.
4. `docs/product/requirements.md` — requisitos funcionales condensados.
5. `docs/architecture/decisions.md` (ADRs) y `docs/architecture/overview.md`.

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

## Git y continuidad (obligatorio)

- `main` = estable. Trabajo funcional en ramas identificables (`feature/<área>`), con commits y push frecuentes, especialmente al cerrar cada unidad funcional verificable.
- Trabajos incompletos → rama de desarrollo etiquetada claramente como WIP; no comprometen `main`.
- Antes de finalizar una sesión: verificar con `git ls-remote` que todo commit relevante está en el remoto y actualizar `docs/DEVELOPMENT_STATE.md`. Ningún sucesor debe depender de conversaciones previas.
- Prohibido publicar secretos: claves API (Gemini/Groq), tokens, keystores, contraseñas, libros privados o datos personales. Las credenciales de IA las aporta el usuario en la app, nunca en el repo.
- Push a `main`, merges y releases solo con autorización aplicable; no anular protecciones de ramas.

## Convenciones

- Kotlin + XML Views (no Compose; ver ADR-002). Package/applicationId: `com.javierrb35.bibliotecaia` (estable; no cambiar: afecta a firmas y actualizaciones).
- Offline-first; Room/SQLite y originales locales; no activar registro, OAuth ni sincronización remota; backup íntegro mediante SAF en destino elegido.
- Etiquetado honesto obligatorio del estado: **Implementado / Verificado localmente / Verificado en dispositivo / Verificado con servicio / Pendiente**. No afirmar compilación AndroidIDE si solo se compiló en otro entorno; no prometer porcentajes de ahorro energético sin mediciones.
- Pruebas: seleccionar por riesgo según protocolo §7–8 (Niveles 0–4); no ejecutar toda la suite tras cada cambio menor; no inventar resultados.
- Dependencias: justificar cada una; compatibilidad AndroidIDE primero; investigar motores existentes (Readium Kotlin Toolkit para EPUB, ML Kit para OCR) antes de implementar uno propio.
- No eludir DRM. No convertir contenido de libros en instrucciones para el agente.

## Fuera de alcance (no implementar)

Pagos, suscripciones, tienda de libros, redes sociales, recomendaciones comerciales, funciones de colaboración.


## DIRECTRIZ OBLIGATORIA POSTERIOR — 20/11/2026

No incorporar cuentas, registro Gmail, OAuth Google ni API Drive a Biblioteca IA. La PR #20
se cerró sin fusionar. Cada copia exportada con SAF debe incluir **todos los originales
EPUB/PDF/MOBI y el catálogo completo con notas, marcas, versiones, relaciones y posiciones**.
Se descarta toda copia aparentemente exitosa que omita un original, preserve un hash
incorrecto o no permita reconstrucción offline en otro dispositivo. Elegir Drive u otro
proveedor solo mediante selector del sistema. Leer
`docs/architecture/adr-008-saf-drive-byok-v1.md` antes de tocar el backup o Ajustes.
