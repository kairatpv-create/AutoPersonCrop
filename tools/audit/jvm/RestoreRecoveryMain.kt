package kz.kairat.organizer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private fun child(root:File,mode:String,payload:File?=null,phase:String="",entry:String="RecoveryWorker"):Pair<Int,String>{
    val logs=Files.createTempDirectory("organizer-d14-worker-log-").toFile()
    val out=File(logs,"out");val err=File(logs,"err")
    val command=listOf(File(System.getProperty("java.home"),"bin/java").path,"-cp",System.getProperty("java.class.path"),
        "kz.kairat.organizer.$entry",root.path,mode,payload?.path?:"",phase)
    val process=ProcessBuilder(command).redirectOutput(out).redirectError(err).start()
    if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();error("Recovery worker timed out")}
    check(process.exitValue()==0||process.exitValue()==73){err.readText()+out.readText()}
    return process.exitValue() to out.readText()
}
private fun snapshot(root:File)=JSONObject(child(root,"snapshot").also{check(it.first==0)}.second)
private fun seed(mode:String):Pair<D06Fixture,JSONObject>{
    val f=D06Fixture("existing")
    f.settings.setPin("1234");f.settings.setPinProtectionEnabled(true)
    f.settings.syncIdToken="synthetic-id-token";f.settings.syncRefreshToken="synthetic-refresh-token";f.settings.syncUid="synthetic-user"
    f.settings.syncLastFingerprint="synthetic-fingerprint";f.settings.syncLastSuccessAt=12345L;f.settings.lastBackupAt=999L
    f.db.writableDatabase.rawQuery("PRAGMA journal_mode=$mode",null).use{check(it.moveToFirst());check(it.getString(0).equals(mode,true))}
    f.db.close()
    return f to snapshot(f.context.sandbox)
}
private fun assertState(actual:JSONObject,old:JSONObject,incoming:JSONObject,newFiles:JSONObject,committed:Boolean,filesOnly:Boolean=false){
    val expected=if(committed&&!filesOnly)incoming else old.getJSONObject("backup")
    for(section in d06Sections)check(expected.getJSONArray(section).similar(actual.getJSONObject("backup").getJSONArray(section))){"Mismatched collection: $section"}
    val attachments=if(committed)incoming.getJSONArray("attachments") else old.getJSONObject("backup").getJSONArray("attachments")
    check(attachments.similar(actual.getJSONObject("backup").getJSONArray("attachments"))){"Mismatched attachments"}
    check(expected.getJSONObject("settings").similar(actual.getJSONObject("backup").getJSONObject("settings"))){"Mismatched preferences"}
    check((if(committed)newFiles else old.getJSONObject("files")).similar(actual.getJSONObject("files"))){"Attachment/index bytes changed"}
    check(old.getJSONObject("protected").similar(actual.getJSONObject("protected"))){"PIN or account state changed"}
    check(old.getString("sentinel")==actual.getString("sentinel"))
    check(actual.getInt("pending")==0){"Recovery not cleaned up"}
}
fun main(){
    val source=D06Fixture("incoming").apply{settings.language="en";settings.fontScale=.9f;settings.boldText=false;settings.backupEmail="synthetic@example.invalid"}
    val incoming=source.backup()
    val payload=File(source.context.cacheDir,"complete.json").apply{writeText(incoming.toString())}
    // A successful restore defines the expected serialized index, not a mock reconstruction.
    val reference=D06Fixture("reference");reference.activate();BackupManager.restore(payload.readText(),reference.db)
    val newFiles=JSONObject(d06Files(File(reference.context.filesDir,"attachments")).filterKeys{it!=RestoreJournal.GENERATION})
    reference.db.close();source.db.close()
    val beforeCommit=listOf("stage-owner","stage-data","stage-index","intent","old-move","new-move")
    val afterCommit=listOf("after-sql-commit","done","cleanup-partial","cleanup-work","cleanup-journal","cleanup-done")
    for(mode in listOf("DELETE","WAL"))for(phase in beforeCommit+afterCommit){
        d06Test("$mode process halt at $phase; restart selects complete ${if(phase in afterCommit)"new" else "old"} data and a second restart is unchanged"){
            val(f,old)=seed(mode)
            check(child(f.context.sandbox,"restore",payload,phase).first==73)
            val recovered=snapshot(f.context.sandbox)
            assertState(recovered,old,incoming,newFiles,phase in afterCommit)
            check(recovered.similar(snapshot(f.context.sandbox))){"Second restart changed recovered data"}
        }
    }
    for(mode in listOf("DELETE","WAL"))for(phase in listOf("intent","old-move","new-move","done","cleanup-partial")){
        d06Test("$mode direct attachment restore process halt at $phase preserves database/PIN and selects a complete file generation"){
            val(f,old)=seed(mode);check(child(f.context.sandbox,"files",payload,phase).first==73)
            assertState(snapshot(f.context.sandbox),old,incoming,newFiles,phase in listOf("new-move","done","cleanup-partial"),true)
        }
    }
    for(mode in listOf("DELETE","WAL"))for(phase in listOf("new-move","after-sql-commit")){
        d06Test("$mode complete empty backup halted at $phase is distinguished from a missing backup"){
            val empty=JSONObject(incoming.toString()).apply{(d06Sections+"attachments").forEach{put(it,org.json.JSONArray())}}
            val emptyPayload=File(source.context.cacheDir,"empty.json").apply{writeText(empty.toString())}
            val emptyReference=D06Fixture("empty reference").apply{activate();BackupManager.restore(empty.toString(),db)}
            val emptyFiles=JSONObject(d06Files(File(emptyReference.context.filesDir,"attachments")).filterKeys{it!=RestoreJournal.GENERATION})
            emptyReference.db.close()
            val(f,old)=seed(mode);check(child(f.context.sandbox,"restore",emptyPayload,phase).first==73)
            assertState(snapshot(f.context.sandbox),old,empty,emptyFiles,phase=="after-sql-commit")
        }
    }
    for(mode in listOf("DELETE","WAL"))for(phase in listOf("rollback-displace","rollback-restore","recovery-done","recovery-cleanup")){
        d06Test("$mode a second process halt during $phase is idempotently recovered at the next launch"){
            val(f,old)=seed(mode);val committed=phase.startsWith("recovery-")
            check(child(f.context.sandbox,"restore",payload,if(committed)"after-sql-commit" else "new-move").first==73)
            check(child(f.context.sandbox,"recover",phase=phase,entry="RecoveryRestartWorker").first==73)
            assertState(snapshot(f.context.sandbox),old,incoming,newFiles,committed)
        }
    }
    d06Test("A second interrupted restore returns to the previous committed generation, not the original empty state"){
        val(f,_)=seed("DELETE");f.activate();BackupManager.restore(payload.readText(),f.db);f.db.close()
        val old=snapshot(f.context.sandbox)
        val other=JSONObject(incoming.toString()).apply{getJSONArray("notes").getJSONObject(0).put("title","Different second restore")}
        val otherFile=File(source.context.cacheDir,"second.json").apply{writeText(other.toString())}
        check(child(f.context.sandbox,"restore",otherFile,"new-move").first==73)
        assertState(snapshot(f.context.sandbox),old,other,newFiles,false)
    }
    d06Test("Opening a synthetic schema-9 copy without the additive state table preserves records, files and user_version"){
        val(f,old)=seed("DELETE")
        java.sql.DriverManager.getConnection("jdbc:sqlite:"+File(f.context.sandbox,"organizer.db")).use{c->c.createStatement().use{it.execute("DROP TABLE backup_restore_state")}}
        val copied=Files.createTempDirectory("organizer-d14-old-schema-").toFile()
        f.context.sandbox.copyRecursively(copied,overwrite=true)
        assertState(snapshot(copied),old,incoming,newFiles,false)
        java.sql.DriverManager.getConnection("jdbc:sqlite:"+File(copied,"organizer.db")).use{c->c.createStatement().use{s->
            s.executeQuery("PRAGMA user_version").use{check(it.next()&&it.getInt(1)==9)}
            s.executeQuery("SELECT name FROM sqlite_master WHERE name='backup_restore_state'").use{check(it.next())}
        }}
    }
    for(fault in listOf("journal-json","journal-work","owner","old-generation","new-index","missing-new-file","unknown-previous","work-symlink")){
        d06Test("Unsafe recovery metadata $fault blocks opening and retains every recovery file"){
            val(f,_)=seed("DELETE")
            val committed=fault in listOf("new-index","missing-new-file")
            check(child(f.context.sandbox,"restore",payload,if(committed)"after-sql-commit" else "new-move").first==73)
            val journal=File(f.context.filesDir,RestoreJournal.JOURNAL)
            val work=f.context.filesDir.listFiles()!!.single{it.name.startsWith("attachments-restore-")}
            when(fault){
                "journal-json"->journal.writeText("{")
                "journal-work"->{val json=JSONObject(journal.readText()).put("work","../outside");journal.writeText(json.toString())}
                "owner"->File(work,RestoreJournal.OWNER).writeText("{}")
                "old-generation"->File(work,"previous/${RestoreJournal.GENERATION}").writeText("damaged")
                "new-index"->File(f.context.filesDir,"attachments/index.json").writeText("[{}]")
                "missing-new-file"->File(f.context.filesDir,"attachments/data").listFiles()!!.first().delete()
                "unknown-previous"->journal.delete()
                "work-symlink"->{val aside=File(f.context.filesDir,"saved-recovery");check(work.renameTo(aside));Files.createSymbolicLink(work.toPath(),aside.toPath())}
            }
            val before=d06Files(f.context.filesDir)
            check(child(f.context.sandbox,"blocked").second.contains("BLOCKED"))
            check(before==d06Files(f.context.filesDir)){"Blocked recovery changed files"}
        }
    }
    d06Test("Durable completion retries cleanup without replaying preferences changed after a successful restore"){
        val(f,_)=seed("DELETE");f.activate()
        val io=object:AttachmentRestoreFiles(){override fun remove(directory:File){throw IOException("Synthetic cleanup failure")}}
        BackupManager.restore(payload.readText(),f.db,"en",io)
        f.settings.language="ru";f.settings.backupEmail="later@example.invalid";f.settings.fontScale=1.4f
        f.db.close()
        val result=snapshot(f.context.sandbox);val settings=result.getJSONObject("backup").getJSONObject("settings")
        check(settings.getString("language")=="ru"&&settings.getString("email")=="later@example.invalid"&&settings.getDouble("fontScale")>1.39)
        check(result.getInt("pending")==0)
    }
    d06Test("An interrupted first owner-marker write preserves unused staging and does not block intact data"){
        val(f,old)=seed("DELETE")
        val unused=File(f.context.filesDir,"attachments-restore-incomplete").apply{mkdir()}
        File(unused,RestoreJournal.OWNER).writeText("{")
        val actual=snapshot(f.context.sandbox)
        check(actual.getInt("pending")==1&&unused.exists())
        actual.put("pending",0);assertState(actual,old,incoming,newFiles,false)
    }
    d06Test("Failed preferences commit after SQLite commit remains recoverable without changing PIN/account fields"){
        val(f,old)=seed("DELETE");f.activate()
        System.setProperty("organizer.audit.failPreferencesCommit","true")
        val failure=try{runCatching{BackupManager.restore(payload.readText(),f.db)}.exceptionOrNull()}finally{System.clearProperty("organizer.audit.failPreferencesCommit")}
        check(failure is RestoreJournal.RecoveryRequired);f.db.close()
        assertState(snapshot(f.context.sandbox),old,incoming,newFiles,true)
    }
    d06Test("Preferences too large for a durable journal are rejected before replacing records or attachments"){
        val(f,old)=seed("DELETE");f.activate()
        val oversized=JSONObject(incoming.toString()).apply{getJSONObject("settings").put("email","x".repeat(1024*1024))}
        val failure=runCatching{BackupManager.restore(oversized.toString(),f.db)}.exceptionOrNull()
        check(failure is IOException&&failure.message.orEmpty().contains("has not been changed"));f.db.close()
        assertState(snapshot(f.context.sandbox),old,incoming,newFiles,false)
    }
    for(mode in listOf("DELETE","WAL"))for(committed in listOf(false,true)){
        d06Test("$mode uncertain COMMIT ${if(committed)"acknowledgement loss" else "deferred constraint failure"} is resolved from SQLite on a fresh open"){
            val(f,_)=seed(mode);f.activate()
            if(!committed){
                val db=f.db.writableDatabase
                db.execSQL("PRAGMA foreign_keys=ON")
                db.execSQL("CREATE TABLE audit_parent(id INTEGER PRIMARY KEY)")
                db.execSQL("CREATE TABLE audit_deferred(parent_id INTEGER REFERENCES audit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
                db.execSQL("CREATE TRIGGER audit_commit_error AFTER INSERT ON notes BEGIN INSERT INTO audit_deferred VALUES(17); END")
            }
            f.db.close();val old=snapshot(f.context.sandbox);f.activate()
            if(!committed)f.db.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
            if(committed)System.setProperty("organizer.audit.afterCommit","throw")
            val failure=try{runCatching{BackupManager.restore(payload.readText(),f.db)}.exceptionOrNull()}finally{System.clearProperty("organizer.audit.afterCommit")}
            check(failure is RestoreJournal.RecoveryRequired){"COMMIT uncertainty was misreported: $failure"}
            assertState(snapshot(f.context.sandbox),old,incoming,newFiles,committed)
        }
    }
    for(phase in listOf("intent-write","after-old-move","after-new-move","done-write")){
        d06Test("I/O failure $phase retains a recoverable old/new pair and never reports an uncertain restore as unchanged"){
            val(f,old)=seed("DELETE");f.activate()
            val io=object:AttachmentRestoreFiles(){
                private var failSync=false
                private var injected=false
                override fun move(from:File,to:File){super.move(from,to);if(!injected&&(phase=="after-old-move"&&to.name=="previous"||phase=="after-new-move"&&to.name=="attachments"))failSync=true}
                override fun syncDirectory(directory:File){if(failSync){failSync=false;injected=true;throw IOException("Synthetic directory sync failure")};super.syncDirectory(directory)}
                override fun write(file:File,bytes:ByteArray){if(phase=="intent-write"&&file.name==RestoreJournal.JOURNAL+".tmp"||phase=="done-write"&&file.name==RestoreJournal.DONE+".tmp")throw IOException("Synthetic metadata write failure");super.write(file,bytes)}
            }
            val failure=runCatching{BackupManager.restore(payload.readText(),f.db,"en",io)}.exceptionOrNull()
            check(failure is IOException)
            if(phase=="done-write")check(failure is RestoreJournal.RecoveryRequired) else check(failure.message.orEmpty().contains("has not been changed"))
            f.db.close();assertState(snapshot(f.context.sandbox),old,incoming,newFiles,phase=="done-write")
        }
    }
    d06Finish("D14 journal recovery: isolated JVM halt/restart, real synthetic SQLite/files")
}

/** Exercise a second hard kill inside the real recovery implementation, before NoteDatabase.onOpen. */
internal object RecoveryRestartWorker{
    @JvmStatic fun main(args:Array<String>){
        val context=Context(File(args[0]));val phase=args[3]
        Class.forName("org.sqlite.JDBC")
        val db=android.database.sqlite.SQLiteDatabase(java.sql.DriverManager.getConnection("jdbc:sqlite:"+File(context.sandbox,"organizer.db")))
        val io=object:AttachmentRestoreFiles(){
            override fun move(from:File,to:File){
                super.move(from,to)
                if(phase=="rollback-displace"&&from.name=="attachments" || phase=="rollback-restore"&&from.name=="previous" || phase=="recovery-done"&&to.name==RestoreJournal.DONE)Runtime.getRuntime().halt(73)
            }
            override fun remove(directory:File){
                if(phase=="recovery-cleanup"&&directory.name.startsWith("attachments-restore-")){
                    File(directory,"previous/data").listFiles().orEmpty().firstOrNull()?.delete()
                    Runtime.getRuntime().halt(73)
                }
                super.remove(directory)
            }
        }
        RestoreJournal.recover(context,db,io)
        db.close();error("Recovery halt phase was not reached: $phase")
    }
}
