from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()

# v0.9.19: Settings must fit on one screen at the middle font slider position (1.15)
# even when Bold is enabled. Larger font settings may still scroll naturally.
old='''    val context=LocalContext.current;var msg by remember{mutableStateOf<String?>(null)};var showPin by remember{mutableStateOf(false)};var showDisablePin by remember{mutableStateOf(false)};var showChangePin by remember{mutableStateOf(false)};var languageMenu by remember{mutableStateOf(false)}'''
new='''    val context=LocalContext.current;var msg by remember{mutableStateOf<String?>(null)};var showPin by remember{mutableStateOf(false)};var showDisablePin by remember{mutableStateOf(false)};var showChangePin by remember{mutableStateOf(false)};var languageMenu by remember{mutableStateOf(false)}
    val compactSettings=fontScale<=1.16f
    val settingsOuterV=if(compactSettings)2.dp else 4.dp
    val settingsGap=if(compactSettings)3.dp else 4.dp
    val topCardPad=if(compactSettings)3.dp else 5.dp
    val controlsCardPad=if(compactSettings)2.dp else 4.dp
    val pinCardPad=if(compactSettings)2.dp else 4.dp
    val backupCardPad=if(compactSettings)3.dp else 5.dp
    val infoCardPad=if(compactSettings)3.dp else 5.dp'''
assert old in s
s=s.replace(old,new,1)

old='''        Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=4.dp),
        contentPadding=PaddingValues(bottom=4.dp),
        verticalArrangement=Arrangement.spacedBy(4.dp)'''
new='''        Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=settingsOuterV),
        contentPadding=PaddingValues(bottom=if(compactSettings)2.dp else 4.dp),
        verticalArrangement=Arrangement.spacedBy(settingsGap)'''
assert old in s
s=s.replace(old,new,1)

old='''        item{SettingsCard("",verticalPadding=5.dp,spacing=3.dp){'''
new='''        item{SettingsCard("",verticalPadding=topCardPad,spacing=if(compactSettings)2.dp else 3.dp){'''
assert old in s
s=s.replace(old,new,1)

old='''                AppLogo(Modifier.size(42.dp))'''
new='''                AppLogo(Modifier.size(if(compactSettings)38.dp else 42.dp))'''
assert old in s
s=s.replace(old,new,1)
old='''                Spacer(Modifier.width(7.dp))'''
new='''                Spacer(Modifier.width(if(compactSettings)5.dp else 7.dp))'''
assert s.count(old)>=2
s=s.replace(old,new,2)
old='''modifier=Modifier.size(44.dp)'''
new='''modifier=Modifier.size(if(compactSettings)40.dp else 44.dp)'''
assert old in s
s=s.replace(old,new,1)
old='''Icon(Icons.Default.Language,null,Modifier.size(23.dp))'''
new='''Icon(Icons.Default.Language,null,Modifier.size(if(compactSettings)21.dp else 23.dp))'''
assert old in s
s=s.replace(old,new,1)

old='''        item{SettingsCard("",verticalPadding=4.dp,spacing=1.dp){SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark);SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold);Row(Modifier.fillMaxWidth().heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically){Text(AppStrings.t(lang,"font"),Modifier.weight(.72f));Slider(fontScale,onFont,modifier=Modifier.weight(1.28f).heightIn(min=28.dp),valueRange=.85f..1.45f,steps=3)};SettingSwitch(AppStrings.t(lang,"show_projects"),projectsEnabled,onProjectsEnabled)}}'''
new='''        item{SettingsCard("",verticalPadding=controlsCardPad,spacing=if(compactSettings)0.dp else 1.dp){SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark);SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold);Row(Modifier.fillMaxWidth().heightIn(min=if(compactSettings)28.dp else 32.dp),verticalAlignment=Alignment.CenterVertically){Text(AppStrings.t(lang,"font"),Modifier.weight(.78f),maxLines=2);Slider(fontScale,onFont,modifier=Modifier.weight(1.22f).heightIn(min=if(compactSettings)26.dp else 28.dp),valueRange=.85f..1.45f,steps=3)};SettingSwitch(AppStrings.t(lang,"show_projects"),projectsEnabled,onProjectsEnabled)}}'''
assert old in s
s=s.replace(old,new,1)

old='''        item{SettingsCard("",verticalPadding=4.dp,spacing=2.dp){val enabled=settings.isPinProtectionEnabled();SettingSwitch(AppStrings.t(lang,"pin"),enabled){want->if(want&&!enabled)showPin=true else if(!want&&enabled)showDisablePin=true};if(enabled)OutlinedButton({showChangePin=true},Modifier.fillMaxWidth().heightIn(min=34.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp)){Text(AppStrings.t(lang,"change_pin"))}}}'''
new='''        item{SettingsCard("",verticalPadding=pinCardPad,spacing=if(compactSettings)1.dp else 2.dp){val enabled=settings.isPinProtectionEnabled();SettingSwitch(AppStrings.t(lang,"pin"),enabled){want->if(want&&!enabled)showPin=true else if(!want&&enabled)showDisablePin=true};if(enabled)OutlinedButton({showChangePin=true},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)30.dp else 34.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp)){Text(AppStrings.t(lang,"change_pin"))}}}'''
assert old in s
s=s.replace(old,new,1)

old='''        item{SettingsCard("",verticalPadding=5.dp,spacing=4.dp){Button({saveBackup.launch(BackupManager.suggestedFileName())},Modifier.fillMaxWidth().heightIn(min=38.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Icon(Icons.Default.Backup,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(AppStrings.t(lang,"save_backup"),maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center)};OutlinedButton({openBackup.launch(arrayOf("application/json","text/plain","*/*"))},Modifier.fillMaxWidth().heightIn(min=36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"restore"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)};OutlinedButton({BackupManager.shareBackup(context,BackupManager.createBackup(db,settings),settings.backupEmail,lang)},Modifier.fillMaxWidth().heightIn(min=36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"send_backup"),maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}}'''
new='''        item{SettingsCard("",verticalPadding=backupCardPad,spacing=if(compactSettings)2.dp else 4.dp){Button({saveBackup.launch(BackupManager.suggestedFileName())},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)34.dp else 38.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Icon(Icons.Default.Backup,null,Modifier.size(if(compactSettings)17.dp else 18.dp));Spacer(Modifier.width(if(compactSettings)5.dp else 6.dp));Text(AppStrings.t(lang,"save_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)};OutlinedButton({openBackup.launch(arrayOf("application/json","text/plain","*/*"))},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)32.dp else 36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"restore"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)};OutlinedButton({BackupManager.shareBackup(context,BackupManager.createBackup(db,settings),settings.backupEmail,lang)},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)32.dp else 36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"send_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}}'''
assert old in s
s=s.replace(old,new,1)

old='''        item{SettingsCard("",verticalPadding=5.dp,spacing=2.dp){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){'''
new='''        item{SettingsCard("",verticalPadding=infoCardPad,spacing=if(compactSettings)1.dp else 2.dp){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(if(compactSettings)0.dp else 2.dp)){'''
assert old in s
s=s.replace(old,new,1)
old='''                Spacer(Modifier.width(10.dp))
                AppLogo(Modifier.size(46.dp))'''
new='''                Spacer(Modifier.width(if(compactSettings)6.dp else 10.dp))
                AppLogo(Modifier.size(if(compactSettings)38.dp else 46.dp))'''
assert old in s
s=s.replace(old,new,1)

old='''@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),maxLines=2);Switch(value,onChange)}}'''
new='''@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){
    val scale=LocalDensity.current.fontScale
    Row(Modifier.fillMaxWidth().heightIn(min=if(scale<=1.16f)32.dp else 36.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),maxLines=2);Switch(value,onChange)}
}'''
assert old in s
s=s.replace(old,new,1)

ui.write_text(s)

assert 'versionCode = 51' in b and 'versionName = "0.9.18"' in b
b=b.replace('versionCode = 51','versionCode = 52').replace('versionName = "0.9.18"','versionName = "0.9.19"')
gradle.write_text(b)
assert '0.9.18 (51)' in a
a=a.replace('0.9.18 (51)','0.9.19 (52)')
strings.write_text(a)

s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'val compactSettings=fontScale<=1.16f' in s
assert 'verticalArrangement=Arrangement.spacedBy(settingsGap)' in s
assert 'verticalPadding=controlsCardPad' in s
assert 'heightIn(min=if(compactSettings)32.dp else 36.dp)' in s
assert 'AppLogo(Modifier.size(if(compactSettings)38.dp else 46.dp))' in s
assert 'versionCode = 52' in b and 'versionName = "0.9.19"' in b
assert '0.9.19 (52)' in a
