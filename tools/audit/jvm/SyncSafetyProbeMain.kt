package kz.kairat.organizer

import android.content.Context
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64

private fun probeCloud(title:String,updated:Long,due:Long=0):String{
    val r=JSONObject().put("format","my-organizer-backup").put("version",7).put("createdAt",0).put("cloudSchema","organizer-sync-v3")
    listOf("notes","tasks","projects","projectStages","projectJournal","projectNoteLinks","money","reminders").forEach{r.put(it,JSONArray())}
    r.getJSONArray("notes").put(JSONObject().put("id",1).put("title",title).put("body",title).put("createdAt",1L).put("updatedAt",updated))
    if(due>0)r.getJSONArray("projects").put(JSONObject().put("id",1).put("name","future project").put("description","").put("createdAt",1L).put("updatedAt",1L).put("dueAt",due))
    return r.toString()
}
private class ProbeRemote:AutoCloseable{
    var payload=probeCloud("remote private",1,4102444800000L);var rev=1L;var puts=0;var conflicts=false
    val token="header."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("sub","1").put("email","a@example.invalid").toString().toByteArray())+".synthetic"
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    init{server.createContext("/"){x->
        var code=200
        val j=when(x.requestURI.path){
            "/api/account"->JSONObject().put("email","a@example.invalid")
            "/api/auth/login"->JSONObject().put("email","b@example.invalid").put("token",token)
            else->if(x.requestMethod=="GET")JSONObject().put("exists",true).put("rev",rev).put("payload",payload)
                else{puts++;if(conflicts){code=409;JSONObject().put("rev",rev).put("payload",payload)}else{payload=JSONObject(x.requestBody.bufferedReader().readText()).getString("payload");rev++;JSONObject().put("ok",true).put("rev",rev)}}
        }
        val bytes=j.toString().toByteArray();x.sendResponseHeaders(code,bytes.size.toLong());x.responseBody.use{it.write(bytes)}
    };server.start();System.setProperty("organizer.audit.server","http://127.0.0.1:${server.address.port}")}
    override fun close(){server.stop(0)}
}
fun main(){
    d06Test("D01/D02 first sync must preserve divergent data despite a future project due date"){
        ProbeRemote().use{r->val c=Context(Files.createTempDirectory("sync-probe-").toFile());AttachmentStore.initialize(c);val db=NoteDatabase(c);val s=SettingsStore(c);s.syncEmail="a@example.invalid";s.syncIdToken=r.token;db.save(null,"local private","keep")
            val original=r.payload;check(runCatching{CloudSync.syncOnce(db,s,"en",c)}.isFailure);check(db.getNotes().single().title=="local private");check(r.payload==original&&r.puts==0)}
    }
    d06Test("D01 409 must never replace pending local edits"){
        ProbeRemote().use{r->val c=Context(Files.createTempDirectory("sync-probe-").toFile());AttachmentStore.initialize(c);val db=NoteDatabase(c);val s=SettingsStore(c);s.syncEmail="a@example.invalid";s.syncIdToken=r.token
            BackupManager.restore(r.payload,db,"en");CloudSync.syncOnce(db,s,"en",c);val id=db.getNotes().single().id;db.save(id,"pending local","keep");r.conflicts=true
            check(runCatching{CloudSync.syncOnce(db,s,"en",c)}.isFailure);check(db.getNote(id)!!.title=="pending local")}
    }
    d06Test("D03 legacy logout must not permit signing into a different owner"){
        ProbeRemote().use{r->val c=Context(Files.createTempDirectory("sync-probe-").toFile());AttachmentStore.initialize(c);val s=SettingsStore(c);s.syncEmail="a@example.invalid";s.syncIdToken=r.token;CloudSync.logout(s)
            check(runCatching{CloudSync.auth(s,"b@example.invalid","synthetic-password",false)}.isFailure);check(s.syncIdToken.isBlank())}
    }
    d06Finish("sync-safety-probe")
}
