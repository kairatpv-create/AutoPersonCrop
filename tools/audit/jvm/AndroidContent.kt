package android.content

import android.net.Uri
import java.io.File
import org.json.JSONObject

/** Host-only adapter: no Android device or real application directory is accessed. */
open class Context(val sandbox:File) {
    companion object { const val MODE_PRIVATE=0 }
    val filesDir=File(sandbox,"files").apply{mkdirs()}
    val cacheDir=File(sandbox,"cache").apply{mkdirs()}
    val noBackupFilesDir=File(sandbox,"no_backup").apply{mkdirs()}
    val applicationContext:Context get()=this
    val packageName="kz.kairat.organizer.audit"
    val contentResolver=ContentResolver()
    private val preferences=mutableMapOf<String,SharedPreferences>()
    fun getSharedPreferences(name:String,mode:Int)=preferences.getOrPut(name){SharedPreferences(File(sandbox,"shared_prefs/$name.json"))}
    fun startActivity(intent:Intent) { error("UI launch is not supported in host audit") }
}
class ContentResolver {
    fun openOutputStream(uri:Uri,mode:String)=File(uri.path).outputStream()
    fun openInputStream(uri:Uri)=File(uri.path).inputStream()
    fun getType(uri:Uri):String?=null
    fun query(uri:Uri,projection:Array<String>?,selection:String?,args:Array<String>?,order:String?):android.database.Cursor?=null
}
class Intent(val action:String) {
    companion object {
        const val ACTION_SEND="SEND"; const val ACTION_VIEW="VIEW"
        const val EXTRA_STREAM="stream";const val EXTRA_SUBJECT="subject";const val EXTRA_EMAIL="email"
        const val FLAG_GRANT_READ_URI_PERMISSION=1;const val FLAG_ACTIVITY_NEW_TASK=2
        fun createChooser(intent:Intent,title:String)=intent
    }
    var type:String=""
    fun putExtra(key:String,value:Any?):Intent=this
    fun addFlags(flags:Int):Intent=this
    fun setDataAndType(uri:Uri,mime:String):Intent=this
}
class ContentValues:LinkedHashMap<String,Any?>(){
    fun putNull(key:String){put(key,null)}
}
class SharedPreferences(private val file:File?=null) {
    private val values=mutableMapOf<String,Any?>()
    init { if(file?.isFile==true){val json=JSONObject(file.readText());json.keys().forEach{k->values[k]=json.get(k)}} }
    val all:Map<String,Any?> get()=values.toMap()
    fun getBoolean(k:String,d:Boolean)=values[k] as? Boolean?:d
    fun getFloat(k:String,d:Float)=(values[k] as? Number)?.toFloat()?:d
    fun getLong(k:String,d:Long)=(values[k] as? Number)?.toLong()?:d
    fun getString(k:String,d:String?)=values[k] as? String?:d
    fun edit()=Editor()
    inner class Editor {
        private val pending=mutableMapOf<String,Any?>()
        private val removed=mutableSetOf<String>()
        fun putBoolean(k:String,v:Boolean)=apply{pending[k]=v}
        fun putFloat(k:String,v:Float)=apply{pending[k]=v}
        fun putInt(k:String,v:Int)=apply{pending[k]=v}
        fun putLong(k:String,v:Long)=apply{pending[k]=v}
        fun putString(k:String,v:String?)=apply{pending[k]=v}
        fun remove(k:String)=apply{removed.add(k)}
        fun commit():Boolean{
            removed.forEach{values.remove(it)};values.putAll(pending)
            if(System.getProperty("organizer.audit.failPreferencesCommit")=="true")return false
            if(file!=null){
                file.parentFile.mkdirs()
                val temporary=File(file.parentFile,file.name+".tmp")
                java.io.FileOutputStream(temporary).use{out->out.write(JSONObject(values as Map<*,*>).toString().toByteArray());out.fd.sync()}
                java.nio.file.Files.move(temporary.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            return true
        }
        fun apply(){commit()}
    }
}
