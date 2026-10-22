package kz.kairat.organizer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun snapshots(f:D06Fixture)=File(f.context.noBackupFilesDir,"restore-safety").listFiles().orEmpty().filter{it.name.startsWith("before-restore-")}
private fun zip(vararg entries:Pair<String,ByteArray>,comment:String=""):ByteArray {
    val out=ByteArrayOutputStream()
    ZipOutputStream(out).use{z->z.setComment(comment);for((name,bytes)in entries){z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}}
    return out.toByteArray()
}
private fun central(bytes:ByteArray):Int=(0 until bytes.size-3).first{i->bytes[i]==0x50.toByte()&&bytes[i+1]==0x4b.toByte()&&bytes[i+2]==1.toByte()&&bytes[i+3]==2.toByte()}
private fun little(bytes:ByteArray,offset:Int,value:Long,count:Int=4){repeat(count){bytes[offset+it]=(value ushr (it*8)).toByte()}}
private fun rejected(label:String,bytes:ByteArray,p:BackupImportLimits.Policy=BackupImportLimits.Policy(),lang:String="en")=d06Test(label){
    val f=D06Fixture("protected import");val before=d06Snapshot(f)
    val failure=runCatching{BackupManager.restore(BackupImportReader.read(bytes.inputStream(),f.context.cacheDir,lang,p),f.db,lang)}.exceptionOrNull()
    check(failure!=null&&failure.message.orEmpty().contains(if(lang=="ru")"Данные не изменены" else "data has not been changed")){"Missing clear refusal: $failure"}
    d06Unchanged(before,f);check(snapshots(f).isEmpty())
    check(f.context.cacheDir.listFiles().orEmpty().none{it.name.startsWith("organizer-import-")})
}
private fun cloneSnapshot(snapshot:File):Pair<Context,NoteDatabase>{
    RestoreSafety.verify(snapshot)
    val context=Context(Files.createTempDirectory("organizer-d14-safety-restore-").toFile())
    File(snapshot,"organizer.db").copyTo(File(context.sandbox,"organizer.db"))
    val files=File(snapshot,"attachments")
    if(files.exists())files.copyRecursively(File(context.filesDir,"attachments"))
    SettingsStore(context).applyRestoredPreferences(BackupManager.preferences(JSONObject(File(snapshot,"settings.json").readText())))
    AttachmentStore.initialize(context)
    return context to NoteDatabase(context)
}
private fun checkSaved(snapshot:File,old:JSONObject,oldFiles:Map<String,String>){
    val(context,db)=cloneSnapshot(snapshot)
    val actual=JSONObject(BackupManager.createBackup(db,SettingsStore(context)))
    (d06Sections+"attachments"+"settings").forEach{key->check(old.get(key).toString()==actual.get(key).toString()||when(val expected=old.get(key)){
        is JSONObject->expected.similar(actual.get(key));is JSONArray->expected.similar(actual.get(key));else->expected==actual.get(key)
    }){"Safety snapshot lost $key"}}
    check(oldFiles==d06Files(File(context.filesDir,"attachments"))){"Indexed/unindexed attachment bytes changed"}
    db.close()
}
private fun process(root:File,mode:String,payload:File?=null,phase:String=""):Pair<Int,String>{
    val logs=Files.createTempDirectory("organizer-d14-safety-process-").toFile()
    val output=File(logs,"out");val error=File(logs,"err")
    val command=mutableListOf(File(System.getProperty("java.home"),"bin/java").path)
    if(mode=="bounded")command.add("-Xmx64m")
    command.addAll(listOf("-cp",System.getProperty("java.class.path"),"kz.kairat.organizer.ImportSafetyWorker",root.path,mode,payload?.path?:"",phase))
    val p=ProcessBuilder(command).redirectOutput(output).redirectError(error).start()
    if(!p.waitFor(20,TimeUnit.SECONDS)){p.destroyForcibly();error("Safety process timed out")}
    check(p.exitValue() in listOf(0,73)){error.readText()+output.readText()}
    return p.exitValue() to output.readText()
}
private fun concurrentSafety(text:String){
    for(mode in listOf("DELETE","WAL"))d06Test("$mode cached concurrent writer waits between safety publication and restore commit, then its new note survives"){
        val target=D06Fixture("concurrent source");target.activate()
        target.db.writableDatabase.rawQuery("PRAGMA journal_mode=$mode",null).use{check(it.moveToFirst())}
        val other=NoteDatabase(target.context);other.writableDatabase
        val paused=CountDownLatch(1);val release=CountDownLatch(1);val writing=CountDownLatch(1);val written=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val io=object:AttachmentRestoreFiles(){override fun move(from:File,to:File){super.move(from,to);if(to.name.startsWith("before-restore-")){paused.countDown();check(release.await(5,TimeUnit.SECONDS))}}}
        val restore=Thread{try{BackupManager.restore(text,target.db,"en",io)}catch(e:Throwable){failure.set(e)}}
        val writer=Thread{writing.countDown();try{other.save(null,"Concurrent note after restore","Must survive")}catch(e:Throwable){failure.set(e)}finally{written.countDown()}}
        restore.start();check(paused.await(5,TimeUnit.SECONDS))
        var blocked=false
        try{writer.start();check(writing.await(5,TimeUnit.SECONDS));blocked=!written.await(150,TimeUnit.MILLISECONDS)}finally{release.countDown()}
        restore.join(5000);writer.join(5000)
        check(!restore.isAlive&&!writer.isAlive&&failure.get()==null){failure.get().toString()}
        check(blocked){"A write slipped between the safety snapshot and destructive replacement"}
        check(target.db.getNotes().any{it.title=="Concurrent note after restore"}){"Concurrent data was lost"}
        other.close()
    }
}
fun main(){
    val source=D06Fixture("incoming limit data");val incoming=source.backup()
    val text=incoming.toString();val bytes=text.toByteArray(Charsets.UTF_8)
    if(System.getProperty("organizer.audit.onlyConcurrency")=="true"){concurrentSafety(text);d06Finish("D14 concurrent pre-image guard");return}
    val p=BackupImportLimits.Policy(jsonBytes=bytes.size)
    for(lang in listOf("ru","en"))rejected("$lang raw JSON byte limit refuses limit+1 without touching data",bytes+byteArrayOf(32),p,lang)
    d06Test("Exact UTF-8 byte boundary accepts Russian text, emoji and quoted brackets"){
        val input=JSONObject(text).apply{getJSONArray("notes").getJSONObject(0).put("body","Русский 😀 "+"[{\\\"'".repeat(100))}.toString()
        val encoded=input.toByteArray(Charsets.UTF_8)
        val read=BackupImportReader.read(encoded.inputStream(),source.context.cacheDir,"en",p.copy(jsonBytes=encoded.size))
        check(read==input)
        val target=D06Fixture("Unicode restore");target.activate();BackupManager.restore(read,target.db)
        d06Restored(JSONObject(input),target)
    }
    d06Test("Unknown-length infinite provider is stopped after at most limit+1 bytes"){
        val f=D06Fixture("infinite provider");val before=d06Snapshot(f);var read=0
        val input=object:InputStream(){override fun read():Int{read++;return 65};override fun available()=0}
        check(runCatching{BackupImportReader.read(input,f.context.cacheDir,"en",p.copy(jsonBytes=1024))}.isFailure)
        check(read==1025);d06Unchanged(before,f);check(snapshots(f).isEmpty())
    }
    d06Test("Short/zero reads and available=0 do not truncate a valid backup"){
        var index=0;var zero=true
        val input=object:InputStream(){
            override fun read()=if(index<bytes.size)bytes[index++].toInt() and 255 else -1
            override fun available()=0
            override fun read(b:ByteArray,off:Int,len:Int):Int{zero=!zero;if(zero)return 0;if(index==bytes.size)return -1
                val n=minOf(len,3,bytes.size-index);bytes.copyInto(b,off,index,index+n);index+=n;return n}
        }
        check(BackupImportReader.read(input,source.context.cacheDir,"en",p)==text)
    }
    rejected("Invalid UTF-8 is refused without replacement characters",byteArrayOf(0xc3.toByte(),0x28))
    rejected("Directory-only ZIP is refused",zip("folder/" to byteArrayOf()))
    rejected("Empty ZIP is refused",zip())
    val archive=zip("folder/" to byteArrayOf(),"folder/backup.json" to bytes,comment="Русский ZIP comment")
    d06Test("Single-document ZIP with a directory and UTF-8 comment restores normally"){
        val target=D06Fixture("ZIP success")
        val read=BackupImportReader.read(archive.inputStream(),target.context.cacheDir,"en",p.copy(archiveBytes=archive.size.toLong()))
        target.activate();BackupManager.restore(read,target.db);d06Restored(incoming,target)
        check(snapshots(target).size==1)
    }
    rejected("Compressed input limit is enforced before opening ZIP",archive,p.copy(archiveBytes=archive.size-1L))
    rejected("ZIP expanded size limit is enforced",archive,p.copy(jsonBytes=bytes.size-1))
    rejected("Ambiguous ZIP with two documents is refused",zip("one.json" to bytes,"two.json" to bytes))
    rejected("ZIP entry count is bounded before ZipFile builds its index",zip(*((1..33).map{"d$it/" to byteArrayOf()}+listOf("backup.json" to bytes)).toTypedArray()))
    val compressed=zip("backup.json" to bytes)
    rejected("Truncated ZIP central directory is refused",compressed.dropLast(8).toByteArray())
    rejected("Wrong central CRC is refused",compressed.clone().also{it[central(it)+16]=(it[central(it)+16].toInt() xor 1).toByte()})
    rejected("Forged small expanded size still cannot bypass the streaming bound",compressed.clone().also{little(it,central(it)+24,1)},p.copy(jsonBytes=128))
    rejected("Directory payload contributes to the expanded-size budget",zip("dir/" to ByteArray(2048){65},"backup.json" to bytes),p.copy(jsonBytes=1024))
    rejected("Forged central entry count cannot hide extra entries",archive.clone().also{little(it,it.size-22-"Русский ZIP comment".toByteArray().size+8,1,2);little(it,it.size-22-"Русский ZIP comment".toByteArray().size+10,1,2)})
    for((name,input)in listOf("deep nesting" to (text.dropLast(1)+",\"extra\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}"),
        "excessive value count" to (text.dropLast(1)+",\"extra\":["+"0,".repeat(1_000_010)+"0]}"))){
        d06Test("Direct String restore rejects $name before allocating its JSON tree"){
            val target=D06Fixture("direct string limit");val before=d06Snapshot(target)
            val failure=runCatching{BackupManager.restore(input,target.db)}.exceptionOrNull()
            check(failure is IOException&&failure.message.orEmpty().contains("too large or complex"));d06Unchanged(before,target);check(snapshots(target).isEmpty())
        }
    }
    d06Test("Direct attachment restore checks the allocation budget before decoding Base64"){
        val target=D06Fixture("direct attachment limit");val before=d06Snapshot(target)
        val a=JSONArray(incoming.getJSONArray("attachments").toString());a.getJSONObject(0).put("size",25L*1024*1024+1)
        check(runCatching{AttachmentStore.restoreBackup(a)}.exceptionOrNull() is IOException);d06Unchanged(before,target)
        val exact=BackupImportLimits.Policy(attachmentBytes=4,decodedBytes=6)
        val b=JSONArray().put(JSONObject().put("data","AAAAAA==").put("size",4))
        BackupImportLimits.checkAttachments(b,"en",exact)
        b.put(JSONObject().put("data","AAAA").put("size",3))
        check(runCatching{BackupImportLimits.checkAttachments(b,"en",exact)}.exceptionOrNull() is IOException)
    }
    for(mode in listOf("DELETE","WAL"))d06Test("$mode saved SQLite snapshot preserves fractional budgets, all SQL types, hidden rows, schema and sequence counters"){
        val target=D06Fixture("native safety source");target.activate()
        val db=target.db.writableDatabase
        db.rawQuery("PRAGMA journal_mode=$mode",null).use{check(it.moveToFirst())}
        target.settings.setPin("1234");target.settings.setPinProtectionEnabled(true);target.settings.syncIdToken="synthetic-private-token"
        target.db.hideSuggestion("money_category","keep hidden")
        db.execSQL("CREATE TABLE audit_extra(id INTEGER PRIMARY KEY, amount REAL, optional TEXT, payload BLOB, big INTEGER, \"spaced name\" TEXT)")
        db.execSQL("INSERT INTO audit_extra VALUES(1,?,?,?,?,?)",arrayOf(987.65,null,byteArrayOf(0,-1,42),Long.MAX_VALUE,"verbatim"))
        db.execSQL("CREATE INDEX audit_index ON audit_extra(amount)")
        db.execSQL("CREATE VIEW audit_view AS SELECT amount FROM audit_extra")
        db.execSQL("CREATE TRIGGER audit_trigger AFTER INSERT ON audit_extra BEGIN UPDATE audit_extra SET optional='triggered' WHERE id=NEW.id; END")
        db.execSQL("INSERT INTO notes(id,title,body,created_at,updated_at)VALUES(50000,'temporary','',1,1)")
        db.execSQL("DELETE FROM notes WHERE id=50000")
        target.db.save(null,"committed before import","Must be in the safety copy")
        if(mode=="WAL")check(File(target.context.sandbox,"organizer.db-wal").length()>0)
        val old=target.backup();val oldFiles=d06Files(File(target.context.filesDir,"attachments"))
        BackupManager.restore(text,target.db)
        val saved=snapshots(target).single();RestoreSafety.verify(saved)
        checkSaved(saved,old,oldFiles)
        val(context,restored)=cloneSnapshot(saved)
        val raw=restored.writableDatabase
        raw.rawQuery("SELECT budget FROM projects LIMIT 1",null).use{check(it.moveToFirst()&&it.getDouble(0)==987.65)}
        raw.rawQuery("SELECT amount,optional,payload,big,\"spaced name\" FROM audit_extra",null).use{c->check(c.moveToFirst());check(c.getDouble(0)==987.65&&c.isNull(1)&&c.getBlob(2).contentEquals(byteArrayOf(0,-1,42))&&c.getLong(3)==Long.MAX_VALUE&&c.getString(4)=="verbatim")}
        raw.rawQuery("SELECT value FROM hidden_suggestions WHERE value='keep hidden'",null).use{check(it.moveToFirst())}
        check(restored.save(null,"after safety restore","")==50002L)
        raw.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('audit_index','audit_view','audit_trigger')",null).use{check(it.moveToFirst()&&it.getInt(0)==3)}
        restored.close()
        check(target.settings.verifyPin("1234")&&target.settings.isPinProtectionEnabled()&&target.settings.syncIdToken=="synthetic-private-token")
        check(!File(saved,"settings.json").readText().contains("synthetic-private-token"))
        target.activate();d06Restored(incoming,target)
    }
    d06Test("Failure creating the safety root cancels restore before any live write"){
        val target=D06Fixture("root refusal");val before=d06Snapshot(target)
        val io=object:AttachmentRestoreFiles(){override fun makeDirectory(directory:File){if(directory.name=="restore-safety")throw IOException("Injected root failure");super.makeDirectory(directory)}}
        val failure=runCatching{BackupManager.restore(text,target.db,"en",io)}.exceptionOrNull()
        check(failure is IOException&&failure.message.orEmpty().contains("Could not create and verify"));d06Unchanged(before,target);check(snapshots(target).isEmpty())
    }
    val phases=listOf("stage","database-sync","settings","copy-1","copy-2","partial-copy","corrupt-copy","manifest","manifest-partial","directory-sync","publish")
    for(phase in phases)d06Test("Safety failure $phase cancels import and preserves both live data and a previous verified snapshot"){
        val target=D06Fixture("safety refusal");target.activate()
        val first=RestoreSafety.capture(target.context,target.db.writableDatabase,"en",AttachmentRestoreFiles())
        val firstHashes=d06Files(first);val before=d06Snapshot(target)
        val io=object:AttachmentRestoreFiles(){
            var copied=0
            override fun makeDirectory(directory:File){
                if(phase=="stage"&&directory.name.startsWith(".pending-"))throw IOException("Injected preparation failure")
                super.makeDirectory(directory)
            }
            override fun syncFile(file:File){if(phase=="database-sync")throw IOException("Injected database sync failure");super.syncFile(file)}
            override fun copyFile(source:File,destination:File){
                copied++
                if(phase=="copy-$copied")throw IOException("Injected copy failure")
                if(phase=="partial-copy"){destination.writeBytes(byteArrayOf(1));throw IOException("Injected partial copy failure")}
                super.copyFile(source,destination)
                if(phase=="corrupt-copy")destination.appendBytes(byteArrayOf(42))
            }
            override fun write(file:File,data:ByteArray){
                if(phase=="settings"&&file.name=="settings.json"||phase in listOf("manifest","manifest-partial")&&file.name=="snapshot.json"){
                    if(phase=="manifest-partial")file.writeBytes(data.take(10).toByteArray())
                    throw IOException("Injected metadata failure")
                }
                super.write(file,data)
            }
            override fun syncDirectory(directory:File){if(phase=="directory-sync"&&directory.path.contains(".pending-"))throw IOException("Injected directory sync failure");super.syncDirectory(directory)}
            override fun move(from:File,to:File){if(phase=="publish"&&to.name.startsWith("before-restore-"))throw IOException("Injected publish failure");super.move(from,to)}
        }
        val failure=runCatching{BackupManager.restore(text,target.db,"en",io)}.exceptionOrNull()
        check(failure is IOException&&failure.message.orEmpty().contains("Could not create and verify")){failure.toString()}
        d06Unchanged(before,target);check(firstHashes==d06Files(first));RestoreSafety.verify(first)
        check(snapshots(target).size==1)
    }
    d06Test("Successive full imports retain earlier verified snapshots"){
        val target=D06Fixture("retained history");val old=target.backup();val oldFiles=d06Files(File(target.context.filesDir,"attachments"))
        BackupManager.restore(text,target.db);val first=snapshots(target).single();val hashes=d06Files(first)
        BackupManager.restore(text,target.db)
        check(snapshots(target).size==2&&hashes==d06Files(first));checkSaved(first,old,oldFiles)
    }
    d06Test("SQL failure after safety publication keeps the independent safety copy and all original records"){
        val target=D06Fixture("SQL refusal");val before=d06Snapshot(target)
        target.db.writableDatabase.execSQL("CREATE TEMP TRIGGER audit_fail BEFORE INSERT ON money_entries BEGIN SELECT RAISE(ABORT,'synthetic failure');END")
        check(runCatching{BackupManager.restore(text,target.db)}.isFailure);d06Unchanged(before,target)
        val saved=snapshots(target).single();checkSaved(saved,before.data,d06Files(File(target.context.filesDir,"attachments")))
    }
    d06Test("Cloud v7 without local sections creates no safety snapshot and retains local attachments"){
        val target=D06Fixture("cloud source");val oldFiles=d06Files(File(target.context.filesDir,"attachments"))
        val cloud=JSONObject(text).put("version",7).apply{remove("settings");remove("attachments")}
        BackupManager.restore(cloud.toString(),target.db)
        check(snapshots(target).isEmpty()&&oldFiles==d06Files(File(target.context.filesDir,"attachments")))
    }
    d06Test("A complete empty pre-import database produces a usable empty safety snapshot"){
        val context=Context(Files.createTempDirectory("organizer-d14-empty-source-").toFile())
        AttachmentStore.initialize(context);val db=NoteDatabase(context);val settings=SettingsStore(context)
        val old=JSONObject(BackupManager.createBackup(db,settings));val files=d06Files(File(context.filesDir,"attachments"))
        BackupManager.restore(text,db)
        val saved=File(context.noBackupFilesDir,"restore-safety").listFiles()!!.single{it.name.startsWith("before-restore-")}
        checkSaved(saved,old,files);db.close()
    }
    d06Test("A directory sync failure after snapshot publication keeps the verified snapshot and cancels import"){
        val target=D06Fixture("published sync refusal");val before=d06Snapshot(target)
        val io=object:AttachmentRestoreFiles(){
            var published=false
            override fun move(from:File,to:File){super.move(from,to);if(to.name.startsWith("before-restore-"))published=true}
            override fun syncDirectory(directory:File){if(published&&directory.name=="restore-safety")throw IOException("Injected sync after publication");super.syncDirectory(directory)}
        }
        check(runCatching{BackupManager.restore(text,target.db,"en",io)}.exceptionOrNull() is IOException)
        d06Unchanged(before,target);RestoreSafety.verify(snapshots(target).single())
    }
    val large=JSONObject(text).apply{getJSONArray("notes").getJSONObject(0).put("body","x".repeat(12*1024*1024))}.toString().toByteArray()
    for(archived in listOf(false,true))d06Test("64 MiB JVM rejects a large ${if(archived)"compressed ZIP" else "raw JSON"} through the public URI path without changing live data"){
        val target=D06Fixture("low heap guard");val before=d06Snapshot(target)
        val file=File(target.context.cacheDir,if(archived)"large.zip" else "large.json").apply{writeBytes(if(archived)zip("backup.json" to large) else large)}
        target.db.close()
        val result=process(target.context.sandbox,"bounded",file)
        check(result.first==0&&result.second.contains("REFUSED"));d06Unchanged(before,target);check(snapshots(target).isEmpty())
    }
    val payload=File(source.context.cacheDir,"process-import.json").apply{writeText(text)}
    concurrentSafety(text)
    source.db.close()
    for(mode in listOf("DELETE","WAL"))for(phase in listOf("sql","copy","manifest","published"))d06Test("$mode process death during safety $phase leaves live data intact and never treats a partial safety copy as complete"){
        val target=D06Fixture("crash before import")
        target.db.writableDatabase.rawQuery("PRAGMA journal_mode=$mode",null).use{check(it.moveToFirst())};target.db.close()
        val before=JSONObject(process(target.context.sandbox,"snapshot").second)
        check(process(target.context.sandbox,"restore",payload,phase).first==73)
        val after=JSONObject(process(target.context.sandbox,"snapshot").second)
        check(before.similar(after)){"Live state changed before safety verification"}
        if(phase=="published"){check(snapshots(target).size==1);RestoreSafety.verify(snapshots(target).single())}
        else check(snapshots(target).isEmpty())
    }
    d06Finish("D14 bounded input and independent verified safety snapshots (synthetic host)")
}

internal object ImportSafetyWorker {
    @JvmStatic fun main(args:Array<String>){
        if(args[1]=="snapshot"){RecoveryWorker.main(arrayOf(args[0],"snapshot"));return}
        val context=Context(File(args[0]));AttachmentStore.initialize(context);val db=NoteDatabase(context);val phase=args[3]
        if(args[1]=="bounded"){
            val failure=runCatching{BackupManager.restore(BackupManager.readFromUri(context,android.net.Uri(args[2])),db)}.exceptionOrNull()
            check(failure is IOException&&(failure.message.orEmpty().contains("Данные не изменены")||failure.message.orEmpty().contains("data has not been changed"))){"Missing resource refusal: $failure"}
            println("REFUSED");return
        }
        if(phase=="sql")System.setProperty("organizer.audit.haltSafetySql","true")
        val io=object:AttachmentRestoreFiles(){
            override fun copyFile(source:File,destination:File){super.copyFile(source,destination);if(phase=="copy")Runtime.getRuntime().halt(73)}
            override fun write(file:File,bytes:ByteArray){super.write(file,bytes);if(phase=="manifest"&&file.name=="snapshot.json")Runtime.getRuntime().halt(73)}
            override fun move(from:File,to:File){super.move(from,to);if(phase=="published"&&to.name.startsWith("before-restore-"))Runtime.getRuntime().halt(73)}
        }
        BackupManager.restore(File(args[2]).readText(),db,"en",io);error("Safety crash phase was not reached")
    }
}
