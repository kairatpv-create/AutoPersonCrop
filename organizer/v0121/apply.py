from pathlib import Path
import sys

root=Path(sys.argv[1])
base=root/'app/src/main/java/kz/kairat/organizer'
ui=base/'OrganizerUi.kt'
cloud=base/'CloudSync.kt'
gradle=root/'app/build.gradle.kts'
strings=base/'AppStrings.kt'

# Keep only file restore in the backup card. Cloud sync is now the normal backup path.
u=ui.read_text()
old='''        item{SettingsCard("",verticalPadding=backupCardPad,spacing=if(compactSettings)2.dp else 4.dp){Button({saveBackup.launch(BackupManager.suggestedFileName())},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)34.dp else 38.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Icon(Icons.Default.Backup,null,Modifier.size(if(compactSettings)17.dp else 18.dp));Spacer(Modifier.width(if(compactSettings)5.dp else 6.dp));Text(AppStrings.t(lang,"save_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)};OutlinedButton({openBackup.launch(arrayOf("application/json","text/plain","*/*"))},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)32.dp else 36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"restore"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)};OutlinedButton({BackupManager.shareBackup(context,BackupManager.createBackup(db,settings),settings.backupEmail,lang)},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)32.dp else 36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"send_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}}
'''
new='''        item{SettingsCard("",verticalPadding=backupCardPad,spacing=if(compactSettings)2.dp else 4.dp){OutlinedButton({openBackup.launch(arrayOf("application/json","text/plain","*/*"))},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)32.dp else 36.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(AppStrings.t(lang,"restore"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}}
'''
assert old in u
u=u.replace(old,new,1)
ui.write_text(u)

# Make the signed-in account block compact and visually match the settings cards.
c=cloud.read_text()
old='''    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(syncText(lang,"title"),style=MaterialTheme.typography.titleMedium)
            if(!logged){
                OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"email"))},singleLine=true)
                OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"password"))},singleLine=true,visualTransformation=PasswordVisualTransformation())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button({doLogin(false)},Modifier.weight(1f),enabled=!busy){Text(syncText(lang,"login"),maxLines=1)}
                    OutlinedButton({doLogin(true)},Modifier.weight(1f),enabled=!busy){Text(syncText(lang,"register"),maxLines=2)}
                }
            }else{
                Text(settings.syncEmail,style=MaterialTheme.typography.bodyMedium)
                Text(syncText(lang,"enabled"),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyMedium)
                Text(syncText(lang,"hint"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({CloudSync.logout(settings);logged=false;status=""},Modifier.fillMaxWidth(),enabled=!busy){Text(syncText(lang,"logout"))}
            }
            if(status.isNotBlank())Text(status,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
        }
    }
'''
new='''    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
        if(!logged){
            Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(syncText(lang,"title"),style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"email"))},singleLine=true)
                OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"password"))},singleLine=true,visualTransformation=PasswordVisualTransformation())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button({doLogin(false)},Modifier.weight(1f),enabled=!busy){Text(syncText(lang,"login"),maxLines=1)}
                    OutlinedButton({doLogin(true)},Modifier.weight(1f),enabled=!busy){Text(syncText(lang,"register"),maxLines=2)}
                }
                if(status.isNotBlank())Text(status,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
            }
        }else{
            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)){
                    Text(syncText(lang,"title"),style=MaterialTheme.typography.titleSmall)
                    Text(settings.syncEmail,style=MaterialTheme.typography.bodySmall,maxLines=1)
                    Text(if(status.isNotBlank())status else syncText(lang,"enabled"),color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium,maxLines=1)
                }
                TextButton({CloudSync.logout(settings);logged=false;status=""},enabled=!busy,modifier=Modifier.heightIn(min=30.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp)){Text(syncText(lang,"logout"),style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
'''
assert old in c
c=c.replace(old,new,1)
cloud.write_text(c)

b=gradle.read_text()
assert 'versionCode = 63' in b and 'versionName = "0.9.30"' in b
b=b.replace('versionCode = 63','versionCode = 64').replace('versionName = "0.9.30"','versionName = "0.9.31"')
gradle.write_text(b)

a=strings.read_text()
assert '0.9.30 (63)' in a
a=a.replace('0.9.30 (63)','0.9.31 (64)')
strings.write_text(a)

final_ui=ui.read_text()
final_cloud=cloud.read_text()
assert 'saveBackup.launch(BackupManager.suggestedFileName())' not in final_ui
assert 'BackupManager.shareBackup(context,BackupManager.createBackup(db,settings)' not in final_ui
assert 'openBackup.launch(arrayOf("application/json","text/plain","*/*"))' in final_ui
assert 'CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)' in final_cloud
assert 'syncText(lang,"hint")' not in final_cloud
assert 'versionCode = 64' in gradle.read_text()
assert 'versionName = "0.9.31"' in gradle.read_text()
