from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
theme=root/'app/src/main/java/kz/kairat/organizer/Theme.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()
t=theme.read_text()

# v0.9.16 unified three-level typography:
# 17sp = primary/top actions and top titles
# 15sp = navigation, tabs, buttons and controls
# 14sp = readable user-created/supporting content
old='''    val type=Typography(bodyLarge=TextStyle(fontSize=16.sp,fontWeight=weight),bodyMedium=TextStyle(fontSize=14.sp,fontWeight=weight),bodySmall=TextStyle(fontSize=12.sp,fontWeight=weight),titleLarge=TextStyle(fontSize=22.sp,fontWeight=if(bold)FontWeight.Bold else FontWeight.SemiBold),titleMedium=TextStyle(fontSize=16.sp,fontWeight=if(bold)FontWeight.Bold else FontWeight.SemiBold),labelLarge=TextStyle(fontSize=14.sp,fontWeight=if(bold)FontWeight.Bold else FontWeight.Medium))'''
new='''    val strong=if(bold)FontWeight.Bold else FontWeight.SemiBold
    val type=Typography(
        displayLarge=TextStyle(fontSize=17.sp,fontWeight=strong),
        displayMedium=TextStyle(fontSize=17.sp,fontWeight=strong),
        displaySmall=TextStyle(fontSize=17.sp,fontWeight=strong),
        headlineLarge=TextStyle(fontSize=17.sp,fontWeight=strong),
        headlineMedium=TextStyle(fontSize=17.sp,fontWeight=strong),
        headlineSmall=TextStyle(fontSize=17.sp,fontWeight=strong),
        titleLarge=TextStyle(fontSize=17.sp,fontWeight=strong),
        titleMedium=TextStyle(fontSize=15.sp,fontWeight=strong),
        titleSmall=TextStyle(fontSize=15.sp,fontWeight=strong),
        bodyLarge=TextStyle(fontSize=14.sp,fontWeight=weight),
        bodyMedium=TextStyle(fontSize=14.sp,fontWeight=weight),
        bodySmall=TextStyle(fontSize=14.sp,fontWeight=weight),
        labelLarge=TextStyle(fontSize=15.sp,fontWeight=strong),
        labelMedium=TextStyle(fontSize=14.sp,fontWeight=weight),
        labelSmall=TextStyle(fontSize=14.sp,fontWeight=weight)
    )'''
assert old in t
t=t.replace(old,new,1)
theme.write_text(t)

# Upper bar logo/spacing follows the same user scale as its text/buttons.
old='if(section!="settings") AppLogo(Modifier.size(36.dp))'
new='if(section!="settings") AppLogo(Modifier.size((36f*fontScale.coerceIn(.85f,1.45f)).dp))'
assert old in s
s=s.replace(old,new,1)
old='Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal=8.dp,vertical=6.dp)'
new='Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal=8.dp,vertical=(6f*fontScale.coerceIn(.85f,1.45f)).dp)'
assert old in s
s=s.replace(old,new,1)
old='Row(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically)'
new='Row(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=(4f*fontScale.coerceIn(.85f,1.45f)).dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically)'
assert old in s
s=s.replace(old,new,1)

# Settings application name is a primary/top-level title (large tier).
old='style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.ExtraBold,letterSpacing=.5.sp,lineHeight=18.sp)'
new='style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.ExtraBold,letterSpacing=.5.sp)'
assert old in s
s=s.replace(old,new,1)

# Replace common top action control: large text tier, scaled button height, marquee kept permanently available.
start=s.index('@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun TopActionPill')
end=s.index('@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun MoneyTabPill', start)
new='''@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit,wide:Boolean=false){
    val uiScale=LocalDensity.current.fontScale.coerceIn(.85f,1.45f)
    val pillHeight=(40f*uiScale).dp
    FilledTonalButton(
        onClick=onClick,
        shape=RoundedCornerShape((19f*uiScale).dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=(7f*uiScale).dp,vertical=0.dp),
        modifier=(if(wide) Modifier.widthIn(min=150.dp,max=190.dp) else Modifier.widthIn(max=118.dp)).height(pillHeight)
    ){
        Icon(icon,null,Modifier.size((17f*uiScale).dp))
        Spacer(Modifier.width((4f*uiScale).dp))
        Text(
            text,
            modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Clip,
            style=MaterialTheme.typography.titleLarge
        )
    }
}
'''
s=s[:start]+new+s[end:]

# Finance tabs are medium tier and their windows scale with the slider.
start=s.index('@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun MoneyTabPill')
end=s.index('@Composable private fun BottomTextNavPill', start)
new='''@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun MoneyTabPill(selected:Boolean,text:String,onClick:()->Unit,modifier:Modifier=Modifier){
    val uiScale=LocalDensity.current.fontScale.coerceIn(.85f,1.45f)
    val shape=RoundedCornerShape((15f*uiScale).dp)
    val pillHeight=(32f*uiScale).dp
    val content: @Composable RowScope.() -> Unit={
        Text(
            text,
            modifier=Modifier.fillMaxWidth().basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Clip,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            style=MaterialTheme.typography.labelLarge
        )
    }
    if(selected) Button(
        onClick=onClick,
        shape=shape,
        colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=3.dp,vertical=0.dp),
        modifier=modifier.height(pillHeight),
        content=content
    ) else OutlinedButton(
        onClick=onClick,
        shape=shape,
        colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary),
        contentPadding=PaddingValues(horizontal=3.dp,vertical=0.dp),
        modifier=modifier.height(pillHeight),
        content=content
    )
}

'''
s=s[:start]+new+s[end:]

# Bottom navigation now behaves like the upper bar: medium tier text, scaled button height and marquee.
start=s.index('@Composable private fun BottomTextNavPill')
end=s.index('@Composable private fun noteColor', start)
new='''@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun BottomTextNavPill(selected:Boolean,text:String,onClick:()->Unit,modifier:Modifier=Modifier){
    val uiScale=LocalDensity.current.fontScale.coerceIn(.85f,1.45f)
    val shape=RoundedCornerShape((15f*uiScale).dp)
    val pillHeight=(44f*uiScale).dp
    val content: @Composable RowScope.() -> Unit={
        Text(
            text,
            modifier=Modifier.fillMaxWidth().basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Clip,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            style=MaterialTheme.typography.labelLarge
        )
    }
    if(selected) Button(onClick=onClick,shape=shape,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp),modifier=modifier.height(pillHeight),content=content)
    else OutlinedButton(onClick=onClick,shape=shape,colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp),modifier=modifier.height(pillHeight),content=content)
}

'''
s=s[:start]+new+s[end:]
ui.write_text(s)

# Version bump only; applicationId/database/data are untouched.
b=gradle.read_text()
assert 'versionCode = 48' in b and 'versionName = "0.9.15"' in b
b=b.replace('versionCode = 48','versionCode = 49').replace('versionName = "0.9.15"','versionName = "0.9.16"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.15 (48)' in a
a=a.replace('0.9.15 (48)','0.9.16 (49)')
strings.write_text(a)

# Gates.
s=ui.read_text(); t=theme.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'titleLarge=TextStyle(fontSize=17.sp' in t
assert 'titleMedium=TextStyle(fontSize=15.sp' in t
assert 'bodyMedium=TextStyle(fontSize=14.sp' in t
assert 'bodySmall=TextStyle(fontSize=14.sp' in t
assert 'fixedTextSize' not in s
assert 'style=MaterialTheme.typography.titleLarge' in s
assert 'style=MaterialTheme.typography.labelLarge' in s
assert 'basicMarquee(iterations=Int.MAX_VALUE' in s
assert 'val pillHeight=(44f*uiScale).dp' in s
assert 'val pillHeight=(40f*uiScale).dp' in s
assert 'val pillHeight=(32f*uiScale).dp' in s
assert 'BottomTextNavPill(section=="settings"' in s
assert 'versionCode = 49' in b and 'versionName = "0.9.16"' in b
assert '0.9.16 (49)' in a
