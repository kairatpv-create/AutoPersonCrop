package kz.kairat.organizer

import android.content.Context
import android.net.Uri
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64

private val results=JSONArray()
private fun checkCase(name:String,risk:Boolean=false,body:()->Unit){
    try{body();results.put(JSONObject().put("name",name).put("result",if(risk)"RISK_REPRODUCED"else"PASS"))}
    catch(e:Throwable){results.put(JSONObject().put("name",name).put("result","HARNESS_FAILURE").put("error",e.toString()));e.printStackTrace()}
}
private fun context()=Context(Files.createTempDirectory("organizer-synthetic-").toFile()).also{AttachmentStore.initialize(it)}
private fun backup(note:String="",updated:Long=1L,due:Long=0L):String{
    val r=JSONObject().put("format","my-organizer-backup").put("version",7).put("createdAt",0).put("cloudSchema","organizer-sync-v3")
    listOf("notes","tasks","projects","projectStages","projectJournal","projectNoteLinks","money","reminders").forEach{r.put(it,JSONArray())}
    if(note.isNotBlank())r.getJSONArray("notes").put(JSONObject().put("id",1).put("title",note).put("body",note).put("createdAt",updated).put("updatedAt",updated))
    if(due>0)r.getJSONArray("projects").put(JSONObject().put("id",1).put("name","Remote future project").put("createdAt",1).put("updatedAt",1).put("dueAt",due))
    return r.toString()
}
private class Remote:AutoCloseable {
    var payload=backup("server",20)
    var rev=1L
    var conflict=false
    var uploaded:String?=null
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    init{
        server.createContext("/api/sync"){x->
            var status=200
            val response=if(x.requestMethod=="GET")JSONObject().put("exists",true).put("rev",rev).put("payload",payload)
            else if(conflict){status=409;JSONObject().put("error","SYNC_CONFLICT").put("payload",payload).put("rev",rev)}
            else {val j=JSONObject(x.requestBody.bufferedReader().readText());uploaded=j.getString("payload");payload=uploaded!!;rev++;JSONObject().put("ok",true).put("rev",rev)}
            val bytes=response.toString().toByteArray();x.responseHeaders.set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.size.toLong());x.responseBody.use{it.write(bytes)}
        }
        server.start();System.setProperty("organizer.audit.server","http://127.0.0.1:${server.address.port}")
    }
    override fun close(){server.stop(0)}
}
fun main(){
    checkCase("Fresh SQLite schema and note/finance CRUD"){
        val c=context();val db=NoteDatabase(c)
        val id=db.save(null,"Синтетическая заметка","English + Русский")
        db.addMoneyEntry("income",1234.56,"Тест",null,"Synthetic only")
        check(db.getNote(id)?.body=="English + Русский");check(db.getMoneyEntries().single().amount==1234.56)
        db.readableDatabase.rawQuery("PRAGMA user_version",null).use{check(it.moveToFirst());check(it.getInt(0)==9)}
    }
    checkCase("JSON and ZIP backup roundtrip preserves finance, project links, preferences and attachments"){
        val c=context();val db=NoteDatabase(c);val s=SettingsStore(c)
        s.darkTheme=true;s.fontScale=1.3f;s.buttonFontScale=1.15f;s.boldText=true;s.language="ru";s.syncIdToken="synthetic-secret"
        val n=db.save(null,"RU/EN","content");val p=db.addProject("Synthetic project","description")
        db.addProjectStage(p,"Stage",1);db.addProjectJournal(p,"Journal");db.linkNoteToProject(p,n)
        db.addMoneyEntry("expense",12.5,"Category",p,"Comment")
        val f=File(c.cacheDir,"sample.txt").apply{writeText("synthetic attachment")}
        AttachmentStore.addLocalFile("note",n,f)
        val payload=BackupManager.createBackup(db,s);check(!payload.contains("synthetic-secret"))
        val zip=Uri(File(c.cacheDir,"backup.zip").path);BackupManager.writeArchiveToUri(c,zip,payload)
        check(BackupManager.readFromUri(c,zip)==payload)
        val target=context();val out=NoteDatabase(target)
        val restored=BackupManager.restore(payload,out,"ru")
        check(out.getNotes().single().title=="RU/EN");check(out.getMoneyEntries().single().amount==12.5)
        check(out.getAllProjectStages().size==1&&out.getAllProjectJournal().size==1&&out.getProjectNoteLinks().size==1)
        check(restored.darkTheme==true&&restored.boldText==true&&restored.language=="ru")
        check(AttachmentStore.file(AttachmentStore.list("note",n).single()).readText()=="synthetic attachment")
    }
    checkCase("PIN verification and RU/EN labels"){
        val s=SettingsStore(context());s.setPin("1234");check(s.verifyPin("1234"));check(!s.verifyPin("1235"))
        check(AppStrings.t("en","button_font")=="Button text size")
        check(AppStrings.t("ru","notes")=="Заметки")
        check(AppStrings.supportedLanguages.size==8)
    }
    checkCase("Incomplete notes-only JSON deletes unrelated finances",true){
        val db=NoteDatabase(context());db.addMoneyEntry("income",99.0,"Keep",null,"Keep me")
        BackupManager.restore("{\"notes\":[]}",db)
        check(db.getMoneyEntries().isEmpty())
    }
    checkCase("Duplicate restore IDs silently commit a partial replacement",true){
        val db=NoteDatabase(context());db.save(null,"Original","Keep")
        val r=JSONObject(backup("first",1));r.getJSONArray("notes").put(JSONObject(r.getJSONArray("notes").getJSONObject(0).toString()).put("title","second"))
        BackupManager.restore(r.toString(),db)
        check(db.getNotes().size==1);check(db.getNotes().single().title=="first")
    }
    checkCase("Invalid attachment data removes old files without reporting failure",true){
        val c=context();val f=File(c.cacheDir,"old.txt").apply{writeText("keep")}
        val old=AttachmentStore.addLocalFile("note",1,f);val oldPath=AttachmentStore.file(old)
        val a=JSONArray().put(JSONObject().put("id",1).put("targetType","note").put("targetId",1).put("storedName","bad.bin").put("data","!invalid-base64!"))
        AttachmentStore.restoreBackup(a)
        check(!oldPath.exists());check(!AttachmentStore.file(AttachmentStore.list("note",1).single()).exists())
    }
    checkCase("Attachment restore accepts a path outside its data directory",true){
        val c=context();val a=JSONArray().put(JSONObject().put("id",1).put("targetType","note").put("targetId",1).put("storedName","../audit-escaped.txt").put("data",Base64.getEncoder().encodeToString("synthetic".toByteArray())))
        AttachmentStore.restoreBackup(a)
        check(File(c.filesDir,"attachments/audit-escaped.txt").readText()=="synthetic")
    }
    checkCase("First sync selects old remote snapshot because of future project due date",true){
        Remote().use{remote->
            val c=context();val db=NoteDatabase(c);val s=SettingsStore(c);s.syncIdToken="synthetic"
            db.save(null,"Unsynced local note","Keep me")
            remote.payload=backup("Old remote",1,System.currentTimeMillis()+365L*86400000L)
            val r=CloudSync.syncOnce(db,s,"en",c)
            check(r.remoteApplied);check(db.getNotes().none{it.title=="Unsynced local note"})
        }
    }
    checkCase("Two dirty devices: newer local snapshot uploads without remote-only records",true){
        Remote().use{remote->
            val c=context();val db=NoteDatabase(c);val s=SettingsStore(c);s.syncIdToken="synthetic";s.syncLastFingerprint="old-snapshot";s.syncLastCloudUpdatedAt=1
            db.save(null,"New local","keep");remote.rev=2;remote.payload=backup("Remote-only",1)
            CloudSync.syncOnce(db,s,"en",c)
            check(remote.uploaded!=null&&!remote.uploaded!!.contains("Remote-only"))
        }
    }
    checkCase("HTTP 409 replaces pending local records with remote snapshot",true){
        Remote().use{remote->
            val c=context();val db=NoteDatabase(c);val s=SettingsStore(c);s.syncIdToken="synthetic";s.syncLastFingerprint="old";s.syncLastCloudUpdatedAt=1
            db.save(null,"Pending financial note","keep");remote.payload=backup("Remote",1);remote.conflict=true
            val r=CloudSync.syncOnce(db,s,"en",c)
            check(r.remoteApplied);check(db.getNotes().none{it.title=="Pending financial note"})
        }
    }
    checkCase("Logging into another account can upload the previous account's local records",true){
        Remote().use{remote->
            val c=context();val db=NoteDatabase(c);val s=SettingsStore(c)
            db.save(null,"Account A private note","keep")
            s.syncIdToken="account-b-synthetic-token";s.syncLastFingerprint="";remote.payload=backup("Account B",1)
            CloudSync.syncOnce(db,s,"en",c)
            check(remote.uploaded?.contains("Account A private note")==true)
        }
    }
    checkCase("SQL transaction rolls back an explicit exception"){
        val db=NoteDatabase(context());db.save(null,"Original","keep")
        db.writableDatabase.beginTransaction()
        try{db.writableDatabase.delete("notes",null,null)}finally{db.writableDatabase.endTransaction()}
        check(db.getNotes().single().title=="Original")
    }
    println(results.toString(2))
    if((0 until results.length()).any{results.getJSONObject(it).getString("result")=="HARNESS_FAILURE"})error("Host audit has failures")
}
