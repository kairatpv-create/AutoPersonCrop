package kz.kairat.organizer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NoteDatabase(context: Context) : SQLiteOpenHelper(context, "organizer.db", null, 9) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL DEFAULT '',body TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,color_key TEXT NOT NULL DEFAULT 'blue')")
        createV2Tables(db); createV3Tables(db); createV4Tables(db); createV5Tables(db); createV6Tables(db); createV7Tables(db)
    }
    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS projects(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'planned',progress INTEGER NOT NULL DEFAULT 0,start_at INTEGER NOT NULL DEFAULT 0,due_at INTEGER NOT NULL DEFAULT 0,budget REAL NOT NULL DEFAULT 0,client TEXT NOT NULL DEFAULT '',address TEXT NOT NULL DEFAULT '',contact TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL DEFAULT 0,color_key TEXT NOT NULL DEFAULT 'blue')")
        db.execSQL("CREATE TABLE IF NOT EXISTS money_entries(id INTEGER PRIMARY KEY AUTOINCREMENT,type TEXT NOT NULL,amount REAL NOT NULL,category TEXT NOT NULL DEFAULT '',project_id INTEGER,note TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,color_key TEXT NOT NULL DEFAULT 'blue')")
    }
    private fun createV3Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,details TEXT NOT NULL DEFAULT '',done INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,target_type TEXT NOT NULL,target_id INTEGER NOT NULL,trigger_at INTEGER NOT NULL,sound INTEGER NOT NULL DEFAULT 1,vibrate INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL)")
    }
    private fun createV4Tables(db: SQLiteDatabase) { try { db.execSQL("ALTER TABLE reminders ADD COLUMN sound_key TEXT NOT NULL DEFAULT 'system'") } catch (_: Exception) {} }
    private fun createV5Tables(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS hidden_suggestions(kind TEXT NOT NULL,value TEXT NOT NULL,hidden_at INTEGER NOT NULL,PRIMARY KEY(kind,value))") }
    private fun createV6Tables(db: SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE reminders ADD COLUMN schedule_type TEXT NOT NULL DEFAULT 'once'") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE reminders ADD COLUMN days_mask INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE reminders ADD COLUMN day_mode TEXT NOT NULL DEFAULT 'single'") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE reminders ADD COLUMN base_minutes INTEGER NOT NULL DEFAULT 540") } catch (_: Exception) {}
    }
    private fun createV7Tables(db: SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE projects ADD COLUMN status TEXT NOT NULL DEFAULT 'planned'") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE projects ADD COLUMN progress INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE projects ADD COLUMN start_at INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE projects ADD COLUMN due_at INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE projects ADD COLUMN budget REAL NOT NULL DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE projects ADD COLUMN client TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE projects ADD COLUMN address TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE projects ADD COLUMN contact TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE projects ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        db.execSQL("UPDATE projects SET updated_at=created_at WHERE updated_at=0")
        db.execSQL("CREATE TABLE IF NOT EXISTS project_stages(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,title TEXT NOT NULL,done INTEGER NOT NULL DEFAULT 0,due_at INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS project_journal(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,text TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS project_note_links(project_id INTEGER NOT NULL,note_id INTEGER NOT NULL,PRIMARY KEY(project_id,note_id))")
    }
    private fun createV8Tables(db: SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE notes ADD COLUMN color_key TEXT NOT NULL DEFAULT 'blue'") } catch (_: Exception) {}
        db.execSQL("UPDATE notes SET color_key='blue' WHERE color_key IS NULL OR TRIM(color_key)=''")
    }
    private fun createV9Tables(db: SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE projects ADD COLUMN color_key TEXT NOT NULL DEFAULT 'blue'") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE money_entries ADD COLUMN color_key TEXT NOT NULL DEFAULT 'blue'") } catch (_: Exception) {}
        db.execSQL("UPDATE projects SET color_key='blue' WHERE color_key IS NULL OR TRIM(color_key)=''")
        db.execSQL("UPDATE money_entries SET color_key='blue' WHERE color_key IS NULL OR TRIM(color_key)=''")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2Tables(db)
        if (oldVersion < 3) createV3Tables(db)
        if (oldVersion < 4) createV4Tables(db)
        if (oldVersion < 5) createV5Tables(db)
        if (oldVersion < 6) createV6Tables(db)
        if (oldVersion < 7) createV7Tables(db)
        if (oldVersion < 8) createV8Tables(db)
        if (oldVersion < 9) createV9Tables(db)
    }
    override fun onOpen(db: SQLiteDatabase) { super.onOpen(db); createV2Tables(db); createV3Tables(db); createV4Tables(db); createV5Tables(db); createV6Tables(db); createV7Tables(db); createV8Tables(db); createV9Tables(db) }

    fun save(id: Long?, title: String, body: String, colorKey: String = "blue"): Long {
        val now = System.currentTimeMillis()
        val safeColor = colorKey.takeIf { it in setOf("blue","yellow","green","orange","pink","purple","red","bright_yellow","cyan") } ?: "blue"
        val values = ContentValues().apply { put("title", title); put("body", body); put("color_key", safeColor); put("updated_at", now) }
        val result = if (id == null || id <= 0L) { values.put("created_at", now); writableDatabase.insertOrThrow("notes", null, values) }
        else { writableDatabase.update("notes", values, "id=?", arrayOf(id.toString())); id }
        if(title.isNotBlank()) unhideSuggestion("note_title", title.trim())
        return result
    }
    fun delete(noteId: Long) { writableDatabase.delete("project_note_links","note_id=?",arrayOf(noteId.toString())); writableDatabase.delete("notes", "id=?", arrayOf(noteId.toString())) }
    fun getNotes(query: String = ""): List<Note> {
        val (sql,args) = if (query.isBlank()) "SELECT id,title,body,created_at,updated_at,color_key FROM notes ORDER BY updated_at DESC" to emptyArray<String>()
        else "SELECT id,title,body,created_at,updated_at,color_key FROM notes WHERE title LIKE ? OR body LIKE ? ORDER BY updated_at DESC" to arrayOf("%$query%", "%$query%")
        return readableDatabase.rawQuery(sql,args).use { c -> buildList { while(c.moveToNext()) add(Note(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),if(c.isNull(5))"blue" else c.getString(5))) } }
    }
    fun getNote(id: Long): Note? = readableDatabase.rawQuery("SELECT id,title,body,created_at,updated_at,color_key FROM notes WHERE id=?", arrayOf(id.toString())).use { c -> if(c.moveToFirst()) Note(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),if(c.isNull(5))"blue" else c.getString(5)) else null }
    fun getNoteTitleSuggestions(query:String=""):List<String>{
        val q=query.trim(); val hidden="AND NOT EXISTS (SELECT 1 FROM hidden_suggestions h WHERE h.kind='note_title' AND h.value=notes.title COLLATE NOCASE)"
        val (sql,args)=if(q.isBlank()) "SELECT title, MAX(updated_at) AS last_used FROM notes WHERE TRIM(title)<>'' $hidden GROUP BY title ORDER BY last_used DESC LIMIT 12" to emptyArray<String>()
        else "SELECT title, MAX(updated_at) AS last_used FROM notes WHERE TRIM(title)<>'' $hidden AND title LIKE ? GROUP BY title ORDER BY CASE WHEN title LIKE ? THEN 0 ELSE 1 END,last_used DESC LIMIT 12" to arrayOf("%$q%","$q%")
        return readableDatabase.rawQuery(sql,args).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    }

    fun addTask(title: String, details: String): Long { val now=System.currentTimeMillis(); return writableDatabase.insertOrThrow("tasks",null,ContentValues().apply{put("title",title);put("details",details);put("done",0);put("created_at",now);put("updated_at",now)}) }
    fun setTaskDone(id:Long,done:Boolean){ writableDatabase.update("tasks",ContentValues().apply{put("done",if(done)1 else 0);put("updated_at",System.currentTimeMillis())},"id=?",arrayOf(id.toString())) }
    fun deleteTask(id:Long){ writableDatabase.delete("tasks","id=?",arrayOf(id.toString())) }
    fun getTasks():List<TaskItem> = readableDatabase.rawQuery("SELECT id,title,details,done,created_at,updated_at FROM tasks ORDER BY done ASC, updated_at DESC",null).use{c->buildList{while(c.moveToNext())add(TaskItem(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)!=0,c.getLong(4),c.getLong(5)))}}

    fun addProject(name:String,description:String,colorKey:String="blue"):Long = saveProject(null,name,description,"planned",0,System.currentTimeMillis(),0L,0.0,"","","",colorKey)
    fun saveProject(id:Long?,name:String,description:String,status:String,progress:Int,startAt:Long,dueAt:Long,budget:Double,client:String,address:String,contact:String,colorKey:String="blue"):Long{
        val safeColor=colorKey.takeIf{it in setOf("blue","yellow","green","orange","pink","purple","red","bright_yellow","cyan")}?:"blue"
        val now=System.currentTimeMillis(); val values=ContentValues().apply{put("name",name);put("description",description);put("status",status);put("progress",progress.coerceIn(0,100));put("start_at",startAt);put("due_at",dueAt);put("budget",budget);put("client",client);put("address",address);put("contact",contact);put("color_key",safeColor);put("updated_at",now)}
        return if(id==null||id<=0L){values.put("created_at",now);writableDatabase.insertOrThrow("projects",null,values)}else{writableDatabase.update("projects",values,"id=?",arrayOf(id.toString()));id}
    }
    fun updateProjectProgress(id:Long,progress:Int){writableDatabase.update("projects",ContentValues().apply{put("progress",progress.coerceIn(0,100));put("updated_at",System.currentTimeMillis())},"id=?",arrayOf(id.toString()))}
    fun getProjects():List<Project> = readableDatabase.rawQuery("SELECT id,name,description,status,progress,start_at,due_at,budget,client,address,contact,created_at,updated_at,color_key FROM projects ORDER BY updated_at DESC,created_at DESC",null).use{c->buildList{while(c.moveToNext())add(projectFromCursor(c))}}
    fun getProject(id:Long):Project?=readableDatabase.rawQuery("SELECT id,name,description,status,progress,start_at,due_at,budget,client,address,contact,created_at,updated_at,color_key FROM projects WHERE id=?",arrayOf(id.toString())).use{c->if(c.moveToFirst())projectFromCursor(c)else null}
    private fun projectFromCursor(c:android.database.Cursor)=Project(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getLong(5),c.getLong(6),c.getDouble(7),c.getString(8),c.getString(9),c.getString(10),c.getLong(11),c.getLong(12),if(c.isNull(13))"blue" else c.getString(13))
    fun deleteProject(id:Long){
        writableDatabase.beginTransaction();try{
            writableDatabase.execSQL("UPDATE money_entries SET project_id=NULL WHERE project_id=?",arrayOf(id));
            listOf("project_stages","project_journal","project_note_links").forEach{writableDatabase.delete(it,"project_id=?",arrayOf(id.toString()))}
            writableDatabase.delete("reminders","target_type='project' AND target_id=?",arrayOf(id.toString()));writableDatabase.delete("projects","id=?",arrayOf(id.toString()));writableDatabase.setTransactionSuccessful()
        }finally{writableDatabase.endTransaction()}
    }

    fun addProjectStage(projectId:Long,title:String,dueAt:Long):Long{val id=writableDatabase.insertOrThrow("project_stages",null,ContentValues().apply{put("project_id",projectId);put("title",title);put("done",0);put("due_at",dueAt);put("created_at",System.currentTimeMillis())});recalculateProjectProgress(projectId);return id}
    fun getProjectStages(projectId:Long):List<ProjectStage> = readableDatabase.rawQuery("SELECT id,project_id,title,done,due_at,created_at FROM project_stages WHERE project_id=? ORDER BY done ASC,created_at ASC",arrayOf(projectId.toString())).use{c->buildList{while(c.moveToNext())add(ProjectStage(c.getLong(0),c.getLong(1),c.getString(2),c.getInt(3)!=0,c.getLong(4),c.getLong(5)))}}
    fun setProjectStageDone(id:Long,done:Boolean){val p=readableDatabase.rawQuery("SELECT project_id FROM project_stages WHERE id=?",arrayOf(id.toString())).use{c->if(c.moveToFirst())c.getLong(0)else 0L};writableDatabase.update("project_stages",ContentValues().apply{put("done",if(done)1 else 0)},"id=?",arrayOf(id.toString()));if(p>0)recalculateProjectProgress(p)}
    fun deleteProjectStage(id:Long){val p=readableDatabase.rawQuery("SELECT project_id FROM project_stages WHERE id=?",arrayOf(id.toString())).use{c->if(c.moveToFirst())c.getLong(0)else 0L};writableDatabase.delete("project_stages","id=?",arrayOf(id.toString()));if(p>0)recalculateProjectProgress(p)}
    private fun recalculateProjectProgress(projectId:Long){val stages=getProjectStages(projectId);if(stages.isNotEmpty())updateProjectProgress(projectId,(stages.count{it.done}*100/stages.size))}

    fun addProjectJournal(projectId:Long,text:String):Long=writableDatabase.insertOrThrow("project_journal",null,ContentValues().apply{put("project_id",projectId);put("text",text);put("created_at",System.currentTimeMillis())})
    fun getProjectJournal(projectId:Long):List<ProjectJournalEntry> = readableDatabase.rawQuery("SELECT id,project_id,text,created_at FROM project_journal WHERE project_id=? ORDER BY created_at DESC",arrayOf(projectId.toString())).use{c->buildList{while(c.moveToNext())add(ProjectJournalEntry(c.getLong(0),c.getLong(1),c.getString(2),c.getLong(3)))}}
    fun deleteProjectJournal(id:Long){writableDatabase.delete("project_journal","id=?",arrayOf(id.toString()))}

    fun linkNoteToProject(projectId:Long,noteId:Long){writableDatabase.insertWithOnConflict("project_note_links",null,ContentValues().apply{put("project_id",projectId);put("note_id",noteId)},SQLiteDatabase.CONFLICT_IGNORE)}
    fun unlinkNoteFromProject(projectId:Long,noteId:Long){writableDatabase.delete("project_note_links","project_id=? AND note_id=?",arrayOf(projectId.toString(),noteId.toString()))}
    fun getLinkedNotes(projectId:Long):List<Note> = readableDatabase.rawQuery("SELECT n.id,n.title,n.body,n.created_at,n.updated_at,n.color_key FROM notes n INNER JOIN project_note_links l ON l.note_id=n.id WHERE l.project_id=? ORDER BY n.updated_at DESC",arrayOf(projectId.toString())).use{c->buildList{while(c.moveToNext())add(Note(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),if(c.isNull(5))"blue" else c.getString(5)))}}
    fun getProjectNoteLinks():List<ProjectNoteLink> = readableDatabase.rawQuery("SELECT project_id,note_id FROM project_note_links",null).use{c->buildList{while(c.moveToNext())add(ProjectNoteLink(c.getLong(0),c.getLong(1)))}}

    fun addMoneyEntry(type:String,amount:Double,category:String,projectId:Long?,note:String,colorKey:String="blue"):Long {
        val safeColor=colorKey.takeIf{it in setOf("blue","yellow","green","orange","pink","purple","red","bright_yellow","cyan")}?:"blue"
        val id=writableDatabase.insertOrThrow("money_entries",null,ContentValues().apply{put("type",type);put("amount",amount);put("category",category);if(projectId==null)putNull("project_id")else put("project_id",projectId);put("note",note);put("color_key",safeColor);put("created_at",System.currentTimeMillis())})
        if(category.isNotBlank()) unhideSuggestion("money_category",category.trim());return id
    }
    fun deleteMoneyEntry(id:Long){ writableDatabase.delete("money_entries","id=?",arrayOf(id.toString())) }
    fun getMoneyEntries():List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,p.name,m.note,m.created_at,m.color_key FROM money_entries m LEFT JOIN projects p ON p.id=m.project_id ORDER BY m.created_at DESC",null).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))"" else c.getString(5),c.getString(6),c.getLong(7),if(c.isNull(8))"blue" else c.getString(8)))}}
    fun getStandaloneMoneyEntries():List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,'' AS project_name,m.note,m.created_at,m.color_key FROM money_entries m WHERE m.project_id IS NULL ORDER BY m.created_at DESC",null).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),null,"",c.getString(6),c.getLong(7),if(c.isNull(8))"blue" else c.getString(8)))}}
    fun getProjectMoneyEntries(projectId:Long):List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,p.name,m.note,m.created_at,m.color_key FROM money_entries m LEFT JOIN projects p ON p.id=m.project_id WHERE m.project_id=? ORDER BY m.created_at DESC",arrayOf(projectId.toString())).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),c.getLong(4),if(c.isNull(5))"" else c.getString(5),c.getString(6),c.getLong(7),if(c.isNull(8))"blue" else c.getString(8)))}}
    fun getMoneyCategorySuggestions(query:String=""):List<String>{
        val q=query.trim();val hidden="AND NOT EXISTS (SELECT 1 FROM hidden_suggestions h WHERE h.kind='money_category' AND h.value=money_entries.category COLLATE NOCASE)"
        val (sql,args)=if(q.isBlank()) "SELECT category, MAX(created_at) AS last_used FROM money_entries WHERE TRIM(category)<>'' $hidden GROUP BY category ORDER BY last_used DESC LIMIT 12" to emptyArray<String>()
        else "SELECT category, MAX(created_at) AS last_used FROM money_entries WHERE TRIM(category)<>'' $hidden AND category LIKE ? GROUP BY category ORDER BY CASE WHEN category LIKE ? THEN 0 ELSE 1 END,last_used DESC LIMIT 12" to arrayOf("%$q%","$q%")
        return readableDatabase.rawQuery(sql,args).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    }
    fun hideSuggestion(kind:String,value:String){val v=value.trim();if(v.isBlank())return;writableDatabase.insertWithOnConflict("hidden_suggestions",null,ContentValues().apply{put("kind",kind);put("value",v);put("hidden_at",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun unhideSuggestion(kind:String,value:String){val v=value.trim();if(v.isBlank())return;writableDatabase.delete("hidden_suggestions","kind=? AND value=? COLLATE NOCASE",arrayOf(kind,v))}

    fun addReminder(targetType:String,targetId:Long,triggerAt:Long,sound:Boolean,vibrate:Boolean,soundKey:String="system",scheduleType:String="once",daysMask:Int=0,dayMode:String="single",baseMinutes:Int=540):Long = writableDatabase.insertOrThrow("reminders",null,ContentValues().apply{put("target_type",targetType);put("target_id",targetId);put("trigger_at",triggerAt);put("sound",if(sound)1 else 0);put("vibrate",if(vibrate)1 else 0);put("sound_key",soundKey);put("created_at",System.currentTimeMillis());put("schedule_type",scheduleType);put("days_mask",daysMask);put("day_mode",dayMode);put("base_minutes",baseMinutes)})
    fun updateReminder(id:Long,triggerAt:Long,scheduleType:String,daysMask:Int,dayMode:String,baseMinutes:Int){ writableDatabase.update("reminders",ContentValues().apply{put("trigger_at",triggerAt);put("schedule_type",scheduleType);put("days_mask",daysMask);put("day_mode",dayMode);put("base_minutes",baseMinutes)},"id=?",arrayOf(id.toString())) }
    fun updateReminderTrigger(id:Long,triggerAt:Long){ writableDatabase.update("reminders",ContentValues().apply{put("trigger_at",triggerAt)},"id=?",arrayOf(id.toString())) }
    fun deleteReminder(id:Long){ writableDatabase.delete("reminders","id=?",arrayOf(id.toString())) }
    fun getReminder(id:Long):ReminderEntry? = getReminders().firstOrNull{it.id==id}
    fun getReminders():List<ReminderEntry> = readableDatabase.rawQuery("SELECT id,target_type,target_id,trigger_at,sound,vibrate,sound_key,created_at,schedule_type,days_mask,day_mode,base_minutes FROM reminders ORDER BY trigger_at ASC",null).use{c->buildList{while(c.moveToNext()){
        val type=c.getString(1);val target=c.getLong(2);val note=if(type=="note")getNote(target)else null;val task=if(type=="task")getTasks().firstOrNull{it.id==target}else null;val project=if(type=="project")getProject(target)else null
        val title=note?.title?:task?.title?:project?.name.orEmpty();val details=note?.body?:task?.details?:project?.description.orEmpty();add(ReminderEntry(c.getLong(0),type,target,c.getLong(3),c.getInt(4)!=0,c.getInt(5)!=0,c.getString(6),c.getLong(7),title,details,c.getString(8),c.getInt(9),c.getString(10),c.getInt(11)))
    }}}

    fun getAllProjectStages():List<ProjectStage> = readableDatabase.rawQuery("SELECT id,project_id,title,done,due_at,created_at FROM project_stages",null).use{c->buildList{while(c.moveToNext())add(ProjectStage(c.getLong(0),c.getLong(1),c.getString(2),c.getInt(3)!=0,c.getLong(4),c.getLong(5)))}}
    fun getAllProjectJournal():List<ProjectJournalEntry> = readableDatabase.rawQuery("SELECT id,project_id,text,created_at FROM project_journal",null).use{c->buildList{while(c.moveToNext())add(ProjectJournalEntry(c.getLong(0),c.getLong(1),c.getString(2),c.getLong(3)))}}

    fun replaceAll(notes:List<Note>,tasks:List<TaskItem>,projects:List<Project>,money:List<MoneyEntry>,reminders:List<ReminderEntry>,stages:List<ProjectStage> = emptyList(),journal:List<ProjectJournalEntry> = emptyList(),links:List<ProjectNoteLink> = emptyList(),beforeCommit:()->Unit = {}){
        // Every insert must succeed before committing; a failed row must roll back all deletions too.
        writableDatabase.beginTransaction(); try {
            listOf("project_stages","project_journal","project_note_links","notes","tasks","projects","money_entries","reminders").forEach{writableDatabase.delete(it,null,null)}
            notes.forEach{n->writableDatabase.insertOrThrow("notes",null,ContentValues().apply{put("id",n.id);put("title",n.title);put("body",n.body);put("created_at",n.createdAt);put("updated_at",n.updatedAt);put("color_key",n.colorKey)})}
            tasks.forEach{t->writableDatabase.insertOrThrow("tasks",null,ContentValues().apply{put("id",t.id);put("title",t.title);put("details",t.details);put("done",if(t.done)1 else 0);put("created_at",t.createdAt);put("updated_at",t.updatedAt)})}
            projects.forEach{p->writableDatabase.insertOrThrow("projects",null,ContentValues().apply{put("id",p.id);put("name",p.name);put("description",p.description);put("status",p.status);put("progress",p.progress);put("start_at",p.startAt);put("due_at",p.dueAt);put("budget",p.budget);put("client",p.client);put("address",p.address);put("contact",p.contact);put("created_at",p.createdAt);put("updated_at",p.updatedAt);put("color_key",p.colorKey)})}
            money.forEach{m->writableDatabase.insertOrThrow("money_entries",null,ContentValues().apply{put("id",m.id);put("type",m.type);put("amount",m.amount);put("category",m.category);if(m.projectId==null)putNull("project_id")else put("project_id",m.projectId);put("note",m.note);put("created_at",m.createdAt);put("color_key",m.colorKey)})}
            reminders.forEach{r->writableDatabase.insertOrThrow("reminders",null,ContentValues().apply{put("id",r.id);put("target_type",r.targetType);put("target_id",r.targetId);put("trigger_at",r.triggerAt);put("sound",if(r.sound)1 else 0);put("vibrate",if(r.vibrate)1 else 0);put("sound_key",r.soundKey);put("created_at",r.createdAt);put("schedule_type",r.scheduleType);put("days_mask",r.daysMask);put("day_mode",r.dayMode);put("base_minutes",r.baseMinutes)})}
            stages.forEach{s->writableDatabase.insertOrThrow("project_stages",null,ContentValues().apply{put("id",s.id);put("project_id",s.projectId);put("title",s.title);put("done",if(s.done)1 else 0);put("due_at",s.dueAt);put("created_at",s.createdAt)})}
            journal.forEach{j->writableDatabase.insertOrThrow("project_journal",null,ContentValues().apply{put("id",j.id);put("project_id",j.projectId);put("text",j.text);put("created_at",j.createdAt)})}
            links.forEach{l->writableDatabase.insertOrThrow("project_note_links",null,ContentValues().apply{put("project_id",l.projectId);put("note_id",l.noteId)})}
            beforeCommit()
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
}
