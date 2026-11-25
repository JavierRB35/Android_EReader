#!/bin/sh
# Run the complete startup suite inside the live emulator. Preserve failure status.
if sh ./gradlew :app:connectedDebugAndroidTest -PBIBLIOTECA_QA=true -Pandroid.testInstrumentationRunnerArguments.class=com.javierrb35.bibliotecaia.SplashActivityLaunchTest,com.javierrb35.bibliotecaia.MainActivityLaunchTest,com.javierrb35.bibliotecaia.BookDetailLaunchTest,com.javierrb35.bibliotecaia.EpubReaderLaunchTest,com.javierrb35.bibliotecaia.backup.SingleFileBackupInstrumentedTest,com.javierrb35.bibliotecaia.backup.BackupRestoreInstrumentedTest,com.javierrb35.bibliotecaia.sync.SharedLibraryJourneyTest,com.javierrb35.bibliotecaia.sync.StorageFolderRebuildTest,com.javierrb35.bibliotecaia.sync.BookDeletionJourneyTest,com.javierrb35.bibliotecaia.sync.SharedFileStoreJourneyTest,com.javierrb35.bibliotecaia.ui.SharedSettingsJourneyDeviceTest,com.javierrb35.bibliotecaia.ui.SettingsBackupJourneyDeviceTest --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.vfs.watch=false; then
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
