#!/usr/bin/env bash
set -euo pipefail

# Rebuild the exact current Organizer Pro source through v0.9.30.
head -n -1 organizer/build_v0930.sh > /tmp/reconstruct_v0930.sh
bash /tmp/reconstruct_v0930.sh

ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.30-source
python3 organizer/v0121/apply.py "$ROOT"
mv "$ROOT" organizer/buildsrc0106/Organizer-Pro-v0.9.31-source
ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.31-source
python3 organizer/v0121/fix_sync_card_style.py "$ROOT"

UI="$ROOT/app/src/main/java/kz/kairat/organizer/OrganizerUi.kt"
CLOUD="$ROOT/app/src/main/java/kz/kairat/organizer/CloudSync.kt"

grep -q 'versionCode = 64' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "0.9.31"' "$ROOT/app/build.gradle.kts"
grep -q 'applicationId = "kz.kairat.organizer"' "$ROOT/app/build.gradle.kts"
grep -q 'SQLiteOpenHelper(context, "organizer.db", null, 9)' "$ROOT/app/src/main/java/kz/kairat/organizer/NoteDatabase.kt"
grep -Fq 'CloudSyncSettingsCard(db,settings,lang)' "$UI"
grep -Fq 'CloudSyncAutoHost(db,settings,lang)' "$UI"
grep -Fq 'https://organizer-pro.onrender.com' "$CLOUD"
grep -Fq 'openBackup.launch(arrayOf("application/json","text/plain","*/*"))' "$UI"
! grep -Fq 'saveBackup.launch(BackupManager.suggestedFileName())' "$UI"
! grep -Fq 'BackupManager.shareBackup(context,BackupManager.createBackup(db,settings)' "$UI"
grep -Fq 'CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)' "$CLOUD"
! grep -Fq 'CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)' "$CLOUD"
! grep -Fq 'syncText(lang,"hint")' "$CLOUD"
grep -q 'connectTimeout=70000' "$CLOUD"
grep -q 'readTimeout=90000' "$CLOUD"
grep -q 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.READ_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"
! grep -q 'android.permission.WRITE_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"

gradle --no-daemon --build-cache -p "$ROOT" assembleDebug
