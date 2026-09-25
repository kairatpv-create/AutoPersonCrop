from pathlib import Path
import sys
root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()

# Finance tabs: about 30% narrower, labels centered, and marquee loops continuously only on overflow.
old='''        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
            listOf("overview","income","expense","analytics").forEachIndexed{i,key->
                MoneyTabPill(
                    selected=selectedTab==i,
                    text=AppStrings.t(lang,key),
                    onClick={onTabSelected(i)},
                    modifier=Modifier.weight(1f)
                )
            }
        }
'''
new='''        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
            listOf("overview","income","expense","analytics").forEachIndexed{i,key->
                MoneyTabPill(
                    selected=selectedTab==i,
                    text=AppStrings.t(lang,key),
                    onClick={onTabSelected(i)},
                    modifier=Modifier.width(58.dp)
                )
            }
        }
'''
assert old in s
s=s.replace(old,new,1)

old='''            modifier=Modifier.fillMaxWidth().basicMarquee(),
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Clip,
            style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp)
'''
new='''            modifier=Modifier.fillMaxWidth().basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Clip,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp)
'''
assert old in s
s=s.replace(old,new,1)

# Top bar: marquee must loop without pauses when text really does not fit.
old='''            modifier=Modifier.weight(1f).basicMarquee(),
'''
new='''            modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),
'''
assert old in s
s=s.replace(old,new,1)

# On finance overview/analytics there is only one top action; let Reminder use a wider pill so full text fits.
old='''                    if(section!="settings") TopActionPill(AppStrings.t(lang,"reminders"),Icons.Default.Notifications,{showReminders=true})
'''
new='''                    if(section!="settings") TopActionPill(AppStrings.t(lang,"reminders"),Icons.Default.Notifications,{showReminders=true},wide=(section=="money" && (moneyTab==0 || moneyTab==3)))
'''
assert old in s
s=s.replace(old,new,1)

old='''@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){
    FilledTonalButton(
        onClick=onClick,
        shape=RoundedCornerShape(19.dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=7.dp,vertical=0.dp),
        modifier=Modifier.widthIn(max=118.dp).heightIn(min=40.dp,max=44.dp)
    ){
'''
new='''@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit,wide:Boolean=false){
    FilledTonalButton(
        onClick=onClick,
        shape=RoundedCornerShape(19.dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=7.dp,vertical=0.dp),
        modifier=(if(wide) Modifier.widthIn(min=150.dp,max=190.dp) else Modifier.widthIn(max=118.dp)).heightIn(min=40.dp,max=44.dp)
    ){
'''
assert old in s
s=s.replace(old,new,1)

# Note editor action buttons: about 30% narrower, same font size, continuous marquee if text overflows.
old='''                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Box(Modifier.weight(1f)){
                        OutlinedButton(
                            onClick={attachmentMenu=true},
                            modifier=Modifier.fillMaxWidth().height(52.dp),
                            contentPadding=PaddingValues(horizontal=6.dp)
                        ){
                            Icon(Icons.Default.AttachFile,null,Modifier.size(20.dp).offset(x=(-6).dp))
                            Spacer(Modifier.width(2.dp))
                            Text(AppStrings.t(lang,"attachment"),maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
                        }
'''
new='''                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                    Box(Modifier.width(76.dp)){
                        OutlinedButton(
                            onClick={attachmentMenu=true},
                            modifier=Modifier.fillMaxWidth().height(52.dp),
                            contentPadding=PaddingValues(horizontal=4.dp)
                        ){
                            Icon(Icons.Default.AttachFile,null,Modifier.size(18.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(AppStrings.t(lang,"attachment"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip)
                        }
'''
assert old in s
s=s.replace(old,new,1)

old='''                    OutlinedButton(
                        onClick={ startSpeechToText() },
                        modifier=Modifier.weight(1f).height(52.dp),
                        contentPadding=PaddingValues(horizontal=6.dp)
                    ){
                        Icon(Icons.Default.KeyboardVoice,null,Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(AppStrings.t(lang,"to_text"),maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick={if(recording){stopRecording()} else {if(androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording()else micPermission.launch(Manifest.permission.RECORD_AUDIO)}},
                        modifier=Modifier.weight(1f).height(52.dp),
                        contentPadding=PaddingValues(horizontal=6.dp),
                        colors=if(recording)ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)else ButtonDefaults.buttonColors()
                    ){
                        Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,null,Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if(recording)"${AppStrings.t(lang,"recording")} ${humanDuration(elapsed)}" else AppStrings.t(lang,"microphone"),maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
                    }
'''
new='''                    OutlinedButton(
                        onClick={ startSpeechToText() },
                        modifier=Modifier.width(76.dp).height(52.dp),
                        contentPadding=PaddingValues(horizontal=4.dp)
                    ){
                        Icon(Icons.Default.KeyboardVoice,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(AppStrings.t(lang,"to_text"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip)
                    }
                    Button(
                        onClick={if(recording){stopRecording()} else {if(androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording()else micPermission.launch(Manifest.permission.RECORD_AUDIO)}},
                        modifier=Modifier.width(76.dp).height(52.dp),
                        contentPadding=PaddingValues(horizontal=4.dp),
                        colors=if(recording)ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)else ButtonDefaults.buttonColors()
                    ){
                        Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(if(recording)"${AppStrings.t(lang,"recording")} ${humanDuration(elapsed)}" else AppStrings.t(lang,"microphone"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip)
                    }
'''
assert old in s
s=s.replace(old,new,1)

ui.write_text(s)

b=gradle.read_text()
assert 'versionCode = 43' in b and 'versionName = "0.9.10"' in b
b=b.replace('versionCode = 43','versionCode = 44').replace('versionName = "0.9.10"','versionName = "0.9.11"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.10 (43)' in a
a=a.replace('0.9.10 (43)','0.9.11 (44)')
strings.write_text(a)

s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'modifier=Modifier.width(58.dp)' in s
assert 'textAlign=androidx.compose.ui.text.style.TextAlign.Center' in s
assert 'basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0)' in s
assert 'wide=(section=="money" && (moneyTab==0 || moneyTab==3))' in s
assert 'Modifier.width(76.dp).height(52.dp)' in s
assert 'BottomTextNavPill' in s
assert 'versionCode = 44' in b and 'versionName = "0.9.11"' in b
assert '0.9.11 (44)' in a
