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
run_case 'kz.kairat.organizer.LegacyUpgradeTest#seedVersion71' upgrade-seed
adb install -r mobile/android/app/build/outputs/apk/debug/app-debug.apk
run_case 'kz.kairat.organizer.LegacyUpgradeTest#verifyVersion72' upgrade-verify
run_case kz.kairat.organizer.MobileSafetyTest data-offline-account
run_case kz.kairat.organizer.ApkHardeningTest apk-hardening
run_case 'kz.kairat.organizer.MobileRestartTest#seed' restart-seed
adb shell am force-stop kz.kairat.organizer
run_case 'kz.kairat.organizer.MobileRestartTest#verifyAfterForceStop' restart-verify
# This instrumentation process must die inside the real restore before SQLite commit.
set +e
adb shell am instrument -w -r -e syntheticOnly 1 -e class 'kz.kairat.organizer.MobileCrashTest#haltBeforeSqlCommit' "$runner" > android-safety-evidence/expected-crash.txt 2>&1
set -e
if rg -q 'OK \(' android-safety-evidence/expected-crash.txt; then
  echo 'The crash injection unexpectedly completed normally'; exit 1
fi
run_case 'kz.kairat.organizer.MobileCrashTest#recoverAfterHalt' crash-recovery
adb shell am force-stop kz.kairat.organizer
adb install -r organizer-apk-stage/Organizer-Pro-v0.9.39-staging.apk
run_case 'kz.kairat.organizer.LegacyUpgradeTest#verifyReleaseUpdate' release-upgrade
run_case kz.kairat.organizer.MobileUiTest ui-navigation
mkdir -p android-safety-evidence/ui
for name in ru-notes ru-finances ru-projects ru-settings en-notes en-finances en-projects en-settings; do
  adb pull "/sdcard/Android/data/kz.kairat.organizer/files/synthetic-ui/$name.png" "android-safety-evidence/ui/$name.png"
done
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
for name in ['upgrade-seed','upgrade-verify','data-offline-account','apk-hardening','restart-seed','restart-verify','crash-recovery','release-upgrade','ui-navigation']:
    text=(root/(name+'.txt')).read_text();counts[name]=int(re.search(r'OK \((\d+) tests?\)',text).group(1))
assert 'FATAL EXCEPTION' not in (root/'runtime-errors.txt').read_text()
assert 'Status: ok' in (root/'launch.txt').read_text()
(root/'summary.json').write_text(json.dumps({'platform':'Android emulator API 36','syntheticOnly':True,'passed':sum(counts.values()),'groups':counts,'expectedAbruptRestoreTermination':True,'realPhone':False},indent=2))
print((root/'summary.json').read_text())
PY
