#!/usr/bin/env bash
set -euo pipefail

# Reuse the AppGallery release source, stopping before the historical build step.
awk '/^gradle --no-daemon/{exit} {print}' organizer/build_v0939_huawei_ci.sh | bash
root=organizer/buildsrc0106/Organizer-Pro-v0.9.38-source

python3 - "$root" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
sources = root / 'app/src/main/java/kz/kairat/organizer'

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise SystemExit(f'Expected exactly one occurrence of {old!r}')
    return text.replace(old, new, 1)

gradle = root / 'app/build.gradle.kts'
build = replace_once(gradle.read_text(), 'versionCode = 72', 'versionCode = 73')
build = replace_once(build, 'versionName = "0.9.39"', 'versionName = "0.9.40"')
gradle.write_text(build)

translations = sources / 'AppStrings.kt'
strings, updated = re.subn(
    r'("version" to "[^"\n]*)0\.9\.38 \(71\)"', r'\g<1>%s"', translations.read_text()
)
if updated != 16:
    raise SystemExit(f'Expected 16 version labels, updated {updated}')
translations.write_text(strings)

ui = sources / 'OrganizerUi.kt'
settings = replace_once(
    ui.read_text(), 'import androidx.compose.ui.layout.ContentScale\n',
    'import androidx.compose.ui.layout.ContentScale\nimport androidx.core.content.pm.PackageInfoCompat\n',
)
settings = replace_once(
    settings, '    val compactSettings=buttonScale<=1.16f\n',
    '''    val installedVersion = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = PackageInfoCompat.getLongVersionCode(info)
        info.versionName?.let { "$it ($code)" } ?: code.toString()
    }
    val compactSettings=buttonScale<=1.16f
''',
)
settings = replace_once(
    settings, 'Text(AppStrings.t(lang,"version"),style=MaterialTheme.typography.bodyMedium)',
    'Text(AppStrings.t(lang,"version").format(Locale.ROOT, installedVersion),style=MaterialTheme.typography.bodyMedium)',
)
ui.write_text(settings)
PY

gradle --no-daemon --build-cache -p "$root" assembleRelease

apk="$root/app/build/outputs/apk/release/app-release-unsigned.apk"
tools="$ANDROID_HOME/build-tools/35.0.0"
badging=$("$tools/aapt" dump badging "$apk")
grep -Fq "package: name='kz.kairat.organizer' versionCode='73' versionName='0.9.40'" <<< "$badging"
manifest=$("$tools/aapt" dump xmltree "$apk" AndroidManifest.xml)
if grep -Eq 'android:(debuggable|testOnly).*0xffffffff' <<< "$manifest"; then
    echo 'APK is debuggable or test-only' >&2
    exit 1
fi
if "$tools/apksigner" verify "$apk"; then
    echo 'Release APK must be unsigned for offline signing' >&2
    exit 1
fi
