from pathlib import Path
import sys

root = Path(sys.argv[1])
ui = root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
models = root/'app/src/main/java/kz/kairat/organizer/Models.kt'
db = root/'app/src/main/java/kz/kairat/organizer/NoteDatabase.kt'
backup = root/'app/src/main/java/kz/kairat/organizer/BackupManager.kt'
gradle = root/'app/build.gradle.kts'
strings = root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

# Models: persist one of the same six colors for projects and finance entries.
s = models.read_text()
old = '''    val createdAt: Long,\n    val updatedAt: Long = createdAt\n)'''
new = '''    val createdAt: Long,\n    val updatedAt: Long = createdAt,\n    val colorKey: String = "blue"\n)'''
assert old in s
s = s.replace(old,new,1)
old = 'data class MoneyEntry(val id: Long, val type: String, val amount: Double, val category: String, val projectId: Long?, val projectName: String, val note: String, val createdAt: Long)'
new = 'data class MoneyEntry(val id: Long, val type: String, val amount: Double, val category: String, val projectId: Long?, val projectName: String, val note: String, val createdAt: Long, val colorKey: String = "blue")'
assert old in s
s = s.replace(old,new,1)
models.write_text(s)

# Database: non-destructive schema v9 adds color_key to projects and money_entries.
s = db.read_text()
assert 'SQLiteOpenHelper(context, "organizer.db", null, 8)' in s
s = s.replace('SQLiteOpenHelper(context, "organizer.db", null, 8)','SQLiteOpenHelper(context, "organizer.db", null, 9)',1)
s = s.replace("contact TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL DEFAULT 0)","contact TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL DEFAULT 0,color_key TEXT NOT NULL DEFAULT 'blue')",1)
s = s.replace("project_id INTEGER,note TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)","project_id INTEGER,note TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,color_key TEXT NOT NULL DEFAULT 'blue')",1)
anchor = '''    private fun createV8Tables(db: SQLiteDatabase) {\n        try { db.execSQL("ALTER TABLE notes ADD COLUMN color_key TEXT NOT NULL DEFAULT 'blue'") } catch (_: Exception) {}\n        db.execSQL("UPDATE notes SET color_key='blue' WHERE color_key IS NULL OR TRIM(color_key)=''")\n    }\n'''
insert = anchor + '''    private fun createV9Tables(db: SQLiteDatabase) {\n        try { db.execSQL("ALTER TABLE projects ADD COLUMN color_key TEXT NOT NULL DEFAULT 'blue'") } catch (_: Exception) {}\n        try { db.execSQL("ALTER TABLE money_entries ADD COLUMN color_key TEXT NOT NULL DEFAULT 'blue'") } catch (_: Exception) {}\n        db.execSQL("UPDATE projects SET color_key='blue' WHERE color_key IS NULL OR TRIM(color_key)=''")\n        db.execSQL("UPDATE money_entries SET color_key='blue' WHERE color_key IS NULL OR TRIM(color_key)=''")\n    }\n'''
assert anchor in s
s = s.replace(anchor,insert,1)
assert 'if (oldVersion < 8) createV8Tables(db)' in s
s = s.replace('if (oldVersion < 8) createV8Tables(db)','if (oldVersion < 8) createV8Tables(db)\n        if (oldVersion < 9) createV9Tables(db)',1)
old_open='override fun onOpen(db: SQLiteDatabase) { super.onOpen(db); createV2Tables(db); createV3Tables(db); createV4Tables(db); createV5Tables(db); createV6Tables(db); createV7Tables(db); createV8Tables(db) }'
new_open='override fun onOpen(db: SQLiteDatabase) { super.onOpen(db); createV2Tables(db); createV3Tables(db); createV4Tables(db); createV5Tables(db); createV6Tables(db); createV7Tables(db); createV8Tables(db); createV9Tables(db) }'
assert old_open in s
s=s.replace(old_open,new_open,1)
old='''    fun addProject(name:String,description:String):Long = saveProject(null,name,description,"planned",0,System.currentTimeMillis(),0L,0.0,"","","")\n    fun saveProject(id:Long?,name:String,description:String,status:String,progress:Int,startAt:Long,dueAt:Long,budget:Double,client:String,address:String,contact:String):Long{\n        val now=System.currentTimeMillis(); val values=ContentValues().apply{put("name",name);put("description",description);put("status",status);put("progress",progress.coerceIn(0,100));put("start_at",startAt);put("due_at",dueAt);put("budget",budget);put("client",client);put("address",address);put("contact",contact);put("updated_at",now)}\n'''
new='''    fun addProject(name:String,description:String,colorKey:String="blue"):Long = saveProject(null,name,description,"planned",0,System.currentTimeMillis(),0L,0.0,"","","",colorKey)\n    fun saveProject(id:Long?,name:String,description:String,status:String,progress:Int,startAt:Long,dueAt:Long,budget:Double,client:String,address:String,contact:String,colorKey:String="blue"):Long{\n        val safeColor=colorKey.takeIf{it in setOf("blue","yellow","green","orange","pink","purple")}?:"blue"\n        val now=System.currentTimeMillis(); val values=ContentValues().apply{put("name",name);put("description",description);put("status",status);put("progress",progress.coerceIn(0,100));put("start_at",startAt);put("due_at",dueAt);put("budget",budget);put("client",client);put("address",address);put("contact",contact);put("color_key",safeColor);put("updated_at",now)}\n'''
assert old in s
s=s.replace(old,new,1)
old='''    fun getProjects():List<Project> = readableDatabase.rawQuery("SELECT id,name,description,status,progress,start_at,due_at,budget,client,address,contact,created_at,updated_at FROM projects ORDER BY updated_at DESC,created_at DESC",null).use{c->buildList{while(c.moveToNext())add(projectFromCursor(c))}}\n    fun getProject(id:Long):Project?=readableDatabase.rawQuery("SELECT id,name,description,status,progress,start_at,due_at,budget,client,address,contact,created_at,updated_at FROM projects WHERE id=?",arrayOf(id.toString())).use{c->if(c.moveToFirst())projectFromCursor(c)else null}\n    private fun projectFromCursor(c:android.database.Cursor)=Project(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getLong(5),c.getLong(6),c.getDouble(7),c.getString(8),c.getString(9),c.getString(10),c.getLong(11),c.getLong(12))\n'''
new='''    fun getProjects():List<Project> = readableDatabase.rawQuery("SELECT id,name,description,status,progress,start_at,due_at,budget,client,address,contact,created_at,updated_at,color_key FROM projects ORDER BY updated_at DESC,created_at DESC",null).use{c->buildList{while(c.moveToNext())add(projectFromCursor(c))}}\n    fun getProject(id:Long):Project?=readableDatabase.rawQuery("SELECT id,name,description,status,progress,start_at,due_at,budget,client,address,contact,created_at,updated_at,color_key FROM projects WHERE id=?",arrayOf(id.toString())).use{c->if(c.moveToFirst())projectFromCursor(c)else null}\n    private fun projectFromCursor(c:android.database.Cursor)=Project(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getLong(5),c.getLong(6),c.getDouble(7),c.getString(8),c.getString(9),c.getString(10),c.getLong(11),c.getLong(12),if(c.isNull(13))"blue" else c.getString(13))\n'''
assert old in s
s=s.replace(old,new,1)
old='''    fun addMoneyEntry(type:String,amount:Double,category:String,projectId:Long?,note:String):Long {\n        val id=writableDatabase.insertOrThrow("money_entries",null,ContentValues().apply{put("type",type);put("amount",amount);put("category",category);if(projectId==null)putNull("project_id")else put("project_id",projectId);put("note",note);put("created_at",System.currentTimeMillis())})\n'''
new='''    fun addMoneyEntry(type:String,amount:Double,category:String,projectId:Long?,note:String,colorKey:String="blue"):Long {\n        val safeColor=colorKey.takeIf{it in setOf("blue","yellow","green","orange","pink","purple")}?:"blue"\n        val id=writableDatabase.insertOrThrow("money_entries",null,ContentValues().apply{put("type",type);put("amount",amount);put("category",category);if(projectId==null)putNull("project_id")else put("project_id",projectId);put("note",note);put("color_key",safeColor);put("created_at",System.currentTimeMillis())})\n'''
assert old in s
s=s.replace(old,new,1)
old='fun getMoneyEntries():List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,p.name,m.note,m.created_at FROM money_entries m LEFT JOIN projects p ON p.id=m.project_id ORDER BY m.created_at DESC",null).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))"" else c.getString(5),c.getString(6),c.getLong(7)))}}'
new='fun getMoneyEntries():List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,p.name,m.note,m.created_at,m.color_key FROM money_entries m LEFT JOIN projects p ON p.id=m.project_id ORDER BY m.created_at DESC",null).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))"" else c.getString(5),c.getString(6),c.getLong(7),if(c.isNull(8))"blue" else c.getString(8)))}}'
assert old in s
s=s.replace(old,new,1)
old='projects.forEach{p->writableDatabase.insert("projects",null,ContentValues().apply{put("id",p.id);put("name",p.name);put("description",p.description);put("status",p.status);put("progress",p.progress);put("start_at",p.startAt);put("due_at",p.dueAt);put("budget",p.budget);put("client",p.client);put("address",p.address);put("contact",p.contact);put("created_at",p.createdAt);put("updated_at",p.updatedAt)})}'
new='projects.forEach{p->writableDatabase.insert("projects",null,ContentValues().apply{put("id",p.id);put("name",p.name);put("description",p.description);put("status",p.status);put("progress",p.progress);put("start_at",p.startAt);put("due_at",p.dueAt);put("budget",p.budget);put("client",p.client);put("address",p.address);put("contact",p.contact);put("created_at",p.createdAt);put("updated_at",p.updatedAt);put("color_key",p.colorKey)})}'
assert old in s
s=s.replace(old,new,1)
old='money.forEach{m->writableDatabase.insert("money_entries",null,ContentValues().apply{put("id",m.id);put("type",m.type);put("amount",m.amount);put("category",m.category);if(m.projectId==null)putNull("project_id")else put("project_id",m.projectId);put("note",m.note);put("created_at",m.createdAt)})}'
new='money.forEach{m->writableDatabase.insert("money_entries",null,ContentValues().apply{put("id",m.id);put("type",m.type);put("amount",m.amount);put("category",m.category);if(m.projectId==null)putNull("project_id")else put("project_id",m.projectId);put("note",m.note);put("created_at",m.createdAt);put("color_key",m.colorKey)})}'
assert old in s
s=s.replace(old,new,1)
db.write_text(s)

# Backups keep the new colors; old backups restore as blue.
s=backup.read_text()
s=s.replace('root.put("version",4)','root.put("version",5)',1)
old='put("contact",p.contact);put("createdAt",p.createdAt);put("updatedAt",p.updatedAt)'
new='put("contact",p.contact);put("createdAt",p.createdAt);put("updatedAt",p.updatedAt);put("colorKey",p.colorKey)'
assert old in s
s=s.replace(old,new,1)
old='put("projectName",m.projectName);put("note",m.note);put("createdAt",m.createdAt)'
new='put("projectName",m.projectName);put("note",m.note);put("createdAt",m.createdAt);put("colorKey",m.colorKey)'
assert old in s
s=s.replace(old,new,1)
old='Project(o.optLong("id"),o.optString("name"),o.optString("description"),o.optString("status","planned"),o.optInt("progress",0),o.optLong("startAt",created),o.optLong("dueAt",0),o.optDouble("budget",0.0),o.optString("client"),o.optString("address"),o.optString("contact"),created,o.optLong("updatedAt",created))'
new='Project(o.optLong("id"),o.optString("name"),o.optString("description"),o.optString("status","planned"),o.optInt("progress",0),o.optLong("startAt",created),o.optLong("dueAt",0),o.optDouble("budget",0.0),o.optString("client"),o.optString("address"),o.optString("contact"),created,o.optLong("updatedAt",created),o.optString("colorKey","blue"))'
assert old in s
s=s.replace(old,new,1)
old='MoneyEntry(o.optLong("id"),o.optString("type"),o.optDouble("amount"),o.optString("category"),if(o.has("projectId"))o.optLong("projectId")else null,o.optString("projectName"),o.optString("note"),o.optLong("createdAt"))'
new='MoneyEntry(o.optLong("id"),o.optString("type"),o.optDouble("amount"),o.optString("category"),if(o.has("projectId"))o.optLong("projectId")else null,o.optString("projectName"),o.optString("note"),o.optLong("createdAt"),o.optString("colorKey","blue"))'
assert old in s
s=s.replace(old,new,1)
backup.write_text(s)

# UI: creation screens have no top back arrow; selected color square replaces save icon.
s=ui.read_text()
# Home callbacks now carry the selected color.
s=s.replace('onAdd={name,description->\n                val id=db.addProject(name,description)','onAdd={name,description,colorKey->\n                val id=db.addProject(name,description,colorKey)',1)
s=s.replace('onAdd={amount,cat,note->\n                db.addMoneyEntry(fixedType,amount,cat,null,note)','onAdd={amount,cat,note,colorKey->\n                db.addMoneyEntry(fixedType,amount,cat,null,note,colorKey)',1)
# Note editor top bar and remove inline color labels grid.
old='Scaffold(topBar={TopAppBar(title={Text(AppStrings.t(lang,if(note==null)"new_note" else "edit_note"))},navigationIcon={IconButton({close()}){Icon(Icons.Default.ArrowBack,null)}},actions={if(note!=null)IconButton({db.delete(note.id);AttachmentStore.deleteTarget("note",note.id);onClose()}){Icon(Icons.Default.Delete,null)};IconButton({save()}){Icon(Icons.Default.Save,null)}},colors=organizerTopBarColors())}){pad->'
new='Scaffold(topBar={TopAppBar(title={Text(AppStrings.t(lang,if(note==null)"new_note" else "edit_note"))},navigationIcon={if(note!=null){IconButton({close()}){Icon(Icons.Default.ArrowBack,null)}}},actions={if(note!=null)IconButton({db.delete(note.id);AttachmentStore.deleteTarget("note",note.id);onClose()}){Icon(Icons.Default.Delete,null)};TopColorMenu(colorKey){colorKey=it}},colors=organizerTopBarColors())}){pad->'
assert old in s
s=s.replace(old,new,1)
old='            item{NoteColorPicker(colorKey,lang){colorKey=it}}\n'
assert old in s
s=s.replace(old,'',1)
# Project list uses selected project colors.
s=s.replace('Card(Modifier.fillMaxWidth().clickable{onOpen(p.id)},colors=brandCardColors((p.id%2).toInt()))','Card(Modifier.fillMaxWidth().clickable{onOpen(p.id)},colors=noteCardColors(p.colorKey))',1)
# Full-screen new project with selected color.
s=s.replace('@Composable private fun AddProjectDialog(lang:String,onDismiss:()->Unit,onAdd:(String,String)->Unit){\n    var name by rememberSaveable{mutableStateOf("")}\n    var description by rememberSaveable{mutableStateOf("")}\n    fun submit(){if(name.isNotBlank())onAdd(name.trim(),description.trim())}', '@Composable private fun AddProjectDialog(lang:String,onDismiss:()->Unit,onAdd:(String,String,String)->Unit){\n    var name by rememberSaveable{mutableStateOf("")}\n    var description by rememberSaveable{mutableStateOf("")}\n    var colorKey by rememberSaveable{mutableStateOf("blue")}\n    fun submit(){if(name.isNotBlank())onAdd(name.trim(),description.trim(),colorKey)}',1)
old='''            TopAppBar(\n                title={Text(AppStrings.t(lang,"new_project"))},\n                navigationIcon={IconButton(onDismiss){Icon(Icons.Default.ArrowBack,null)}},\n                actions={IconButton({submit()}){Icon(Icons.Default.Save,null)}},\n                colors=organizerTopBarColors()\n            )'''
new='''            TopAppBar(\n                title={Text(AppStrings.t(lang,"new_project"))},\n                actions={TopColorMenu(colorKey){colorKey=it}},\n                colors=organizerTopBarColors()\n            )'''
assert old in s
s=s.replace(old,new,1)
# Preserve project color when its detail editor saves metadata.
old='db.saveProject(project.id,name.trim(),description.trim(),status,progress.toInt(),startAt,dueAt,parseAmountInput(budget)?:0.0,client.trim(),address.trim(),contact.trim());onSaved()'
new='db.saveProject(project.id,name.trim(),description.trim(),status,progress.toInt(),startAt,dueAt,parseAmountInput(budget)?:0.0,client.trim(),address.trim(),contact.trim(),project.colorKey);onSaved()'
assert old in s
s=s.replace(old,new,1)
# Analytics project card follows project color too.
s=s.replace('Card(Modifier.fillMaxWidth(),colors=brandCardColors((project.id%3).toInt()))','Card(Modifier.fillMaxWidth(),colors=noteCardColors(project.colorKey))',1)
# Finance entries use selected color.
old='Card(Modifier.fillMaxWidth(),colors=brandCardColors(cardTone)){\n        Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp)){'
new='Card(Modifier.fillMaxWidth(),colors=noteCardColors(m.colorKey)){\n        Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp)){'
assert old in s
s=s.replace(old,new,1)
# Finance add screen with color, no arrow, selected square in top actions.
s=s.replace('@Composable private fun AddMoneyDialog(db:NoteDatabase,lang:String,fixedType:String,onDismiss:()->Unit,onAdd:(Double,String,String)->Unit){\n    var amount by rememberSaveable{mutableStateOf("")}\n    var cat by rememberSaveable{mutableStateOf("")}\n    var note by rememberSaveable{mutableStateOf("")}\n    fun submit(){parseAmountInput(amount)?.let{onAdd(it,cat.trim(),note.trim())}}', '@Composable private fun AddMoneyDialog(db:NoteDatabase,lang:String,fixedType:String,onDismiss:()->Unit,onAdd:(Double,String,String,String)->Unit){\n    var amount by rememberSaveable{mutableStateOf("")}\n    var cat by rememberSaveable{mutableStateOf("")}\n    var note by rememberSaveable{mutableStateOf("")}\n    var colorKey by rememberSaveable{mutableStateOf("blue")}\n    fun submit(){parseAmountInput(amount)?.let{onAdd(it,cat.trim(),note.trim(),colorKey)}}',1)
old='''            TopAppBar(\n                title={Text(AppStrings.t(lang,fixedType))},\n                navigationIcon={IconButton(onDismiss){Icon(Icons.Default.ArrowBack,null)}},\n                actions={IconButton({submit()}){Icon(Icons.Default.Save,null)}},\n                colors=organizerTopBarColors()\n            )'''
new='''            TopAppBar(\n                title={Text(AppStrings.t(lang,fixedType))},\n                actions={TopColorMenu(colorKey){colorKey=it}},\n                colors=organizerTopBarColors()\n            )'''
assert old in s
s=s.replace(old,new,1)

# Replace the old labeled color grid with compact square-only dropdown used in top bars.
start=s.index('@Composable private fun NoteColorPicker')
end=s.index('@Composable private fun VoiceNoteBlock')
menu='''@Composable private fun TopColorMenu(selected:String,onSelected:(String)->Unit){\n    var expanded by remember{mutableStateOf(false)}\n    val colors=listOf("blue","yellow","green","orange","pink","purple")\n    Box{\n        IconButton({expanded=true}){\n            Surface(\n                color=noteColor(selected),\n                shape=RoundedCornerShape(7.dp),\n                border=BorderStroke(2.dp,Color.White.copy(alpha=.92f)),\n                modifier=Modifier.size(30.dp)\n            ){}\n        }\n        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){\n            Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){\n                colors.chunked(3).forEach{row->\n                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){\n                        row.forEach{key->\n                            val c=noteColor(key)\n                            Surface(\n                                color=c,\n                                shape=RoundedCornerShape(7.dp),\n                                border=if(selected==key)BorderStroke(2.dp,MaterialTheme.colorScheme.primary)else BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.45f)),\n                                modifier=Modifier.size(38.dp).clickable{onSelected(key);expanded=false}\n                            ){\n                                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){\n                                    if(selected==key)Icon(Icons.Default.Check,null,tint=if(c.luminance()<.5f)Color.White else Color.Black,modifier=Modifier.size(18.dp))\n                                }\n                            }\n                        }\n                    }\n                }\n            }\n        }\n    }\n}\n\n'''
s=s[:start]+menu+s[end:]
ui.write_text(s)

# Version bump.
b=gradle.read_text()
assert 'versionCode = 38' in b and 'versionName = "0.9.5"' in b
b=b.replace('versionCode = 38','versionCode = 39').replace('versionName = "0.9.5"','versionName = "0.9.6"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.5 (38)' in a
a=a.replace('0.9.5 (38)','0.9.6 (39)')
strings.write_text(a)

# Gates.
assert 'versionCode = 39' in gradle.read_text()
assert 'versionName = "0.9.6"' in gradle.read_text()
assert 'SQLiteOpenHelper(context, "organizer.db", null, 9)' in db.read_text()
assert 'ALTER TABLE projects ADD COLUMN color_key' in db.read_text()
assert 'ALTER TABLE money_entries ADD COLUMN color_key' in db.read_text()
assert 'TopColorMenu(colorKey){colorKey=it}' in ui.read_text()
assert 'NoteColorPicker(' not in ui.read_text()
assert 'navigationIcon={IconButton(onDismiss)' not in ui.read_text()[ui.read_text().index('@Composable private fun AddProjectDialog'):ui.read_text().index('@Composable private fun ProjectDetailScreen')]
assert 'navigationIcon={IconButton(onDismiss)' not in ui.read_text()[ui.read_text().index('@Composable private fun AddMoneyDialog'):ui.read_text().index('@Composable private fun RemindersScreen')]
assert 'db.addMoneyEntry(fixedType,amount,cat,null,note,colorKey)' in ui.read_text()
assert 'db.addProject(name,description,colorKey)' in ui.read_text()
assert 'supportedLanguages = listOf("kk","ru","en","zh","de","es","fr","tr")' in strings.read_text()
