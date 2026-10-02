#!/usr/bin/env bash
set -euo pipefail

# Reconstruct exact Organizer Pro v0.9.34 source, stopping before compilation.
awk '/^gradle --no-daemon/{exit} {print}' organizer/build_v0934_ci.sh > /tmp/reconstruct_v0934.sh
bash /tmp/reconstruct_v0934.sh

ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.34-source
python3 organizer/v0125/apply.py "$ROOT"
mv "$ROOT" organizer/buildsrc0106/Organizer-Pro-v0.9.35-source
ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.35-source

python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
for p in root.rglob('*'):
    if p.is_file() and p.suffix in {'.kt','.kts','.xml','.txt'}:
        try: s=p.read_text()
        except UnicodeDecodeError: continue
        ns=s.replace('0.9.34 (67)','0.9.35 (68)')
        if ns!=s: p.write_text(ns)
PY

UI="$ROOT/app/src/main/java/kz/kairat/organizer/OrganizerUi.kt"

grep -q 'versionCode = 68' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "0.9.35"' "$ROOT/app/build.gradle.kts"
grep -q 'applicationId = "kz.kairat.organizer"' "$ROOT/app/build.gradle.kts"
grep -q 'SQLiteOpenHelper(context, "organizer.db", null, 9)' "$ROOT/app/src/main/java/kz/kairat/organizer/NoteDatabase.kt"
grep -Fq 'listOf("overview","income","expense")' "$UI"
! grep -Fq 'listOf("overview","income","expense","analytics")' "$UI"
grep -Fq 'Text(title,style=MaterialTheme.typography.labelMedium' "$UI"
grep -Fq 'wide=(section=="money" && moneyTab==0)' "$UI"
grep -q 'android.permission.READ_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"
! grep -q 'android.permission.WRITE_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"

gradle --no-daemon --build-cache -p "$ROOT" assembleDebug

test -f "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
