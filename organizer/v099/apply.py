from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()
s=s.replace('import android.app.LocaleManager\n','import android.app.LocaleManager\nimport android.graphics.BitmapFactory\n',1)
s=s.replace('import androidx.compose.foundation.lazy.LazyColumn\n','import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.LazyRow\n',1)
s=s.replace('import androidx.compose.ui.graphics.Color\n','import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.asImageBitmap\n',1)
s=s.replace('import androidx.compose.ui.platform.LocalContext\n','import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalDensity\n',1)
s=s.replace('import androidx.compose.ui.text.input.KeyboardCapitalization\n','import androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.text.input.KeyboardCapitalization\n',1)

old='''                    BottomNavPill(section=="notes",AppStrings.t(lang,"notes"),Icons.Default.Description,{section="notes"},Modifier.weight(1f))\n                    BottomNavPill(section=="projects",AppStrings.t(lang,"projects"),Icons.Default.FolderSpecial,{section="projects"},Modifier.weight(1f))\n                    BottomNavPill(section=="money",AppStrings.t(lang,"money"),Icons.Default.AccountBalanceWallet,{section="money"},Modifier.weight(1f))\n                    BottomNavIconPill(section=="settings",Icons.Default.Settings,{section="settings"},Modifier.width(48.dp))\n'''
new='''                    BottomTextNavPill(section=="notes",AppStrings.t(lang,"notes"),{section="notes"},Modifier.weight(1f))\n                    BottomTextNavPill(section=="projects",AppStrings.t(lang,"projects"),{section="projects"},Modifier.weight(1f))\n                    BottomTextNavPill(section=="money",AppStrings.t(lang,"money"),{section="money"},Modifier.weight(1f))\n                    BottomTextNavPill(section=="settings",AppStrings.t(lang,"settings"),{section="settings"},Modifier.weight(1f))\n'''
assert old in s
s=s.replace(old,new,1)

old='''            val files=atts.filterNot{it.isVoice};if(files.isNotEmpty()){item{Text(AppStrings.t(lang,"attachment"),style=MaterialTheme.typography.titleMedium)};items(files,key={it.id}){AttachmentRow(it,lang){refresh++}}}\n'''
new='''            val files=atts.filterNot{it.isVoice};if(files.isNotEmpty()){item{Text(AppStrings.t(lang,"attachment"),style=MaterialTheme.typography.titleMedium)};item{AttachmentStrip(files,lang){refresh++}}}\n'''
assert old in s
s=s.replace(old,new,1)
old='''            val files=atts.filterNot{it.isVoice};if(files.isNotEmpty())items(files,key={it.id}){AttachmentRow(it,lang){refresh++}}\n'''
new='''            val files=atts.filterNot{it.isVoice};if(files.isNotEmpty())item{AttachmentStrip(files,lang){refresh++}}\n'''
assert old in s
s=s.replace(old,new,1)

old='''            if(atts.isNotEmpty())items(atts,key={it.id}){a->AttachmentRow(a,lang){refresh++;onChanged()}}\n'''
new='''            val voices=atts.filter{it.isVoice};if(voices.isNotEmpty())items(voices,key={it.id}){a->VoiceNoteBlock(a,lang,{AttachmentStore.delete(a);refresh++;onChanged()})}\n            val files=atts.filterNot{it.isVoice};if(files.isNotEmpty())item{AttachmentStrip(files,lang){refresh++;onChanged()}}\n'''
assert old in s
s=s.replace(old,new,1)

old='''        if(files.isEmpty())EmptyState(AppStrings.t(lang,"files")) else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(files,key={it.id}){a->if(a.isVoice)VoiceNoteBlock(a,lang,{AttachmentStore.delete(a);changed()})else AttachmentRow(a,lang){changed()}}}\n'''
new='''        if(files.isEmpty())EmptyState(AppStrings.t(lang,"files")) else {\n            val voices=files.filter{it.isVoice}\n            val docs=files.filterNot{it.isVoice}\n            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){\n                if(voices.isNotEmpty())items(voices,key={it.id}){a->VoiceNoteBlock(a,lang,{AttachmentStore.delete(a);changed()})}\n                if(docs.isNotEmpty())item{AttachmentStrip(docs,lang){changed()}}\n            }\n        }\n'''
assert old in s
s=s.replace(old,new,1)

start=s.index('@Composable private fun TopColorMenu')
end=s.index('@Composable private fun VoiceNoteBlock')
new_palette='''@Composable private fun TopColorMenu(selected:String,onSelected:(String)->Unit){\n    var expanded by remember{mutableStateOf(false)}\n    val colors=listOf("blue","yellow","green","orange","pink","purple","red","bright_yellow","cyan")\n    val selectedColor=noteColor(selected)\n    Box{\n        Surface(\n            color=MaterialTheme.colorScheme.surface.copy(alpha=.97f),\n            shape=RoundedCornerShape(12.dp),\n            border=BorderStroke(1.dp,MaterialTheme.colorScheme.onPrimary.copy(alpha=.30f)),\n            tonalElevation=3.dp,\n            shadowElevation=2.dp,\n            modifier=Modifier.size(42.dp).clickable{expanded=true}\n        ){\n            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){\n                Canvas(Modifier.size(30.dp)){\n                    val r=3.2.dp.toPx()\n                    drawCircle(Color(0xFFFF3B30),r,Offset(size.width*.22f,size.height*.24f))\n                    drawCircle(Color(0xFFFFD60A),r,Offset(size.width*.78f,size.height*.24f))\n                    drawCircle(Color(0xFF00CFE8),r,Offset(size.width*.22f,size.height*.76f))\n                    drawCircle(Color(0xFF8A5CF6),r,Offset(size.width*.78f,size.height*.76f))\n                    drawCircle(selectedColor,7.2.dp.toPx(),Offset(size.width*.50f,size.height*.50f))\n                    drawCircle(Color.White.copy(alpha=.92f),7.2.dp.toPx(),Offset(size.width*.50f,size.height*.50f),style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.4.dp.toPx()))\n                }\n            }\n        }\n        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){\n            Column(Modifier.padding(7.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){\n                colors.chunked(3).forEach{row->\n                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){\n                        row.forEach{key->\n                            val c=noteColor(key)\n                            Box(Modifier.size(36.dp),contentAlignment=Alignment.Center){\n                                Surface(\n                                    color=c,\n                                    shape=RoundedCornerShape(9.dp),\n                                    border=if(selected==key)BorderStroke(2.5.dp,MaterialTheme.colorScheme.primary)else BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.35f)),\n                                    tonalElevation=1.dp,\n                                    shadowElevation=if(selected==key)2.dp else 0.dp,\n                                    modifier=Modifier.size(32.dp).clickable{onSelected(key);expanded=false}\n                                ){\n                                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){\n                                        if(selected==key)Icon(Icons.Default.Check,null,tint=if(c.luminance()<.5f)Color.White else Color.Black,modifier=Modifier.size(15.dp))\n                                    }\n                                }\n                            }\n                        }\n                    }\n                }\n            }\n        }\n    }\n}\n\n'''
s=s[:start]+new_palette+s[end:]

old='''@Composable private fun AttachmentRow(e:AttachmentEntry,lang:String,onChanged:()->Unit){Card(Modifier.fillMaxWidth()){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AttachFile,null);Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f).clickable{runCatching{AttachmentStore.open(e)}}){Text(e.displayName,maxLines=1,overflow=TextOverflow.Ellipsis);Text(AttachmentStore.humanSize(e.size),style=MaterialTheme.typography.bodySmall)};IconButton({AttachmentStore.share(e)}){Icon(Icons.Default.Share,null)};IconButton({AttachmentStore.delete(e);onChanged()}){Icon(Icons.Default.Delete,null)}}}}\n'''
new='''private fun decodeAttachmentThumbnail(file:File):android.graphics.Bitmap?{\n    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}\n    BitmapFactory.decodeFile(file.absolutePath,bounds)\n    if(bounds.outWidth<=0||bounds.outHeight<=0)return null\n    var sample=1\n    while(bounds.outWidth/sample>320||bounds.outHeight/sample>320)sample*=2\n    return BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample})\n}\n\n@Composable private fun AttachmentStrip(files:List<AttachmentEntry>,lang:String,onChanged:()->Unit){\n    LazyRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(end=4.dp)){\n        items(files,key={it.id}){e->AttachmentTile(e,lang,onChanged)}\n    }\n}\n\n@Composable private fun AttachmentTile(e:AttachmentEntry,lang:String,onChanged:()->Unit){\n    val isImage=e.mimeType.startsWith("image/")\n    val bitmap=remember(e.id,e.size){if(isImage)runCatching{decodeAttachmentThumbnail(AttachmentStore.file(e))}.getOrNull() else null}\n    Card(Modifier.width(104.dp),shape=RoundedCornerShape(12.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){\n        Column(Modifier.padding(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){\n            Box(Modifier.fillMaxWidth().height(62.dp).clickable{runCatching{AttachmentStore.open(e)}},contentAlignment=Alignment.Center){\n                if(bitmap!=null)Image(bitmap.asImageBitmap(),e.displayName,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)\n                else Icon(if(e.mimeType.contains("pdf",true))Icons.Default.PictureAsPdf else Icons.Default.Description,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)\n            }\n            Text(e.displayName,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)\n            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){\n                IconButton({AttachmentStore.share(e)},modifier=Modifier.size(28.dp)){Icon(Icons.Default.Share,null,Modifier.size(16.dp))}\n                IconButton({AttachmentStore.delete(e);onChanged()},modifier=Modifier.size(28.dp)){Icon(Icons.Default.Delete,null,Modifier.size(16.dp))}\n            }\n        }\n    }\n}\n\n@Composable private fun AttachmentRow(e:AttachmentEntry,lang:String,onChanged:()->Unit){AttachmentStrip(listOf(e),lang,onChanged)}\n'''
assert old in s
s=s.replace(old,new,1)

old='''@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){\n    FilledTonalButton(onClick=onClick,shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp),modifier=Modifier.heightIn(min=34.dp,max=38.dp)){\n        Icon(icon,null,Modifier.size(17.dp))\n        Spacer(Modifier.width(5.dp))\n        Text(text,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)\n    }\n}\n'''
new='''@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){\n    FilledTonalButton(onClick=onClick,shape=RoundedCornerShape(19.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=14.dp,vertical=0.dp),modifier=Modifier.heightIn(min=40.dp,max=44.dp)){\n        Icon(icon,null,Modifier.size(19.dp))\n        Spacer(Modifier.width(6.dp))\n        Text(text,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp,fontWeight=FontWeight.Bold))\n    }\n}\n'''
assert old in s
s=s.replace(old,new,1)

start=s.index('@Composable private fun BottomNavPill')
end=s.index('@Composable private fun noteColor')
new_bottom='''@Composable private fun BottomTextNavPill(selected:Boolean,text:String,onClick:()->Unit,modifier:Modifier=Modifier){\n    val shape=RoundedCornerShape(15.dp)\n    val fixedTextSize=with(LocalDensity.current){13.dp.toSp()}\n    val content: @Composable RowScope.() -> Unit={\n        Text(text,maxLines=1,softWrap=false,overflow=TextOverflow.Clip,style=MaterialTheme.typography.bodyMedium.copy(fontSize=fixedTextSize,fontWeight=FontWeight.SemiBold))\n    }\n    if(selected) Button(onClick=onClick,shape=shape,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp),modifier=modifier.height(44.dp),content=content)\n    else OutlinedButton(onClick=onClick,shape=shape,colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp),modifier=modifier.height(44.dp),content=content)\n}\n\n'''
s=s[:start]+new_bottom+s[end:]
ui.write_text(s)

b=gradle.read_text()
assert 'versionCode = 41' in b and 'versionName = "0.9.8"' in b
b=b.replace('versionCode = 41','versionCode = 42').replace('versionName = "0.9.8"','versionName = "0.9.9"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.8 (41)' in a
a=a.replace('0.9.8 (41)','0.9.9 (42)')
strings.write_text(a)

s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'BottomTextNavPill(section=="settings",AppStrings.t(lang,"settings")' in s
assert 'Icons.Default.Description,{section="notes"}' not in s
assert 'fontWeight=FontWeight.Bold' in s
assert 'AttachmentStrip(files,lang)' in s
assert 'decodeAttachmentThumbnail' in s
assert 'BitmapFactory' in s and 'asImageBitmap' in s
assert 'versionCode = 42' in b and 'versionName = "0.9.9"' in b
assert '0.9.9 (42)' in a
