#!/bin/sh
# Prepare the exact QA APKs before the emulator starts.
# Retry only Maven throttling during the build (never retry assertions here).
if [ "${1:-}" = "--prepare" ]; then
  attempt=1
  log="$(mktemp)"
  trap 'rm -f "$log"' EXIT HUP INT TERM
  while :; do
    if sh ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PBIBLIOTECA_QA=true --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.vfs.watch=false > "$log" 2>&1; then
      cat "$log"
      exit 0
    else
      result=$?
      cat "$log"
      if [ "$attempt" -ge 3 ] || ! grep -q 'Received status code 429' "$log"; then
        exit "$result"
      fi
      echo "Dependency server throttled the prebuild (HTTP 429); retry $attempt/2."
      sleep "$((attempt * 30))"
      attempt=$((attempt + 1))
    fi
  done
fi

# Isolated QA app: no live updater or system notification during UI assertions.
# Preserve the full instrumentation class list and original test exit status.
if sh ./gradlew :app:connectedDebugAndroidTest -PBIBLIOTECA_QA=true "-Pandroid.testInstrumentationRunnerArguments.class=$1" --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.vfs.watch=false; then
  exit 0
else
  result=$?
  mkdir -p app/build/outputs/androidTest-results/connected/diagnostics
  adb shell dumpsys window > app/build/outputs/androidTest-results/connected/diagnostics/windows.txt || true
  adb shell dumpsys activity activities > app/build/outputs/androidTest-results/connected/diagnostics/activities.txt || true
  adb shell dumpsys power > app/build/outputs/androidTest-results/connected/diagnostics/power.txt || true
  adb logcat -d -t 2000 > app/build/outputs/androidTest-results/connected/diagnostics/logcat.txt || true
  exit "$result"
fi
