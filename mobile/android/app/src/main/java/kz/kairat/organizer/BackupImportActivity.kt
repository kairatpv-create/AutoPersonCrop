package kz.kairat.organizer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens a mail/file attachment. Reading and confirmation never replace existing records. */
class BackupImportActivity:ComponentActivity() {
    private var settings:SettingsStore?=null
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val store=try{SettingsStore(this).also{it.ensureSecureStorage()}}catch(_:Exception){
            android.app.AlertDialog.Builder(this)
                .setMessage("Не удалось открыть защищённые настройки. Данные сохранены. / Could not open protected settings. Your data has been kept.")
                .setPositiveButton("OK"){_,_->finish()}.setOnCancelListener{finish()}.show()
            return
        }
        settings=store;unlocked=!store.isPinProtectionEnabled()
        val uri=when(intent.action){
            Intent.ACTION_VIEW->intent.data
            Intent.ACTION_SEND->intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else->null
        }?.takeIf{it.scheme=="content"}
        setContent{
            OrganizerTheme(store.darkTheme,store.fontScale,store.buttonFontScale,store.boldText){
                ImportAttachment(store,uri,unlocked,{unlocked=true},{finish()}){
                    startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            }
        }
    }
    override fun onResume(){
        super.onResume()
        if(settings?.isPinProtectionEnabled()==true)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onNewIntent(newIntent:Intent){
        super.onNewIntent(newIntent)
        // Mail/file apps may reuse the top activity. Never keep the previous attachment preview.
        setIntent(newIntent)
        recreate()
    }
    override fun onPause(){
        if(settings?.isPinProtectionEnabled()==true)unlocked=false
        super.onPause()
    }
}

@Composable private fun ImportAttachment(settings:SettingsStore,uri:Uri?,unlocked:Boolean,onUnlock:()->Unit,onCancel:()->Unit,onOpen:()->Unit){
    val context=androidx.compose.ui.platform.LocalContext.current
    val lang=settings.language
    val scope=rememberCoroutineScope()
    var json by remember{mutableStateOf<String?>(null)}
    var summary by remember{mutableStateOf("")}
    var error by remember{mutableStateOf<String?>(null)}
    var restoring by remember{mutableStateOf(false)}
    var restored by remember{mutableStateOf(false)}
    LaunchedEffect(unlocked){
        if(unlocked&&json==null&&error==null){
            val result=runCatching{withContext(Dispatchers.IO){
                requireNotNull(uri){SyncSnapshot.message(lang,"Вложение недоступно. Сохраните его из письма и выберите файл в настройках.","The attachment is unavailable. Save it from the email and choose the file in Settings.")}
                val text=BackupManager.readFromUri(context,uri)
                val root=BackupValidation.parse(text,lang)
                text to SyncSnapshot.message(lang,
                    "Заметок: ${root.getJSONArray("notes").length()}, проектов: ${root.getJSONArray("projects").length()}, финансовых записей: ${root.getJSONArray("money").length()}.",
                    "Notes: ${root.getJSONArray("notes").length()}, projects: ${root.getJSONArray("projects").length()}, financial entries: ${root.getJSONArray("money").length()}.")
            }}
            result.onSuccess{json=it.first;summary=it.second}.onFailure{
                if(it is kotlinx.coroutines.CancellationException)throw it
                error=it.message?:AppStrings.t(lang,"error")
            }
        }
    }
    if(!unlocked){UnlockScreen(lang,settings,onUnlock);return}
    Surface(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text(AppStrings.t(lang,"app"),style=MaterialTheme.typography.headlineSmall)
            Text(SyncSnapshot.message(lang,"Восстановление из копии","Restore from backup"),style=MaterialTheme.typography.titleLarge)
            when{
                restored->{
                    Text(AppStrings.t(lang,"restore_ok"))
                    Button(onClick=onOpen){Text(SyncSnapshot.message(lang,"Открыть Органайзер","Open Organizer"))}
                }
                error!=null->{
                    Text(SyncSnapshot.message(lang,"Не удалось восстановить копию","Could not restore the backup"),color=MaterialTheme.colorScheme.error)
                    Text(error!!)
                    Button(onClick=onCancel){Text(AppStrings.t(lang,"cancel"))}
                }
                restoring->{CircularProgressIndicator();Text(SyncSnapshot.message(lang,"Восстановление…","Restoring…"))}
                json==null->{CircularProgressIndicator();Text(SyncSnapshot.message(lang,"Проверка копии…","Checking backup…"))}
                else->{
                    Text(summary)
                    Text(SyncSnapshot.message(lang,
                        "Текущие записи будут заменены данными из этой копии. Несохранённые изменения открытого редактора будут закрыты. Продолжить восстановление?",
                        "Your current records will be replaced with this backup. Unsaved changes in an open editor will be closed. Restore this backup?"))
                    Button(onClick={
                        restoring=true
                        scope.launch{
                            val result=runCatching{withContext(Dispatchers.IO){
                                AttachmentStore.initialize(context)
                                val db=NoteDatabase(context)
                                try{
                                    val p=BackupManager.restore(checkNotNull(json),db,lang)
                                    check(settings.applyRestoredPreferences(p))
                                    ReminderScheduler.rescheduleAll(context)
                                }finally{db.close()}
                            }}
                            restoring=false
                            result.onSuccess{restored=true}.onFailure{
                                if(it is kotlinx.coroutines.CancellationException)throw it
                                error=it.message?:AppStrings.t(lang,"error")
                            }
                        }
                    }){Text(SyncSnapshot.message(lang,"Восстановить копию","Restore backup"))}
                    OutlinedButton(onClick=onCancel){Text(AppStrings.t(lang,"cancel"))}
                }
            }
        }
    }
}
