# Google y Supabase: identidad nativa de Biblioteca IA

Estado: cliente implementado para configuración explícita; integración con servicios **no verificada**. El inventario conectado consultado por el coordinador no incluye un proyecto BibliotecaIA. No se han usado credenciales, tablas ni configuraciones de otros proyectos.

## Qué incorpora este incremento

`AccountActivity` muestra el modo local, el estado real de configuración, inicio de sesión, verificación de cuenta, cierre de sesión remoto y opción explícita de olvidar la sesión solo en este dispositivo. Si falta la configuración pública, el inicio de sesión queda deshabilitado y la lectura local sigue disponible.

Credential Manager 1.3.0 y googleid 1.1.1 se fijan para mantener compileSdk34, JDK17, AGP8.7.3 y Gradle8.9. La comprobación AAR de estas dependencias ha pasado en Windows; AndroidIDE ARM64 sigue pendiente. No se seleccionan las versiones alfa sugeridas por documentación más reciente.

El flujo genera 32 bytes aleatorios, pasa SHA-256 del nonce a Google y el nonce original al intercambio real `POST /auth/v1/token?grant_type=id_token`. Supabase valida el ID token. El cliente verifica además `/user`, exige identidad Google y usuario no anónimo. La renovación se realiza bajo demanda, con rotación guardada antes de operaciones posteriores y exclusión mutua entre consumidores. No hay bucle de conexiones permanente.

La sesión se cifra con AES-GCM y una clave del Android Keystore, se escribe mediante AtomicFile dentro de `noBackupFilesDir` y nunca se incluye en exportaciones de biblioteca. Se rechazan respuestas excesivas y redirecciones HTTP; HTTPS conserva la validación de certificado y hostname del sistema. Solo se aceptan orígenes alojados `https://<ref>.supabase.co`, sin rutas, puertos, usuario, query ni fragmento. Los errores mostrados no incluyen respuestas, claves ni tokens.

`AccountRepository.savedSession()` solo sirve para presentar el estado guardado. Un servicio debe llamar `requireOnlineSession()` y enviar su access token para la autorización del servidor; una identidad leída de caché no autoriza acciones. El servidor debe aplicar RLS y validar usuario/sesión. El `user_metadata` editable no se usa para autorizar.

**La autenticación no vincula ni sube libros.** Drive, asociación local→cuenta, aislamiento de bibliotecas y sincronización siguen siendo incrementos separados. No habilitar una sincronización automática al incorporar esta pantalla. Cambiar de cuenta no debe reasignar silenciosamente datos locales.

## Preparación del propietario

1. Identificar o crear, con autorización del propietario, el proyecto Supabase correcto para BibliotecaIA. Si existe en otra organización, conceder acceso al conector. No reutilizar ScripturaWeb/Nexo ni sus claves.
2. En Google Cloud / Google Auth Platform, seleccionar el proyecto de BibliotecaIA, configurar nombre, contactos, audiencia y pantalla de consentimiento. Si está en pruebas, añadir los usuarios de prueba permitidos.
3. Crear un cliente OAuth de tipo **Android** para `com.javierrb35.bibliotecaia`, con la huella SHA-1 del certificado que firma el APK instalado. Repetir para firma debug y firma de distribución si se utilizan ambas. No reutilizar una huella de otro proyecto.
4. Obtener la huella real mediante `sh ./gradlew :app:signingReport` (teléfono) o `gradlew.bat :app:signingReport` (Windows). Comparar el certificado del APK con `apksigner verify --print-certs <apk>` antes de registrarlo. No publicar keystore, alias con datos privados, contraseñas ni claves. Las huellas SHA-1/SHA-256 no son claves privadas, pero deben corresponder a ese APK concreto. No se han verificado aún en Google Cloud.
5. Crear un cliente OAuth de tipo **Aplicación web** en el mismo proyecto. Ese client ID es la audiencia enviada por Credential Manager, no el client ID Android. Configurar el proveedor Google de Supabase con los client IDs necesarios; cuando se use también OAuth web, registrar el callback que muestre Supabase y guardar el secreto web exclusivamente en la configuración del proveedor del servidor.
6. Mantener activada la comprobación de nonce. No activar **Skip Nonce Check** como solución a un error de configuración.
7. Obtener la URL del proyecto y su clave **publishable** (preferida), o la antigua clave `anon`. Nunca usar `service_role`, `sb_secret_`, una contraseña de base de datos ni una clave de proveedor IA.

## Configuración de la compilación

Los valores públicos se leen exclusivamente desde `local.properties` (ignorado por Git) o las variables de entorno del mismo nombre:

```properties
BIBLIOTECA_SUPABASE_URL=https://<project-ref>.supabase.co
BIBLIOTECA_SUPABASE_KEY=<publishable-key-or-legacy-anon-key>
BIBLIOTECA_GOOGLE_WEB_CLIENT_ID=<web-client-id>.apps.googleusercontent.com
```

Sin estos valores, el APK funciona en modo local. Son identificadores/configuración de cliente, visibles en un APK; no insertar secretos aunque el archivo sea local. El script Gradle rechaza claves que no sean publishable o JWT con rol `anon`. No pasarlos en comandos que puedan aparecer en registros compartidos. No es necesario incorporarlos al repositorio.

Integración por el coordinador: permiso `android.permission.INTERNET`, `android:usesCleartextTraffic="false"` y actividad `.ui.AccountActivity` con `android:exported="false"`. No necesita callback exportado ni deep link para el flujo nativo de ID token. Conectar una entrada real de Cuenta en navegación. El almacenamiento de sesión queda fuera del backup del sistema; no cambiar esto al implementar copias.

## Verificaciones necesarias antes de considerar listo el servicio

- Compilar con la configuración pública real y la firma registrada; abrir selector de Google en dispositivo con Play Services.
- Consentimiento, cancelación, ninguna cuenta disponible, configuración de client ID/huella incorrecta, token/nonce incorrecto y respuesta válida de Supabase.
- `/user` devuelve el usuario Google esperado; sesión guardada cifrada y restaurada tras reiniciar proceso.
- Renovación después de caducidad, fallo de red sin pérdida de libros, revocación y rechazo de sesión inválida.
- Cierre remoto y local; con red caída, informar que olvidar localmente no confirma revocación remota. Los access tokens ya emitidos pueden seguir válidos hasta caducar.
- Dos cuentas: datos de una nunca se vinculan a otra automáticamente. Verificar políticas RLS cuando se añada el esquema.
- AndroidIDE ARM64 y dispositivos API26/API34. No afirmar validación de servicios con pruebas sintéticas.

Hay tests JVM de origen HTTPS, client ID, nonce/hash y margen de renovación. Los tests instrumentados `SecureSessionStoreTest` verifican cifrado recuperable, manipulación rechazada, aislamiento por proyecto y borrado de sesión sin tocar un archivo ajeno; utilizan directorio y alias de prueba propios.

## Drive es otra autorización

El inicio de sesión Google autentica identidad. Para Drive debe añadirse AuthorizationClient y pedir `drive.file` cuando el usuario elija vincular/subir libros, sin solicitar acceso completo a Drive. Antes de implementar, resolver la cuenta seleccionada, consentimiento, revocación, verificación de hash, reintentos y persistencia de transferencias. No se ha realizado ninguna operación Drive en este incremento.

## Fuentes oficiales consultadas (2026-11-20)

- [Google: Credential Manager y distinción de autorización](https://developer.android.com/identity/sign-in/credential-manager-siwg).
- [Google: implementación de inicio de sesión](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).
- [AndroidX Credentials: versiones](https://developer.android.com/jetpack/androidx/releases/credentials).
- [Supabase: Google nativo y nonce](https://supabase.com/docs/guides/auth/social-sign-in/auth-google?platform=android).
- [Supabase Auth: contrato REST](https://github.com/supabase/auth/blob/master/openapi.yaml).
- [Supabase: cambios recientes](https://supabase.com/changelog.md). Revisado cambio de TLS del 12-10-2026: no desactivar TLS ni certificados para acomodar dispositivos antiguos; comprobar conexión real del dispositivo objetivo.
