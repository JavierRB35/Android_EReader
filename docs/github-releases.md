# Publicación de APK firmadas en GitHub Releases

El workflow `.github/workflows/release-apk.yml` compila el APK `release` **sin firmar** en un runner Linux y lo alinea y firma con `zipalign` y `apksigner`. No modifica la compilación de AndroidIDE-Rv2 ni publica versiones al hacer push a `main`.

**Memoria de compilación en GitHub Actions:** el build Linux de release utiliza `-Dorg.gradle.jvmargs=-Xmx3g` y Kotlin in-process, porque la primera compilación CI con heap predeterminado produjo `OutOfMemoryError` en Readium/Kotlin. Este valor es exclusivo del runner alojado y **no modifica** los límites del Gradle de AndroidIDE. Antes de emplear la firma definitiva, [PR #7](https://github.com/JavierRB35/Android_EReader/pull/7) prueba también `assembleRelease` y exige la existencia de `app-release-unsigned.apk`.

**Criterio de bloqueo:** aunque `assembleRelease` sea satisfactorio, la APK resultante está **sin firmar**. La firma release requiere el keystore auténtico, sus secretos y comprobación de huella; no publicar etiquetas basándose solo en CI.

## Preparación de la firma

1. **Reutilizar la clave de publicación existente** si ya se distribuyó alguna APK firmada con el mismo `applicationId` (`com.javierrb35.bibliotecaia`). Verificar localmente su alias y huella SHA-256 con `keytool -list -v -keystore ruta/a/clave.jks`. No subir la clave ni las contraseñas al repositorio, a un issue o al chat.
2. Si no existe ninguna clave de publicación anterior, generar **una sola** en un entorno de confianza y conservar una copia de seguridad cifrada, separada de GitHub. Perder la clave impide actualizar instalaciones firmadas con ella.
3. En [Settings → Secrets and variables → Actions](https://github.com/JavierRB35/Android_EReader/settings/secrets/actions), configurar estos **repository secrets**:
   - `ANDROID_KEYSTORE_BASE64`: contenido binario del keystore convertido a Base64 (sin archivo intermedio publicado).
   - `ANDROID_KEYSTORE_PASSWORD`: contraseña del keystore.
   - `ANDROID_KEY_ALIAS`: alias exacto de la clave.
   - `ANDROID_KEY_PASSWORD`: contraseña de la clave.
4. No crear ningún token personal: el job de publicación utiliza `GITHUB_TOKEN` con permiso `contents: write` limitado a ese job.

En Linux/Termux con GitHub CLI autenticada, se puede cargar el keystore sin generar un fichero Base64 persistente:

```sh
base64 -w 0 /ruta/segura/bibliotecaia-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R JavierRB35/Android_EReader
```

Las demás contraseñas se introducen mediante GitHub Settings o `gh secret set` sin escribirlas en la línea de comandos. Base64 **no cifra** el archivo: tratar su contenido como una clave privada.

## Ensayo manual de firma, sin publicar versiones

El mismo workflow acepta **Run workflow** desde GitHub Actions → **Publish signed APK** → rama `main`. No necesita tag, y su job `publish` se omite obligatoriamente en este modo. Conservará únicamente un artefacto temporal `signed-release-apk` durante siete días. No usar ese artefacto como v1.0 pública sin QA.

Además de los cuatro **Actions Secrets** indicados arriba, debes configurar una **Actions Variable pública** (no secret):

`ANDROID_RELEASE_CERT_SHA256`: huella SHA-256 de la **clave definitiva realmente custodiada**, obtenida localmente con `keytool -list -v -keystore /ruta/a/la/clave.jks`. No inventar ni reutilizar una huella de pruebas; si existen APK ya distribuidas, debe coincidir con su certificado. El workflow admite los 32 bytes hexadecimales con o sin `:` y rechazará una huella vacía, malformada o distinta de `apksigner verify --print-certs`.

En GitHub: **Settings → Secrets and variables → Actions → Variables → New repository variable**. Registrar solo el valor público de la huella; nunca la contraseña ni el keystore en Variables.

Para lanzar la prueba desde GitHub CLI (alternativa a la interfaz web):

```sh
gh workflow run release-apk.yml -R JavierRB35/Android_EReader --ref main
gh run list -R JavierRB35/Android_EReader --workflow release-apk.yml --event workflow_dispatch --limit 1
```

Comprueba en el run **Build, sign and verify APK = success**, **Publish GitHub Release = skipped**, la huella del certificado, el checksum `.sha256` y que no existe un tag/release nuevo. El archivo de prueba se denomina `BibliotecaIA-main.apk`, no `v1.0.0`.

**No ejecutar un tag de producción** mientras falten firma auténtica, verificación AndroidIDE/teléfono, restauración Drive SAF y paridad de selección PDF. Para publicar v1.0, cambiar previamente `versionName` y aumentar `versionCode`; mantener el mismo certificado de publicación en todas las actualizaciones.

## Publicar una versión

**Solo después de integrar y verificar el cambio, y de configurar los cuatro secretos**:

1. Asegurarse de que la versión de `app/build.gradle` coincide con la etiqueta: `versionName "0.1.0"` para `v0.1.0`. Aumentar `versionCode` con cada actualización para que Android acepte la nueva versión.
2. Verificar el build de la rama estable y revisar los cambios. No distribuir instalaciones con la firma debug esperando una actualización in situ con la firma release: Android exige conservar el certificado de firma (de lo contrario, puede ser necesario desinstalar, con riesgo para los datos locales).
3. Crear una etiqueta anotada sobre el commit **ya integrado en main** y publicarla:

```sh
git switch main
git pull --ff-only
git tag -a v0.1.0 -m "Biblioteca IA v0.1.0"
git push origin v0.1.0
```

El workflow comprobará que la etiqueta tiene formato `vX.Y.Z`, corresponde a `versionName` y apunta a un commit contenido en `main`. Solo tras compilar, firmar y verificar el APK, creará la GitHub Release con `BibliotecaIA-vX.Y.Z.apk` y el archivo `.sha256`. Si faltan los secretos, la firma falla y **no se publica** nada. No sobrescribe releases existentes.

GitHub Releases distribuye el archivo; **no instala silenciosamente actualizaciones** en Android. La instalación sigue dependiendo del usuario o de un mecanismo de actualización específico de la aplicación.

## Validación pendiente de la primera publicación

Revisar el workflow en la PR y ejecutar el primer lanzamiento con secretos auténticos solo cuando exista autorización de publicación. Verificar la firma/certificado del APK publicado, compatibilidad de instalación sobre una versión anterior firmada con esa misma clave y conservación de datos. No considerar completado ese recorrido hasta observarlo realmente.
