from pathlib import Path
import sys

root=Path(sys.argv[1])
here=Path(__file__).parent
base=root/'app/src/main/java/kz/kairat/organizer'
ui=base/'OrganizerUi.kt'
cloud=base/'CloudSync.kt'
gradle=root/'app/build.gradle.kts'
strings=base/'AppStrings.kt'

# Replace the Firebase/manual bridge with the first-party Organizer Pro server client.
cloud.write_text((here/'CloudSync.kt').read_text())

u=ui.read_text()
# v0.9.27 inserted the old Firebase auto-sync host. Remove it before wiring the first-party host.
u=u.replace('    CloudAutoSyncHost(db,settings,lang)\n','')
home='''@Composable private fun OrganizerHome(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,buttonScale:Float,onButtonScale:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,onLock:()->Unit){
    val context=LocalContext.current'''
assert home in u
u=u.replace(home,home+'\n    CloudSyncAutoHost(db,settings,lang)',1)
u=u.replace('https://kairatpv-create.github.io/AutoPersonCrop/','https://organizer-pro.app')
assert 'CloudAutoSyncHost(db,settings,lang)' not in u
assert u.count('CloudSyncAutoHost(db,settings,lang)')==1
ui.write_text(u)

b=gradle.read_text()
assert 'versionCode = 61' in b and 'versionName = "0.9.28"' in b
b=b.replace('versionCode = 61','versionCode = 62').replace('versionName = "0.9.28"','versionName = "0.9.29"')
gradle.write_text(b)

a=strings.read_text()
assert '0.9.28 (61)' in a
a=a.replace('0.9.28 (61)','0.9.29 (62)')
strings.write_text(a)

c=cloud.read_text()
assert 'https://organizer-pro.app' in c
assert '/api/auth/login' in c and '/api/auth/register' in c and '/api/sync' in c
assert 'CloudSyncAutoHost' in c and 'CloudSyncSettingsCard' in c
assert 'Project ID' not in c and 'Web API key' not in c and 'Firebase' not in c
assert 'Отправить с телефона' not in c and 'Получить из облака' not in c
assert 'https://organizer-pro.app' in ui.read_text()
assert 'kairatpv-create.github.io' not in ui.read_text()
assert 'versionCode = 62' in gradle.read_text() and 'versionName = "0.9.29"' in gradle.read_text()
