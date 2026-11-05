#!/usr/bin/env bash
set -euo pipefail

# Reconstruct the tested 0.9.38 Android sources without compiling first.
awk '/^gradle --no-daemon/{exit} {print}' organizer/build_v0938_ci.sh > /tmp/organizer_reconstruct_0938.sh
bash /tmp/organizer_reconstruct_0938.sh
ROOT=organizer/buildsrc0106/Organizer-Pro-v0.9.38-source

python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
u=ui.read_text()
old='''        item{SettingsCard("",verticalPadding=if(compactSettings)6.dp else 8.dp,spacing=3.dp){
            Text(
                if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-страница" else "Web page",
                style=MaterialTheme.typography.titleSmall,
                maxLines=1
            )
            Text(
                "https://organizer-pro.onrender.com",
                modifier=Modifier.fillMaxWidth(),
                style=MaterialTheme.typography.bodySmall,
                maxLines=2,
                softWrap=true,
                overflow=TextOverflow.Visible
            )
        }}
'''
assert u.count(old)==1, "Expected web-page card not found exactly once"
u=u.replace(old,'',1)
ui.write_text(u)
g=gradle.read_text()
assert 'versionCode = 71' in g and 'versionName = "0.9.38"' in g
g=g.replace('versionCode = 71','versionCode = 72',1).replace('versionName = "0.9.38"','versionName = "0.9.39"',1)
gradle.write_text(g)
assert 'organizer-pro.onrender.com' not in u or 'https://organizer-pro.onrender.com' in u, "Unexpected source state"
PY

# Diagnostic build only; this APK is NOT a signed AppGallery release.
gradle --no-daemon --build-cache -p "$ROOT" assembleDebug
test -f "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
