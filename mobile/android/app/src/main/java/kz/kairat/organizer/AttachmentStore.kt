package kz.kairat.organizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

object AttachmentStore {
    private const val MAX_FILE_SIZE = 25L * 1024L * 1024L
    private lateinit var app: Context
    private val rootDir get():File { recoverBeforeAccess(); return File(app.filesDir, "attachments").apply{mkdirs()} }
    private val dataDir get() = File(rootDir,"data").apply{mkdirs()}
    private val indexFile get() = File(rootDir,"index.json")
    @Synchronized fun initialize(context:Context){
        app=context.applicationContext
        if(RestoreJournal.pending(app))recoverBeforeAccess(true)
        rootDir;dataDir
    }
    private fun recoverBeforeAccess(force:Boolean=false){
        if(!RestoreJournal.active()&&(force||File(app.filesDir,RestoreJournal.JOURNAL).exists()||File(app.filesDir,RestoreJournal.DONE).exists())){
            val helper=NoteDatabase(app)
            try{helper.writableDatabase}finally{helper.close()}
        }
    }

    @Synchronized private fun readIndex():MutableList<AttachmentEntry>{
        if(!indexFile.exists()) return mutableListOf()
        return runCatching{
            val a=JSONArray(indexFile.readText()); MutableList(a.length()){i->a.getJSONObject(i).toEntry()}
        }.getOrElse{ mutableListOf() }
    }
    @Synchronized private fun writeIndex(list:List<AttachmentEntry>){
        indexFile.writeText(indexJson(list).toString())
    }
    private fun indexJson(list:List<AttachmentEntry>)=JSONArray().apply{list.forEach{e->put(JSONObject().apply{put("id",e.id);put("targetType",e.targetType);put("targetId",e.targetId);put("displayName",e.displayName);put("storedName",e.storedName);put("mimeType",e.mimeType);put("size",e.size);put("createdAt",e.createdAt);put("durationMs",e.durationMs)})}}
    private fun JSONObject.toEntry()=AttachmentEntry(optLong("id"),optString("targetType"),optLong("targetId"),optString("displayName"),optString("storedName"),optString("mimeType"),optLong("size"),optLong("createdAt"),optLong("durationMs",0L))
    private fun nextId(list:List<AttachmentEntry>)=(list.maxOfOrNull{it.id}?:0L)+1

    @Synchronized fun list(targetType:String,targetId:Long):List<AttachmentEntry> = readIndex().filter{it.targetType==targetType&&it.targetId==targetId}.sortedByDescending{it.createdAt}
    fun count(targetType:String,targetId:Long)=list(targetType,targetId).size
    @Synchronized fun file(entry:AttachmentEntry)=File(dataDir,entry.storedName)

    fun addFromUri(targetType:String,targetId:Long,uri:Uri,durationMs:Long=0L):AttachmentEntry{
        val cr=app.contentResolver
        val mime=(cr.getType(uri)?:"application/octet-stream")
        val displayName=queryName(uri)?:"file_${System.currentTimeMillis()}"
        val temp=File(app.cacheDir,"incoming_${System.nanoTime()}")
        cr.openInputStream(uri)!!.use{input->temp.outputStream().use{input.copyTo(it)}}
        return addLocalFile(targetType,targetId,temp,displayName,mime,durationMs,true)
    }
    @Synchronized fun addLocalFile(targetType:String,targetId:Long,source:File,displayName:String=source.name,mimeType:String="application/octet-stream",durationMs:Long=0L,deleteSource:Boolean=false):AttachmentEntry{
        require(source.length()<=MAX_FILE_SIZE){AppStrings.t(SettingsStore(app).language,"file_too_large")}
        val entries=readIndex(); val id=nextId(entries)
        val ext=displayName.substringAfterLast('.',"").takeIf{it.length in 1..8}?.let{".$it"}.orEmpty()
        val stored="${System.currentTimeMillis()}_${id}$ext"; val dest=File(dataDir,stored); source.copyTo(dest,true); if(deleteSource)source.delete()
        val e=AttachmentEntry(id,targetType,targetId,displayName,stored,mimeType,dest.length(),System.currentTimeMillis(),durationMs)
        entries.add(e); writeIndex(entries); return e
    }
    @Synchronized fun reassignTarget(targetType:String,oldTargetId:Long,newTargetId:Long){ val entries=readIndex().map{if(it.targetType==targetType&&it.targetId==oldTargetId)it.copy(targetId=newTargetId)else it}; writeIndex(entries) }
    @Synchronized fun delete(entry:AttachmentEntry){ file(entry).delete(); writeIndex(readIndex().filterNot{it.id==entry.id}) }
    @Synchronized fun deleteTarget(targetType:String,targetId:Long){ val all=readIndex(); all.filter{it.targetType==targetType&&it.targetId==targetId}.forEach{file(it).delete()}; writeIndex(all.filterNot{it.targetType==targetType&&it.targetId==targetId}) }
    @Synchronized fun clearAll(){ rootDir.deleteRecursively(); rootDir.mkdirs(); dataDir.mkdirs() }
    fun open(entry:AttachmentEntry){ val f=file(entry); val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",f); val intent=Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,entry.mimeType.ifBlank{"*/*"});addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)}; app.startActivity(intent) }
    fun share(entry:AttachmentEntry){val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",file(entry)); app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=entry.mimeType;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)},AppStrings.t(SettingsStore(app).language,"share_title")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun createCameraTarget():Pair<File,Uri>{ val f=File(app.cacheDir,"organizer_photo_${System.currentTimeMillis()}.jpg"); return f to FileProvider.getUriForFile(app,"${app.packageName}.files",f) }
    private fun queryName(uri:Uri):String? { var name:String?=null; app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())name=c.getString(0)}; return name }
    fun humanSize(size:Long)=when{size<1024->"$size B";size<1024*1024->"%.1f KB".format(size/1024.0);else->"%.1f MB".format(size/1024.0/1024.0)}

    @Synchronized fun exportBackup():JSONArray{ val a=JSONArray(); readIndex().forEach{e->val f=file(e); if(f.exists())a.put(JSONObject().apply{put("id",e.id);put("targetType",e.targetType);put("targetId",e.targetId);put("displayName",e.displayName);put("storedName",e.storedName);put("mimeType",e.mimeType);put("size",e.size);put("createdAt",e.createdAt);put("durationMs",e.durationMs);put("data",Base64.getEncoder().encodeToString(f.readBytes()))})}; return a }
    fun restoreBackup(a:JSONArray){withPreparedBackup(a,"en",AttachmentRestoreFiles()){install->install()}}

    /** Hold the attachment lock until the caller's SQLite transaction has completed. */
    @Synchronized internal fun withPreparedBackup(a:JSONArray,lang:String,io:AttachmentRestoreFiles,replace:((()->Unit)->Unit)){
        withJournalBackup(a,lang,io,false,null){install,_->replace(install)}
    }
    internal fun withDatabaseBackup(a:JSONArray,lang:String,io:AttachmentRestoreFiles,db:NoteDatabase,settings:JSONObject?,replace:((()->Unit,String)->Unit)){
        db.writableDatabase // Do not wait for SQLiteOpenHelper.onOpen while holding the attachment/recovery locks.
        synchronized(this){withJournalBackup(a,lang,io,true,settings,replace)}
    }
    private fun withJournalBackup(a:JSONArray,lang:String,io:AttachmentRestoreFiles,database:Boolean,settings:JSONObject?,replace:((()->Unit,String)->Unit))=synchronized(RestoreJournal.lock){
        BackupValidation.attachments(a,lang)
        recoverBeforeAccess()
        val live=File(app.filesDir,"attachments")
        val work=try{io.createWorkDirectory(app.filesDir)}catch(e:IOException){throw restoreError(lang,e)}catch(e:SecurityException){throw restoreError(lang,e)}
        val staged=File(work,"new"); val previous=File(work,"previous")
        val token=UUID.randomUUID().toString()
        var intent:RestoreJournal.Intent?=null
        var movedOld=false; var installed=false; var databaseCommitted=false
        try{
            RestoreJournal.own(work,token,io)
            io.makeDirectory(staged)
            val data=File(staged,"data"); io.makeDirectory(data)
            val entries=mutableListOf<AttachmentEntry>()
            for(i in 0 until a.length()){
                val o=a.getJSONObject(i); val entry=o.toEntry()
                val destination=File(data,entry.storedName)
                if(destination.canonicalFile.parentFile!=data.canonicalFile)throw IOException("Invalid attachment destination")
                io.write(destination,Base64.getDecoder().decode(o.getString("data")))
                entries.add(entry)
            }
            io.write(File(staged,"index.json"),indexJson(entries).toString().toByteArray(Charsets.UTF_8))
            io.write(File(staged,RestoreJournal.GENERATION),token.toByteArray(Charsets.UTF_8))
            io.syncDirectory(data);io.syncDirectory(staged);io.syncDirectory(work);io.syncDirectory(app.filesDir)
            intent=RestoreJournal.plan(app,work,token,database,settings)
            RestoreJournal.begin(app,work,intent,io)
            RestoreJournal.during(token){
                replace({
                    check(!installed&&!movedOld)
                    if(live.exists()){io.move(live,previous);movedOld=true;RestoreJournal.moved(app,work,io)}
                    io.move(staged,live);installed=true;RestoreJournal.moved(app,work,io)
                },token)
            }
            databaseCommitted=true
            RestoreJournal.complete(app,intent,true,io)
        }catch(failure:Throwable){
            if(databaseCommitted||failure is RestoreJournal.TransactionEndFailed)throw RestoreJournal.required(app,failure)
            try{
                if(installed){io.move(live,staged);installed=false;RestoreJournal.moved(app,work,io)}
                if(movedOld){io.move(previous,live);movedOld=false;RestoreJournal.moved(app,work,io)}
                if(intent!=null&&File(app.filesDir,RestoreJournal.JOURNAL).exists())RestoreJournal.complete(app,intent,false,io)
                else{io.remove(work);io.syncDirectory(app.filesDir)}
            }catch(rollback:Throwable){
                failure.addSuppressed(rollback)
                val message=if(AppStrings.normalizeLanguage(lang)=="ru")"Не удалось автоматически вернуть прежние вложения. Резервный каталог сохранён." else "The previous attachments could not be restored automatically. Their recovery directory has been retained."
                throw IOException(message,failure)
            }
            if(failure is IOException||failure is SecurityException)throw restoreError(lang,failure)
            throw failure
        }
    }
    private fun restoreError(lang:String,cause:Throwable)=IOException(if(AppStrings.normalizeLanguage(lang)=="ru")"Не удалось восстановить вложения. Данные не изменены." else "Could not restore attachments. Your data has not been changed.",cause)
}
