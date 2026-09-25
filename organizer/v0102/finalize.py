from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
s=ui.read_text()

# Note editor: center labels, compact title field and save button.
for marker in [
'Text(AppStrings.t(lang,"attachment"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip)',
'Text(AppStrings.t(lang,"to_text"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip)',
'Text(if(recording)"${AppStrings.t(lang,"recording")} ${humanDuration(elapsed)}" else AppStrings.t(lang,"microphone"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip)'
]:
    assert marker in s
    s=s.replace(marker,marker[:-1]+',textAlign=androidx.compose.ui.text.style.TextAlign.Center)',1)
old='''                modifier=Modifier.fillMaxWidth()\n            )}'''
new='''                modifier=Modifier.fillMaxWidth().height(52.dp)\n            )}'''
assert old in s
s=s.replace(old,new,1)
old='item{Button({save()},Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)){Icon(Icons.Default.Save,null);Spacer(Modifier.width(8.dp));Text(AppStrings.t(lang,"save"))}}'
new='item{Button({save()},Modifier.fillMaxWidth().height(38.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=0.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)){Icon(Icons.Default.Save,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(AppStrings.t(lang,"save"),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}'
assert old in s
s=s.replace(old,new,1)

# Localized plural label for the compact language button.
anchor='@Composable private fun SettingsScreen('
assert anchor in s
helper='''private fun languagesLabel(lang:String):String=when(AppStrings.normalizeLanguage(lang)){\n    "kk"->"Тілдер"\n    "ru"->"Языки"\n    "zh"->"语言"\n    "de"->"Sprachen"\n    "es"->"Idiomas"\n    "fr"->"Langues"\n    "tr"->"Diller"\n    else->"Languages"\n}\n\n'''
s=s.replace(anchor,helper+anchor,1)

# Proportionally compress settings vertically so the whole page fits without scrolling.
s=s.replace('Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=8.dp),\n        contentPadding=PaddingValues(bottom=8.dp),\n        verticalArrangement=Arrangement.spacedBy(8.dp)','Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=4.dp),\n        contentPadding=PaddingValues(bottom=4.dp),\n        verticalArrangement=Arrangement.spacedBy(4.dp)',1)

old='''        item{SettingsCard(""){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                AppLogo(Modifier.size(48.dp))
                Spacer(Modifier.weight(1f))
                Box{
                    FilledTonalButton(onClick={languageMenu=true},colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)){
                        Icon(Icons.Default.Language,null)
                        Spacer(Modifier.width(6.dp))
                        Text(AppStrings.languageFlag(lang))
                        Spacer(Modifier.width(5.dp))
                        Text(AppStrings.languageName(lang),maxLines=1)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown,null)
                    }
                    DropdownMenu(expanded=languageMenu,onDismissRequest={languageMenu=false}){
                        AppStrings.supportedLanguages.forEach{code->
                            DropdownMenuItem(
                                text={Text(AppStrings.languageName(code))},
                                leadingIcon={Text(AppStrings.languageFlag(code),fontSize=20.sp)},
                                trailingIcon={if(AppStrings.normalizeLanguage(lang)==code) Icon(Icons.Default.Check,null) else Spacer(Modifier.size(24.dp))},
                                onClick={onLanguage(code);languageMenu=false}
                            )
                        }
                    }
                }
            }
        }}'''
new='''        item{SettingsCard("",verticalPadding=5.dp,spacing=3.dp){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                AppLogo(Modifier.size(42.dp))
                Spacer(Modifier.width(7.dp))
                Text(AppStrings.t(lang,"app"),modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold,letterSpacing=.4.sp))
                Spacer(Modifier.width(7.dp))
                Box{
                    FilledTonalButton(onClick={languageMenu=true},colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),modifier=Modifier.height(36.dp).widthIn(min=104.dp,max=132.dp)){
                        Icon(Icons.Default.Language,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(languagesLabel(lang),modifier=Modifier.weight(1f),maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold));Spacer(Modifier.width(2.dp));Icon(Icons.Default.ArrowDropDown,null,Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded=languageMenu,onDismissRequest={languageMenu=false}){
                        AppStrings.supportedLanguages.forEach{code->DropdownMenuItem(text={Text(AppStrings.languageName(code))},leadingIcon={Text(AppStrings.languageFlag(code),fontSize=20.sp)},trailingIcon={if(AppStrings.normalizeLanguage(lang)==code) Icon(Icons.Default.Check,null) else Spacer(Modifier.size(24.dp))},onClick={onLanguage(code);languageMenu=false})}
                    }
                }
            }
        }}'''
assert old in s
s=s.replace(old,new,1)

old='item{SettingsCard(""){SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark);SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold);Text(AppStrings.t(lang,"font"));Slider(fontScale,onFont,valueRange=.85f..1.45f,steps=3)}}'
new='item{SettingsCard("",verticalPadding=4.dp,spacing=1.dp){SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark);SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold);Row(Modifier.fillMaxWidth().height(32.dp),verticalAlignment=Alignment.CenterVertically){Text(AppStrings.t(lang,"font"),Modifier.weight(.72f));Slider(fontScale,onFont,modifier=Modifier.weight(1.28f).height(28.dp),valueRange=.85f..1.45f,steps=3)}}}'
assert old in s
s=s.replace(old,new,1)
old='item{SettingsCard(""){val enabled=settings.isPinProtectionEnabled();SettingSwitch(AppStrings.t(lang,"pin"),enabled){want->if(want&&!enabled)showPin=true else if(!want&&enabled)showDisablePin=true};if(enabled)OutlinedButton({showChangePin=true},Modifier.fillMaxWidth()){Text(AppStrings.t(lang,"change_pin"))}}}'
new='item{SettingsCard("",verticalPadding=4.dp,spacing=2.dp){val enabled=settings.isPinProtectionEnabled();SettingSwitch(AppStrings.t(lang,"pin"),enabled){want->if(want&&!enabled)showPin=true else if(!want&&enabled)showDisablePin=true};if(enabled)OutlinedButton({showChangePin=true},Modifier.fillMaxWidth().height(34.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp)){Text(AppStrings.t(lang,"change_pin"))}}}'
assert old in s
s=s.replace(old,new,1)
old='item{SettingsCard(""){Button({saveBackup.launch(BackupManager.suggestedFileName())},Modifier.fillMaxWidth()){Icon(Icons.Default.Backup,null);Spacer(Modifier.width(8.dp));Text(AppStrings.t(lang,"save_backup"),maxLines=2)};OutlinedButton({openBackup.launch(arrayOf("application/json","text/plain","*/*"))},Modifier.fillMaxWidth()){Text(AppStrings.t(lang,"restore"),maxLines=1)};OutlinedButton({BackupManager.shareBackup(context,BackupManager.createBackup(db,settings),settings.backupEmail,lang)},Modifier.fillMaxWidth()){Text(AppStrings.t(lang,"send_backup"),maxLines=2)}}}'
new='item{SettingsCard("",verticalPadding=5.dp,spacing=4.dp){Button({saveBackup.launch(BackupManager.suggestedFileName())},Modifier.fillMaxWidth().height(38.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Icon(Icons.Default.Backup,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(AppStrings.t(lang,"save_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)};OutlinedButton({openBackup.launch(arrayOf("application/json","text/plain","*/*"))},Modifier.fillMaxWidth().height(36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"restore"),maxLines=1,textAlign=androidx.compose.ui.text.style.TextAlign.Center)};OutlinedButton({BackupManager.shareBackup(context,BackupManager.createBackup(db,settings),settings.backupEmail,lang)},Modifier.fillMaxWidth().height(36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"send_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}}'
assert old in s
s=s.replace(old,new,1)
# Compact the lower information card without altering its data.
s=s.replace('''        item{SettingsCard(""){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){''','''        item{SettingsCard("",verticalPadding=5.dp,spacing=2.dp){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){''',1)
s=s.replace('AppLogo(Modifier.size(58.dp))','AppLogo(Modifier.size(46.dp))',1)

old='@Composable private fun SettingsCard(title:String,content: @Composable ColumnScope.() -> Unit){Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleMedium);content()}}}'
new='@Composable private fun SettingsCard(title:String,verticalPadding:androidx.compose.ui.unit.Dp=10.dp,spacing:androidx.compose.ui.unit.Dp=6.dp,content: @Composable ColumnScope.() -> Unit){Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(horizontal=14.dp,vertical=verticalPadding),verticalArrangement=Arrangement.spacedBy(spacing)){if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleMedium);content()}}}'
assert old in s
s=s.replace(old,new,1)
old='@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onChange)}}'
new='@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().height(36.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onChange)}}'
assert old in s
s=s.replace(old,new,1)

ui.write_text(s)

# Final gates: visual-only changes, bottom nav and data model retained.
s=ui.read_text()
assert 'languagesLabel(lang)' in s and 'Text(AppStrings.languageFlag(lang))' not in s
assert 'AppStrings.t(lang,"app")' in s
assert 'Modifier.fillMaxWidth().height(34.dp)' in s
assert 'Modifier.fillMaxWidth().height(38.dp)' in s
assert 'verticalPadding=4.dp' in s
assert 'BottomTextNavPill(section=="settings"' in s
