#!/bin/sh
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
