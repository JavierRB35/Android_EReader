#!/bin/sh
# Compilación para AndroidIDE-Rv2 (teléfono Android, ARM64).
# Uso: sh ./build-on-phone.sh
# Genera /storage/emulated/0/Download/BibliotecaIA.apk únicamente si la compilación y el APK existen.
set -e

# Raíz del proyecto (ubicación donde esté este script)
cd "$(dirname "$0")"

APP_NAME="BibliotecaIA.apk"

# Opciones JVM validadas en el dispositivo objetivo (Prompt Maestro §4.3).
# No aumentar -Xmx: valores mayores han provocado bloqueos en AndroidIDE.
export JAVA_OPTS="-Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8"

sh ./gradlew assembleDebug --no-daemon --max-workers=1 \
  -Pandroid.aapt2FromMavenOverride=$HOME/.androidide/aapt2

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "ERROR: no se ha generado el APK en $APK"
  exit 1
fi

cp "$APK" "/storage/emulated/0/Download/$APP_NAME"

echo "BUILD OK: /storage/emulated/0/Download/$APP_NAME"
