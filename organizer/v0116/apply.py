from pathlib import Path
import sys

root=Path(sys.argv[1])
base=root/'app/src/main/java/kz/kairat/organizer'
ui=base/'OrganizerUi.kt'
settings=base/'SettingsStore.kt'
strings=base/'AppStrings.kt'
gradle=root/'app/build.gradle.kts'
manifest=root/'app/src/main/AndroidManifest.xml'
cloud=base/'CloudSync.kt'

# v0.9.26: first manual cloud sync bridge shared with Web v0.3.0.
# No database migration: sync uses the existing backup schema and preserves local settings/attachments.

s=settings.read_text()
marker='''    fun isPinProtectionEnabled(): Boolean = prefs.getBoolean("pin_enabled", false) && hasPin()'''
assert marker in s
sync_props='''    var syncProjectId: String
        get() = prefs.getString("sync_project_id", "") ?: ""
        set(v) = prefs.edit().putString("sync_project_id", v.trim()).apply()
    var syncApiKey: String
        get() = prefs.getString("sync_api_key", "") ?: ""
        set(v) = prefs.edit().putString("sync_api_key", v.trim()).apply()
    var syncEmail: String
        get() = prefs.getString("sync_email", "") ?: ""
        set(v) = prefs.edit().putString("sync_email", v.trim()).apply()
    var syncIdToken: String
        get() = prefs.getString("sync_id_token", "") ?: ""
        set(v) = prefs.edit().putString("sync_id_token", v).apply()
    var syncRefreshToken: String
        get() = prefs.getString("sync_refresh_token", "") ?: ""
        set(v) = prefs.edit().putString("sync_refresh_token", v).apply()
    var syncUid: String
        get() = prefs.getString("sync_uid", "") ?: ""
        set(v) = prefs.edit().putString("sync_uid", v).apply()
    var syncTokenExpiry: Long
        get() = prefs.getLong("sync_token_expiry", 0L)
        set(v) = prefs.edit().putLong("sync_token_expiry", v).apply()
    fun clearSyncSession() = prefs.edit()
        .remove("sync_id_token").remove("sync_refresh_token").remove("sync_uid").remove("sync_token_expiry").apply()

'''
s=s.replace(marker,sync_props+marker,1)
settings.write_text(s)

cloud.write_text(r'''package kz.kairat.organizer

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CloudSync {
    private fun request(url:String,method:String="GET",body:String?=null,contentType:String="application/json",token:String?=null):JSONObject{
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            requestMethod=method
            connectTimeout=15000
            readTimeout=25000
            setRequestProperty("Accept","application/json")
            if(token!=null)setRequestProperty("Authorization","Bearer $token")
            if(body!=null){doOutput=true;setRequestProperty("Content-Type",contentType)}
        }
        if(body!=null)c.outputStream.bufferedWriter(Charsets.UTF_8).use{it.write(body)}
        val code=c.responseCode
        val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty()
        val json=if(text.isBlank())JSONObject() else runCatching{JSONObject(text)}.getOrElse{JSONObject().put("raw",text)}
        if(code !in 200..299){
            val msg=json.optJSONObject("error")?.optString("message")?.takeIf{it.isNotBlank()}?:"HTTP $code"
            throw IllegalStateException(msg.replace('_',' '))
        }
        return json
    }
    private fun requireConfig(s:SettingsStore){
        require(s.syncProjectId.isNotBlank()&&s.syncApiKey.isNotBlank()){ "Firebase Project ID / Web API key not configured" }
    }
    private fun saveAuth(s:SettingsStore,email:String,json:JSONObject){
        s.syncEmail=email
        s.syncIdToken=json.optString("idToken")
        s.syncRefreshToken=json.optString("refreshToken")
        s.syncUid=json.optString("localId")
        s.syncTokenExpiry=System.currentTimeMillis()+json.optLong("expiresIn",3600L)*1000L
    }
    fun auth(s:SettingsStore,email:String,password:String,create:Boolean){
        requireConfig(s)
        require(email.isNotBlank()){ "Email is required" }
        require(password.length>=6){ "Password must contain at least 6 characters" }
        val mode=if(create)"signUp" else "signInWithPassword"
        val body=JSONObject().put("email",email.trim()).put("password",password).put("returnSecureToken",true).toString()
        val j=request("https://identitytoolkit.googleapis.com/v1/accounts:$mode?key=${s.syncApiKey}","POST",body)
        saveAuth(s,email.trim(),j)
    }
    private fun token(s:SettingsStore):Pair<String,String>{
        requireConfig(s)
        require(s.syncUid.isNotBlank()){ "Sign in to sync first" }
        if(s.syncIdToken.isNotBlank()&&s.syncTokenExpiry>System.currentTimeMillis()+60000L)return s.syncIdToken to s.syncUid
        require(s.syncRefreshToken.isNotBlank()){ "Session expired. Sign in again." }
        val body="grant_type=refresh_token&refresh_token="+java.net.URLEncoder.encode(s.syncRefreshToken,"UTF-8")
        val j=request("https://securetoken.googleapis.com/v1/token?key=${s.syncApiKey}","POST",body,"application/x-www-form-urlencoded")
        s.syncIdToken=j.optString("id_token")
        s.syncRefreshToken=j.optString("refresh_token",s.syncRefreshToken)
        s.syncUid=j.optString("user_id",s.syncUid)
        s.syncTokenExpiry=System.currentTimeMillis()+j.optLong("expires_in",3600L)*1000L
        return s.syncIdToken to s.syncUid
    }
    private fun documentName(s:SettingsStore,uid:String)="projects/${s.syncProjectId}/databases/(default)/documents/users/$uid/organizer/main"
    private fun documentUrl(s:SettingsStore,uid:String)="https://firestore.googleapis.com/v1/"+documentName(s,uid)
    fun upload(db:NoteDatabase,s:SettingsStore){
        val (idToken,uid)=token(s)
        val backup=JSONObject(BackupManager.createBackup(db,s)).apply{
            remove("attachments")
            remove("settings")
            put("version",7)
            put("cloudSchema","organizer-sync-v1")
            put("createdAt",System.currentTimeMillis())
        }.toString()
        require(backup.toByteArray(Charsets.UTF_8).size<850000){ "Sync payload is too large. Attachments are not uploaded in this version." }
        val fields=JSONObject()
            .put("payload",JSONObject().put("stringValue",backup))
            .put("updatedAt",JSONObject().put("integerValue",System.currentTimeMillis().toString()))
            .put("schema",JSONObject().put("stringValue","organizer-sync-v1"))
        val update=JSONObject().put("name",documentName(s,uid)).put("fields",fields)
        val body=JSONObject().put("writes",org.json.JSONArray().put(JSONObject().put("update",update))).toString()
        request("https://firestore.googleapis.com/v1/projects/${s.syncProjectId}/databases/(default)/documents:commit","POST",body,"application/json",idToken)
    }
    fun download(s:SettingsStore):String{
        val (idToken,uid)=token(s)
        val j=request(documentUrl(s,uid),"GET",null,"application/json",idToken)
        return j.optJSONObject("fields")?.optJSONObject("payload")?.optString("stringValue")?.takeIf{it.isNotBlank()}
            ?: throw IllegalStateException("No Organizer data in cloud yet")
    }
    fun logout(s:SettingsStore){s.clearSyncSession()}
}

private fun syncT(lang:String,key:String):String{
    val ru=AppStrings.normalizeLanguage(lang)=="ru"
    return if(ru) when(key){
        "title"->"Синхронизация"
        "server"->"Сервер Firebase"
        "project"->"Project ID"
        "api"->"Web API key"
        "save"->"Сохранить сервер"
        "email"->"Email"
        "password"->"Пароль"
        "login"->"Войти"
        "register"->"Создать аккаунт"
        "upload"->"Отправить с телефона"
        "download"->"Получить из облака"
        "logout"->"Выйти"
        "configured"->"Сервер сохранён"
        "signed"->"Вход выполнен"
        "registered"->"Аккаунт создан"
        "uploaded"->"Данные телефона отправлены в облако"
        "downloaded"->"Данные получены из облака"
        "working"->"Выполняю…"
        "manual"->"Первая версия синхронизируется вручную. Используйте один email и пароль на телефоне и компьютере."
        "scope"->"Синхронизируются заметки, проекты, этапы, финансы и напоминания. Вложения пока остаются локально."
        "confirm"->"Получение из облака заменит данные Органайзера на телефоне облачной копией. Вложения останутся локально. Продолжить?"
        "yes"->"Получить"
        "cancel"->"Отмена"
        else->key
    } else when(key){
        "title"->"Synchronization"
        "server"->"Firebase server"
        "project"->"Project ID"
        "api"->"Web API key"
        "save"->"Save server"
        "email"->"Email"
        "password"->"Password"
        "login"->"Sign in"
        "register"->"Create account"
        "upload"->"Send from phone"
        "download"->"Get from cloud"
        "logout"->"Sign out"
        "configured"->"Server saved"
        "signed"->"Signed in"
        "registered"->"Account created"
        "uploaded"->"Phone data uploaded"
        "downloaded"->"Cloud data restored"
        "working"->"Working…"
        "manual"->"The first sync version is manual. Use the same email and password on phone and computer."
        "scope"->"Notes, projects, stages, finance and reminders are synced. Attachments stay local for now."
        "confirm"->"Downloading will replace Organizer data on this phone with the cloud copy. Attachments remain local. Continue?"
        "yes"->"Download"
        "cancel"->"Cancel"
        else->key
    }
}

@Composable fun CloudSyncSettingsCard(db:NoteDatabase,settings:SettingsStore,lang:String){
    val context=androidx.compose.ui.platform.LocalContext.current
    val main=remember{Handler(Looper.getMainLooper())}
    var projectId by rememberSaveable{mutableStateOf(settings.syncProjectId)}
    var apiKey by rememberSaveable{mutableStateOf(settings.syncApiKey)}
    var email by rememberSaveable{mutableStateOf(settings.syncEmail)}
    var password by rememberSaveable{mutableStateOf("")}
    var logged by remember{mutableStateOf(settings.syncUid.isNotBlank())}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    var error by remember{mutableStateOf(false)}
    var confirmDownload by remember{mutableStateOf(false)}

    fun runAsync(block:()->String,onDone:(()->Unit)?=null){
        if(busy)return
        busy=true;error=false;status=syncT(lang,"working")
        Thread{
            val r=runCatching(block)
            main.post{
                busy=false
                r.onSuccess{status=it;error=false;onDone?.invoke()}.onFailure{status=it.message?:it.javaClass.simpleName;error=true}
            }
        }.start()
    }

    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(syncT(lang,"title"),style=MaterialTheme.typography.titleMedium)
            Text(syncT(lang,"server"),style=MaterialTheme.typography.bodyMedium)
            OutlinedTextField(projectId,{projectId=it},Modifier.fillMaxWidth(),label={Text(syncT(lang,"project"))},singleLine=true)
            OutlinedTextField(apiKey,{apiKey=it},Modifier.fillMaxWidth(),label={Text(syncT(lang,"api"))},singleLine=true,visualTransformation=PasswordVisualTransformation())
            Button({
                val changed=settings.syncProjectId!=projectId.trim()||settings.syncApiKey!=apiKey.trim()
                settings.syncProjectId=projectId;settings.syncApiKey=apiKey
                if(changed){settings.clearSyncSession();logged=false}
                status=syncT(lang,"configured");error=false
            },Modifier.fillMaxWidth(),enabled=!busy){Text(syncT(lang,"save"))}
            HorizontalDivider()
            if(!logged){
                OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(syncT(lang,"email"))},singleLine=true)
                OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(syncT(lang,"password"))},singleLine=true,visualTransformation=PasswordVisualTransformation())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button({runAsync({CloudSync.auth(settings,email,password,false);syncT(lang,"signed")}){logged=true;password=""}},Modifier.weight(1f),enabled=!busy){Text(syncT(lang,"login"),maxLines=2)}
                    OutlinedButton({runAsync({CloudSync.auth(settings,email,password,true);syncT(lang,"registered")}){logged=true;password=""}},Modifier.weight(1f),enabled=!busy){Text(syncT(lang,"register"),maxLines=2)}
                }
            }else{
                Text(settings.syncEmail,style=MaterialTheme.typography.bodyMedium)
                Button({runAsync({CloudSync.upload(db,settings);syncT(lang,"uploaded")})},Modifier.fillMaxWidth(),enabled=!busy){Text(syncT(lang,"upload"))}
                OutlinedButton({confirmDownload=true},Modifier.fillMaxWidth(),enabled=!busy){Text(syncT(lang,"download"))}
                TextButton({CloudSync.logout(settings);logged=false;status=""},Modifier.fillMaxWidth(),enabled=!busy){Text(syncT(lang,"logout"))}
            }
            if(status.isNotBlank())Text(status,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
            Text(syncT(lang,"manual"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(syncT(lang,"scope"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(confirmDownload)AlertDialog(
        onDismissRequest={confirmDownload=false},
        title={Text(syncT(lang,"download"))},
        text={Text(syncT(lang,"confirm"))},
        confirmButton={Button({
            confirmDownload=false
            runAsync({
                val payload=CloudSync.download(settings)
                BackupManager.restore(payload,db,lang)
                ReminderScheduler.rescheduleAll(context)
                BirthdayScheduler.rescheduleAll(context)
                syncT(lang,"downloaded")
            }){(context as? Activity)?.recreate()}
        }){Text(syncT(lang,"yes"))}},
        dismissButton={TextButton({confirmDownload=false}){Text(syncT(lang,"cancel"))}}
    )
}
''')

u=ui.read_text()
info='''        item{SettingsCard("",verticalPadding=infoCardPad,spacing=if(compactSettings)1.dp else 2.dp){'''
assert info in u
u=u.replace(info,'        item{CloudSyncSettingsCard(db,settings,lang)}\n'+info,1)
ui.write_text(u)

m=manifest.read_text()
if 'android.permission.INTERNET' not in m:
    i=m.index('>')+1
    m=m[:i]+'\n    <uses-permission android:name="android.permission.INTERNET" />'+m[i:]
manifest.write_text(m)

b=gradle.read_text()
assert 'versionCode = 58' in b and 'versionName = "0.9.25"' in b
b=b.replace('versionCode = 58','versionCode = 59').replace('versionName = "0.9.25"','versionName = "0.9.26"')
gradle.write_text(b)

a=strings.read_text()
assert '0.9.25 (58)' in a
a=a.replace('0.9.25 (58)','0.9.26 (59)')
strings.write_text(a)

# Gates: preserve app identity/database and previous features.
assert cloud.exists()
assert 'identitytoolkit.googleapis.com' in cloud.read_text()
assert 'firestore.googleapis.com' in cloud.read_text()
assert 'CloudSyncSettingsCard(db,settings,lang)' in ui.read_text()
assert 'android.permission.INTERNET' in manifest.read_text()
assert 'versionCode = 59' in gradle.read_text() and 'versionName = "0.9.26"' in gradle.read_text()
assert '0.9.26 (59)' in strings.read_text()
