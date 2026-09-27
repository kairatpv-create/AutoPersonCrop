from pathlib import Path
import sys

root=Path(sys.argv[1])
here=Path(__file__).parent
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
calui=root/'app/src/main/java/kz/kairat/organizer/CalendarUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()

# v0.9.23: remove the app logo from the MAIN top action bar and let the action controls use its space.
old='''                    if(section!="settings") AppLogo(Modifier.size((36f*fontScale.coerceIn(.85f,1.45f)).dp))
                    Spacer(Modifier.weight(1f))'''
assert old in s
s=s.replace(old,'',1)

# Calendar is a main-workspace action only; Settings must not show it.
old='''                    TopIconAction(Icons.Default.CalendarMonth){'''
new='''                    if(section!="settings") TopIconAction(Icons.Default.CalendarMonth){'''
assert old in s
s=s.replace(old,new,1)

# Keep the calendar button compact, but give it a proportional slot in the top row.
old='''@Composable private fun TopIconAction(icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){
    val uiScale=LocalDensity.current.fontScale.coerceIn(.85f,1.45f)
    FilledTonalIconButton(
        onClick=onClick,
        colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        modifier=Modifier.size((40f*uiScale).dp)
    ){Icon(icon,AppStrings.t("en","calendar"),Modifier.size((19f*uiScale).dp))}
}
'''
new='''@Composable private fun RowScope.TopIconAction(icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){
    val uiScale=LocalDensity.current.fontScale.coerceIn(.85f,1.45f)
    Box(Modifier.weight(.55f),contentAlignment=Alignment.Center){
        FilledTonalIconButton(
            onClick=onClick,
            colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
            modifier=Modifier.size((40f*uiScale).dp)
        ){Icon(icon,AppStrings.t("en","calendar"),Modifier.size((19f*uiScale).dp))}
    }
}
'''
assert old in s
s=s.replace(old,new,1)

# Reminder/Create pills: no icons. Each available text action gets an equal share of the top bar.
start=s.index('@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun TopActionPill')
end=s.index('@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun MoneyTabPill',start)
new_block='''@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RowScope.TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit,wide:Boolean=false){
    val uiScale=LocalDensity.current.fontScale.coerceIn(.85f,1.45f)
    val pillHeight=(40f*uiScale).dp
    FilledTonalButton(
        onClick=onClick,
        shape=RoundedCornerShape((19f*uiScale).dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=(7f*uiScale).dp,vertical=0.dp),
        modifier=Modifier.weight(1f).height(pillHeight)
    ){
        Text(
            text,
            modifier=Modifier.fillMaxWidth().basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Clip,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            style=MaterialTheme.typography.titleLarge
        )
    }
}
'''
s=s[:start]+new_block+s[end:]
ui.write_text(s)

# Replace the compact popup calendar with the full-screen calendar surface.
calui.write_text((here/'CalendarUi.kt').read_text())

# Version bump only. Keep applicationId, database/schema and user data unchanged.
b=gradle.read_text()
assert 'versionCode = 55' in b and 'versionName = "0.9.22"' in b
b=b.replace('versionCode = 55','versionCode = 56').replace('versionName = "0.9.22"','versionName = "0.9.23"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.22 (55)' in a
a=a.replace('0.9.22 (55)','0.9.23 (56)')
strings.write_text(a)

# Gates.
s=ui.read_text(); c=calui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'if(section!="settings") AppLogo(Modifier.size((36f*fontScale' not in s
assert 'if(section!="settings") TopIconAction(Icons.Default.CalendarMonth)' in s
assert '@Composable private fun RowScope.TopActionPill' in s
assert 'modifier=Modifier.weight(1f).height(pillHeight)' in s
assert 'Icon(icon,null,Modifier.size((17f*uiScale).dp))' not in s
assert 'dismissOnBackPress=false,dismissOnClickOutside=false' in c
assert 'Modifier.fillMaxSize()' in c
assert 'shape=RoundedCornerShape(0.dp)' in c
assert 'AppStrings.t(lang,"birthday_notice")' not in c
assert 'versionCode = 56' in b and 'versionName = "0.9.23"' in b
assert '0.9.23 (56)' in a
