#!/usr/bin/env bash
set -euo pipefail

# Reconstruct exact Organizer Pro v0.9.32 sources, but stop before its ephemeral debug signing step.
awk '/# Ensure a known debug-key location exists before AGP signs the debug APK\./{exit} {print}' organizer/build_v0932.sh > /tmp/reconstruct_v0932.sh
bash /tmp/reconstruct_v0932.sh

ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.32-source
GRADLE_FILE="$ROOT/app/build.gradle.kts"

python3 - "$GRADLE_FILE" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text()
assert 'versionCode = 65' in s
assert 'versionName = "0.9.32"' in s
s=s.replace('versionCode = 65','versionCode = 66',1)
s=s.replace('versionName = "0.9.32"','versionName = "0.9.33"',1)
p.write_text(s)
PY

# Keep any user-visible hard-coded version label aligned if present.
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
for p in root.rglob('*'):
    if p.is_file() and p.suffix in {'.kt','.kts','.xml','.txt'}:
        try:
            s=p.read_text()
        except UnicodeDecodeError:
            continue
        ns=s.replace('0.9.32 (65)','0.9.33 (66)')
        if ns!=s:
            p.write_text(ns)
PY

mv "$ROOT" organizer/buildsrc0106/Organizer-Pro-v0.9.33-source
ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.33-source

grep -q 'versionCode = 66' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "0.9.33"' "$ROOT/app/build.gradle.kts"
grep -q 'applicationId = "kz.kairat.organizer"' "$ROOT/app/build.gradle.kts"
grep -q 'SQLiteOpenHelper(context, "organizer.db", null, 9)' "$ROOT/app/src/main/java/kz/kairat/organizer/NoteDatabase.kt"
grep -q 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.READ_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"
! grep -q 'android.permission.WRITE_CALENDAR' "$ROOT/app/src/main/AndroidManifest.xml"

gradle --no-daemon --build-cache -p "$ROOT" assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
test -f "$APK"
KEYSTORE="organizer/signing/organizer-pro-stable.jks"
KEY_ALIAS="organizerpro"
KEY_PASS="OrganizerProTest2026"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
test -f "$KEYSTORE"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  "$APK"

"$APKSIGNER" verify --verbose --print-certs "$APK" | tee /tmp/apksigner-v0933.txt
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' /tmp/apksigner-v0933.txt
grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' /tmp/apksigner-v0933.txt

if [ -n "${EXPECTED_SIGNER_SHA256:-}" ]; then
  grep -q "Signer #1 certificate SHA-256 digest: ${EXPECTED_SIGNER_SHA256}" /tmp/apksigner-v0933.txt
fi
