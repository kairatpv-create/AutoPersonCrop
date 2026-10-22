package kz.kairat.organizer

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private const val ORGANIZER_SERVER = "https://organizer-pro.onrender.com"

data class SyncOutcome(val remoteApplied:Boolean=false,val message:String="Синхронизировано")

object CloudSync {
    internal data class HttpResult(val code:Int,val json:JSONObject)
    // A test may provide a synthetic transport. There is no user-configurable production endpoint.
    internal var testTransport:((String,String,String?,String?)->HttpResult)?=null
    private val coordinator=Any()
    private fun request(path:String,method:String="GET",body:String?=null,token:String?=null,allowConflict:Boolean=false):HttpResult{
        val r=testTransport?.invoke(path,method,body,token)?:run{
            val c=(URL(ORGANIZER_SERVER+path).openConnection() as HttpURLConnection).apply{
                requestMethod=method;connectTimeout=70000;readTimeout=90000
                setRequestProperty("Accept","application/json")
                if(token!=null)setRequestProperty("Authorization","Bearer $token")
                if(body!=null){doOutput=true;setRequestProperty("Content-Type","application/json; charset=utf-8")}
            }
            try{
                if(body!=null)c.outputStream.bufferedWriter(Charsets.UTF_8).use{it.write(body)}
                val code=c.responseCode
                val text=(if(code in 200..299)c.inputStream else c.errorStream)?.use{input->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=2000000){"Server response too large"};out.write(buffer,0,n)}
                    out.toString("UTF-8")
                }.orEmpty()
                HttpResult(code,if(text.isBlank())JSONObject() else JSONObject(text))
            }finally{c.disconnect()}
        }
        if(r.code !in 200..299&&!(allowConflict&&r.code==409)){
            if(r.code==401)throw IllegalStateException("Сессия завершена. Войдите снова. / Session expired. Sign in again.")
            throw IllegalStateException(r.json.optString("message").ifBlank{"Ошибка соединения / Connection error"})
        }
        return r
    }
    private data class Account(val owner:String,val email:String)
    private fun account(token:String):Account{
        require(token.isNotBlank()){"Сначала войдите в Органайзер / Sign in first"}
        // This request validates the JWT on the existing server before its sub is used as an identifier.
        val verified=request("/api/account",token=token).json
        val claims=JSONObject(String(java.util.Base64.getUrlDecoder().decode(token.split('.').also{require(it.size==3)}[1]),Charsets.UTF_8))
        val id=claims.getString("sub");require(id.matches(Regex("[1-9][0-9]*")))
        val email=verified.getString("email").trim().lowercase(java.util.Locale.ROOT)
        require(email==claims.getString("email").trim().lowercase(java.util.Locale.ROOT))
        return Account("organizer-pro:$id",email)
    }
    private fun ownerError(lang:String)=IllegalStateException(SyncSnapshot.message(lang,
        "Локальные данные связаны с другим аккаунтом. Войдите в прежний аккаунт; данные и вложения сохранены.",
        "Local data belongs to another account. Sign in to the original account; your data and attachments are preserved."))
    private fun checkOwner(s:SettingsStore,a:Account,lang:String):SyncStateStore.State?{
        val state=SyncStateStore(s.appContext,lang).read()
        if(state!=null&&(state.owner!=a.owner||state.email!=a.email))throw ownerError(lang)
        // Upgrade guard: old releases retained sync_email when signing out, but had no owner file.
        if(state==null&&s.syncEmail.isNotBlank()&&!s.syncEmail.equals(a.email,true))throw ownerError(lang)
        return state
    }
    fun auth(s:SettingsStore,email:String,password:String,create:Boolean)=synchronized(coordinator){
        val lang=s.language
        val normalized=email.trim().lowercase(java.util.Locale.ROOT)
        require(normalized.contains("@")){"Введите корректный email / Enter a valid email"}
        require(password.length in 6..200){"Пароль: от 6 до 200 символов / Password: 6–200 characters"}
        val state=SyncStateStore(s.appContext,lang).read()
        val previous=state?.email?:s.syncEmail
        if(previous.isNotBlank()&&!previous.equals(normalized,true))throw ownerError(lang)
        val j=request(if(create)"/api/auth/register" else "/api/auth/login","POST",JSONObject().put("email",normalized).put("password",password).toString()).json
        val token=j.getString("token");val a=account(token)
        require(a.email==normalized)
        checkOwner(s,a,lang)
        // Persist ownership before enabling automatic sync; a failed commit must not enable a different owner.
        if(state==null)SyncStateStore(s.appContext,lang).write(SyncStateStore.State(a.owner,a.email,null,0))
        s.saveSession(a.email,token,a.owner);s.syncError=""
    }
    private fun snapshot(db:NoteDatabase,s:SettingsStore,lang:String):String{
        val database=db.writableDatabase
        database.beginTransaction()
        return try{SyncSnapshot.parse(BackupManager.createCloudBackup(db,s),lang)}finally{database.endTransaction()}
    }
    private fun revision(j:JSONObject):Long{
        val value=j.get("rev");require(value is Number)
        val n=java.math.BigDecimal(value.toString()).longValueExact();require(n>=0);return n
    }
    private fun acknowledge(payload:String,rev:Long,a:Account,s:SettingsStore,lang:String){
        SyncStateStore(s.appContext,lang).write(SyncStateStore.State(a.owner,a.email,payload,rev))
        s.syncLastFingerprint=SyncSnapshot.hash(payload);s.syncLastCloudUpdatedAt=rev;s.syncLastSuccessAt=System.currentTimeMillis()
    }
    private fun applyRemote(payload:String,expected:String,db:NoteDatabase,s:SettingsStore,lang:String,context:Context){
        // Lock order follows local restore: attachment -> recovery -> SQLite. Recheck inside the writer transaction.
        db.writableDatabase
        synchronized(AttachmentStore){
            BackupManager.restore(payload,db,lang,AttachmentRestoreFiles()){
                val actual=SyncSnapshot.parse(BackupManager.createCloudBackup(db,s),lang)
                if(actual!=expected)throw SyncSnapshot.Conflict(lang)
                AttachmentStore.checkCloudTargets(JSONObject(expected),JSONObject(payload),lang)
                RestoreSafety.capture(context,db.writableDatabase,lang,AttachmentRestoreFiles(),insideRestore=true)
            }
        }
    }
    fun syncOnce(db:NoteDatabase,s:SettingsStore,lang:String,context:Context):SyncOutcome=synchronized(coordinator){
        var conflictLocal:String?=null;var conflictRemote:String?=null;var conflictRev=0L;var conflictBase:String?=null;var conflictOwner=""
        try{
            val token=s.syncIdToken;val a=account(token);val store=SyncStateStore(context,lang)
            var state=checkOwner(s,a,lang)
            if(state==null){state=SyncStateStore.State(a.owner,a.email,null,0);store.write(state)}
            val local=snapshot(db,s,lang)
            conflictLocal=local;conflictBase=state.payload;conflictOwner=a.owner
            var response=request("/api/sync",token=token).json
            // CAS retries always recompute from the same acknowledged base; 409 never replaces local data.
            repeat(3){
                val exists=response.get("exists") as? Boolean?:throw IllegalStateException("Invalid sync response")
                val rev=revision(response)
                require(exists||rev==0L)
                val remote=if(exists)SyncSnapshot.parse(response.getString("payload"),lang) else null
                conflictRemote=remote;conflictRev=rev
                val base=state.payload
                val target=when{
                    remote==null&&base==null->local
                    remote==null->throw SyncSnapshot.Conflict(lang)
                    local==remote->local
                    base==null&&SyncSnapshot.empty(local)->remote
                    base==null&&SyncSnapshot.empty(remote)->local
                    base==null->throw SyncSnapshot.Conflict(lang)
                    else->SyncSnapshot.merge(base,local,remote,lang)
                }
                // Check attachment identity before publishing a merged snapshot as well as before local replacement.
                if(target!=local)synchronized(AttachmentStore){AttachmentStore.checkCloudTargets(JSONObject(local),JSONObject(target),lang)}
                var publishedRev=rev
                if(target!=remote){
                    val body=JSONObject().put("payload",target).put("baseRev",rev).toString()
                    require(body.toByteArray(Charsets.UTF_8).size<1000000){"Sync request too large"}
                    val put=request("/api/sync","PUT",body,token,true)
                    if(put.code==409){
                        // An intervening writer may have changed the cloud again. Refetch and merge, never pick a winner by date.
                        response=request("/api/sync",token=token).json
                        return@repeat
                    }
                    require(put.json.get("ok")==true)
                    publishedRev=revision(put.json);require(publishedRev>rev)
                }
                var changed=false
                if(target!=local){
                    applyRemote(target,local,db,s,lang,context);changed=true
                    ReminderScheduler.rescheduleAll(context)
                    if(OrganizerCalendarRepository.hasCalendarPermission(context))BirthdayScheduler.rescheduleAll(context)
                }
                acknowledge(target,publishedRev,a,s,lang);s.syncError=""
                return@synchronized SyncOutcome(changed,SyncSnapshot.message(lang,"Синхронизировано","Synced"))
            }
            throw SyncSnapshot.Conflict(lang)
        }catch(e:Exception){
            if(e is SyncSnapshot.Conflict&&conflictLocal!=null&&conflictRemote!=null){
                try{SyncConflictStore.preserve(context,conflictOwner,snapshot(db,s,lang),conflictRemote,conflictBase,conflictRev)}catch(save:Exception){e.addSuppressed(save)}
            }
            s.syncError=e.message?:e.javaClass.simpleName;throw e
        }
    }
    fun exportConflict(s:SettingsStore,remote:Boolean):String=synchronized(coordinator){
        val state=SyncStateStore(s.appContext,s.language).read()?:throw IllegalStateException("No conflict copy")
        SyncConflictStore.read(s.appContext,state.owner,remote,s.language)
    }
    fun deleteAccount(s:SettingsStore,password:String,confirmation:String)=synchronized(coordinator){
        val a=account(s.syncIdToken);checkOwner(s,a,s.language)
        require(confirmation.trim().equals(a.email,true)){SyncSnapshot.message(s.language,"Введите email удаляемого аккаунта.","Enter the email of the account to delete.")}
        require(password.isNotBlank())
        val capability=request("/api/account",token=s.syncIdToken).json
        require(capability.optBoolean("accountDeletion",false)){SyncSnapshot.message(s.language,"Сервер ещё не поддерживает удаление аккаунта. Обновление сервера ожидает отдельного развёртывания.","The server does not support account deletion yet. The server update awaits deployment.")}
        val result=request("/api/account/delete","POST",JSONObject().put("password",password).put("confirmation",a.email).toString(),s.syncIdToken)
        require(result.json.get("deleted")==true)
        // Keep the database, attachments and durable owner. Signing up again is a different account.
        s.clearSyncSession();s.syncError=""
    }
    fun logout(s:SettingsStore)=synchronized(coordinator){s.clearSyncSession();s.syncError=""}
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
                if(r.exceptionOrNull()?.message?.contains("Сессия завершена")==true)withContext(Dispatchers.IO){CloudSync.logout(settings)}
            }
            delay(30000)
        }
    }
}

@Composable fun CloudSyncSettingsCard(db:NoteDatabase,settings:SettingsStore,lang:String,compact:Boolean=false){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var email by rememberSaveable{mutableStateOf(settings.syncEmail)}
    var password by remember{mutableStateOf("")}
    var logged by remember{mutableStateOf(settings.syncIdToken.isNotBlank())}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf(if(logged)syncText(lang,"enabled") else "")}
    var error by remember{mutableStateOf(false)}
    var showPrivacy by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf(false)}
    var deletePassword by remember{mutableStateOf("")}
    var deleteEmail by remember{mutableStateOf("")}
    LaunchedEffect(settings){
        while(true){
            if(!busy){
                logged=settings.syncIdToken.isNotBlank()
                if(settings.syncError.isNotBlank()){status=settings.syncError;error=true}
            }
            delay(1000)
        }
    }
    if(showPrivacy)AlertDialog(onDismissRequest={showPrivacy=false},
        title={Text(SyncSnapshot.message(lang,"Данные и приватность","Data and privacy"))},
        text={Text(PrivacyNotice.text(lang),modifier=Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick={showPrivacy=false}){Text(SyncSnapshot.message(lang,"Закрыть","Close"))}})
    if(deleting)AlertDialog(onDismissRequest={if(!busy){deleting=false;deletePassword=""}},
        title={Text(SyncSnapshot.message(lang,"Удалить аккаунт?","Delete account?"))},
        text={Column(modifier=Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(SyncSnapshot.message(lang,"Аккаунт и синхронизированные данные на сервере будут удалены без возможности отмены. Локальные заметки, финансы и вложения останутся на этом устройстве. Копии на других устройствах и созданные вами файлы также сохранятся.","Your account and synced server data will be permanently deleted. Local notes, finances and attachments will remain on this device. Copies on other devices and files you exported will also remain."))
            OutlinedTextField(deleteEmail,{deleteEmail=it},label={Text("Email")},singleLine=true)
            OutlinedTextField(deletePassword,{deletePassword=it},label={Text(syncText(lang,"password"))},singleLine=true,visualTransformation=PasswordVisualTransformation())
        }},
        confirmButton={TextButton(enabled=!busy&&deletePassword.isNotBlank()&&deleteEmail.equals(settings.syncEmail,true),onClick={
            busy=true
            scope.launch{
                val result=runCatching{withContext(Dispatchers.IO){CloudSync.deleteAccount(settings,deletePassword,deleteEmail)}}
                busy=false;deletePassword="";deleting=false
                result.onSuccess{logged=false;status=SyncSnapshot.message(lang,"Аккаунт удалён. Локальные данные сохранены.","Account deleted. Local data is preserved.");error=false}
                    .onFailure{status=it.message.orEmpty();error=true}
            }
        }){Text(SyncSnapshot.message(lang,"Удалить навсегда","Delete permanently"))}},
        dismissButton={TextButton(enabled=!busy,onClick={deleting=false;deletePassword=""}){Text(SyncSnapshot.message(lang,"Отмена","Cancel"))}})


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
            }.onFailure{logged=settings.syncIdToken.isNotBlank();password="";status=it.message?:it.javaClass.simpleName;error=true}
        }
    }

    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){
        if(!logged){
            Column(Modifier.padding(horizontal=10.dp,vertical=if(compact)4.dp else 8.dp),verticalArrangement=Arrangement.spacedBy(if(compact)3.dp else 6.dp)){
                Text(syncText(lang,"title"),style=if(compact)MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                if(compact){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        OutlinedTextField(email,{email=it},Modifier.weight(1f),label={Text(syncText(lang,"email"))},textStyle=userTextStyle(),singleLine=true)
                        OutlinedTextField(password,{password=it},Modifier.weight(1f),label={Text(syncText(lang,"password"))},textStyle=userTextStyle(),singleLine=true,visualTransformation=PasswordVisualTransformation())
                    }
                }else{
                    OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"email"))},textStyle=userTextStyle(),singleLine=true)
                    OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"password"))},textStyle=userTextStyle(),singleLine=true,visualTransformation=PasswordVisualTransformation())
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(compact)5.dp else 8.dp)){
                    Button({doLogin(false)},Modifier.weight(1f).heightIn(min=if(compact)32.dp else 40.dp),enabled=!busy,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(syncText(lang,"login"),maxLines=1)}
                    OutlinedButton({doLogin(true)},Modifier.weight(1f).heightIn(min=if(compact)32.dp else 40.dp),enabled=!busy,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(syncText(lang,"register"),maxLines=2)}
                }
                if(status.isNotBlank())Text(status,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
            }
        }else{
            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=if(compact)3.dp else 5.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(if(compact)0.dp else 1.dp)){
                    Text(syncText(lang,"title"),style=MaterialTheme.typography.titleSmall)
                    Text(settings.syncEmail,style=MaterialTheme.typography.bodySmall,maxLines=1)
                    Text(if(status.isNotBlank())status else syncText(lang,"enabled"),color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                    if(error){
                        TextButton(onClick={BackupManager.shareBackup(context,BackupManager.createBackup(db,settings),lang=lang)}){Text(SyncSnapshot.message(lang,"Сохранить копию данных","Export local backup"))}
                        TextButton(enabled=!busy,onClick={
                            busy=true
                            scope.launch{
                                val result=runCatching{withContext(Dispatchers.IO){CloudSync.exportConflict(settings,true)}}
                                busy=false
                                result.onSuccess{BackupManager.shareBackup(context,it,lang=lang)}.onFailure{status=SyncSnapshot.message(lang,"Сохранённая облачная копия конфликта недоступна.","The saved cloud conflict copy is unavailable.")}
                            }
                        }){Text(SyncSnapshot.message(lang,"Сохранить облачную копию конфликта","Export cloud conflict copy"))}
                    }
                    TextButton(enabled=!busy,onClick={deleting=true;deletePassword="";deleteEmail=""}){Text(SyncSnapshot.message(lang,"Удалить аккаунт","Delete account"))}
                }
                TextButton({busy=true;scope.launch{withContext(Dispatchers.IO){CloudSync.logout(settings)};busy=false;logged=false;status=""}},enabled=!busy,modifier=Modifier.heightIn(min=if(compact)28.dp else 30.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp)){Text(syncText(lang,"logout"),style=MaterialTheme.typography.bodySmall)}
            }
        }
        TextButton(onClick={showPrivacy=true}){Text(SyncSnapshot.message(lang,"Данные и приватность","Data and privacy"))}
    }
}
