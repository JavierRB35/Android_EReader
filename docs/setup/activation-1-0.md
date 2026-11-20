# Activación externa de Biblioteca IA 1.0 — recorrido operativo

Fecha: 2026-11-20. **No es evidencia de integración final**. Fuente técnica: [AGENTS.md](../../AGENTS.md), [Prompt Maestro](../product/master-prompt.md), [Google/Supabase](google-cloud.md), [firmado](../github-releases.md), [estado](../DEVELOPMENT_STATE.md). Coordinación: [issue #3](https://github.com/JavierRB35/Android_EReader/issues/3).

## Verificado y ya disponible

- Supabase **BibliotecaIA** (independiente de ScripturaWeb y Nexo): `krxdcfyvksjhpohxxvcf`, `eu-west-3`, `ACTIVE_HEALTHY`, URL pública `https://krxdcfyvksjhpohxxvcf.supabase.co`.
- Clave **publishable** de Supabase: existe y está habilitada; obtener su valor en [API Keys del proyecto](https://supabase.com/dashboard/project/krxdcfyvksjhpohxxvcf/settings/api-keys) desde la cuenta autorizada. La existencia de la clave **no** prueba sincronización ni autenticación.
- Para la creación del proyecto se obtuvo cotización explícita de **0 / mes**. No activar planes de pago, facturación ni complementos.
- `app/build.gradle` utiliza `com.javierrb35.bibliotecaia`, `versionName 0.1.0` y lee `BIBLIOTECA_SUPABASE_URL`, `BIBLIOTECA_SUPABASE_KEY`, `BIBLIOTECA_GOOGLE_WEB_CLIENT_ID` de `local.properties` o entorno, sin credenciales de servidor.

## 1. Google Cloud, identidad y Drive — acciones en cuentas

1. En [Google Cloud Console](https://console.cloud.google.com/) crear o seleccionar un proyecto **exclusivo de BibliotecaIA**; evitar afectar otros proyectos. En Google Auth Platform configurar nombre de aplicación, datos de contacto, público (externo si procede), consentimiento y usuarios de prueba. No vincular facturación sin autorización.
2. En la copia **local del repositorio que realmente compile el APK**, ejecutar `gradlew.bat :app:signingReport` (Windows) o `sh ./gradlew :app:signingReport` (AndroidIDE). Obtener el **SHA-1 de la variante debug** y después el de publicación cuando exista su keystore. Las firmas de distintas máquinas pueden ser distintas. Si se dispone del APK, contrastar con `apksigner verify --print-certs ruta.apk`.
3. En **Google Auth Platform > Clients** crear cliente OAuth tipo **Android** con `com.javierrb35.bibliotecaia` y el **SHA-1 del certificado utilizado**; crear también un cliente de tipo **Web application** en el mismo proyecto. El **Web Client ID** se utiliza en Credential Manager: `BIBLIOTECA_GOOGLE_WEB_CLIENT_ID`. El SHA-256 del nonce del flujo de login no sustituye al SHA-1 del certificado.
4. En [Supabase Auth Providers](https://supabase.com/dashboard/project/krxdcfyvksjhpohxxvcf/auth/providers) habilitar Google e introducir los OAuth client IDs y, donde se solicite, el secreto del cliente web **solo en el panel del servidor**, según [guía oficial](https://supabase.com/docs/guides/auth/social-sign-in/auth-google?platform=android). Mantener la verificación del nonce.
5. Habilitar **Google Drive API** en ese proyecto de Cloud. La autorización granular para Drive (`drive.file`) es **distinta** del inicio de sesión y todavía requiere implementar/verificar `AuthorizationClient` y transferencias.

En `local.properties` privado (ignorado por Git):
```properties
BIBLIOTECA_SUPABASE_URL=https://krxdcfyvksjhpohxxvcf.supabase.co
BIBLIOTECA_SUPABASE_KEY=<copiar clave sb_publishable_ desde Supabase>
BIBLIOTECA_GOOGLE_WEB_CLIENT_ID=<client-id-web>.apps.googleusercontent.com
```
La clave publishable y el client ID son valores de configuración públicos incorporables al APK; **no** sustituirlos por `sb_secret_`, `service_role`, OAuth client secret ni tokens de usuario. La copia de `local.properties` solo se realiza en el entorno local de compilación, no en GitHub.

**Aceptación:** compilar con IDs reales; selector de cuentas; login y cancelación; sesión validada con Supabase `/user`; logout, reinicio y corte de red; otro usuario no accede a datos del primero. Drive requiere otra aceptación: consentimiento `drive.file`, subir/descargar libro real con hash y revocación, todavía no implementada completamente.

## 2. Gemini y Groq — preparar las cuentas, no distribuir claves

- [Google AI Studio / API Keys](https://aistudio.google.com/api-keys) y [GroqCloud / API Keys](https://console.groq.com/keys): acceder con la cuenta elegida, revisar cuotas y límites gratuitos **antes de aceptar facturación**, y preparar claves **solo cuando exista un asistente seguro para guardarlas**.
- Según [Prompt Maestro §13](../product/master-prompt.md), las credenciales corresponden a cada usuario y se almacenan cifradas en el backend con aislamiento, revocación y llamadas a modelos desde servidor. **No** poner claves de Gemini/Groq en `local.properties` destinado al APK, GitHub Actions, código, issues, chats ni logs. No compartir aquí valores de clave.
- **Pendiente de desarrollo:** bóveda de claves/Edge Functions; asistente de configuración, consultas del lector, recuperación RAG con citas, fallback real, límites y costes. Crear claves no constituye implementación.
- **Aceptación:** consulta de libro con cita verificable, control de contexto enviado, aislamiento entre usuarios, fallo seguro, revocación y prueba de límites de consumo sin exposición de secretos.

## 3. Firma de APK — trabajo local del propietario y CI

- Antes de crear un keystore nuevo, comprobar si existe **una clave de publicación anterior para el mismo applicationId**. Si existe, reutilizarla para permitir actualizaciones; si no, generar la clave **en un dispositivo de confianza**, protegerla con contraseñas fuertes y guardar respaldo cifrado independiente. **No enviar ni publicar el archivo de la clave**.
- En [GitHub Actions Secrets del repositorio](https://github.com/JavierRB35/Android_EReader/settings/secrets/actions) cargar **Repository secrets**: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Base64 **no cifra**. Véase [instrucción ya implementada](../github-releases.md).
- Revisar si hubo credenciales guardadas como **Actions Variables** (véase [PR #2](https://github.com/JavierRB35/Android_EReader/pull/2)); si se confirma exposición, **eliminar esas variables y rotar las credenciales afectadas**. No repetir ni copiar sus valores en el chat.
- La [PR #2](https://github.com/JavierRB35/Android_EReader/pull/2) prepara **ensayo manual de firma sin publicar**. Debe revisarla e integrarla el coordinador antes de usarla; un ensayo de firma no equivale a una release. No crear `v*` ni publicar hasta QA y versión aprobadas.

**Aceptación:** workflow manual verifica el certificado y SHA-256 del APK, artifact sin release; firma coherente con el APK anterior si lo hubo; credenciales fuera del repositorio.

## 4. AndroidIDE en teléfono — requiere el dispositivo físico

1. Clonar/actualizar `JavierRB35/Android_EReader` en AndroidIDE-Rv2 ARM64. Mantener JDK17, SDK34, Gradle8.9, AGP8.7.3, Kotlin2.0.21. Leer [AGENTS.md](../../AGENTS.md).
2. En la raíz del proyecto ejecutar: `sh ./build-on-phone.sh`. El script usa límites conservadores de JVM, un worker y AAPT2 de AndroidIDE; espera generar `/storage/emulated/0/Download/BibliotecaIA.apk`. **No cambiar** `org.gradle.jvmargs` a `-Xmx2048m`.
3. Abrir el APK de prueba y comprobar catálogo, importación real EPUB/PDF/MOBI, posiciones, cuaderno, backup/restauración, Ajustes y cierre/reapertura. Para OAuth probar Google Play Services; sincronización, Drive e IA requieren sus implementaciones reales.
4. Si falla, enviar al agente solo error de build y diagnóstico **depurado**, jamás contraseñas, tokens, `local.properties`, keystore o archivos de libros privados.

**Aceptación:** compilación real ARM64 en AndroidIDE, instalación y pruebas funcionales documentadas. **No confundir** un APK debug de teléfono con una release firmada: las firmas distintas impiden actualizar in situ.

## Estado de cierre y responsabilidad

| Área | Requisitos del propietario | Trabajo del agente para alcanzar la 1.0 |
|---|---|---|
| Google/Auth/Drive | Cloud project, cliente Android SHA-1, Web ID, consentimientos, activar Google provider | Validar Google+Supabase y desarrollar Drive real |
| Gemini/Groq | Cuentas, cuotas gratuitas, consentimiento y claves suministradas **por canal seguro** | Bóveda cifrada, API servidor, RAG/asistente/visión |
| GitHub | Keystore en posesión del propietario, 4 secrets, revisión de variables | Integrar PR #2, ensayo de firma y QA |
| AndroidIDE | Terminal del teléfono Android con proyecto | Reparar incompatibilidades observadas y registrar evidencia |

Actualizar `docs/DEVELOPMENT_STATE.md` únicamente cuando las pruebas lo justifiquen. Este documento no acredita que los cuatro servicios estén ya configurados ni autoriza costes, releases o almacenamiento de credenciales fuera de sus mecanismos seguros.
