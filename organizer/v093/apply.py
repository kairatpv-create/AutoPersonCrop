from pathlib import Path
import base64, zlib, sys

root = Path(sys.argv[1])
assets = Path(__file__).resolve().parent

# Keep application id/data storage intact; only bump the release version.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text()
s = s.replace('versionCode = 35', 'versionCode = 36').replace('versionName = "0.9.2"', 'versionName = "0.9.3"')
assert 'versionCode = 36' in s and 'versionName = "0.9.3"' in s
gradle.write_text(s)

text_payloads = {
    'models.zlib.b64': 'app/src/main/java/kz/kairat/organizer/Models.kt',
    'db.zlib.b64': 'app/src/main/java/kz/kairat/organizer/NoteDatabase.kt',
    'backup.zlib.b64': 'app/src/main/java/kz/kairat/organizer/BackupManager.kt',
    'strings.zlib.b64': 'app/src/main/java/kz/kairat/organizer/AppStrings.kt',
}
for payload, rel in text_payloads.items():
    data = zlib.decompress(base64.b64decode((assets / payload).read_text()))
    target = root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)

ui_b64 = ''.join((assets / f'ui.part{i:02d}').read_text() for i in range(4))
ui_target = root / 'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
ui_target.write_bytes(zlib.decompress(base64.b64decode(ui_b64)))

# Bright glossy 3D logo approved for v0.9.3.
old_xml = root / 'app/src/main/res/drawable/ic_app_icon_approved.xml'
old_webp = root / 'app/src/main/res/drawable/ic_app_icon_approved.webp'
old_png = root / 'app/src/main/res/drawable-nodpi/ic_app_icon_approved.png'
old_nodpi_webp = root / 'app/src/main/res/drawable-nodpi/ic_app_icon_approved.webp'
for f in (old_xml, old_webp, old_png, old_nodpi_webp):
    if f.exists(): f.unlink()
logo_xml = r'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#0A63B5" android:pathData="M22,8 H88 Q103,8 103,23 V88 Q103,103 88,103 H22 Q7,103 7,88 V23 Q7,8 22,8 Z"/>
    <path android:fillColor="#20BFF2" android:pathData="M20,5 H86 Q102,5 102,21 V86 Q102,102 86,102 H20 Q5,102 5,86 V21 Q5,5 20,5 Z"/>
    <path android:fillColor="#0A8FE6" android:pathData="M7,59 C26,83 56,99 89,94 Q102,92 102,80 V86 Q102,102 86,102 H20 Q5,102 5,86 V59 Z"/>
    <path android:fillColor="#55FFFFFF" android:pathData="M20,9 H80 Q94,9 98,20 C77,13 39,14 12,31 V21 Q12,9 20,9 Z"/>
    <path android:fillColor="#55004477" android:pathData="M29,27 H69 Q79,27 79,37 V80 Q79,90 69,90 H29 Q19,90 19,80 V37 Q19,27 29,27 Z"/>
    <path android:fillColor="#FFFFFF" android:pathData="M27,22 H67 Q77,22 77,32 V76 Q77,86 67,86 H27 Q17,86 17,76 V32 Q17,22 27,22 Z"/>
    <path android:fillColor="#EAF8FF" android:pathData="M28,26 H66 Q72,26 72,32 V76 Q72,81 66,81 H28 Z"/>
    <path android:fillColor="#0A84D8" android:pathData="M30,39 H62 V42 H30 Z M30,49 H62 V52 H30 Z M30,59 H57 V62 H30 Z M30,69 H51 V72 H30 Z"/>
    <path android:fillColor="#53C8FA" android:pathData="M31,37 H59 V38 H31 Z M31,47 H59 V48 H31 Z"/>
    <path android:fillColor="#0870B5" android:pathData="M13,32 H26 Q30,32 30,36 Q30,40 26,40 H13 Q9,40 9,36 Q9,32 13,32 Z M13,47 H26 Q30,47 30,51 Q30,55 26,55 H13 Q9,55 9,51 Q9,47 13,47 Z M13,62 H26 Q30,62 30,66 Q30,70 26,70 H13 Q9,70 9,66 Q9,62 13,62 Z M13,77 H26 Q30,77 30,81 Q30,85 26,85 H13 Q9,85 9,81 Q9,77 13,77 Z"/>
    <path android:fillColor="#A9EEFF" android:pathData="M13,34 H25 Q27,34 27,36 H13 Z M13,49 H25 Q27,49 27,51 H13 Z M13,64 H25 Q27,64 27,66 H13 Z M13,79 H25 Q27,79 27,81 H13 Z"/>
    <path android:fillColor="#66004466" android:pathData="M78,21 L93,28 L67,88 L52,81 Z"/>
    <path android:fillColor="#FFD629" android:pathData="M75,17 L90,24 L64,84 L49,77 Z"/>
    <path android:fillColor="#F1A800" android:pathData="M84,21 L90,24 L64,84 L58,81 Z"/>
    <path android:fillColor="#FFF59A" android:pathData="M77,20 L81,22 L57,78 L53,76 Z"/>
    <path android:fillColor="#0A84D8" android:pathData="M69,29 L84,36 L81,43 L66,36 Z"/>
    <path android:fillColor="#F6B500" android:pathData="M49,77 L64,84 L51,93 Z"/>
    <path android:fillColor="#FFF2A1" android:pathData="M51,79 L57,82 L52,88 Z"/>
    <path android:fillColor="#FFFFFFFF" android:pathData="M88,10 L90,16 L96,18 L90,20 L88,26 L86,20 L80,18 L86,16 Z"/>
    <path android:fillColor="#AAFFFFFF" android:pathData="M18,13 L19,17 L23,18 L19,19 L18,23 L17,19 L13,18 L17,17 Z"/>
</vector>
'''
icon_path = root / 'app/src/main/res/drawable/ic_app_icon_approved.xml'
icon_path.write_text(logo_xml)

# Safety gates: old data model is migrated non-destructively and all requested UI pieces exist.
models = (root / 'app/src/main/java/kz/kairat/organizer/Models.kt').read_text()
db = (root / 'app/src/main/java/kz/kairat/organizer/NoteDatabase.kt').read_text()
ui = (root / 'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt').read_text()
strings = (root / 'app/src/main/java/kz/kairat/organizer/AppStrings.kt').read_text()
backup = (root / 'app/src/main/java/kz/kairat/organizer/BackupManager.kt').read_text()
assert 'val colorKey: String = "blue"' in models
assert 'SQLiteOpenHelper(context, "organizer.db", null, 8)' in db
assert "ALTER TABLE notes ADD COLUMN color_key TEXT NOT NULL DEFAULT 'blue'" in db
assert 'BackHandler{onBack()}' in ui and 'BackHandler{close()}' in ui
assert 'NoteColorPicker(colorKey,lang)' in ui and 'noteCardColors(n.colorKey)' in ui
assert 'MoneyAnalytics(db,list,lang' in ui and 'AnalyticsChartCard(entries,lang)' in ui
assert 'languageFlag(code)' in ui
assert 'val supportedLanguages = listOf("kk","ru","en","zh","de","es","fr","tr")' in strings
assert '0.9.3 (36)' in strings
assert 'put("colorKey",n.colorKey)' in backup and 'optString("colorKey","blue")' in backup
assert icon_path.exists() and icon_path.stat().st_size > 2000
