package kz.kairat.organizer

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** The existing v7 wire contract, without local preferences or attachment contents. */
internal object SyncSnapshot {
    val sections=listOf("notes","tasks","projects","projectStages","projectJournal","projectNoteLinks","money","reminders")
    fun message(lang:String,ru:String,en:String)=if(AppStrings.normalizeLanguage(lang)=="ru")ru else en
    class Conflict(lang:String):IllegalStateException(message(lang,
        "На устройствах есть несовместимые изменения. Автозамена остановлена; локальные и облачные данные сохранены. Сохраните локальную резервную копию перед ручным разрешением конфликта.",
        "The devices have conflicting changes. Automatic replacement stopped; local and cloud data are preserved. Export a local backup before resolving the conflict manually."))
    private val fields=mapOf(
        "notes" to "id title body createdAt updatedAt colorKey",
        "tasks" to "id title details done createdAt updatedAt",
        "projects" to "id name description status progress startAt dueAt budget client address contact createdAt updatedAt colorKey",
        "projectStages" to "id projectId title done dueAt createdAt",
        "projectJournal" to "id projectId text createdAt",
        "projectNoteLinks" to "projectId noteId",
        "money" to "id type amount category projectId projectName note createdAt colorKey",
        "reminders" to "id targetType targetId triggerAt sound vibrate soundKey createdAt scheduleType daysMask dayMode baseMinutes")
    fun parse(payload:String,lang:String):String {
        require(payload.toByteArray(Charsets.UTF_8).size<=850000){message(lang,"Слишком большой объём данных для синхронизации.","The sync snapshot is too large.")}
        val r=BackupValidation.parse(payload,lang)
        require(r.getInt("version")==7&&!r.has("attachments")&&!r.has("settings")&&r.optString("cloudSchema","organizer-sync-v3")=="organizer-sync-v3") {
            message(lang,"Неподдерживаемый облачный снимок. Данные не изменены.","Unsupported cloud snapshot. Your data has not been changed.")
        }
        for(section in sections){
            val rows=r.getJSONArray(section)
            val allowed=fields.getValue(section).split(' ').toSet()
            for(i in 0 until rows.length()){
                val o=rows.getJSONObject(i)
                // Older web exporters include edit timestamps that the Android schema does not store.
                if(section in listOf("projectStages","projectJournal","money","reminders"))o.remove("updatedAt")
                require(o.keys().asSequence().all{it in allowed}){message(lang,"Облачный снимок содержит неизвестные поля. Данные сохранены.","The cloud snapshot contains unknown fields. Your data is preserved.")}
                if(section!="projectNoteLinks")require(o.getLong("id")>0)
                fun default(k:String,v:Any){if(!o.has(k))o.put(k,v)}
                if(section in listOf("notes","projects","money"))default("colorKey","blue")
                if(section=="projects"){
                    mapOf("status" to "planned","progress" to 0,"startAt" to o.getLong("createdAt"),"dueAt" to 0L,"budget" to 0,
                        "client" to "","address" to "","contact" to "","updatedAt" to o.getLong("createdAt")).forEach{(k,v)->default(k,v)}
                }
                if(section=="reminders")mapOf("sound" to true,"vibrate" to true,"soundKey" to "system","scheduleType" to "once","daysMask" to 0,"dayMode" to "single","baseMinutes" to 540).forEach{(k,v)->default(k,v)}
            }
            r.put(section,JSONArray((0 until rows.length()).map{rows.getJSONObject(it)}.sortedBy{identity(section,it)}))
        }
        // projectName is derived by SQLite, not an independently editable value.
        val projects=rows(r,"projects")
        val money=r.getJSONArray("money")
        for(i in 0 until money.length()){
            val m=money.getJSONObject(i)
            m.put("projectName",if(m.has("projectId"))projects[m.getLong("projectId").toString()]?.getString("name").orEmpty() else "")
        }
        val root=JSONObject().put("format","my-organizer-backup").put("version",7).put("createdAt",0).put("cloudSchema","organizer-sync-v3")
        sections.forEach{root.put(it,r.getJSONArray(it))}
        return canonical(root)
    }
    fun empty(payload:String)=sections.all{JSONObject(payload).getJSONArray(it).length()==0}
    fun hash(payload:String)=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun identity(section:String,o:JSONObject)=if(section=="projectNoteLinks")"${o.getLong("projectId")}:${o.getLong("noteId")}" else o.getLong("id").toString()
    private fun rows(root:JSONObject,section:String)=root.getJSONArray(section).let{a->(0 until a.length()).associate{i->a.getJSONObject(i).let{identity(section,it) to it}}}
    private fun same(a:JSONObject?,b:JSONObject?)=if(a==null||b==null)a==null&&b==null else canonical(a)==canonical(b)
    fun merge(base:String,local:String,remote:String,lang:String):String {
        val b=JSONObject(base);val l=JSONObject(local);val r=JSONObject(remote);val result=JSONObject(l.toString())
        for(section in sections){
            val br=rows(b,section);val lr=rows(l,section);val rr=rows(r,section);val merged=JSONArray()
            for(id in (br.keys+lr.keys+rr.keys).sorted()){
                val old=br[id];val left=lr[id];val right=rr[id]
                val chosen=when{same(left,right)->left;same(left,old)->right;same(right,old)->left;else->throw Conflict(lang)}
                chosen?.let{merged.put(it)}
            }
            result.put(section,merged)
        }
        // A deletion cannot silently strand a simultaneous edit to a dependent row.
        val projects=rows(result,"projects");val notes=rows(result,"notes");val tasks=rows(result,"tasks")
        for(section in listOf("projectStages","projectJournal","projectNoteLinks","money","reminders")){
            for(o in rows(result,section).values){
                if(o.has("projectId")&&!projects.containsKey(o.getLong("projectId").toString()))throw Conflict(lang)
                if(section=="projectNoteLinks"&&!notes.containsKey(o.getLong("noteId").toString()))throw Conflict(lang)
                if(section=="reminders"){
                    val targets=when(o.getString("targetType")){"note"->notes;"task"->tasks;"project"->projects;else->throw Conflict(lang)}
                    if(!targets.containsKey(o.getLong("targetId").toString()))throw Conflict(lang)
                }
            }
        }
        return parse(result.toString(),lang)
    }
    private fun canonical(value:Any?):String=when(value){
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))}
        is Number->java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        null,JSONObject.NULL->"null"
        is Boolean->value.toString()
        else->JSONObject.quote(value.toString())
    }
}
