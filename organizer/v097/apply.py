from pathlib import Path
import sys

root = Path(sys.argv[1])
ui = root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle = root/'app/build.gradle.kts'
strings = root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s = ui.read_text()
start = s.index('@Composable private fun TopColorMenu')
end = s.index('@Composable private fun VoiceNoteBlock')
new = '''@Composable private fun TopColorMenu(selected:String,onSelected:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    val colors=listOf("blue","yellow","green","orange","pink","purple")
    val selectedColor=noteColor(selected)
    Box{
        IconButton({expanded=true}){
            Canvas(Modifier.size(34.dp)){
                val ringWidth=5.dp.toPx()
                val outerRadius=size.minDimension/2f-1.dp.toPx()
                drawCircle(
                    brush=androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(
                            Color(0xFFFF3B30),
                            Color(0xFFFFCC00),
                            Color(0xFF34C759),
                            Color(0xFF32ADE6),
                            Color(0xFF007AFF),
                            Color(0xFFAF52DE),
                            Color(0xFFFF3B30)
                        )
                    ),
                    radius=outerRadius,
                    style=androidx.compose.ui.graphics.drawscope.Stroke(width=ringWidth)
                )
                drawCircle(
                    color=selectedColor,
                    radius=(outerRadius-ringWidth-2.dp.toPx()).coerceAtLeast(4.dp.toPx())
                )
                drawCircle(
                    color=Color.White.copy(alpha=.9f),
                    radius=outerRadius,
                    style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.2.dp.toPx())
                )
            }
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
            Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                colors.chunked(3).forEach{row->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        row.forEach{key->
                            val c=noteColor(key)
                            Surface(
                                color=c,
                                shape=androidx.compose.foundation.shape.CircleShape,
                                border=if(selected==key)BorderStroke(3.dp,MaterialTheme.colorScheme.primary)else BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.45f)),
                                modifier=Modifier.size(40.dp).clickable{onSelected(key);expanded=false}
                            ){
                                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                                    if(selected==key)Icon(Icons.Default.Check,null,tint=if(c.luminance()<.5f)Color.White else Color.Black,modifier=Modifier.size(18.dp))
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
s = s[:start] + new + s[end:]
ui.write_text(s)

b = gradle.read_text()
assert 'versionCode = 39' in b and 'versionName = "0.9.6"' in b
b = b.replace('versionCode = 39','versionCode = 40').replace('versionName = "0.9.6"','versionName = "0.9.7"')
gradle.write_text(b)

a = strings.read_text()
assert '0.9.6 (39)' in a
a = a.replace('0.9.6 (39)','0.9.7 (40)')
strings.write_text(a)

s = ui.read_text()
assert 'Brush.sweepGradient' in s
assert 'CircleShape' in s
assert 'TopColorMenu(colorKey){colorKey=it}' in s
assert 'NoteColorPicker(' not in s
assert 'versionCode = 40' in gradle.read_text()
assert 'versionName = "0.9.7"' in gradle.read_text()
