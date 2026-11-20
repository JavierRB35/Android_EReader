# Actualizaciones automáticas de Biblioteca IA (Android)

## Comportamiento real

- En cada cambio integrado en `main`, se ejecutan dos validaciones **independientes**:
  1. `Android quality gate` (pruebas JVM, compilación debug, APK de tests Android y release sin firmar).
  2. `Android startup smoke (emulator)` (apertura real del lector en emulador Android 14).
- `.github/workflows/auto-publish-updates.yml` solo distribuye una actualización si **ambas validaciones terminaron con éxito en el mismo commit exacto de `main`**. No firma pull requests ni compila ramas desconocidas con credenciales.
- Si el commit se vuelve obsoleto mientras se firma, se descarta la APK para evitar distribuir una versión vieja. Un **reintento automático cada 10 minutos** vuelve a comprobar el HEAD actual y sus dos CI, sin relajar ninguna validación; GitHub Actions puede retrasar las ejecuciones programadas.
- El workflow compila una nueva APK release, con `versionName` y `versionCode` crecientes, y la firma con la clave original del propietario. Antes de publicar, valida el certificado contra `ANDROID_RELEASE_CERT_SHA256`, el paquete `com.javierrb35.bibliotecaia`, la versión Android y el checksum SHA-256.
- Publica los dos archivos `BibliotecaIA-vX.Y.N.apk` y `BibliotecaIA-vX.Y.N.apk.sha256` en una **GitHub Release pública oficial**, sin sobreescribir ediciones previas. Si otra CI ya publicó el mismo SHA, se omite la duplicación.
- Mientras el proyecto tenga en Gradle versión base `0.1.0`, se generan versiones de **desarrollo 0.1.N**. No se anuncian como v1.0.0 completa hasta la aceptación de QA. El número N lo asigna automáticamente GitHub Actions, sin necesidad de editar Gradle o crear tags manuales.
- El proceso de ensayo de firma `release-apk.yml` sigue disponible manualmente. Ejecutarlo sin tag **no publica** una release.

## Detección dentro de la app

- Revisión **al entrar o volver a una pantalla** y, mientras la aplicación está en primer plano, cada dos minutos como máximo. Se consulta la API pública `releases/latest` de GitHub vía HTTPS.
- La app solo anuncia una versión semántica más reciente cuyo paquete oficial incluye **APK y fichero .sha256**, con enlaces verificados del repositorio; ignora versiones borrador, prereleases y enlaces no oficiales.
- En la biblioteca principal aparece un diálogo con `Descargar APK` / `Ver novedades` / `Más tarde`; en las pantallas de lectura se usa un aviso discreto con acción, sin interrumpir la selección.
- Android **no permite comprobar instantáneamente en segundo plano sin un servicio de notificaciones push**. WorkManager comprueba la disponibilidad **como mínimo cada 15 minutos, cuando el sistema lo permita** (modo ahorro, batería, red y restricciones del fabricante pueden retrasarlo). Para mostrar avisos en segundo plano Android 13+ exige conceder el permiso desde **Ajustes → Activar avisos automáticos de actualización**. La detección con la app abierta funciona aunque se deniegue.
- La instalación no es silenciosa. Al tocar `Descargar APK`, se abre el archivo oficial desde GitHub en el navegador; Android pide autorización para instalarlo. No se tocan las carpetas privadas de libros, notas ni copias. Android rechazará la actualización si el certificado de firma no coincide con el de la app instalada o si el `versionCode` no aumenta.
- El botón existente `Ajustes → Comprobar actualizaciones` sigue disponible, sin dependencia de la detección periódica.

## Requisitos y seguridad

1. Repository Actions **Secrets**: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
2. Repository Actions **Variable** `ANDROID_RELEASE_CERT_SHA256` con huella pública del certificado definitivo.
3. **Variables públicas opcionales** `BIBLIOTECA_SUPABASE_URL`, `BIBLIOTECA_SUPABASE_KEY` (solo publishable / anon), y `BIBLIOTECA_GOOGLE_WEB_CLIENT_ID` si las funciones de cuenta requieren esos valores en el APK. No introducir `service_role`, contraseñas, keystore ni API privadas en Variables.
4. Permisos `contents:write` **solo** durante la publicación; los pasos de checkout y compilación utilizan acceso de solo lectura. Los workflows de validación no tienen acceso a secretos de firma.
5. La referencia a `main` y ambas CI se comprueba antes de firmar; se vuelve a comprobar que el SHA siga siendo HEAD antes de publicar. Los releases son inmutables por versión, sin force-push.

## Comprobaciones de aceptación

- Fusionar una modificación en `main`; comprobar ambas CI en el **mismo SHA** y un run de `Automatically publish signed Android updates`.
- Confirmar `SUCCESS` en `preflight`, `build`, `publish`, que la etiqueta publicada coincide con la APK firmada, SHA-256 y certificado.
- Verificar que una CI fallida, una PR, un commit ya publicado o un commit que dejó de ser HEAD **no** crea una Release.
- En una instalación previa **firmada con la misma clave**, abrir Biblioteca IA y confirmar que ofrece la nueva versión; instalarla aceptando el aviso de Android y comprobar que libros, notas y ajustes siguen presentes.
- Dejar la app abierta para comprobar la consulta periódica. En segundo plano, permitir notificaciones y comprobar que WorkManager puede mostrar aviso cuando Android lo ejecute; no prometer entrega inmediata.
- Sin conexión o con error 403/404 de GitHub, la lectura sigue funcionando y no aparece una actualización ficticia.

El lanzamiento v1.0.0 requiere sus criterios de QA específicos; esta automatización **no certifica por sí sola la funcionalidad de cada versión** más allá de los gates establecidos.
