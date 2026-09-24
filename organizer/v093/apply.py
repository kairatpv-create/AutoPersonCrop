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
    'ui.zlib.b64': 'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt',
}
for payload, rel in text_payloads.items():
    data = zlib.decompress(base64.b64decode((assets / payload).read_text()))
    target = root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)

# Bright glossy 3D logo approved for v0.9.3.
icon = base64.b64decode((assets / 'icon.b64').read_text())
old_xml = root / 'app/src/main/res/drawable/ic_app_icon_approved.xml'
if old_xml.exists():
    old_xml.unlink()
old_webp = root / 'app/src/main/res/drawable/ic_app_icon_approved.webp'
if old_webp.exists():
    old_webp.unlink()
icon_path = root / 'app/src/main/res/drawable-nodpi/ic_app_icon_approved.webp'
icon_path.parent.mkdir(parents=True, exist_ok=True)
icon_path.write_bytes(icon)

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
assert icon_path.exists() and icon_path.stat().st_size > 20000
