from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'

u=ui.read_text()

# Compact visual controls on Settings screen.
if 'import androidx.compose.ui.draw.scale\n' not in u:
    u=u.replace('import androidx.compose.ui.Modifier\n','import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.scale\n',1)

start=u.index('@Composable private fun SettingSwitch(')
mid=u.index('@Composable private fun FontScaleRow(', start)
end=u.index('@Composable private fun PinSetupDialog(', mid)

new_helpers='''@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){
    val scale=LocalContentTextScale.current
    val compact=scale<=1.16f
    Row(
        Modifier.fillMaxWidth().heightIn(min=if(compact)26.dp else 32.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Text(label,Modifier.weight(1f),maxLines=2)
        Box(
            Modifier.width(if(compact)44.dp else 50.dp).height(if(compact)26.dp else 30.dp),
            contentAlignment=Alignment.Center
        ){
            Switch(value,onChange,modifier=Modifier.scale(if(compact).72f else .84f))
        }
    }
}
@Composable private fun FontScaleRow(label:String,value:Float,onChange:(Float)->Unit,compact:Boolean){
    Row(
        Modifier.fillMaxWidth().heightIn(min=if(compact)26.dp else 32.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Text(label,Modifier.weight(1.05f),maxLines=2)
        Box(
            Modifier.weight(.95f).height(if(compact)22.dp else 28.dp),
            contentAlignment=Alignment.Center
        ){
            Slider(
                value,onChange,
                modifier=Modifier.fillMaxWidth().scale(scaleX=1f,scaleY=if(compact).68f else .82f),
                valueRange=.85f..1.45f,
                steps=3
            )
        }
    }
}
'''
u=u[:start]+new_helpers+u[end:]

# Make the Organizer Pro Web card one compact row whenever Settings is in compact mode.
marker='Text(if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-версия Органайзер Про" else "Organizer Pro Web"'
pos=u.index(marker)
web_start=u.rfind('        item{SettingsCard("",verticalPadding=controlsCardPad',0,pos)
info_start=u.index('        item{SettingsCard("",verticalPadding=infoCardPad',pos)
new_web='''        item{SettingsCard("",verticalPadding=if(compactSettings)0.dp else controlsCardPad,spacing=0.dp){
            if(compactSettings){
                Row(Modifier.fillMaxWidth().heightIn(min=28.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-версия Органайзер Про" else "Organizer Pro Web",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1)
                    TextButton(
                        onClick={webUriHandler.openUri("https://organizer-pro.onrender.com")},
                        modifier=Modifier.heightIn(min=28.dp),
                        contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)
                    ){Text(AppStrings.t(lang,"open"),maxLines=1)}
                }
            }else{
                Text(if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-версия Органайзер Про" else "Organizer Pro Web",style=MaterialTheme.typography.titleSmall)
                TextButton(onClick={webUriHandler.openUri("https://organizer-pro.onrender.com")},modifier=Modifier.fillMaxWidth()){
                    Text("https://organizer-pro.onrender.com",maxLines=2)
                }
            }
        }}
'''
u=u[:web_start]+new_web+u[info_start:]

ui.write_text(u)

# Bump Android version only; keep applicationId/database schema unchanged.
g=gradle.read_text()
assert 'versionCode = 66' in g
assert 'versionName = "0.9.33"' in g
g=g.replace('versionCode = 66','versionCode = 67',1)
g=g.replace('versionName = "0.9.33"','versionName = "0.9.34"',1)
gradle.write_text(g)
