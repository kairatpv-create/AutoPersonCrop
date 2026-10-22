#!/usr/bin/env bash
set -euo pipefail
# Only called by the disposable emulator workflow. Never run against a personal phone.
[[ "${ORGANIZER_SYNTHETIC_ONLY:-}" == 1 ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
mkdir -p android-safety-evidence
adb install -r legacy-version71/mobile/android/app/build/outputs/apk/debug/app-debug.apk
adb install -r mobile/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant kz.kairat.organizer android.permission.POST_NOTIFICATIONS
runner=kz.kairat.organizer.test/androidx.test.runner.AndroidJUnitRunner
trap 'adb logcat -d -s AndroidRuntime:E > android-safety-evidence/runtime-errors.txt' EXIT
run_case() {
    local case_name="$1" file_name="$2"
    adb shell am instrument -w -r -e syntheticOnly 1 -e class "$case_name" "$runner" | tee "android-safety-evidence/$file_name.txt"
    python3 - "$file_name" <<'PY'
from pathlib import Path
import re,sys
text=Path('android-safety-evidence/'+sys.argv[1]+'.txt').read_text()
assert re.search(r'OK \(\d+ tests?\)', text), text
assert 'FAILURES' not in text and 'shortMsg' not in text, text
PY
}
# Collect independent groups in one run; every failure still blocks the final APK artifact.
failed=0
checked_case() { run_case "$@" || failed=1; }
run_case 'kz.kairat.organizer.LegacyUpgradeTest#seedVersion71' upgrade-seed
adb install -r mobile/android/app/build/outputs/apk/debug/app-debug.apk
run_case 'kz.kairat.organizer.LegacyUpgradeTest#verifyCandidateUpdate' upgrade-verify
checked_case kz.kairat.organizer.MobileSafetyTest data-offline-account
checked_case kz.kairat.organizer.ApkHardeningTest apk-hardening
checked_case 'kz.kairat.organizer.MobileRestartTest#seed' restart-seed
adb shell am force-stop kz.kairat.organizer
checked_case 'kz.kairat.organizer.MobileRestartTest#verifyAfterForceStop' restart-verify
# This instrumentation process must die inside the real restore before SQLite commit.
set +e
adb shell am instrument -w -r -e syntheticOnly 1 -e class 'kz.kairat.organizer.MobileCrashTest#haltBeforeSqlCommit' "$runner" > android-safety-evidence/expected-crash.txt 2>&1
set -e
if rg -q 'OK \(' android-safety-evidence/expected-crash.txt; then
  echo 'The crash injection unexpectedly completed normally'; exit 1
fi
checked_case 'kz.kairat.organizer.MobileCrashTest#recoverAfterHalt' crash-recovery
adb shell am force-stop kz.kairat.organizer
adb install -r organizer-apk-stage/Organizer-Pro-v0.9.40-staging.apk
checked_case 'kz.kairat.organizer.LegacyUpgradeTest#verifyReleaseUpdate' release-upgrade
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
checked_case kz.kairat.organizer.MobileUiTest ui-navigation
checked_case kz.kairat.organizer.EmailBackupImportTest email-backup
adb pull /sdcard/Android/data/kz.kairat.organizer/files/synthetic-ui android-safety-evidence/ || failed=1
adb logcat -c
adb shell am start -W -n kz.kairat.organizer/.MainActivity | tee android-safety-evidence/launch.txt
adb shell uiautomator dump /sdcard/synthetic-window.xml
adb pull /sdcard/synthetic-window.xml android-safety-evidence/launch-window.xml
adb logcat -d -s AndroidRuntime:E > android-safety-evidence/runtime-errors.txt
python3 - <<'PY'
import json,re
from pathlib import Path
root=Path('android-safety-evidence')
counts={}
for name in ['upgrade-seed','upgrade-verify','data-offline-account','apk-hardening','restart-seed','restart-verify','crash-recovery','release-upgrade','ui-navigation','email-backup']:
    text=(root/(name+'.txt')).read_text();counts[name]=int(re.search(r'OK \((\d+) tests?\)',text).group(1))
for lang in ['ru','en']:
    for screen in ['notes','finances','projects','settings']:
        assert (root/'synthetic-ui'/f'{lang}-{screen}.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
assert 'FATAL EXCEPTION' not in (root/'runtime-errors.txt').read_text()
assert 'Status: ok' in (root/'launch.txt').read_text()
(root/'summary.json').write_text(json.dumps({'platform':'Android emulator API 36','syntheticOnly':True,'passed':sum(counts.values()),'groups':counts,'expectedAbruptRestoreTermination':True,'realPhone':False},indent=2))
print((root/'summary.json').read_text())
PY

[[ "$failed" == 0 ]]
