package kz.kairat.organizer

import org.json.JSONArray
import java.io.IOException

/** Bounds are enforced before JSON parsing / Base64 allocation, also for direct restore callers. */
internal object BackupImportLimits {
    data class Policy(val jsonBytes:Int=64*1024*1024, val archiveBytes:Long=64L*1024*1024,
                      val depth:Int=32, val values:Int=1_000_000, val zipEntries:Int=32,
                      val attachmentBytes:Long=25L*1024*1024, val decodedBytes:Long=48L*1024*1024)
    fun current():Policy {
        val r=Runtime.getRuntime()
        val available=(r.maxMemory()-(r.totalMemory()-r.freeMemory())).coerceAtLeast(0L)
        // Reserve room for the input, JSON tree, models, Base64 decoding and the running UI.
        return Policy(jsonBytes=minOf(64L*1024*1024,available/8).coerceAtLeast(0).toInt(),
            values=minOf(1_000_000L,available/256).coerceAtLeast(0).toInt(),
            attachmentBytes=minOf(25L*1024*1024,available/4),decodedBytes=minOf(48L*1024*1024,available/4))
    }
    fun error(lang:String)=IOException(if(AppStrings.normalizeLanguage(lang)=="ru")
        "Резервная копия слишком велика или сложна для безопасного восстановления на этом устройстве. Данные не изменены."
        else "The backup is too large or complex to restore safely on this device. Your data has not been changed.")
    fun checkJson(json:String,lang:String,policy:Policy=current()) {
        if(json.length>policy.jsonBytes)throw error(lang)
        var bytes=0L;var depth=0;var units=0;var quote='\u0000';var escaped=false;var i=0
        while(i<json.length){
            val c=json[i]
            bytes+=when{
                c.code<128->1
                c.code<2048->2
                Character.isHighSurrogate(c)&&i+1<json.length&&Character.isLowSurrogate(json[i+1])->4
                else->3
            }
            if(bytes>policy.jsonBytes)throw error(lang)
            if(quote!='\u0000'){
                if(escaped)escaped=false else if(c=='\\')escaped=true else if(c==quote)quote='\u0000'
            }else when(c){
                '"','\''->{quote=c;units++}
                '{','['->{depth++;units++}
                '}',']'->depth--
                ',',':',';'->units++ // Count work even for org.json's permissive syntax.
            }
            if(depth>policy.depth||units>policy.values)throw error(lang)
            if(Character.isHighSurrogate(c)&&i+1<json.length&&Character.isLowSurrogate(json[i+1]))i++
            i++
        }
    }
    fun checkAttachments(array:JSONArray,lang:String,policy:Policy=current()) {
        if(array.length()>10_000)throw error(lang)
        var decoded=0L
        var characters=0L
        for(i in 0 until array.length()){
            val item=array.optJSONObject(i)?:continue // The structural validator supplies the format error.
            for(key in listOf("data","targetType","displayName","storedName","mimeType"))characters+=(item.opt(key) as? String)?.length?:0
            if(characters>policy.jsonBytes)throw error(lang)
            val encoded=item.opt("data") as? String?:continue
            val maximum=(policy.attachmentBytes+2)/3*4
            if(encoded.length.toLong()>maximum)throw error(lang)
            val size=(item.opt("size") as? Number)?.toLong()?:0L
            if(size>policy.attachmentBytes)throw error(lang)
            val padding=if(encoded.endsWith("=="))2 else if(encoded.endsWith("="))1 else 0
            decoded+=(encoded.length.toLong()*3/4-padding).coerceAtLeast(0)
            if(decoded>policy.decodedBytes)throw error(lang)
        }
    }
}
