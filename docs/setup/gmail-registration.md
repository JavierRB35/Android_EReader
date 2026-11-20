# Registro de una dirección Gmail en Biblioteca IA (Android)

## Estado técnico — 20/11/2026

Se ha verificado que existe un proyecto Supabase **BibliotecaIA** propio, activo, separado de ScripturaWeb y Nexo Connect. La app incorpora exclusivamente la URL de ese proyecto y su clave **publishable**, que son identificadores públicos previstos para estar en el APK. El código rechaza `service_role`, `sb_secret_*` y claves de otros roles.

En **Cuenta** hay dos formas diferentes de autenticarse:

1. **Crear cuenta con mi correo**: admite una dirección `@gmail.com` u otro correo y una contraseña **nueva para Biblioteca IA**. La operación real es `POST /auth/v1/signup` de Supabase. Se exige confirmación del correo por el servidor, no se da una sesión por registrada sin confirmación. Tras seguir el enlace de confirmación, usar **Acceder con correo y contraseña**; el cliente valida la sesión con `/auth/v1/user` y exige una identidad `email` confirmada antes de guardarla en Android Keystore.
2. **Continuar con Google**: acceso nativo de Google mediante Credential Manager. **No está activado solo por la clave pública de Supabase**: además requiere Google Auth Platform, Web Client ID y clientes Android asociados al nombre de paquete y al **SHA-1 de la firma real**, y el proveedor Google configurado en Supabase. Hasta completar eso, el botón explica el requisito sin simular un inicio de sesión.

Nunca solicita la contraseña propia de Gmail: la contraseña del formulario pertenece exclusivamente a la cuenta de **Biblioteca IA**. El uso de una dirección Gmail aquí **no** concede acceso a la bandeja Gmail, Google Drive ni a los archivos de esa cuenta.

## Limitación real del correo de Supabase

El proveedor SMTP por defecto solo envía mensajes a direcciones pertenecientes al equipo del proyecto, con cuotas de prueba muy bajas. Para admitir cualquier Gmail en producción, un propietario debe configurar **SMTP propio** o el Send Email Auth Hook y comprobar la entrega, DNS y límites; no deshabilitar la confirmación del correo para eludirlo.

- [Configurar SMTP — Supabase](https://supabase.com/docs/guides/auth/auth-smtp)
- [Configurar acceso con Google — Supabase](https://supabase.com/docs/guides/auth/social-sign-in/auth-google?platform=android)
- [Configurar clientes OAuth — Android Developers](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)

## Configuración de compilación

`config/biblioteca-auth-public.properties` está versionado deliberadamente con **solo información pública** del proyecto. El archivo `local.properties` privado o las variables de entorno `BIBLIOTECA_SUPABASE_URL`, `BIBLIOTECA_SUPABASE_KEY` y `BIBLIOTECA_GOOGLE_WEB_CLIENT_ID` pueden prevalecer cuando se compile la app con otra configuración legítima. No registrar secretos en estos campos.

Para habilitar el acceso nativo de Google:

1. En [Google Auth Platform](https://console.cloud.google.com/auth/overview), habilitar audiencia y usuarios de prueba si corresponde.
2. Crear un cliente OAuth **Android** con `com.javierrb35.bibliotecaia` y SHA-1 del certificado que firma exactamente el APK instalado; separar debug de distribución.
3. Crear un cliente OAuth **Web** en el mismo proyecto y pasar su identificador (no el secreto) a `BIBLIOTECA_GOOGLE_WEB_CLIENT_ID`.
4. Habilitar el proveedor Google en `BibliotecaIA > Authentication > Providers > Google`, configurando las credenciales del proveedor en el **servidor**, no en el APK. Mantener la comprobación de nonce.
5. Compilar/firmar con ese Web Client ID y el certificado registrado; verificar un login completo en dispositivo con Google Play Services y `/user`.

## Criterios de aceptación

- [ ] Gmail de prueba autorizado recibe confirmación (si SMTP de pruebas), confirma y puede iniciar sesión, conservar sesión cifrada y cerrarla.
- [ ] Gmail no autorizado obtiene aviso de restricción SMTP, nunca una sesión ficticia.
- [ ] Contraseña incorrecta, sesión no confirmada y errores de red no afectan a libros ni anotaciones locales.
- [ ] Google OAuth: verificación con SHA-1 **de la APK instalada** y Web Client ID configurado; prueba real en dispositivo.
- [ ] Para alta abierta al público, SMTP personalizado confirmado y defensa frente a abusos (límites/CAPTCHA).

La compilación y los tests automatizados **no prueban** la entrega real de correo o el consentimiento del usuario. No cerrar estas casillas sin observación real.
