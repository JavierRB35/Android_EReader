# Puerta de calidad Android y preparación de la entrega

Estado: **workflow propuesto**, hasta que el coordinador lo integre y exista una ejecución real satisfactoria. No confundir un archivo YAML válido con una compilación probada.

## CI de código — `.github/workflows/android-ci.yml`

Se ejecuta sobre PR a `main`, pushes a `main` y manualmente después de integrarse. Usa JDK 17, Android SDK 34 y las versiones Gradle/AGP/Kotlin **ya fijadas por el proyecto**. Ejecuta:

```sh
sh ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon --max-workers=2
```

El runner utiliza un override de memoria Gradle `-Xmx3g` y compilación Kotlin en proceso **solo en CI**; `gradle.properties` y AndroidIDE conservan sus límites propios.

Esta comprobación garantiza, **solo tras pasar en GitHub Actions**:
- que compila la variante debug en Linux;
- que los tests JVM se ejecutan y pasan;
- que se genera un archivo `app-debug.apk` no vacío;
- que se genera el artefacto `app-release-unsigned.apk` (sin firmar), exactamente el que consume el workflow de firma posterior. Ese APK release sin firma no se distribuye.

La prueba de CI [#44767999620](https://github.com/JavierRB35/Android_EReader/actions/runs/44767999620) ya validó **77/77 JVM y assembleDebug** (antes de incorporar `assembleRelease`); el nuevo paso de release requiere validación en la siguiente ejecución del workflow.

El workflow no lee claves API, credenciales Supabase, keystores ni secretos de GitHub Actions; su `GITHUB_TOKEN` solo puede leer repositorio. Para evitar distribuir binarios de una PR aún no aceptada, solo guarda la APK debug cuando el código llega a `main` o la ejecución es manual. Se guardan resultados de tests siete días.

**Limitación:** una APK debug está firmada con clave de desarrollo temporal del runner. **No sirve como release pública ni actualiza una instalación firmada con otra clave**. Nunca se anunciará como APK 1.0 final.

## Firma de publicación

- La [PR #2](https://github.com/JavierRB35/Android_EReader/pull/2) prepara un ensayo **manual**, sin crear releases ni tags. Está separada del workflow de tests.
- Para activarlo, el coordinador debe revisar/fusionar la PR #2 y comprobar previamente en el equipo confiable del propietario que **la huella pública SHA-256 fijada en esa PR coincide con su keystore real**. En caso de primera publicación, conservar la misma clave para actualizaciones sucesivas.
- El propietario debe cargar las cuatro credenciales exclusivas como **Actions Secrets**: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. No ponerlas en Variables, código, issues, mensajes, APK ni archivos de copia.
- Primero ensayo `workflow_dispatch` en `main`, comprobar que se genera artefacto firmado y **no** se publica GitHub Release. Solo después del cierre QA y de autorización expresa crear un tag de versión coincidente con `versionName`.
- Antes de distribuir, comprobar `apksigner verify --verbose --print-certs` y checksum, mismo certificado que la versión previa (si existe), e incremento de `versionCode` en actualizaciones.

## Cierre funcional independiente de CI

El agente Android se responsabiliza de demostrar en un **teléfono físico con Google Drive u otro DocumentsProvider elegido mediante SAF**:
1. Desde Ajustes, exportar JSON y recuperarlo en un segundo dispositivo o instalación, conservando categorías, colecciones, notas, versiones, posiciones y marcadores.
2. Seleccionar originales EPUB/PDF/MOBI en Drive, guardarlos localmente para lectura offline y revincularlos por hash tras restauración. El ZIP completo **SÍ incluye los originales EPUB/PDF/MOBI** y sus datos; las copias JSON previas son heredadas e incompletas. No dar por buena una ruta/URI del dispositivo antiguo.
3. Configuración BYOK de Gemini/Groq, validación auténtica de credenciales, almacenamiento local cifrado, revocación y prueba de consulta RAG con citas verificables y errores visibles, sin enviar libros completos de forma predeterminada.
4. OCR real, accesibilidad TalkBack/escala de fuente, tablet y prueba `sh ./build-on-phone.sh` en AndroidIDE ARM64, según alcance de aceptación de la 1.0.
5. Prueba sin conexión, falta de permisos o espacio, reinicio/reinstalación y actualización desde una versión instalada firmada con el **mismo** certificado.

No activar OAuth, Google Sign-In, Gmail ni `SyncEngine`. El backup completo no utiliza cuentas ni API Drive. Probar backup y restore reales en el teléfono.

La prueba CI es necesaria, **no suficiente**. Registrar resultados evidenciados en `docs/DEVELOPMENT_STATE.md`; no prometer 1.0 instalada/firmada hasta disponer de pruebas y secretos reales.
