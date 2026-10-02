#!/usr/bin/env bash
set -euo pipefail

awk '/^gradle --no-daemon/{exit} {print}' organizer/build_v0933.sh > /tmp/reconstruct_v0933.sh
bash /tmp/reconstruct_v0933.sh

ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.33-source
python3 organizer/v0124/apply.py "$ROOT"
mv "$ROOT" organizer/buildsrc0106/Organizer-Pro-v0.9.34-source
ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.34-source

python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
for p in root.rglob('*'):
    if p.is_file() and p.suffix in {'.kt','.kts','.xml','.txt'}:
        try: s=p.read_text()
        except UnicodeDecodeError: continue
        ns=s.replace('0.9.33 (66)','0.9.34 (67)')
        if ns!=s: p.write_text(ns)
PY

UI="$ROOT/app/src/main/java/kz/kairat/organizer/OrganizerUi.kt"
grep -q 'versionCode = 67' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "0.9.34"' "$ROOT/app/build.gradle.kts"
grep -q 'applicationId = "kz.kairat.organizer"' "$ROOT/app/build.gradle.kts"
grep -q 'SQLiteOpenHelper(context, "organizer.db", null, 9)' "$ROOT/app/src/main/java/kz/kairat/organizer/NoteDatabase.kt"
grep -Fq 'Switch(value,onChange,modifier=Modifier.scale(if(compact).72f else .84f))' "$UI"
grep -Fq 'scale(scaleX=1f,scaleY=if(compact).68f else .82f)' "$UI"
grep -Fq 'verticalPadding=if(compactSettings)0.dp else controlsCardPad' "$UI"
grep -q 'android.permission.READ_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"
! grep -q 'android.permission.WRITE_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"

gradle --no-daemon --build-cache -p "$ROOT" assembleDebug

test -f "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
