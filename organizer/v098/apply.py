from pathlib import Path
import sys

root = Path(sys.argv[1])
ui = root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
db = root/'app/src/main/java/kz/kairat/organizer/NoteDatabase.kt'
gradle = root/'app/build.gradle.kts'
strings = root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

# Allow three new vivid colors everywhere colors are validated before saving.
s = db.read_text()
old_set='setOf("blue","yellow","green","orange","pink","purple")'
new_set='setOf("blue","yellow","green","orange","pink","purple","red","bright_yellow","cyan")'
assert s.count(old_set) >= 3
s=s.replace(old_set,new_set)
db.write_text(s)

s = ui.read_text()

# Finance tabs: replace plain TabRow tabs by compact rounded pill buttons matching bottom navigation.
old = '''        TabRow(selectedTabIndex=selectedTab,containerColor=Color.Transparent,divider={}){
            listOf("overview","income","expense","analytics").forEachIndexed{i,key->
                Tab(selected=selectedTab==i,onClick={onTabSelected(i)},text={Text(AppStrings.t(lang,key),maxLines=1,style=MaterialTheme.typography.labelSmall)})
            }
        }
'''
new = '''        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
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
assert old in s
s=s.replace(old,new,1)

# Replace the round editor-style selector with a familiar square color-swatch palette icon.
start=s.index('@Composable private fun TopColorMenu')
end=s.index('@Composable private fun VoiceNoteBlock')
new_menu='''@Composable private fun TopColorMenu(selected:String,onSelected:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    val colors=listOf("blue","yellow","green","orange","pink","purple","red","bright_yellow","cyan")
    Box{
        IconButton({expanded=true}){
            Canvas(Modifier.size(34.dp)){
                val cells=listOf(
                    Color(0xFF202124),Color(0xFF777777),Color(0xFFD9D9D9),Color(0xFFFFFFFF),
                    Color(0xFF0057D9),Color(0xFF00A651),Color(0xFFFFD600),Color(0xFFFF2D20),
                    Color(0xFFE6007E),Color(0xFF7C4DFF),Color(0xFFFF7A00),Color(0xFF00CFE8),
                    Color(0xFF69D2E7),Color(0xFFB8E986),Color(0xFFFF9EB5),noteColor(selected)
                )
                val n=4
                val gap=1.dp.toPx()
                val cell=(size.minDimension-gap*(n-1))/n
                drawRect(Color.White)
                cells.forEachIndexed{index,c->
                    val row=index/n
                    val col=index%n
                    drawRect(
                        color=c,
                        topLeft=androidx.compose.ui.geometry.Offset(col*(cell+gap),row*(cell+gap)),
                        size=androidx.compose.ui.geometry.Size(cell,cell)
                    )
                }
                drawRect(
                    color=Color.Black.copy(alpha=.65f),
                    style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.dp.toPx())
                )
            }
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
            Column(Modifier.padding(5.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                colors.chunked(3).forEach{row->
                    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        row.forEach{key->
                            val c=noteColor(key)
                            Surface(
                                color=c,
                                shape=RoundedCornerShape(5.dp),
                                border=if(selected==key)BorderStroke(2.dp,MaterialTheme.colorScheme.primary)else BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.45f)),
                                modifier=Modifier.size(28.dp).clickable{onSelected(key);expanded=false}
                            ){
                                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                                    if(selected==key)Icon(Icons.Default.Check,null,tint=if(c.luminance()<.5f)Color.White else Color.Black,modifier=Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

'''
s=s[:start]+new_menu+s[end:]

# Add finance pill component before the existing bottom-navigation pill helpers.
anchor='''@Composable private fun BottomNavPill(selected:Boolean,text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit,modifier:Modifier=Modifier){
'''
assert anchor in s
money_pill='''@Composable private fun MoneyTabPill(selected:Boolean,text:String,onClick:()->Unit,modifier:Modifier=Modifier){
    val shape=RoundedCornerShape(15.dp)
    val content: @Composable RowScope.() -> Unit={
        Text(
            text,
            maxLines=1,
            softWrap=false,
            overflow=TextOverflow.Ellipsis,
            style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp)
        )
    }
    if(selected) Button(
        onClick=onClick,
        shape=shape,
        colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=3.dp,vertical=0.dp),
        modifier=modifier.height(42.dp),
        content=content
    ) else OutlinedButton(
        onClick=onClick,
        shape=shape,
        colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary),
        contentPadding=PaddingValues(horizontal=3.dp,vertical=0.dp),
        modifier=modifier.height(42.dp),
        content=content
    )
}

'''
s=s.replace(anchor,money_pill+anchor,1)

# Add vivid colors and set card content color for reliable contrast.
old='''@Composable private fun noteColor(key:String):Color{
    val dark=MaterialTheme.colorScheme.background.luminance()<0.35f
    return when(key){
        "yellow"->if(dark)Color(0xFF5A4700) else Color(0xFFFFF1A8)
        "green"->if(dark)Color(0xFF194A33) else Color(0xFFD8F6E5)
        "orange"->if(dark)Color(0xFF5A3315) else Color(0xFFFFE0B2)
        "pink"->if(dark)Color(0xFF5A2940) else Color(0xFFFFD6E7)
        "purple"->if(dark)Color(0xFF412C66) else Color(0xFFE7D9FF)
        else->if(dark)Color(0xFF0A4968) else Color(0xFFD9F2FF)
    }
}
@Composable private fun noteCardColors(key:String)=CardDefaults.cardColors(containerColor=noteColor(key))
'''
new='''@Composable private fun noteColor(key:String):Color{
    val dark=MaterialTheme.colorScheme.background.luminance()<0.35f
    return when(key){
        "yellow"->if(dark)Color(0xFF5A4700) else Color(0xFFFFF1A8)
        "green"->if(dark)Color(0xFF194A33) else Color(0xFFD8F6E5)
        "orange"->if(dark)Color(0xFF5A3315) else Color(0xFFFFE0B2)
        "pink"->if(dark)Color(0xFF5A2940) else Color(0xFFFFD6E7)
        "purple"->if(dark)Color(0xFF412C66) else Color(0xFFE7D9FF)
        "red"->Color(0xFFFF3B30)
        "bright_yellow"->Color(0xFFFFD60A)
        "cyan"->Color(0xFF00CFE8)
        else->if(dark)Color(0xFF0A4968) else Color(0xFFD9F2FF)
    }
}
@Composable private fun noteCardColors(key:String):CardColors{
    val c=noteColor(key)
    return CardDefaults.cardColors(containerColor=c,contentColor=if(c.luminance()<.48f)Color.White else Color.Black)
}
'''
assert old in s
s=s.replace(old,new,1)
ui.write_text(s)

# Version bump.
b=gradle.read_text()
assert 'versionCode = 40' in b and 'versionName = "0.9.7"' in b
b=b.replace('versionCode = 40','versionCode = 41').replace('versionName = "0.9.7"','versionName = "0.9.8"')
gradle.write_text(b)

a=strings.read_text()
assert '0.9.7 (40)' in a
a=a.replace('0.9.7 (40)','0.9.8 (41)')
a=a.replace('"analytics" to "Аналитика"','"analytics" to "Анализ"',1)
strings.write_text(a)

# Build-time behavior gates.
s=ui.read_text(); d=db.read_text(); a=strings.read_text(); b=gradle.read_text()
assert 'MoneyTabPill(' in s
assert 'fontSize=15.sp' in s
assert 'Modifier.size(28.dp)' in s
assert 'Color(0xFFFF3B30)' in s and 'Color(0xFFFFD60A)' in s and 'Color(0xFF00CFE8)' in s
assert '"red","bright_yellow","cyan"' in s
assert '"red","bright_yellow","cyan"' in d
assert 'Brush.sweepGradient' not in s
assert 'versionCode = 41' in b and 'versionName = "0.9.8"' in b
assert '0.9.8 (41)' in a
