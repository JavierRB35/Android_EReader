# Publicación de APK firmadas en GitHub Releases

El workflow `.github/workflows/release-apk.yml` compila el APK `release` **sin firmar** en un runner Linux y lo alinea y firma con `zipalign` y `apksigner`. No modifica la compilación de AndroidIDE-Rv2 ni publica versiones al hacer push a `main`.

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

## Verificación manual de la firma (no publica releases)

El mismo workflow puede ejecutarse **manualmente sobre `main`**, pero solo cuando esta modificación esté revisada e integrada en la rama principal. La ejecución manual compila, firma y verifica el APK y guarda un artefacto temporal de siete días. **El job de publicación solo se ejecuta al hacer push de un tag de versión**, nunca con el disparador manual.

Desde GitHub CLI autenticada:

```sh
gh workflow run release-apk.yml -R JavierRB35/Android_EReader --ref main
gh run list -R JavierRB35/Android_EReader --workflow release-apk.yml --event workflow_dispatch --limit 1
```

Abrir la ejecución en [GitHub Actions](https://github.com/JavierRB35/Android_EReader/actions) para comprobar `Build, sign and verify APK`. Si finaliza correctamente, descargar el artefacto `signed-release-apk`, que contendrá `BibliotecaIA-main.apk` y su checksum, **sin crear una GitHub Release**.

El workflow compara automáticamente la huella SHA-256 del certificado de firma del APK con la huella aprobada de la clave de publicación actual:

`AD:26:7C:CB:94:11:8E:81:72:F2:E5:FC:20:2F:F0:C7:E0:07:AD:C5:BE:AA:0D:B8:85:06:E9:EA:FA:0F:F6:AE`

La huella del certificado es pública y no revela la clave privada. Si la firma no coincide, el job falla. No modificar esta huella al rotar una clave sin analizar primero el efecto sobre las instalaciones existentes.

Para comprobar nombres de secretos sin mostrarlos: `gh secret list -R JavierRB35/Android_EReader`. Los valores se gestionan solo en GitHub Actions Secrets, **nunca en Variables**, archivos del repositorio o logs.

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
