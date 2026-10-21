package android.content

import android.net.Uri
import java.io.File

/** Host-only adapter: no Android device or real application directory is accessed. */
open class Context(val sandbox:File) {
    companion object { const val MODE_PRIVATE=0 }
    val filesDir=File(sandbox,"files").apply{mkdirs()}
    val cacheDir=File(sandbox,"cache").apply{mkdirs()}
    val applicationContext:Context get()=this
    val packageName="kz.kairat.organizer.audit"
    val contentResolver=ContentResolver()
    private val preferences=mutableMapOf<String,SharedPreferences>()
    fun getSharedPreferences(name:String,mode:Int)=preferences.getOrPut(name){SharedPreferences()}
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
class SharedPreferences {
    private val values=mutableMapOf<String,Any?>()
    fun getBoolean(k:String,d:Boolean)=values[k] as? Boolean?:d
    fun getFloat(k:String,d:Float)=values[k] as? Float?:d
    fun getLong(k:String,d:Long)=values[k] as? Long?:d
    fun getString(k:String,d:String?)=values[k] as? String?:d
    fun edit()=Editor()
    inner class Editor {
        private val pending=mutableMapOf<String,Any?>()
        private val removed=mutableSetOf<String>()
        fun putBoolean(k:String,v:Boolean)=apply{pending[k]=v}
        fun putFloat(k:String,v:Float)=apply{pending[k]=v}
        fun putLong(k:String,v:Long)=apply{pending[k]=v}
        fun putString(k:String,v:String?)=apply{pending[k]=v}
        fun remove(k:String)=apply{removed.add(k)}
        fun apply(){removed.forEach{values.remove(it)};values.putAll(pending)}
    }
}
