#!/usr/bin/env bash
set -euo pipefail

python3 organizer/v0106/rebuild.py
test -f organizer/buildsrc0106/Organizer-Pro-v0.9.16-source/app/build.gradle.kts
python3 organizer/v0107/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.16-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.16-source organizer/buildsrc0106/Organizer-Pro-v0.9.17-source
python3 organizer/v0108/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.17-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.17-source organizer/buildsrc0106/Organizer-Pro-v0.9.18-source
python3 organizer/v0109/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.18-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.18-source organizer/buildsrc0106/Organizer-Pro-v0.9.19-source
python3 organizer/v0110/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.19-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.19-source organizer/buildsrc0106/Organizer-Pro-v0.9.20-source
python3 organizer/v0111/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.20-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.20-source organizer/buildsrc0106/Organizer-Pro-v0.9.21-source
python3 organizer/v0112/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.21-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.21-source organizer/buildsrc0106/Organizer-Pro-v0.9.22-source
python3 organizer/v0113/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.22-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.22-source organizer/buildsrc0106/Organizer-Pro-v0.9.23-source
python3 organizer/v0114/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.23-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.23-source organizer/buildsrc0106/Organizer-Pro-v0.9.24-source
sed -i "s/assert a.count('0.9.24 (57)')==8/assert a.count('0.9.24 (57)')==16/" organizer/v0115/apply.py
python3 organizer/v0115/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.24-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.24-source organizer/buildsrc0106/Organizer-Pro-v0.9.25-source
python3 organizer/v0115/fix_theme_compat.py organizer/buildsrc0106/Organizer-Pro-v0.9.25-source
python3 organizer/v0116/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.25-source
python3 organizer/v0116/fix_manifest.py organizer/buildsrc0106/Organizer-Pro-v0.9.25-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.25-source organizer/buildsrc0106/Organizer-Pro-v0.9.26-source
python3 organizer/v0117/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.26-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.26-source organizer/buildsrc0106/Organizer-Pro-v0.9.27-source
python3 organizer/v0118/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.27-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.27-source organizer/buildsrc0106/Organizer-Pro-v0.9.28-source
python3 organizer/v0119/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.28-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.28-source organizer/buildsrc0106/Organizer-Pro-v0.9.29-source
python3 organizer/v0120/apply.py organizer/buildsrc0106/Organizer-Pro-v0.9.29-source
mv organizer/buildsrc0106/Organizer-Pro-v0.9.29-source organizer/buildsrc0106/Organizer-Pro-v0.9.30-source

ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.30-source
grep -q 'versionCode = 63' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "0.9.30"' "$ROOT/app/build.gradle.kts"
grep -q 'applicationId = "kz.kairat.organizer"' "$ROOT/app/build.gradle.kts"
grep -q 'SQLiteOpenHelper(context, "organizer.db", null, 9)' "$ROOT/app/src/main/java/kz/kairat/organizer/NoteDatabase.kt"
grep -Fq 'CloudSyncSettingsCard(db,settings,lang)' "$ROOT/app/src/main/java/kz/kairat/organizer/OrganizerUi.kt"
grep -Fq 'CloudSyncAutoHost(db,settings,lang)' "$ROOT/app/src/main/java/kz/kairat/organizer/OrganizerUi.kt"
grep -Fq 'https://organizer-pro.onrender.com' "$ROOT/app/src/main/java/kz/kairat/organizer/OrganizerUi.kt"
grep -Fq 'https://organizer-pro.onrender.com' "$ROOT/app/src/main/java/kz/kairat/organizer/CloudSync.kt"
! grep -Fq 'https://organizer-pro.app' "$ROOT/app/src/main/java/kz/kairat/organizer/CloudSync.kt"
grep -Fq '/api/auth/login' "$ROOT/app/src/main/java/kz/kairat/organizer/CloudSync.kt"
grep -Fq '/api/auth/register' "$ROOT/app/src/main/java/kz/kairat/organizer/CloudSync.kt"
grep -Fq '/api/sync' "$ROOT/app/src/main/java/kz/kairat/organizer/CloudSync.kt"
! grep -q 'Firebase' "$ROOT/app/src/main/java/kz/kairat/organizer/CloudSync.kt"
grep -q 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.READ_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"
! grep -q 'android.permission.WRITE_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"

gradle --no-daemon --build-cache -p "$ROOT" assembleDebug
