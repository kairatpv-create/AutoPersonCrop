#!/usr/bin/env bash
set -euo pipefail

# Reconstruct exact Organizer Pro v0.9.35 source, stopping before compilation.
awk '/^gradle --no-daemon/{exit} {print}' organizer/build_v0935_ci.sh > /tmp/reconstruct_v0935.sh
bash /tmp/reconstruct_v0935.sh

ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.35-source
python3 organizer/v0126/apply.py "$ROOT"
mv "$ROOT" organizer/buildsrc0106/Organizer-Pro-v0.9.36-source
ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.36-source

python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
for p in root.rglob('*'):
    if p.is_file() and p.suffix in {'.kt','.kts','.xml','.txt'}:
        try: s=p.read_text()
        except UnicodeDecodeError: continue
        ns=s.replace('0.9.35 (68)','0.9.36 (69)')
        if ns!=s: p.write_text(ns)
PY

UI="$ROOT/app/src/main/java/kz/kairat/organizer/OrganizerUi.kt"
THEME="$ROOT/app/src/main/java/kz/kairat/organizer/Theme.kt"

grep -q 'versionCode = 69' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "0.9.36"' "$ROOT/app/build.gradle.kts"
grep -q 'applicationId = "kz.kairat.organizer"' "$ROOT/app/build.gradle.kts"
grep -q 'SQLiteOpenHelper(context, "organizer.db", null, 9)' "$ROOT/app/src/main/java/kz/kairat/organizer/NoteDatabase.kt"
grep -Fq 'val uiBodySize=(14f*buttonTextScale).sp' "$THEME"
grep -Fq 'fun userTextStyle(sizeSp:Float=14f,strong:Boolean=false)' "$THEME"
grep -Fq 'val compactSettings=buttonScale<=1.16f' "$UI"
grep -Fq 'val scale=LocalButtonTextScale.current' "$UI"
grep -Fq 'style=userTextStyle(15f,true)' "$UI"
grep -Fq 'textStyle=userTextStyle()' "$UI"
grep -Fq 'listOf("overview","income","expense")' "$UI"
! grep -Fq 'listOf("overview","income","expense","analytics")' "$UI"
grep -q 'android.permission.READ_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"
! grep -q 'android.permission.WRITE_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"

gradle --no-daemon --build-cache -p "$ROOT" assembleDebug

test -f "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
