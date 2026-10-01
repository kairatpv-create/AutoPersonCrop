from pathlib import Path
import sys

root=Path(sys.argv[1])
base=root/'app/src/main/java/kz/kairat/organizer'
ui=base/'OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=base/'AppStrings.kt'

u=ui.read_text()
sig='@Composable private fun SettingsScreen(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,buttonScale:Float,onButtonScale:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,projectsEnabled:Boolean,onProjectsEnabled:(Boolean)->Unit){'
assert sig in u
u=u.replace(sig,sig+'\n    val webUriHandler=androidx.compose.ui.platform.LocalUriHandler.current',1)
marker='        item{CloudSyncSettingsCard(db,settings,lang)}'
assert marker in u
web_item='''        item{CloudSyncSettingsCard(db,settings,lang)}
        item{SettingsCard("",verticalPadding=controlsCardPad,spacing=if(compactSettings)1.dp else 2.dp){
            Text(if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-версия Органайзер Про" else "Organizer Pro Web",style=MaterialTheme.typography.titleSmall)
            TextButton(onClick={webUriHandler.openUri("https://kairatpv-create.github.io/AutoPersonCrop/")},modifier=Modifier.fillMaxWidth()){
                Text("https://kairatpv-create.github.io/AutoPersonCrop/",maxLines=2)
            }
        }}'''
u=u.replace(marker,web_item,1)
ui.write_text(u)

b=gradle.read_text()
assert 'versionCode = 60' in b and 'versionName = "0.9.27"' in b
b=b.replace('versionCode = 60','versionCode = 61').replace('versionName = "0.9.27"','versionName = "0.9.28"')
gradle.write_text(b)

a=strings.read_text()
assert '0.9.27 (60)' in a
a=a.replace('0.9.27 (60)','0.9.28 (61)')
strings.write_text(a)

assert 'https://kairatpv-create.github.io/AutoPersonCrop/' in ui.read_text()
assert 'webUriHandler.openUri' in ui.read_text()
assert 'versionCode = 61' in gradle.read_text()
assert 'versionName = "0.9.28"' in gradle.read_text()
