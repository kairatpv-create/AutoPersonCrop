from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/kz/kairat/organizer'
cloud = base / 'CloudSync.kt'
ui = base / 'OrganizerUi.kt'
gradle = root / 'app/build.gradle.kts'
strings = base / 'AppStrings.kt'

# Switch the production client from the reserved custom domain to the live Render endpoint.
c = cloud.read_text()
assert 'https://organizer-pro.app' in c
assert 'connectTimeout=15000' in c
assert 'readTimeout=25000' in c
c = c.replace('https://organizer-pro.app', 'https://organizer-pro.onrender.com')
# Render free services can need tens of seconds to wake after inactivity.
c = c.replace('connectTimeout=15000', 'connectTimeout=70000')
c = c.replace('readTimeout=25000', 'readTimeout=90000')
cloud.write_text(c)

u = ui.read_text()
assert 'https://organizer-pro.app' in u
u = u.replace('https://organizer-pro.app', 'https://organizer-pro.onrender.com')
ui.write_text(u)

b = gradle.read_text()
assert 'versionCode = 62' in b and 'versionName = "0.9.29"' in b
b = b.replace('versionCode = 62', 'versionCode = 63').replace('versionName = "0.9.29"', 'versionName = "0.9.30"')
gradle.write_text(b)

a = strings.read_text()
assert '0.9.29 (62)' in a
a = a.replace('0.9.29 (62)', '0.9.30 (63)')
strings.write_text(a)

final_cloud = cloud.read_text()
assert 'https://organizer-pro.onrender.com' in final_cloud
assert 'https://organizer-pro.app' not in final_cloud
assert 'connectTimeout=70000' in final_cloud
assert 'readTimeout=90000' in final_cloud
assert 'https://organizer-pro.onrender.com' in ui.read_text()
assert 'versionCode = 63' in gradle.read_text()
assert 'versionName = "0.9.30"' in gradle.read_text()
