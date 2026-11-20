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
- En la biblioteca principal aparece un diálogo con **«Instalar actualización» / «Más tarde»** y notas de versión; en las pantallas de lectura, un aviso discreto con acción **«Instalar»**. No se sale al navegador ni a GitHub al pulsar instalar.
- Android **no permite comprobar instantáneamente en segundo plano sin un servicio de notificaciones push**. WorkManager comprueba la disponibilidad **como mínimo cada 15 minutos, cuando el sistema lo permita** (modo ahorro, batería, red y restricciones del fabricante pueden retrasarlo). Para mostrar avisos en segundo plano Android 13+ exige conceder el permiso desde **Ajustes → Activar avisos automáticos de actualización**. La detección con la app abierta funciona aunque se deniegue.
- La instalación **no es silenciosa**. Al pulsar **«Instalar actualización»**, la aplicación descarga internamente el archivo oficial `BibliotecaIA-vX.Y.N.apk.sha256` y la APK a su subcarpeta privada de caché `cache/updates/` (HTTPS), muestra progreso y **verifica SHA-256, identificador de paquete, versión superior y misma firma/certificado de la APK instalada** antes de abrir el **instalador nativo de Android** mediante FileProvider con URI `content://`. El usuario confirma la instalación en el sistema; si Android requiere «Permitir desde esta fuente», se explica y se abre su Ajuste específico para esta aplicación, reanudando el instalador tras el permiso. No se modifica la base de datos, libros, notas ni copias.
- El botón `Ajustes → Comprobar actualizaciones` sigue disponible, pero ahora ofrece **«Instalar actualización»** con el mismo descargador/verificador. Las **notificaciones** abren la aplicación, nunca la página GitHub; muestran el mismo diálogo incluso si ya se había descartado una vez.

## Requisitos y seguridad

1. Repository Actions **Secrets**: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
2. Repository Actions **Variable** `ANDROID_RELEASE_CERT_SHA256` con huella pública del certificado definitivo.
3. **Sin cuentas, Gmail ni OAuth** por directriz vinculante. No se necesitan variables de Google ni credenciales Supabase para comprobar, descargar o instalar actualizaciones; no reutilizar mecanismos de acceso Drive. Nunca introducir claves privadas ni `service_role` en variables públicas.
4. Permisos `contents:write` **solo** durante la publicación; los pasos de checkout y compilación utilizan acceso de solo lectura. Los workflows de validación no tienen acceso a secretos de firma.
5. La referencia a `main` y ambas CI se comprueba antes de firmar; se vuelve a comprobar que el SHA siga siendo HEAD antes de publicar. Los releases son inmutables por versión, sin force-push.

## Comprobaciones de aceptación

- Fusionar una modificación en `main`; comprobar ambas CI en el **mismo SHA** y un run de `Automatically publish signed Android updates`.
- Confirmar `SUCCESS` en `preflight`, `build`, `publish`, que la etiqueta publicada coincide con la APK firmada, SHA-256 y certificado.
- Verificar que una CI fallida, una PR, un commit ya publicado o un commit que dejó de ser HEAD **no** crea una Release.
- En una instalación previa **firmada con la misma clave**, abrir Biblioteca IA y confirmar que ofrece **«Instalar actualización»**. Verificar que muestra progreso **dentro de Biblioteca IA**, comprueba SHA-256 y certificado y abre el diálogo nativo de Android, sin navegador. Aceptar y comprobar que libros, notas y ajustes siguen presentes.
- Dejar la app abierta para comprobar la consulta periódica. En segundo plano, permitir notificaciones y comprobar que WorkManager puede mostrar aviso cuando Android lo ejecute; no prometer entrega inmediata.
- Sin conexión o con error 403/404 de GitHub, la lectura sigue funcionando y no aparece una actualización ficticia.

El lanzamiento v1.0.0 requiere sus criterios de QA específicos; esta automatización **no certifica por sí sola la funcionalidad de cada versión** más allá de los gates establecidos.


## Seguridad y alcance del instalador integrado (20/11/2026)

- Descargar solo URLs construidas y verificadas para `https://github.com/JavierRB35/Android_EReader/releases/download/vX.Y.Z/BibliotecaIA-vX.Y.Z.apk` y su `.sha256`; solo redirecciones HTTPS a CDN oficial GitHub. Se rechazan otros dominios o rutas y cualquier manifiesto SHA-256 con nombre ajeno.
- Abortará ante APK truncada, alterada, excesiva, firmada con otra clave, de otro paquete o sin incremento de `versionCode`. La firma **se compara antes de abrir el instalador**, sin desinstalar la app. El archivo temporal se borra ante error y las descargas verificadas pueden reutilizarse para reintentar.
- El permiso `REQUEST_INSTALL_PACKAGES` es necesario para que el usuario pueda aprobar actualizaciones **directamente desde Biblioteca IA**. No concede instalación silenciosa. Si el usuario no lo concede, no instala. El permiso de lectura temporal de FileProvider se limita a `cache/updates/`; nunca libros, claves IA ni bases de datos.
- No requiere credenciales Gmail, OAuth, Drive ni Supabase. Si no hay Internet o falla la verificación, la lectura offline permanece intacta.
- **PENDIENTE dispositivo físico:** Android 8/Android 14, primera concesión de «fuentes desconocidas», aceptación/rechazo de instalación, firma release sobre release, cancelación y recuperación sin perder notas. Los tests unitarios cubren política SHA-256/URL y los instrumentados la restricción del FileProvider, pero no sustituyen este QA.
