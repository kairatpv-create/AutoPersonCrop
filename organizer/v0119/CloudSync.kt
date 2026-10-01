package kz.kairat.organizer

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val ORGANIZER_SERVER = "https://organizer-pro.app"

data class SyncOutcome(val remoteApplied:Boolean=false,val message:String="Синхронизировано")

object CloudSync {
    private data class HttpResult(val code:Int,val json:JSONObject)

    private fun request(path:String,method:String="GET",body:String?=null,token:String?=null,allowConflict:Boolean=false):HttpResult{
        val c=(URL(ORGANIZER_SERVER+path).openConnection() as HttpURLConnection).apply{
            requestMethod=method
            connectTimeout=15000
            readTimeout=25000
            setRequestProperty("Accept","application/json")
            if(token!=null)setRequestProperty("Authorization","Bearer $token")
            if(body!=null){doOutput=true;setRequestProperty("Content-Type","application/json; charset=utf-8")}
        }
        if(body!=null)c.outputStream.bufferedWriter(Charsets.UTF_8).use{it.write(body)}
        val code=c.responseCode
        val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty()
        val json=if(text.isBlank())JSONObject() else runCatching{JSONObject(text)}.getOrElse{JSONObject().put("message",text)}
        if(code !in 200..299 && !(allowConflict&&code==409)){
            val msg=json.optString("message").takeIf{it.isNotBlank()} ?: when(code){
                401->"Сессия завершена. Войдите снова."
                in 500..599->"Сервер временно недоступен"
                else->"Ошибка соединения"
            }
            throw IllegalStateException(msg)
        }
        return HttpResult(code,json)
    }

    private fun canonicalBackup(db:NoteDatabase,s:SettingsStore):String{
        val root=JSONObject(BackupManager.createBackup(db,s))
        root.remove("attachments")
        root.remove("settings")
        root.put("version",7)
        root.put("createdAt",0L)
        root.put("cloudSchema","organizer-sync-v3")
        return root.toString()
    }
    private fun fingerprint(payload:String)="${payload.hashCode().toUInt().toString(16)}-${payload.length}"
    private fun arrayMax(a:JSONArray?,timeKeys:List<String>):Long{
        if(a==null)return 0L
        var max=0L
        for(i in 0 until a.length()){
            val o=a.optJSONObject(i)?:continue
            for(k in timeKeys)max=kotlin.math.max(max,o.optLong(k,0L))
        }
        return max
    }
    private fun dataTime(payload:String):Long=runCatching{
        val r=JSONObject(payload)
        listOf(
            arrayMax(r.optJSONArray("notes"),listOf("updatedAt","createdAt")),
            arrayMax(r.optJSONArray("projects"),listOf("updatedAt","createdAt","dueAt")),
            arrayMax(r.optJSONArray("projectStages"),listOf("createdAt","dueAt")),
            arrayMax(r.optJSONArray("money"),listOf("createdAt")),
            arrayMax(r.optJSONArray("reminders"),listOf("createdAt","triggerAt"))
        ).maxOrNull()?:0L
    }.getOrDefault(0L)

    fun auth(s:SettingsStore,email:String,password:String,create:Boolean){
        require(email.trim().contains("@")){"Введите корректный email"}
        require(password.length>=6){"Пароль должен быть не короче 6 символов"}
        val body=JSONObject().put("email",email.trim()).put("password",password).toString()
        val j=request(if(create)"/api/auth/register" else "/api/auth/login","POST",body).json
        val token=j.optString("token")
        require(token.isNotBlank()){"Сервер не вернул сессию"}
        s.syncEmail=j.optString("email",email.trim())
        s.syncIdToken=token
        s.syncUid="account"
        s.syncRefreshToken=""
        s.syncTokenExpiry=Long.MAX_VALUE
        s.syncLastFingerprint=""
        s.syncLastCloudUpdatedAt=0L
        s.syncLastSuccessAt=0L
    }

    private fun requireToken(s:SettingsStore):String{
        val token=s.syncIdToken
        require(token.isNotBlank()){ "Сначала войдите в Органайзер" }
        return token
    }

    private fun applyRemote(payload:String,rev:Long,db:NoteDatabase,s:SettingsStore,lang:String,context:Context):SyncOutcome{
        BackupManager.restore(payload,db,lang)
        ReminderScheduler.rescheduleAll(context)
        if(OrganizerCalendarRepository.hasCalendarPermission(context))BirthdayScheduler.rescheduleAll(context)
        s.syncLastFingerprint=fingerprint(payload)
        s.syncLastCloudUpdatedAt=rev
        s.syncLastSuccessAt=System.currentTimeMillis()
        return SyncOutcome(true,"Данные обновлены")
    }

    private fun upload(payload:String,baseRev:Long,db:NoteDatabase,s:SettingsStore,lang:String,context:Context):SyncOutcome{
        require(payload.toByteArray(Charsets.UTF_8).size<850000){"Слишком большой объём данных. Вложения пока остаются на устройстве."}
        val body=JSONObject().put("payload",payload).put("baseRev",baseRev).toString()
        val r=request("/api/sync","PUT",body,requireToken(s),true)
        if(r.code==409){
            val remote=r.json.optString("payload")
            if(remote.isNotBlank())return applyRemote(remote,r.json.optLong("rev",baseRev),db,s,lang,context)
            throw IllegalStateException("Данные изменились на другом устройстве")
        }
        s.syncLastFingerprint=fingerprint(payload)
        s.syncLastCloudUpdatedAt=r.json.optLong("rev",baseRev+1)
        s.syncLastSuccessAt=System.currentTimeMillis()
        return SyncOutcome(false,"Синхронизировано")
    }

    fun syncOnce(db:NoteDatabase,s:SettingsStore,lang:String,context:Context):SyncOutcome{
        val token=requireToken(s)
        val localPayload=canonicalBackup(db,s)
        val localSig=fingerprint(localPayload)
        val remoteDoc=request("/api/sync","GET",null,token).json
        val exists=remoteDoc.optBoolean("exists",false)
        if(!exists)return upload(localPayload,0L,db,s,lang,context)

        val remotePayload=remoteDoc.optString("payload")
        val remoteRev=remoteDoc.optLong("rev",0L)
        if(remotePayload.isBlank())return upload(localPayload,remoteRev,db,s,lang,context)
        val remoteSig=fingerprint(remotePayload)
        val lastSig=s.syncLastFingerprint
        val lastRev=s.syncLastCloudUpdatedAt

        if(lastSig.isBlank()){
            if(localSig==remoteSig){
                s.syncLastFingerprint=localSig;s.syncLastCloudUpdatedAt=remoteRev;s.syncLastSuccessAt=System.currentTimeMillis()
                return SyncOutcome(false,"Синхронизировано")
            }
            return if(dataTime(localPayload)>dataTime(remotePayload)) upload(localPayload,remoteRev,db,s,lang,context)
            else applyRemote(remotePayload,remoteRev,db,s,lang,context)
        }

        val localDirty=localSig!=lastSig
        val remoteDirty=remoteRev!=lastRev || remoteSig!=lastSig
        return when{
            localDirty&&!remoteDirty->upload(localPayload,remoteRev,db,s,lang,context)
            !localDirty&&remoteDirty->applyRemote(remotePayload,remoteRev,db,s,lang,context)
            localDirty&&remoteDirty->if(dataTime(localPayload)>dataTime(remotePayload))upload(localPayload,remoteRev,db,s,lang,context) else applyRemote(remotePayload,remoteRev,db,s,lang,context)
            else->{s.syncLastSuccessAt=System.currentTimeMillis();SyncOutcome(false,"Синхронизировано")}
        }
    }

    fun logout(s:SettingsStore){s.clearSyncSession()}
}

private fun syncText(lang:String,key:String):String{
    val ru=AppStrings.normalizeLanguage(lang)=="ru"
    return if(ru)when(key){
        "title"->"Аккаунт и синхронизация"
        "email"->"Email"
        "password"->"Пароль"
        "login"->"Войти"
        "register"->"Создать аккаунт"
        "logout"->"Выйти"
        "working"->"Подключение…"
        "enabled"->"Синхронизация включена"
        "hint"->"Заметки, проекты, этапы, финансы и напоминания синхронизируются автоматически. Вложения пока остаются на устройстве."
        else->key
    }else when(key){
        "title"->"Account and sync"
        "email"->"Email"
        "password"->"Password"
        "login"->"Sign in"
        "register"->"Create account"
        "logout"->"Sign out"
        "working"->"Connecting…"
        "enabled"->"Sync enabled"
        "hint"->"Notes, projects, stages, finance and reminders sync automatically. Attachments remain on the device for now."
        else->key
    }
}

@Composable fun CloudSyncAutoHost(db:NoteDatabase,settings:SettingsStore,lang:String){
    val context=LocalContext.current
    LaunchedEffect(Unit){
        while(true){
            if(settings.syncIdToken.isNotBlank()){
                val r=runCatching{withContext(Dispatchers.IO){CloudSync.syncOnce(db,settings,lang,context)}}
                val outcome=r.getOrNull()
                if(outcome?.remoteApplied==true){
                    (context as? Activity)?.recreate()
                    return@LaunchedEffect
                }
                if(r.exceptionOrNull()?.message?.contains("Сессия завершена")==true)CloudSync.logout(settings)
            }
            delay(30000)
        }
    }
}

@Composable fun CloudSyncSettingsCard(db:NoteDatabase,settings:SettingsStore,lang:String){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var email by rememberSaveable{mutableStateOf(settings.syncEmail)}
    var password by rememberSaveable{mutableStateOf("")}
    var logged by remember{mutableStateOf(settings.syncIdToken.isNotBlank())}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf(if(logged)syncText(lang,"enabled") else "")}
    var error by remember{mutableStateOf(false)}

    fun doLogin(create:Boolean){
        if(busy)return
        busy=true;error=false;status=syncText(lang,"working")
        scope.launch{
            val r=runCatching{
                withContext(Dispatchers.IO){
                    CloudSync.auth(settings,email,password,create)
                    CloudSync.syncOnce(db,settings,lang,context)
                }
            }
            busy=false
            r.onSuccess{outcome->
                logged=true;password="";status=outcome.message;error=false
                if(outcome.remoteApplied)(context as? Activity)?.recreate()
            }.onFailure{status=it.message?:it.javaClass.simpleName;error=true}
        }
    }

    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(syncText(lang,"title"),style=MaterialTheme.typography.titleMedium)
            if(!logged){
                OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"email"))},singleLine=true)
                OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"password"))},singleLine=true,visualTransformation=PasswordVisualTransformation())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button({doLogin(false)},Modifier.weight(1f),enabled=!busy){Text(syncText(lang,"login"),maxLines=1)}
                    OutlinedButton({doLogin(true)},Modifier.weight(1f),enabled=!busy){Text(syncText(lang,"register"),maxLines=2)}
                }
            }else{
                Text(settings.syncEmail,style=MaterialTheme.typography.bodyMedium)
                Text(syncText(lang,"enabled"),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyMedium)
                Text(syncText(lang,"hint"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({CloudSync.logout(settings);logged=false;status=""},Modifier.fillMaxWidth(),enabled=!busy){Text(syncText(lang,"logout"))}
            }
            if(status.isNotBlank())Text(status,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
        }
    }
}
