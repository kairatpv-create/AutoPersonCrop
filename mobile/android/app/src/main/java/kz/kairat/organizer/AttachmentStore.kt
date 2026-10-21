package kz.kairat.organizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

object AttachmentStore {
    private const val MAX_FILE_SIZE = 25L * 1024L * 1024L
    private lateinit var app: Context
    private val rootDir get() = File(app.filesDir, "attachments").apply{mkdirs()}
    private val dataDir get() = File(rootDir,"data").apply{mkdirs()}
    private val indexFile get() = File(rootDir,"index.json")
    fun initialize(context:Context){app=context.applicationContext; rootDir; dataDir}

    @Synchronized private fun readIndex():MutableList<AttachmentEntry>{
        if(!indexFile.exists()) return mutableListOf()
        return runCatching{
            val a=JSONArray(indexFile.readText()); MutableList(a.length()){i->a.getJSONObject(i).toEntry()}
        }.getOrElse{ mutableListOf() }
    }
    @Synchronized private fun writeIndex(list:List<AttachmentEntry>){
        val a=JSONArray(); list.forEach{e->a.put(JSONObject().apply{put("id",e.id);put("targetType",e.targetType);put("targetId",e.targetId);put("displayName",e.displayName);put("storedName",e.storedName);put("mimeType",e.mimeType);put("size",e.size);put("createdAt",e.createdAt);put("durationMs",e.durationMs)})}; indexFile.writeText(a.toString())
    }
    private fun JSONObject.toEntry()=AttachmentEntry(optLong("id"),optString("targetType"),optLong("targetId"),optString("displayName"),optString("storedName"),optString("mimeType"),optLong("size"),optLong("createdAt"),optLong("durationMs",0L))
    private fun nextId(list:List<AttachmentEntry>)=(list.maxOfOrNull{it.id}?:0L)+1

    fun list(targetType:String,targetId:Long):List<AttachmentEntry> = readIndex().filter{it.targetType==targetType&&it.targetId==targetId}.sortedByDescending{it.createdAt}
    fun count(targetType:String,targetId:Long)=list(targetType,targetId).size
    fun file(entry:AttachmentEntry)=File(dataDir,entry.storedName)

    fun addFromUri(targetType:String,targetId:Long,uri:Uri,durationMs:Long=0L):AttachmentEntry{
        val cr=app.contentResolver
        val mime=(cr.getType(uri)?:"application/octet-stream")
        val displayName=queryName(uri)?:"file_${System.currentTimeMillis()}"
        val temp=File(app.cacheDir,"incoming_${System.nanoTime()}")
        cr.openInputStream(uri)!!.use{input->temp.outputStream().use{input.copyTo(it)}}
        return addLocalFile(targetType,targetId,temp,displayName,mime,durationMs,true)
    }
    fun addLocalFile(targetType:String,targetId:Long,source:File,displayName:String=source.name,mimeType:String="application/octet-stream",durationMs:Long=0L,deleteSource:Boolean=false):AttachmentEntry{
        require(source.length()<=MAX_FILE_SIZE){AppStrings.t(SettingsStore(app).language,"file_too_large")}
        val entries=readIndex(); val id=nextId(entries)
        val ext=displayName.substringAfterLast('.',"").takeIf{it.length in 1..8}?.let{".$it"}.orEmpty()
        val stored="${System.currentTimeMillis()}_${id}$ext"; val dest=File(dataDir,stored); source.copyTo(dest,true); if(deleteSource)source.delete()
        val e=AttachmentEntry(id,targetType,targetId,displayName,stored,mimeType,dest.length(),System.currentTimeMillis(),durationMs)
        entries.add(e); writeIndex(entries); return e
    }
    fun reassignTarget(targetType:String,oldTargetId:Long,newTargetId:Long){ val entries=readIndex().map{if(it.targetType==targetType&&it.targetId==oldTargetId)it.copy(targetId=newTargetId)else it}; writeIndex(entries) }
    fun delete(entry:AttachmentEntry){ file(entry).delete(); writeIndex(readIndex().filterNot{it.id==entry.id}) }
    fun deleteTarget(targetType:String,targetId:Long){ val all=readIndex(); all.filter{it.targetType==targetType&&it.targetId==targetId}.forEach{file(it).delete()}; writeIndex(all.filterNot{it.targetType==targetType&&it.targetId==targetId}) }
    fun clearAll(){ rootDir.deleteRecursively(); rootDir.mkdirs(); dataDir.mkdirs() }
    fun open(entry:AttachmentEntry){ val f=file(entry); val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",f); val intent=Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,entry.mimeType.ifBlank{"*/*"});addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)}; app.startActivity(intent) }
    fun share(entry:AttachmentEntry){val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",file(entry)); app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=entry.mimeType;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)},AppStrings.t(SettingsStore(app).language,"share_title")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun createCameraTarget():Pair<File,Uri>{ val f=File(app.cacheDir,"organizer_photo_${System.currentTimeMillis()}.jpg"); return f to FileProvider.getUriForFile(app,"${app.packageName}.files",f) }
    private fun queryName(uri:Uri):String? { var name:String?=null; app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())name=c.getString(0)}; return name }
    fun humanSize(size:Long)=when{size<1024->"$size B";size<1024*1024->"%.1f KB".format(size/1024.0);else->"%.1f MB".format(size/1024.0/1024.0)}

    fun exportBackup():JSONArray{ val a=JSONArray(); readIndex().forEach{e->val f=file(e); if(f.exists())a.put(JSONObject().apply{put("id",e.id);put("targetType",e.targetType);put("targetId",e.targetId);put("displayName",e.displayName);put("storedName",e.storedName);put("mimeType",e.mimeType);put("size",e.size);put("createdAt",e.createdAt);put("durationMs",e.durationMs);put("data",Base64.getEncoder().encodeToString(f.readBytes()))})}; return a }
    fun restoreBackup(a:JSONArray){ clearAll(); val list=mutableListOf<AttachmentEntry>(); for(i in 0 until a.length()){val o=a.getJSONObject(i);val e=o.toEntry();runCatching{File(dataDir,e.storedName).writeBytes(Base64.getDecoder().decode(o.optString("data")))};list.add(e)};writeIndex(list)}
}
