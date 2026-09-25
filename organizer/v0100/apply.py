from pathlib import Path
import sys
root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()
# Marquee support for constrained top-bar buttons and finance tabs.
if 'import androidx.compose.foundation.basicMarquee\n' not in s:
    s=s.replace('import androidx.compose.foundation.background\n','import androidx.compose.foundation.background\nimport androidx.compose.foundation.basicMarquee\n',1)
if 'import androidx.compose.foundation.ExperimentalFoundationApi\n' not in s:
    s=s.replace('import androidx.compose.foundation.BorderStroke\n','import androidx.compose.foundation.BorderStroke\nimport androidx.compose.foundation.ExperimentalFoundationApi\n',1)

# Main section top bar uses the same background as bottom bar.
s=s.replace('Surface(color=MaterialTheme.colorScheme.primary,shadowElevation=2.dp){','Surface(color=MaterialTheme.colorScheme.secondaryContainer,shadowElevation=2.dp){',1)

# Audio waveform: about half the previous visual width.
old='Waveform(progress,entry.id,Modifier.weight(1f))'
assert old in s
s=s.replace(old,'Waveform(progress,entry.id,Modifier.widthIn(min=72.dp,max=120.dp))',1)

# Attachment previews/actions about half size, still in one horizontal row.
s=s.replace('Card(Modifier.width(104.dp),shape=RoundedCornerShape(12.dp)','Card(Modifier.width(58.dp),shape=RoundedCornerShape(10.dp)',1)
s=s.replace('Column(Modifier.padding(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp))','Column(Modifier.padding(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp))',1)
s=s.replace('Box(Modifier.fillMaxWidth().height(62.dp).clickable','Box(Modifier.fillMaxWidth().height(38.dp).clickable',1)
s=s.replace('Icons.Default.Description,null,Modifier.size(32.dp)','Icons.Default.Description,null,Modifier.size(18.dp)',1)
s=s.replace('modifier=Modifier.size(28.dp)){Icon(Icons.Default.Share,null,Modifier.size(16.dp))','modifier=Modifier.size(22.dp)){Icon(Icons.Default.Share,null,Modifier.size(12.dp))',1)
s=s.replace('modifier=Modifier.size(28.dp)){Icon(Icons.Default.Delete,null,Modifier.size(16.dp))','modifier=Modifier.size(22.dp)){Icon(Icons.Default.Delete,null,Modifier.size(12.dp))',1)

# Replace top action buttons: ~20% narrower, same text size, marquee when text does not fit.
old='''@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){
    FilledTonalButton(onClick=onClick,shape=RoundedCornerShape(19.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=14.dp,vertical=0.dp),modifier=Modifier.heightIn(min=40.dp,max=44.dp)){
        Icon(icon,null,Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(text,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp,fontWeight=FontWeight.Bold))
    }
}
'''
new='''@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){
    FilledTonalButton(
        onClick=onClick,
        shape=RoundedCornerShape(19.dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=7.dp,vertical=0.dp),
        modifier=Modifier.widthIn(max=118.dp).heightIn(min=40.dp,max=44.dp)
    ){
        Icon(icon,null,Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            modifier=Modifier.weight(1f).basicMarquee(),
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Clip,
            style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp,fontWeight=FontWeight.Bold)
        )
    }
}
'''
assert old in s
s=s.replace(old,new,1)

# Finance top tab buttons also marquee when enlarged; do not reduce label font.
old='''        Text(
            text,
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Ellipsis,
            style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp)
        )
'''
new='''        Text(
            text,
            modifier=Modifier.fillMaxWidth().basicMarquee(),
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Clip,
            style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp)
        )
'''
assert old in s
s=s.replace(old,new,1)
# Opt-in for basicMarquee in MoneyTabPill.
s=s.replace('@Composable private fun MoneyTabPill(selected:Boolean,text:String,onClick:()->Unit,modifier:Modifier=Modifier){','@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun MoneyTabPill(selected:Boolean,text:String,onClick:()->Unit,modifier:Modifier=Modifier){',1)

# All standard TopAppBars use the same secondaryContainer shade as the bottom bar.
old='@Composable private fun organizerTopBarColors()=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.primary,titleContentColor=Color.White,navigationIconContentColor=Color.White,actionIconContentColor=Color.White)'
new='@Composable private fun organizerTopBarColors()=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.secondaryContainer,titleContentColor=MaterialTheme.colorScheme.onSecondaryContainer,navigationIconContentColor=MaterialTheme.colorScheme.onSecondaryContainer,actionIconContentColor=MaterialTheme.colorScheme.onSecondaryContainer)'
assert old in s
s=s.replace(old,new,1)

ui.write_text(s)

b=gradle.read_text()
assert 'versionCode = 42' in b and 'versionName = "0.9.9"' in b
b=b.replace('versionCode = 42','versionCode = 43').replace('versionName = "0.9.9"','versionName = "0.9.10"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.9 (42)' in a
a=a.replace('0.9.9 (42)','0.9.10 (43)')
strings.write_text(a)

# Gates
s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'basicMarquee()' in s
assert 'Modifier.widthIn(max=118.dp).heightIn(min=40.dp,max=44.dp)' in s
assert 'Waveform(progress,entry.id,Modifier.widthIn(min=72.dp,max=120.dp))' in s
assert 'Card(Modifier.width(58.dp)' in s
assert 'Surface(color=MaterialTheme.colorScheme.secondaryContainer,shadowElevation=2.dp)' in s
assert 'containerColor=MaterialTheme.colorScheme.secondaryContainer' in s
assert 'BottomTextNavPill' in s  # bottom bar retained, not redesigned
assert 'versionCode = 43' in b and 'versionName = "0.9.10"' in b
assert '0.9.10 (43)' in a
