#!/usr/bin/env bash
set -euo pipefail

# Rebuild exact Organizer Pro v0.9.31 without compiling it again.
head -n -1 organizer/build_v0931.sh > /tmp/reconstruct_v0931.sh
bash /tmp/reconstruct_v0931.sh

ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.31-source
python3 organizer/v0122/apply.py "$ROOT"
mv "$ROOT" organizer/buildsrc0106/Organizer-Pro-v0.9.32-source
ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.32-source

UI="$ROOT/app/src/main/java/kz/kairat/organizer/OrganizerUi.kt"
CLOUD="$ROOT/app/src/main/java/kz/kairat/organizer/CloudSync.kt"
BACKUP="$ROOT/app/src/main/java/kz/kairat/organizer/BackupManager.kt"
STRINGS="$ROOT/app/src/main/java/kz/kairat/organizer/AppStrings.kt"

grep -q 'versionCode = 65' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "0.9.32"' "$ROOT/app/build.gradle.kts"
grep -q 'applicationId = "kz.kairat.organizer"' "$ROOT/app/build.gradle.kts"
grep -q 'SQLiteOpenHelper(context, "organizer.db", null, 9)' "$ROOT/app/src/main/java/kz/kairat/organizer/NoteDatabase.kt"
grep -Fq 'CloudSyncSettingsCard(db,settings,lang,compactSettings)' "$UI"
grep -Fq 'onePageSettings=fontScale<=.90f&&buttonScale<=.90f' "$UI"
grep -Fq 'saveBackup.launch(BackupManager.suggestedFileName(archiveBackup))' "$UI"
grep -Fq 'BackupManager.writeArchiveToUri' "$UI"
grep -Fq 'application/zip' "$UI"
grep -Fq '@Composable fun CloudSyncSettingsCard(db:NoteDatabase,settings:SettingsStore,lang:String,compact:Boolean=false)' "$CLOUD"
grep -Fq 'fun writeArchiveToUri' "$BACKUP"
grep -Fq 'ZipInputStream' "$BACKUP"
grep -Fq 'archive_backup' "$STRINGS"
! grep -Fq 'Сохранить в файл / Google Drive' "$STRINGS"
grep -q 'connectTimeout=70000' "$CLOUD"
grep -q 'readTimeout=90000' "$CLOUD"
grep -q 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.READ_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"
! grep -q 'android.permission.WRITE_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"

gradle --no-daemon --build-cache -p "$ROOT" assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
test -f "$APK"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
DEBUG_KS="$HOME/.android/debug.keystore"
test -f "$DEBUG_KS"

# AGP debug signing currently emits v2 only. Re-sign the already aligned APK with
# both v2 and v3 enabled, using the same debug key generated for this build.
"$APKSIGNER" sign \
  --ks "$DEBUG_KS" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  "$APK"

"$APKSIGNER" verify --verbose --print-certs "$APK" | tee /tmp/apksigner-v0932.txt
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' /tmp/apksigner-v0932.txt
grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' /tmp/apksigner-v0932.txt
