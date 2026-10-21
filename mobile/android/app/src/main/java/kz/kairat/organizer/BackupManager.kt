package kz.kairat.organizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {
    fun createBackup(db:NoteDatabase,settings:SettingsStore):String{
        val root=JSONObject(); root.put("format","my-organizer-backup"); root.put("version",6); root.put("createdAt",System.currentTimeMillis())
        root.put("notes",JSONArray().apply{db.getNotes().forEach{n->put(JSONObject().apply{put("id",n.id);put("title",n.title);put("body",n.body);put("createdAt",n.createdAt);put("updatedAt",n.updatedAt);put("colorKey",n.colorKey)})}})
        root.put("tasks",JSONArray().apply{db.getTasks().forEach{t->put(JSONObject().apply{put("id",t.id);put("title",t.title);put("details",t.details);put("done",t.done);put("createdAt",t.createdAt);put("updatedAt",t.updatedAt)})}})
        root.put("projects",JSONArray().apply{db.getProjects().forEach{p->put(JSONObject().apply{
            put("id",p.id);put("name",p.name);put("description",p.description);put("status",p.status);put("progress",p.progress);put("startAt",p.startAt);put("dueAt",p.dueAt);put("budget",p.budget);put("client",p.client);put("address",p.address);put("contact",p.contact);put("createdAt",p.createdAt);put("updatedAt",p.updatedAt);put("colorKey",p.colorKey)
        })}})
        root.put("projectStages",JSONArray().apply{db.getAllProjectStages().forEach{s->put(JSONObject().apply{put("id",s.id);put("projectId",s.projectId);put("title",s.title);put("done",s.done);put("dueAt",s.dueAt);put("createdAt",s.createdAt)})}})
        root.put("projectJournal",JSONArray().apply{db.getAllProjectJournal().forEach{j->put(JSONObject().apply{put("id",j.id);put("projectId",j.projectId);put("text",j.text);put("createdAt",j.createdAt)})}})
        root.put("projectNoteLinks",JSONArray().apply{db.getProjectNoteLinks().forEach{l->put(JSONObject().apply{put("projectId",l.projectId);put("noteId",l.noteId)})}})
        root.put("money",JSONArray().apply{db.getMoneyEntries().forEach{m->put(JSONObject().apply{put("id",m.id);put("type",m.type);put("amount",m.amount);put("category",m.category);if(m.projectId!=null)put("projectId",m.projectId);put("projectName",m.projectName);put("note",m.note);put("createdAt",m.createdAt);put("colorKey",m.colorKey)})}})
        root.put("reminders",JSONArray().apply{db.getReminders().forEach{r->put(JSONObject().apply{put("id",r.id);put("targetType",r.targetType);put("targetId",r.targetId);put("triggerAt",r.triggerAt);put("sound",r.sound);put("vibrate",r.vibrate);put("soundKey",r.soundKey);put("createdAt",r.createdAt);put("scheduleType",r.scheduleType);put("daysMask",r.daysMask);put("dayMode",r.dayMode);put("baseMinutes",r.baseMinutes)})}})
        root.put("settings",JSONObject().apply{put("darkTheme",settings.darkTheme);put("fontScale",settings.fontScale);put("contentFontScale",settings.fontScale);put("buttonFontScale",settings.buttonFontScale);put("boldText",settings.boldText);put("projectsEnabled",settings.projectsEnabled);put("backupMode",settings.backupMode);put("email",settings.backupEmail);put("language",settings.language)})
        root.put("attachments",AttachmentStore.exportBackup())
        return root.toString(2)
    }
    fun restore(json:String,db:NoteDatabase,lang:String="en"):RestoredPreferences{
        return restore(json,db,lang,AttachmentRestoreFiles())
    }
    internal fun restore(json:String,db:NoteDatabase,lang:String,files:AttachmentRestoreFiles):RestoredPreferences{
        val root=BackupValidation.parse(json,lang)
        fun JSONArray?.objs()=sequence{val a=this@objs?:return@sequence;for(i in 0 until a.length())yield(a.getJSONObject(i))}
        val notes=root.optJSONArray("notes").objs().map{o->Note(o.optLong("id"),o.optString("title"),o.optString("body"),o.optLong("createdAt"),o.optLong("updatedAt"),o.optString("colorKey","blue"))}.toList()
        val tasks=root.optJSONArray("tasks").objs().map{o->TaskItem(o.optLong("id"),o.optString("title"),o.optString("details"),o.optBoolean("done"),o.optLong("createdAt"),o.optLong("updatedAt"))}.toList()
        val projects=root.optJSONArray("projects").objs().map{o->
            val created=o.optLong("createdAt")
            Project(o.optLong("id"),o.optString("name"),o.optString("description"),o.optString("status","planned"),o.optInt("progress",0),o.optLong("startAt",created),o.optLong("dueAt",0),o.optDouble("budget",0.0),o.optString("client"),o.optString("address"),o.optString("contact"),created,o.optLong("updatedAt",created),o.optString("colorKey","blue"))
        }.toList()
        val stages=root.optJSONArray("projectStages").objs().map{o->ProjectStage(o.optLong("id"),o.optLong("projectId"),o.optString("title"),o.optBoolean("done"),o.optLong("dueAt"),o.optLong("createdAt"))}.toList()
        val journal=root.optJSONArray("projectJournal").objs().map{o->ProjectJournalEntry(o.optLong("id"),o.optLong("projectId"),o.optString("text"),o.optLong("createdAt"))}.toList()
        val links=root.optJSONArray("projectNoteLinks").objs().map{o->ProjectNoteLink(o.optLong("projectId"),o.optLong("noteId"))}.toList()
        val money=root.optJSONArray("money").objs().map{o->MoneyEntry(o.optLong("id"),o.optString("type"),o.optDouble("amount"),o.optString("category"),if(o.has("projectId"))o.optLong("projectId")else null,o.optString("projectName"),o.optString("note"),o.optLong("createdAt"),o.optString("colorKey","blue"))}.toList()
        val reminders=root.optJSONArray("reminders").objs().map{o->ReminderEntry(o.optLong("id"),o.optString("targetType"),o.optLong("targetId"),o.optLong("triggerAt"),o.optBoolean("sound",true),o.optBoolean("vibrate",true),o.optString("soundKey","system"),o.optLong("createdAt"),"","",o.optString("scheduleType","once"),o.optInt("daysMask",0),o.optString("dayMode","single"),o.optInt("baseMinutes",540))}.toList()
        val preferences=preferences(root.optJSONObject("settings")?:JSONObject())
        val attachments=root.optJSONArray("attachments")
        if(attachments==null)db.replaceAll(notes,tasks,projects,money,reminders,stages,journal,links)
        else AttachmentStore.withDatabaseBackup(attachments,lang,files,db,root.optJSONObject("settings")){install,token->
            db.replaceAll(notes,tasks,projects,money,reminders,stages,journal,links,beforeCommit=install,restoreToken=token)
        }
        return preferences
    }
    internal fun preferences(s:JSONObject):RestoredPreferences{
        val restoredContentScale=when{
            s.has("contentFontScale")->s.optDouble("contentFontScale").toFloat()
            s.has("fontScale")->s.optDouble("fontScale").toFloat()
            else->null
        }
        val restoredButtonScale=when{
            s.has("buttonFontScale")->s.optDouble("buttonFontScale").toFloat()
            s.has("fontScale")->s.optDouble("fontScale").toFloat()
            else->null
        }
        return RestoredPreferences(if(s.has("darkTheme"))s.optBoolean("darkTheme")else null,restoredContentScale,s.optString("backupMode","manual"),s.optString("email",""),if(s.has("boldText"))s.optBoolean("boldText")else null,s.optString("language",null),if(s.has("projectsEnabled"))s.optBoolean("projectsEnabled")else null,restoredButtonScale)
    }
    fun writeToUri(context:Context,uri:Uri,json:String){context.contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use{it.write(json)}}
    fun writeArchiveToUri(context:Context,uri:Uri,json:String){
        context.contentResolver.openOutputStream(uri,"w")!!.use{raw->
            ZipOutputStream(raw.buffered()).use{zip->
                zip.putNextEntry(ZipEntry(suggestedFileName(false)))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
    fun readFromUri(context:Context,uri:Uri):String{
        val bytes=context.contentResolver.openInputStream(uri)!!.use{it.readBytes()}
        val zipped=bytes.size>=4&&bytes[0]==0x50.toByte()&&bytes[1]==0x4b.toByte()
        if(!zipped)return bytes.toString(Charsets.UTF_8)
        ZipInputStream(bytes.inputStream()).use{zip->
            var entry=zip.nextEntry
            while(entry!=null){
                if(!entry.isDirectory)return zip.readBytes().toString(Charsets.UTF_8)
                entry=zip.nextEntry
            }
        }
        throw IllegalArgumentException("Empty backup archive")
    }
    fun shareBackup(context:Context,json:String,email:String="",lang:String="en"){
        val dir=File(context.cacheDir,"backups").apply{mkdirs()}; val f=File(dir,suggestedFileName()).apply{writeText(json)}; val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",f)
        val send=Intent(Intent.ACTION_SEND).apply{type="application/json";putExtra(Intent.EXTRA_STREAM,uri);putExtra(Intent.EXTRA_SUBJECT,AppStrings.t(lang,"backup_subject"));if(email.isNotBlank())putExtra(Intent.EXTRA_EMAIL,arrayOf(email));addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        context.startActivity(Intent.createChooser(send,AppStrings.t(lang,"backup_share_title")))
    }
    fun suggestedFileName(archived:Boolean=false):String{
        val ext=if(archived)"zip" else "json"
        return "my_organizer_backup_${SimpleDateFormat("yyyyMMdd_HHmm",Locale.US).format(Date())}.$ext"
    }
}
