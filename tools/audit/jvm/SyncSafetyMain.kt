package kz.kairat.organizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun token(id:Int=1,email:String="a@example.invalid")="eyJhbGciOiJIUzI1NiJ9."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("sub",id.toString()).put("email",email).toString().toByteArray())+".synthetic"
private class SyncFixture{
    val c=Context(Files.createTempDirectory("organizer-sync-synthetic-").toFile())
    var db=NoteDatabase(c);val s=SettingsStore(c)
    init{AttachmentStore.initialize(c);s.language="en";s.syncEmail="a@example.invalid";s.syncIdToken=token();s.setPin("2580")}
    fun data()=JSONObject(BackupManager.createBackup(db,s)).apply{remove("createdAt")}.toString()
    fun cloud()=SyncSnapshot.parse(BackupManager.createCloudBackup(db,s),"en")
    fun sync()=CloudSync.syncOnce(db,s,"en",c)
    fun attach(id:Long){AttachmentStore.addLocalFile("note",id,File(c.cacheDir,"synthetic.txt").apply{writeText("private bytes")})}
}
private class Remote{
    var payload:String?=null;var rev=0L;var puts=0;var gets=0;var conflicts=0;var fail=false
    var onGet:(()->Unit)?=null;var onPut:(()->Unit)?=null
    var deletionSupported=true;var deleted=false;var deletionStatus=200;var deletionRequests=0
    init{CloudSync.testTransport={path,method,body,t->
        if(fail)throw java.io.IOException("Synthetic offline")
        val claims=if(!t.isNullOrBlank())JSONObject(String(Base64.getUrlDecoder().decode(t.split('.')[1]))) else JSONObject()
        when{
            path=="/api/account"->CloudSync.HttpResult(if(deleted)401 else 200,JSONObject().put("email",claims.getString("email")).put("accountDeletion",deletionSupported))
            path=="/api/account/delete"->{deletionRequests++;if(deletionStatus==200){deleted=true;payload=null;rev=0};CloudSync.HttpResult(deletionStatus,JSONObject().put("deleted",deleted))}
            path.startsWith("/api/auth/")->CloudSync.HttpResult(200,JSONObject().put("token",token()).put("email","a@example.invalid"))
            method=="GET"->{gets++;onGet?.invoke();CloudSync.HttpResult(200,JSONObject().put("exists",payload!=null).put("rev",rev).put("payload",payload?:JSONObject.NULL))}
            else->{
                puts++;onPut?.invoke();val j=JSONObject(body!!)
                if(conflicts>0||j.getLong("baseRev")!=rev){conflicts--;CloudSync.HttpResult(409,JSONObject().put("rev",rev).put("payload",payload))}
                else{payload=j.getString("payload");rev++;CloudSync.HttpResult(200,JSONObject().put("ok",true).put("rev",rev))}
            }
        }
    }}
}
private fun mutate(payload:String,section:String,index:Int=0,change:(JSONObject)->Unit):String{
    val root=JSONObject(payload);change(root.getJSONArray(section).getJSONObject(index));return SyncSnapshot.parse(root.toString(),"en")
}
private fun fails(f:SyncFixture,remote:Remote,body:()->Unit={f.sync()}){
    val data=f.data();val files=d06Files(f.c.filesDir);val cloud=remote.payload
    check(runCatching(body).isFailure){"Unsafe input accepted"}
    check(f.data()==data){"Local records or exported settings changed"};check(d06Files(f.c.filesDir)==files){"Local files changed"}
    check(remote.payload==cloud){"Cloud snapshot changed"};check(f.s.verifyPin("2580"))
}
fun main(){
    d06Test("Initial upload uses v7; ownership and acknowledgement are durable"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"initial","body");f.sync()
        check(r.puts==1&&r.rev==1L);check(r.payload==f.cloud());check(SyncStateStore(f.c,"en").read()!!.payload==f.cloud())
        check(!JSONObject(r.payload!!).has("attachments"));check(!JSONObject(r.payload!!).has("settings"))
    }
    d06Test("An empty device downloads an existing valid snapshot"){
        val r=Remote();val source=SyncFixture();source.db.save(null,"remote","body");r.payload=source.cloud();r.rev=4
        val f=SyncFixture();f.sync();check(f.db.getNotes().single().title=="remote");check(r.puts==0)
        check(File(f.c.noBackupFilesDir,RestoreSafety.DIRECTORY).listFiles()!!.any{it.name.startsWith("before-restore-")})
    }
    d06Test("A complete empty cloud snapshot can receive local data on first sync"){
        val r=Remote();val f=SyncFixture();r.payload=f.cloud();r.rev=1;f.db.save(null,"local","keep");f.sync();check(r.payload==f.cloud())
    }
    for(due in listOf(0L,4102444800000L,Long.MAX_VALUE))d06Test("D02 first divergent sync preserves both snapshots regardless of dueAt=$due"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"local","keep")
        r.payload=mutate(f.cloud(),"notes"){it.put("title","other");it.put("updatedAt",1L)}
        val root=JSONObject(r.payload!!);root.getJSONArray("projects").put(JSONObject().put("id",10).put("name","future").put("description","").put("createdAt",1L).put("dueAt",due))
        r.payload=SyncSnapshot.parse(root.toString(),"en");r.rev=1;fails(f,r);check(r.puts==0)
    }
    d06Test("An edit on only the local device uploads against the acknowledged revision"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.sync();f.db.save(id,"local edit","body");f.sync();check(r.payload==f.cloud()&&r.rev==2L)
    }
    d06Test("An edit on only the remote device downloads without losing local attachments"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.attach(id);f.sync();val bytes=d06Files(File(f.c.filesDir,"attachments"))
        r.payload=mutate(r.payload!!,"notes"){it.put("body","remote edit")};r.rev++;f.sync();check(f.db.getNote(id)!!.body=="remote edit")
        check(d06Files(File(f.c.filesDir,"attachments"))==bytes)
    }
    for(section in SyncSnapshot.sections.filter{it!="projectNoteLinks"})d06Test("Disjoint local and remote edits merge in $section without date ordering"){
        val r=Remote();val fixture=D06Fixture("sync");val fdb=fixture.db;val s=fixture.settings;s.syncEmail="a@example.invalid";s.syncIdToken=token()
        CloudSync.syncOnce(fdb,s,"en",fixture.context)
        val base=JSONObject(r.payload!!);val rows=base.getJSONArray(section);val original=JSONObject(rows.getJSONObject(0).toString())
        val field=when(section){"notes","tasks","projectStages"->"title";"projects"->"name";"projectJournal"->"text";"money"->"note";else->"soundKey"}
        original.put("id",9876L).put(field,"remote addition");rows.put(original);r.payload=SyncSnapshot.parse(base.toString(),"en");r.rev++
        // Local note edit is disjoint from the remote added identity, including notes.
        val note=fdb.getNotes().first();fdb.save(note.id,"local edit","body")
        CloudSync.syncOnce(fdb,s,"en",fixture.context)
        val merged=JSONObject(r.payload!!).getJSONArray(section);check((0 until merged.length()).any{merged.getJSONObject(it).optLong("id")==9876L})
        check(fdb.getNotes().any{it.title=="local edit"})
    }
    for(section in SyncSnapshot.sections.filter{it!="projectNoteLinks"})d06Test("Concurrent edits to the same $section row are rejected, preserving both sides"){
        val r=Remote();val fixture=D06Fixture("conflict");val s=fixture.settings;s.syncEmail="a@example.invalid";s.syncIdToken=token();CloudSync.syncOnce(fixture.db,s,"en",fixture.context)
        val original=r.payload!!;val field=when(section){"notes","tasks","projectStages"->"title";"projects"->"name";"projectJournal"->"text";"money"->"note";else->"soundKey"}
        val l=mutate(original,section){it.put(field,"left")};val rr=mutate(original,section){it.put(field,"right")}
        check(runCatching{SyncSnapshot.merge(original,l,rr,"en")}.exceptionOrNull() is SyncSnapshot.Conflict)
        check(runCatching{SyncSnapshot.merge(original,rr,l,"en")}.isFailure)
    }
    d06Test("Same-row edits through sync refuse all DB and attachment changes"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.attach(id);f.sync()
        r.payload=mutate(r.payload!!,"notes"){it.put("body","remote")};r.rev++;f.db.save(id,"local","body");fails(f,r)
    }
    d06Test("A concurrently created row with the same numeric ID is a conflict, not a timestamp winner"){
        val r=Remote();val f=SyncFixture();f.sync();val base=r.payload!!;f.db.save(null,"left","body")
        r.payload=mutate(f.cloud(),"notes"){it.put("title","right")};r.rev++;fails(f,r);check(SyncSnapshot.empty(base))
    }
    d06Test("A confirmed deletion propagates when the other device did not edit that record"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"delete","body");f.sync();f.db.delete(id);f.sync();check(SyncSnapshot.empty(r.payload!!))
    }
    d06Test("Delete versus edit refuses to discard either change"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.sync();r.payload=mutate(r.payload!!,"notes"){it.put("body","edit")};r.rev++;f.db.delete(id);fails(f,r)
    }
    d06Test("Project deletion versus a newly linked financial record is a conflict"){
        val r=Remote();val f=SyncFixture();val id=f.db.addProject("project","body");f.sync();val remote=JSONObject(r.payload!!)
        remote.getJSONArray("money").put(JSONObject().put("id",1).put("type","expense").put("amount",19.95).put("category","").put("projectId",id).put("projectName","project").put("note","remote").put("createdAt",1))
        r.payload=SyncSnapshot.parse(remote.toString(),"en");r.rev++;f.db.deleteProject(id);fails(f,r)
    }
    d06Test("409 retries merge a new cloud revision without discarding pending local changes"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.sync();f.db.save(id,"pending","body")
        var first=true;r.onPut={if(first){first=false;val root=JSONObject(r.payload!!);root.getJSONArray("tasks").put(JSONObject().put("id",1).put("title","remote task").put("details","").put("done",false).put("createdAt",1).put("updatedAt",1));r.payload=SyncSnapshot.parse(root.toString(),"en");r.rev++}}
        f.sync();check(f.db.getNotes().single().title=="pending");check(f.db.getTasks().single().title=="remote task");check(r.puts==3)
    }
    d06Test("Repeated 409s fail without a remote overwrite of local records"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.sync();f.db.save(id,"pending","body");r.conflicts=9;fails(f,r)
    }
    d06Test("A local edit arriving during GET is not overwritten by a stale download"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.attach(id);f.sync();r.payload=mutate(r.payload!!,"notes"){it.put("body","remote")};r.rev++
        val files=d06Files(f.c.filesDir);r.onGet={f.db.save(id,"new during network","body")}
        check(runCatching{f.sync()}.isFailure);check(f.db.getNotes().single().title=="new during network");check(files==d06Files(f.c.filesDir))
    }
    d06Test("A local edit during upload remains pending for the next sync"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.sync();f.db.save(id,"first","body")
        r.onPut={f.db.save(id,"during PUT","body")};f.sync();check(f.db.getNote(id)!!.title=="during PUT");r.onPut=null;f.sync();check(r.payload==f.cloud())
    }
    d06Test("Two sync callers use one coordinator, with no duplicate revision or lost update"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"once","body");val errors=java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads=List(2){Thread{try{f.sync()}catch(e:Throwable){errors.add(e)}}.apply{start()}};threads.forEach{it.join(20000);check(!it.isAlive)}
        check(errors.isEmpty()){errors.toString()};check(r.puts==1)
    }
    d06Test("Logout and reopening preserve ownership and the baseline for the same account"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.sync();val owner=SyncStateStore(f.c,"en").read()!!
        CloudSync.logout(f.s);f.db.close();f.db=NoteDatabase(f.c);f.db.save(id,"offline edit","body");CloudSync.auth(f.s,"A@example.invalid","synthetic",false);f.sync()
        check(SyncStateStore(f.c,"en").read()!!.owner==owner.owner);check(r.payload==f.cloud())
    }
    d06Test("D03 changing account after logout never sends the original account's data"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"A private","body");f.attach(1);f.sync();CloudSync.logout(f.s)
        fails(f,r){CloudSync.auth(f.s,"b@example.invalid","synthetic",false)};check(f.s.syncIdToken.isBlank())
    }
    d06Test("D03 an old release's retained email blocks switching accounts even before an owner file exists"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"legacy A","private");CloudSync.logout(f.s)
        fails(f,r){CloudSync.auth(f.s,"b@example.invalid","synthetic",false)};check(r.puts==0)
    }
    d06Test("D03 substituted credentials cannot bypass durable ownership"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"A private","body");f.sync();f.s.syncIdToken=token(2,"b@example.invalid");f.s.syncEmail="b@example.invalid";fails(f,r)
    }
    d06Test("D03 a newly created account with the old email has a different identity"){
        val r=Remote();val f=SyncFixture();f.sync();f.s.syncIdToken=token(2);fails(f,r)
    }
    d06Test("Offline editing, reopening and reconnecting upload the pending record"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"base","body");f.sync();r.fail=true;f.db.save(null,"offline","pending");fails(f,r);f.db.close();f.db=NoteDatabase(f.c);r.fail=false;f.sync();check(r.payload==f.cloud());check(f.db.getNotes().size==2)
    }
    for(bad in listOf("{not-json","{\"notes\":[]}","[]"))d06Test("Malformed or incomplete remote snapshot is rejected: $bad"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"keep","body");f.attach(1);f.sync();r.payload=bad;r.rev++;fails(f,r)
    }
    d06Test("A remote snapshot cannot import preferences or attachments via sync"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"keep","body");f.sync();r.payload=BackupManager.createBackup(f.db,f.s);r.rev++;fails(f,r)
    }
    d06Test("Cloud record generation changes cannot misattach a local file"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"keep","body");f.attach(id);f.sync();r.payload=mutate(r.payload!!,"notes"){it.put("createdAt",1L)};r.rev++;fails(f,r)
    }
    d06Test("Remote deletion of a row with local-only files is stopped without deleting those files"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"keep","body");f.attach(id);f.sync();r.payload=JSONObject(r.payload!!).put("notes",JSONArray()).toString();r.rev++;fails(f,r)
    }
    d06Test("Cloud export does not read attachment bytes (a missing private file is not transmitted)"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"keep","body");f.attach(id);File(f.c.filesDir,"attachments/data").listFiles()!!.forEach{it.delete()};f.sync();check(r.payload==f.cloud())
    }
    d06Test("Semantic comparison ignores field order, array order and numeric representation"){
        Remote();val f=SyncFixture();f.db.save(null,"one","body");f.db.save(null,"two","body");val base=f.cloud();val root=JSONObject(base)
        val a=root.getJSONArray("notes");root.put("notes",JSONArray().put(a.getJSONObject(1)).put(a.getJSONObject(0)));root.put("createdAt",999)
        check(SyncSnapshot.parse(root.toString(2),"en")==base)
    }
    d06Test("A corrupt acknowledgement fails closed rather than treating the device as fresh"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"keep","body");f.sync();File(f.c.noBackupFilesDir,"sync-state/state.json").writeText("{broken");fails(f,r)
    }
    d06Test("Fractional project budgets survive real SQL cloud download and acknowledgement"){
        val r=Remote();val f=SyncFixture();f.db.saveProject(null,"project","body","active",0,1L,2L,987.65,"","","", "blue");f.sync()
        r.payload=mutate(r.payload!!,"projects"){it.put("description","remote")};r.rev++;f.sync();check(f.db.getProjects().single().budget==987.65);check(r.payload==f.cloud())
    }
    d06Test("Account deletion requires the matching email before any delete request"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"keep local","body");f.attach(1);f.sync();fails(f,r){CloudSync.deleteAccount(f.s,"synthetic-password","other@example.invalid")};check(r.deletionRequests==0)
    }
    d06Test("An old server without deletion capability preserves session and all local data"){
        val r=Remote();val f=SyncFixture();f.sync();r.deletionSupported=false;fails(f,r){CloudSync.deleteAccount(f.s,"synthetic-password",f.s.syncEmail)};check(r.deletionRequests==0&&f.s.syncIdToken.isNotBlank())
    }
    d06Test("A failed deletion response preserves local data, attachments, owner and session"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"keep local","body");f.attach(1);f.sync();val owner=SyncStateStore(f.c,"en").read();r.deletionStatus=401
        fails(f,r){CloudSync.deleteAccount(f.s,"wrong-password",f.s.syncEmail)};check(f.s.syncIdToken.isNotBlank());check(SyncStateStore(f.c,"en").read()==owner)
    }
    d06Test("Successful synthetic server deletion keeps all local notes, finance, attachments, PIN and ownership"){
        val r=Remote();val f=SyncFixture();f.db.save(null,"keep local","body");f.attach(1);f.db.addMoneyEntry("expense",9.99,"synthetic",null,"keep");f.sync()
        val before=f.data();val files=d06Files(f.c.filesDir);val owner=SyncStateStore(f.c,"en").read()
        CloudSync.deleteAccount(f.s,"synthetic-password",f.s.syncEmail)
        check(r.deleted&&r.payload==null);check(f.s.syncIdToken.isBlank());check(f.data()==before);check(d06Files(f.c.filesDir)==files);check(f.s.verifyPin("2580"));check(SyncStateStore(f.c,"en").read()==owner)
    }
    d06Test("Both conflict snapshots reopen through a fresh Context and SettingsStore with verified hashes"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.sync();f.db.save(id,"left","local");r.payload=mutate(r.payload!!,"notes"){it.put("title","right")};r.rev++;fails(f,r)
        check(CloudSync.exportConflict(f.s,false)==f.cloud());check(CloudSync.exportConflict(f.s,true)==r.payload)
        val fresh=SettingsStore(Context(f.c.sandbox));check(CloudSync.exportConflict(fresh,true)==r.payload)
        val before=File(f.c.noBackupFilesDir,"sync-conflicts").listFiles()!!.size;fails(f,r);check(File(f.c.noBackupFilesDir,"sync-conflicts").listFiles()!!.size==before)
    }
    d06Test("A changed saved conflict snapshot is rejected by its checksum"){
        val r=Remote();val f=SyncFixture();val id=f.db.save(null,"base","body");f.sync();f.db.save(id,"left","body");r.payload=mutate(r.payload!!,"notes"){it.put("title","right")};r.rev++;fails(f,r)
        val root=File(f.c.noBackupFilesDir,"sync-conflicts");val directory=File(root,File(root,"latest").readText());File(directory,"remote.json").writeText(f.cloud());check(runCatching{CloudSync.exportConflict(f.s,true)}.isFailure)
    }
    CloudSync.testTransport=null
    d06Finish("sync-safety")
}
