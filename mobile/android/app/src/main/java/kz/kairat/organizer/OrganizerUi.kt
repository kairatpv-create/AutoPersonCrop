@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package kz.kairat.organizer

import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity
import android.app.LocaleManager
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.speech.RecognizerIntent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun OrganizerApp(pinRequest:String=""){
    val context=LocalContext.current
    val db=remember{NoteDatabase(context)}; val store=remember{SettingsStore(context)}
    var dark by rememberSaveable{mutableStateOf(store.darkTheme)}; var font by rememberSaveable{mutableFloatStateOf(store.fontScale)}; var buttonFont by rememberSaveable{mutableFloatStateOf(store.buttonFontScale)}; var bold by rememberSaveable{mutableStateOf(store.boldText)}; var lang by rememberSaveable{mutableStateOf(store.language)}
    var unlocked by remember{mutableStateOf(!store.isPinProtectionEnabled())}
    var handledPinRequest by remember{mutableStateOf("")}
    LaunchedEffect(Unit){syncAndroidAppLocale(context,lang)}
    OrganizerTheme(dark,font,buttonFont,bold){
        if(!unlocked||(store.isPinProtectionEnabled()&&pinRequest.isNotBlank()&&handledPinRequest!=pinRequest)) UnlockScreen(lang,store){unlocked=true;handledPinRequest=pinRequest}
        else OrganizerHome(db,store,lang,onLanguage={newLang->store.language=newLang;lang=newLang;syncAndroidAppLocale(context,newLang)},dark=dark,onDark={dark=it;store.darkTheme=it},fontScale=font,onFont={font=it;store.fontScale=it},buttonScale=buttonFont,onButtonScale={buttonFont=it;store.buttonFontScale=it},bold=bold,onBold={bold=it;store.boldText=it},onLock={unlocked=false})
    }
}

@Composable private fun UnlockScreen(lang:String,store:SettingsStore,onUnlock:()->Unit){
    var pin by remember{mutableStateOf("")};var error by remember{mutableStateOf(false)}
    var retry by remember{mutableLongStateOf(store.pinRetrySeconds())}
    LaunchedEffect(Unit){while(true){retry=store.pinRetrySeconds();delay(1000)}}
    Box(Modifier.fillMaxSize().padding(28.dp),contentAlignment=Alignment.Center){Card{Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text(AppStrings.t(lang,"app"),style=MaterialTheme.typography.titleLarge);Text(AppStrings.t(lang,"unlock"));OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(12)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation(),singleLine=true);if(error)Text(if(retry>0) (if(lang=="ru") "Повторите через $retry с" else "Try again in $retry s") else AppStrings.t(lang,"wrong_pin"),color=MaterialTheme.colorScheme.error);Button({if(store.verifyPin(pin))onUnlock()else {error=true;retry=store.pinRetrySeconds()}},Modifier.fillMaxWidth(),enabled=retry==0L){Text(AppStrings.t(lang,"confirm"))}}}}
}

@Composable private fun OrganizerHome(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,buttonScale:Float,onButtonScale:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,onLock:()->Unit){
    val context=LocalContext.current
    CloudSyncAutoHost(db,settings,lang)
    var section by rememberSaveable{mutableStateOf("notes")}
    var editorNoteId by rememberSaveable{mutableStateOf<Long?>(null)}
    var newEditor by rememberSaveable{mutableStateOf(false)}
    var readerId by rememberSaveable{mutableStateOf<Long?>(null)}
    var projectId by rememberSaveable{mutableStateOf<Long?>(null)}
    var projectInitialTab by rememberSaveable{mutableIntStateOf(0)}
    var showCalendar by rememberSaveable{mutableStateOf(false)}
    var calendarPermissionRevision by remember{mutableIntStateOf(0)}
    val hasCalendarPermission=remember(calendarPermissionRevision){OrganizerCalendarRepository.hasCalendarPermission(context)}
    val calendarPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        calendarPermissionRevision++
        if(granted)BirthdayScheduler.rescheduleAll(context)
    }
    var showNewProject by rememberSaveable{mutableStateOf(false)}
    var projectRevision by remember{mutableIntStateOf(0)}
    var showReminders by rememberSaveable{mutableStateOf(false)}
    var addMoneyType by rememberSaveable{mutableStateOf<String?>(null)}
    var moneyTab by rememberSaveable{mutableIntStateOf(0)}
    var moneyRevision by remember{mutableIntStateOf(0)}
    var projectsEnabled by rememberSaveable{mutableStateOf(settings.projectsEnabled)}
    val visibleSections=if(projectsEnabled) listOf("notes","projects","money","settings") else listOf("notes","money","settings")
    val swipeDensity=LocalDensity.current.density
    fun switchSectionBySwipe(direction:Int){
        val current=visibleSections.indexOf(section).coerceAtLeast(0)
        val next=(current+direction).coerceIn(0,visibleSections.lastIndex)
        if(next!=current)section=visibleSections[next]
    }
    LaunchedEffect(projectsEnabled){if(!projectsEnabled&&section=="projects")section="notes"}
    if(newEditor||editorNoteId!=null){ val n=editorNoteId?.let{db.getNote(it)}; NoteEditor(db,n,lang,onClose={newEditor=false;editorNoteId=null},onSaved={id->newEditor=false;editorNoteId=null;readerId=id});return }
    readerId?.let{id->db.getNote(id)?.let{n->NoteReader(db,n,lang,onBack={readerId=null},onEdit={editorNoteId=n.id},onDeleted={readerId=null})};return}
    projectId?.let{id->ProjectDetailScreen(db,id,lang,projectInitialTab,onBack={projectId=null;projectInitialTab=0;projectRevision++},onDeleted={projectId=null;projectInitialTab=0;projectRevision++});return}
    if(showReminders){RemindersScreen(db,lang){showReminders=false};return}
    if(showNewProject){
        AddProjectDialog(
            lang=lang,
            onDismiss={showNewProject=false},
            onAdd={name,description,colorKey->
                val id=db.addProject(name,description,colorKey)
                showNewProject=false
                projectRevision++
                projectInitialTab=0
                projectId=id
            }
        )
        return
    }
    addMoneyType?.let{fixedType->
        AddMoneyDialog(
            db=db,
            lang=lang,
            fixedType=fixedType,
            onDismiss={addMoneyType=null},
            onAdd={amount,cat,note,colorKey->
                db.addMoneyEntry(fixedType,amount,cat,null,note,colorKey)
                moneyRevision++
                addMoneyType=null
            }
        )
        return
    }
    Scaffold(
        topBar={
            Surface(color=MaterialTheme.colorScheme.secondaryContainer,shadowElevation=2.dp){
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal=8.dp,vertical=(6f*buttonScale.coerceIn(.85f,1.45f)).dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),
                    verticalAlignment=Alignment.CenterVertically
                ){

                    if(section!="settings") TopIconAction(Icons.Default.CalendarMonth){
                        showCalendar=true
                        if(hasCalendarPermission)BirthdayScheduler.rescheduleAll(context) else calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }
                    if(section!="settings") TopActionPill(AppStrings.t(lang,"reminders"),Icons.Default.Notifications,{showReminders=true},wide=(section=="money" && moneyTab==0))
                    when(section){
                        "notes" -> TopActionPill(AppStrings.t(lang,"create"),Icons.Default.Add,{newEditor=true})
                        "projects" -> TopActionPill(AppStrings.t(lang,"create"),Icons.Default.Add,{showNewProject=true})
                        "money" -> when(moneyTab){
                            1->TopActionPill(AppStrings.t(lang,"create"),Icons.Default.Add,{addMoneyType="income"})
                            2->TopActionPill(AppStrings.t(lang,"create"),Icons.Default.Add,{addMoneyType="expense"})
                            else->Unit
                        }
                        "settings" -> if(settings.hasPin()) TopActionPill(AppStrings.t(lang,"lock_action"),Icons.Default.Lock,onLock)
                    }
                }
            }
        },
        bottomBar={
            Surface(color=MaterialTheme.colorScheme.secondaryContainer,tonalElevation=3.dp,shadowElevation=3.dp,modifier=Modifier.fillMaxWidth().navigationBarsPadding()){
                Row(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=(4f*buttonScale.coerceIn(.85f,1.45f)).dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically){
                    BottomTextNavPill(section=="notes",AppStrings.t(lang,"notes"),{section="notes"},Modifier.weight(1f))
                    if(projectsEnabled) BottomTextNavPill(section=="projects",AppStrings.t(lang,"projects"),{section="projects"},Modifier.weight(1f))
                    BottomTextNavPill(section=="money",AppStrings.t(lang,"money"),{section="money"},Modifier.weight(1f))
                    BottomTextNavPill(section=="settings",AppStrings.t(lang,"settings"),{section="settings"},Modifier.weight(1f))
                }
            }
        }
    ){pad->Box(
        Modifier.padding(pad).fillMaxSize().pointerInput(section,projectsEnabled){
            awaitEachGesture{
                val down=awaitFirstDown(requireUnconsumed=false)
                val start=down.position
                var end=start
                while(true){
                    val event=awaitPointerEvent()
                    val change=event.changes.firstOrNull{it.id==down.id}?:break
                    end=change.position
                    if(!change.pressed)break
                }
                val dx=end.x-start.x
                val dy=end.y-start.y
                if(kotlin.math.abs(dx)>110f*swipeDensity && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.35f){
                    if(dx<0) switchSectionBySwipe(1) else switchSectionBySwipe(-1)
                }
            }
        }
    ){when(section){
        "projects"->if(projectsEnabled) ProjectsScreen(db,lang,projectRevision,onOpen={projectInitialTab=0;projectId=it}) else NotesScreen(db,lang,onOpen={readerId=it})
        "money"->MoneyScreen(db,lang,moneyRevision,moneyTab){moneyTab=it}
        "settings"->SettingsScreen(db,settings,lang,onLanguage,dark,onDark,fontScale,onFont,buttonScale,onButtonScale,bold,onBold,projectsEnabled){enabled->settings.projectsEnabled=enabled;projectsEnabled=enabled;if(!enabled&&section=="projects")section="notes"}
        else->NotesScreen(db,lang,onOpen={readerId=it})
    }}}
    if(showCalendar)OrganizerCalendarPopup(
        db=db,lang=lang,hasSystemCalendarAccess=hasCalendarPermission,refreshKey=calendarPermissionRevision,
        onRequestCalendarAccess={calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)},
        onDismiss={showCalendar=false},
        onOpenProject={id,stages->showCalendar=false;projectInitialTab=if(stages)1 else 0;projectId=id}
    )
}

@Composable private fun NotesScreen(db:NoteDatabase,lang:String,onOpen:(Long)->Unit){
    var q by rememberSaveable{mutableStateOf("")};var refresh by remember{mutableIntStateOf(0)};val notes=remember(q,refresh){db.getNotes(q)}
    Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp)){
        OutlinedTextField(q,{q=it},Modifier.fillMaxWidth().height(48.dp),leadingIcon={Icon(Icons.Default.Search,null,Modifier.size(20.dp))},placeholder={Text(AppStrings.t(lang,"search"),style=MaterialTheme.typography.bodyMedium)},singleLine=true,colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.primaryContainer,unfocusedContainerColor=MaterialTheme.colorScheme.primaryContainer))
        Spacer(Modifier.height(5.dp))
        if(notes.isEmpty()) EmptyState(AppStrings.t(lang,"no_notes"))
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=4.dp)){
            items(notes,key={it.id}){n->Card(Modifier.fillMaxWidth().clickable{onOpen(n.id)},colors=noteCardColors(n.colorKey)){Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(1.dp)){Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=userTextStyle(15f,true));if(n.body.isNotBlank())Text(n.body,maxLines=3,overflow=TextOverflow.Ellipsis,style=userTextStyle());Text(formatTime(n.updatedAt,lang),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
        }
    }
}

@Composable private fun NoteReader(db:NoteDatabase,note:Note,lang:String,onBack:()->Unit,onEdit:()->Unit,onDeleted:()->Unit){
    val context=LocalContext.current
    var refresh by remember{mutableIntStateOf(0)}
    var showReminderEditor by remember{mutableStateOf(false)}
    val atts=remember(refresh){AttachmentStore.list("note",note.id)}
    BackHandler{onBack()}
    Scaffold(topBar={TopAppBar(title={Text(note.title.ifBlank{AppStrings.t(lang,"note")},maxLines=1,style=userTextStyle(15f,true))},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,null)}},actions={IconButton(onEdit){Icon(Icons.Default.Edit,null)};IconButton({ReminderScheduler.cancelAllForTarget(context,"note",note.id,db);db.delete(note.id);AttachmentStore.deleteTarget("note",note.id);onDeleted()}){Icon(Icons.Default.Delete,null)}},colors=organizerTopBarColors())}){pad->
        LazyColumn(Modifier.padding(pad).padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            item{Text(note.body,style=userTextStyle())}
            val voices=atts.filter{it.isVoice}; if(voices.isNotEmpty()){items(voices,key={it.id}){VoiceNoteBlock(it,lang)}}
            val files=atts.filterNot{it.isVoice};if(files.isNotEmpty()){item{Text(AppStrings.t(lang,"attachment"),style=MaterialTheme.typography.titleMedium)};item{AttachmentStrip(files,lang){refresh++}}}
            item{OutlinedButton({showReminderEditor=true},Modifier.fillMaxWidth()){Icon(Icons.Default.Notifications,null);Spacer(Modifier.width(8.dp));Text(AppStrings.t(lang,"remind"))}}
        }
    }
    if(showReminderEditor) ReminderEditorDialog(db,lang,"note",note.id,null,{showReminderEditor=false}){showReminderEditor=false;toast(context,AppStrings.t(lang,"reminder_saved"))}
}

@Composable private fun NoteEditor(db:NoteDatabase,note:Note?,lang:String,onClose:()->Unit,onSaved:(Long)->Unit){
    val context=LocalContext.current;var title by rememberSaveable(note?.id){mutableStateOf(note?.title.orEmpty())};var body by rememberSaveable(note?.id){mutableStateOf(note?.body.orEmpty())};var colorKey by rememberSaveable(note?.id){mutableStateOf(note?.colorKey ?: "blue")};val tempId=remember(note?.id){note?.id?:-System.currentTimeMillis()};var refresh by remember{mutableIntStateOf(0)};val atts=remember(refresh){AttachmentStore.list("note",tempId)}
    val recorder=remember{VoiceRecorder(context)};var recording by remember{mutableStateOf(false)};var elapsed by remember{mutableLongStateOf(0L)};var startedAt by remember{mutableLongStateOf(0L)}
    var attachmentMenu by remember{mutableStateOf(false)};var pendingCameraFile by remember{mutableStateOf<File?>(null)}
    val speechLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        if(result.resultCode==Activity.RESULT_OK){
            val spoken=result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if(spoken.isNotEmpty()) body=appendRecognizedText(body,spoken)
        }
    }
    DisposableEffect(Unit){onDispose{if(recording)recorder.cancel()}}
    LaunchedEffect(recording){while(recording){elapsed=android.os.SystemClock.elapsedRealtime()-startedAt;delay(200)}}
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{runCatching{AttachmentStore.addFromUri("note",tempId,it)}.onSuccess{refresh++}.onFailure{e->toast(context,e.message?:AppStrings.t(lang,"error"))}}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->val f=pendingCameraFile;if(ok&&f!=null){runCatching{AttachmentStore.addLocalFile("note",tempId,f,f.name,"image/jpeg",deleteSource=true)}.onSuccess{refresh++}}else f?.delete();pendingCameraFile=null}
    fun startRecording(){runCatching{recorder.start();startedAt=android.os.SystemClock.elapsedRealtime();elapsed=0;recording=true}.onFailure{toast(context,it.message?:AppStrings.t(lang,"error"))}}
    fun stopRecording(){val v=recorder.stop();recording=false;if(v!=null){AttachmentStore.addLocalFile("note",tempId,v.file,"${AppStrings.t(lang,"voice_file")}_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())}.m4a","audio/mp4",v.durationMs,true);refresh++}else toast(context,AppStrings.t(lang,"record_failed"))}
    val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)startRecording()else toast(context,AppStrings.t(lang,"permission_mic"))}
    fun startSpeechToText(){
        runCatching{
            val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,AppStrings.localeTag(lang))
                putExtra(RecognizerIntent.EXTRA_PROMPT,AppStrings.t(lang,"speech_to_text"))
            }
            speechLauncher.launch(intent)
        }.onFailure{toast(context,it.message?:AppStrings.t(lang,"error"))}
    }
    fun save(){if(recording)stopRecording();val id=db.save(note?.id,title.trim(),body.trim(),colorKey);if(tempId<0)AttachmentStore.reassignTarget("note",tempId,id);onSaved(id)}
    fun close(){if(recording)recorder.cancel();if(note==null)AttachmentStore.deleteTarget("note",tempId);onClose()}
    BackHandler{close()}
    Scaffold(topBar={TopAppBar(title={Text(AppStrings.t(lang,if(note==null)"new_note" else "edit_note"))},navigationIcon={if(note!=null){IconButton({close()}){Icon(Icons.Default.ArrowBack,null)}}},actions={if(note!=null)IconButton({ReminderScheduler.cancelAllForTarget(context,"note",note.id,db);db.delete(note.id);AttachmentStore.deleteTarget("note",note.id);onClose()}){Icon(Icons.Default.Delete,null)};TopColorMenu(colorKey){colorKey=it}},colors=organizerTopBarColors())}){pad->
        LazyColumn(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            if(note!=null)item{Text("${AppStrings.t(lang,"created")}: ${formatDate(note.createdAt,lang)}\n${AppStrings.t(lang,"changed")}: ${formatDate(note.updatedAt,lang)}",style=MaterialTheme.typography.bodySmall)}
            item{SuggestionTextField(
                value=title,
                onValueChange={title=capitalizeSentences(it)},
                label=AppStrings.t(lang,"title"),
                suggestions=db.getNoteTitleSuggestions(title),
                onSuggestion={title=it},
                onDeleteSuggestion={db.hideSuggestion("note_title",it)},
                modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)
            )}
            item{OutlinedTextField(body,{body=capitalizeSentences(it)},Modifier.fillMaxWidth().heightIn(min=220.dp),label={Text(AppStrings.t(lang,"note"))},placeholder={Text(AppStrings.t(lang,"note_hint"))},textStyle=userTextStyle(),keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences))}
            val voices=atts.filter{it.isVoice};if(voices.isNotEmpty())items(voices,key={it.id}){VoiceNoteBlock(it,lang,onDelete={AttachmentStore.delete(it);refresh++})}
            val files=atts.filterNot{it.isVoice};if(files.isNotEmpty())item{AttachmentStrip(files,lang){refresh++}}
            item{
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                    Box(Modifier.weight(1f)){
                        OutlinedButton(
                            onClick={attachmentMenu=true},
                            modifier=Modifier.fillMaxWidth().heightIn(min=36.dp),
                            contentPadding=PaddingValues(horizontal=6.dp)
                        ){
                            Icon(Icons.Default.AttachFile,null,Modifier.size(18.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(AppStrings.t(lang,"attachment"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        DropdownMenu(attachmentMenu,{attachmentMenu=false}){DropdownMenuItem({Text(AppStrings.t(lang,"file"))},{attachmentMenu=false;filePicker.launch(arrayOf("image/*","application/pdf","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/plain"))},leadingIcon={Icon(Icons.Default.InsertDriveFile,null)});DropdownMenuItem({Text(AppStrings.t(lang,"camera"))},{attachmentMenu=false;val(f,u)=AttachmentStore.createCameraTarget();pendingCameraFile=f;camera.launch(u)},leadingIcon={Icon(Icons.Default.PhotoCamera,null)})}
                    }
                    OutlinedButton(
                        onClick={ startSpeechToText() },
                        modifier=Modifier.weight(1f).heightIn(min=36.dp),
                        contentPadding=PaddingValues(horizontal=4.dp)
                    ){
                        Icon(Icons.Default.KeyboardVoice,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(AppStrings.t(lang,"to_text"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Button(
                        onClick={if(recording){stopRecording()} else {if(androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording()else micPermission.launch(Manifest.permission.RECORD_AUDIO)}},
                        modifier=Modifier.weight(1f).heightIn(min=36.dp),
                        contentPadding=PaddingValues(horizontal=4.dp),
                        colors=if(recording)ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)else ButtonDefaults.buttonColors()
                    ){
                        Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(if(recording)"${AppStrings.t(lang,"recording")} ${humanDuration(elapsed)}" else AppStrings.t(lang,"microphone"),modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=0,initialDelayMillis=0),maxLines=1,softWrap=false,overflow=TextOverflow.Clip,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            item{Button({save()},Modifier.fillMaxWidth().heightIn(min=38.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=0.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)){Icon(Icons.Default.Save,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(AppStrings.t(lang,"save"),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
        }
    }
}

@Composable private fun TopColorMenu(selected:String,onSelected:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    val colors=listOf("blue","yellow","green","orange","pink","purple","red","bright_yellow","cyan")
    val selectedColor=noteColor(selected)
    Box{
        Surface(
            color=MaterialTheme.colorScheme.surface.copy(alpha=.97f),
            shape=RoundedCornerShape(12.dp),
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.onPrimary.copy(alpha=.30f)),
            tonalElevation=3.dp,
            shadowElevation=2.dp,
            modifier=Modifier.size(42.dp).clickable{expanded=true}
        ){
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                Canvas(Modifier.size(30.dp)){
                    val r=3.2.dp.toPx()
                    drawCircle(Color(0xFFFF3B30),r,Offset(size.width*.22f,size.height*.24f))
                    drawCircle(Color(0xFFFFD60A),r,Offset(size.width*.78f,size.height*.24f))
                    drawCircle(Color(0xFF00CFE8),r,Offset(size.width*.22f,size.height*.76f))
                    drawCircle(Color(0xFF8A5CF6),r,Offset(size.width*.78f,size.height*.76f))
                    drawCircle(selectedColor,7.2.dp.toPx(),Offset(size.width*.50f,size.height*.50f))
                    drawCircle(Color.White.copy(alpha=.92f),7.2.dp.toPx(),Offset(size.width*.50f,size.height*.50f),style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.4.dp.toPx()))
                }
            }
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
            Column(Modifier.padding(7.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                colors.chunked(3).forEach{row->
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        row.forEach{key->
                            val c=noteColor(key)
                            Box(Modifier.size(36.dp),contentAlignment=Alignment.Center){
                                Surface(
                                    color=c,
                                    shape=RoundedCornerShape(9.dp),
                                    border=if(selected==key)BorderStroke(2.5.dp,MaterialTheme.colorScheme.primary)else BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.35f)),
                                    tonalElevation=1.dp,
                                    shadowElevation=if(selected==key)2.dp else 0.dp,
                                    modifier=Modifier.size(32.dp).clickable{onSelected(key);expanded=false}
                                ){
                                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                                        if(selected==key)Icon(Icons.Default.Check,null,tint=if(c.luminance()<.5f)Color.White else Color.Black,modifier=Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun VoiceNoteBlock(entry:AttachmentEntry,lang:String,onDelete:(()->Unit)?=null){
    val context=LocalContext.current;var playing by remember(entry.id){mutableStateOf(false)};var progress by remember(entry.id){mutableFloatStateOf(0f)};var player by remember(entry.id){mutableStateOf<MediaPlayer?>(null)}
    DisposableEffect(entry.id){onDispose{player?.release()}}
    LaunchedEffect(playing){while(playing){val p=player;if(p!=null&&p.duration>0)progress=(p.currentPosition.toFloat()/p.duration).coerceIn(0f,1f);delay(100)}}
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=6.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({if(playing){player?.pause();playing=false}else{var p=player;if(p==null){p=MediaPlayer().apply{setDataSource(AttachmentStore.file(entry).absolutePath);prepare();setOnCompletionListener{playing=false;progress=0f;seekTo(0)}};player=p};p.start();playing=true}},modifier=Modifier.size(40.dp)){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,AppStrings.t(lang,"audio"))}
            Waveform(progress,entry.id,Modifier.widthIn(min=72.dp,max=120.dp))
            Spacer(Modifier.width(8.dp))
            Text(humanDuration(if(entry.durationMs>0)entry.durationMs else player?.duration?.toLong()?:0L),style=MaterialTheme.typography.bodySmall)
            if(onDelete!=null)IconButton(onDelete,modifier=Modifier.size(40.dp)){Icon(Icons.Default.Close,null)}
        }
    }
}
@Composable private fun Waveform(progress:Float,seed:Long,modifier:Modifier=Modifier){
    val played=MaterialTheme.colorScheme.primary
    val unplayed=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.45f)
    Canvas(modifier.fillMaxWidth().height(22.dp)){val bars=36;val gap=size.width/bars;for(i in 0 until bars){val v=0.25f+(((i*37+seed.toInt()*13)and 15)/20f);val h=size.height*v.coerceIn(.2f,1f);drawLine(color=if(i.toFloat()/bars<=progress)played else unplayed,start=Offset(i*gap,size.height/2-h/2),end=Offset(i*gap,size.height/2+h/2),strokeWidth=2.5.dp.toPx(),cap=StrokeCap.Round)}}
}

private fun decodeAttachmentThumbnail(file:File):android.graphics.Bitmap?{
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeFile(file.absolutePath,bounds)
    if(bounds.outWidth<=0||bounds.outHeight<=0)return null
    var sample=1
    while(bounds.outWidth/sample>320||bounds.outHeight/sample>320)sample*=2
    return BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample})
}

@Composable private fun AttachmentStrip(files:List<AttachmentEntry>,lang:String,onChanged:()->Unit){
    LazyRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(end=4.dp)){
        items(files,key={it.id}){e->AttachmentTile(e,lang,onChanged)}
    }
}

@Composable private fun AttachmentTile(e:AttachmentEntry,lang:String,onChanged:()->Unit){
    val isImage=e.mimeType.startsWith("image/")
    val bitmap=remember(e.id,e.size){if(isImage)runCatching{decodeAttachmentThumbnail(AttachmentStore.file(e))}.getOrNull() else null}
    Card(Modifier.width(58.dp),shape=RoundedCornerShape(10.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){
        Column(Modifier.padding(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
            Box(Modifier.fillMaxWidth().heightIn(min=38.dp).clickable{runCatching{AttachmentStore.open(e)}},contentAlignment=Alignment.Center){
                if(bitmap!=null)Image(bitmap.asImageBitmap(),e.displayName,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                else Icon(if(e.mimeType.contains("pdf",true))Icons.Default.PictureAsPdf else Icons.Default.Description,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
            }
            Text(e.displayName,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                IconButton({AttachmentStore.share(e)},modifier=Modifier.size(22.dp)){Icon(Icons.Default.Share,null,Modifier.size(12.dp))}
                IconButton({AttachmentStore.delete(e);onChanged()},modifier=Modifier.size(22.dp)){Icon(Icons.Default.Delete,null,Modifier.size(12.dp))}
            }
        }
    }
}

@Composable private fun AttachmentRow(e:AttachmentEntry,lang:String,onChanged:()->Unit){AttachmentStrip(listOf(e),lang,onChanged)}

@Composable private fun TasksScreen(db:NoteDatabase,lang:String){
    var refresh by remember{mutableIntStateOf(0)}
    var add by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<TaskItem?>(null)}
    val tasks=remember(refresh){db.getTasks()}
    Box(Modifier.fillMaxSize()){
        if(tasks.isEmpty()) EmptyState(AppStrings.t(lang,"no_tasks"))
        else LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(tasks,key={it.id}){t->
                Card(Modifier.fillMaxWidth().clickable{selected=t}){
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                        Checkbox(t.done,{db.setTaskDone(t.id,it);refresh++})
                        Column(Modifier.weight(1f)){Text(t.title,style=userTextStyle(15f,true));if(t.details.isNotBlank())Text(t.details,style=userTextStyle());val count=AttachmentStore.count("task",t.id);if(count>0)Text("📎 $count",style=MaterialTheme.typography.bodySmall)}
                        IconButton({db.deleteTask(t.id);AttachmentStore.deleteTarget("task",t.id);refresh++}){Icon(Icons.Default.Delete,null)}
                    }
                }
            }
        }
        FloatingActionButton({add=true},Modifier.align(Alignment.BottomEnd).padding(18.dp)){Icon(Icons.Default.Add,null)}
    }
    if(add) AddTaskDialog(lang,{add=false}){a,b->db.addTask(a,b);refresh++;add=false}
    selected?.let{task->TaskDetailDialog(task,db,lang,onDismiss={selected=null},onChanged={refresh++})}
}

@Composable private fun TaskDetailDialog(task:TaskItem,db:NoteDatabase,lang:String,onDismiss:()->Unit,onChanged:()->Unit){
    val context=LocalContext.current
    var refresh by remember{mutableIntStateOf(0)}
    var pendingCameraFile by remember{mutableStateOf<File?>(null)}
    var showReminderEditor by remember{mutableStateOf(false)}
    val atts=remember(refresh){AttachmentStore.list("task",task.id)}
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{runCatching{AttachmentStore.addFromUri("task",task.id,it)}.onSuccess{refresh++;onChanged()}.onFailure{e->toast(context,e.message?:AppStrings.t(lang,"error"))}}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->val f=pendingCameraFile;if(ok&&f!=null){runCatching{AttachmentStore.addLocalFile("task",task.id,f,f.name,"image/jpeg",deleteSource=true)}.onSuccess{refresh++;onChanged()}}else f?.delete();pendingCameraFile=null}
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(task.title,style=userTextStyle(15f,true))},
        text={LazyColumn(Modifier.heightIn(max=520.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(task.details.isNotBlank())item{Text(task.details,style=userTextStyle())}
            val voices=atts.filter{it.isVoice};if(voices.isNotEmpty())items(voices,key={it.id}){a->VoiceNoteBlock(a,lang,{AttachmentStore.delete(a);refresh++;onChanged()})}
            val files=atts.filterNot{it.isVoice};if(files.isNotEmpty())item{AttachmentStrip(files,lang){refresh++;onChanged()}}
            item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({filePicker.launch(arrayOf("image/*","application/pdf","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/plain"))},Modifier.weight(1f)){Icon(Icons.Default.AttachFile,null);Spacer(Modifier.width(4.dp));Text(AppStrings.t(lang,"file"))}
                OutlinedButton({val(f,u)=AttachmentStore.createCameraTarget();pendingCameraFile=f;camera.launch(u)},Modifier.weight(1f)){Icon(Icons.Default.PhotoCamera,null);Spacer(Modifier.width(4.dp));Text(AppStrings.t(lang,"camera"))}
            }}
            item{OutlinedButton({showReminderEditor=true},Modifier.fillMaxWidth()){Icon(Icons.Default.Notifications,null);Spacer(Modifier.width(6.dp));Text(AppStrings.t(lang,"remind"))}}
        }},
        confirmButton={TextButton(onDismiss){Text(AppStrings.t(lang,"close"))}}
    )
    if(showReminderEditor) ReminderEditorDialog(db,lang,"task",task.id,null,{showReminderEditor=false}){showReminderEditor=false;toast(context,AppStrings.t(lang,"reminder_saved"))}
}

@Composable private fun AddTaskDialog(lang:String,onDismiss:()->Unit,onAdd:(String,String)->Unit){var t by remember{mutableStateOf("")};var d by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"new_task"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(t,{t=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"title"))},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences));OutlinedTextField(d,{d=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"details"))},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences))}},confirmButton={Button({if(t.isNotBlank())onAdd(t,d)}){Text(AppStrings.t(lang,"add"))}},dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}})}


@Composable private fun ProjectsScreen(db:NoteDatabase,lang:String,externalRevision:Int,onOpen:(Long)->Unit){
    val projects=remember(externalRevision){db.getProjects()}
    if(projects.isEmpty()){EmptyState(AppStrings.t(lang,"no_projects"));return}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=4.dp)){
        items(projects,key={it.id}){p->
            Card(Modifier.fillMaxWidth().clickable{onOpen(p.id)},colors=noteCardColors(p.colorKey)){
                Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text(p.name,style=userTextStyle(15f,true),modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(projectStatusText(lang,p.status),style=MaterialTheme.typography.labelMedium,color=projectStatusColor(p.status))
                    }
                    if(p.description.isNotBlank())Text(p.description,maxLines=2,overflow=TextOverflow.Ellipsis,style=userTextStyle())
                    LinearProgressIndicator(progress={p.progress.coerceIn(0,100)/100f},modifier=Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text("${AppStrings.t(lang,"progress")}: ${p.progress}%",style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                        if(p.dueAt>0)Text("${AppStrings.t(lang,"due_date")}: ${formatDay(p.dueAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun AddProjectDialog(lang:String,onDismiss:()->Unit,onAdd:(String,String,String)->Unit){
    var name by rememberSaveable{mutableStateOf("")}
    var description by rememberSaveable{mutableStateOf("")}
    var colorKey by rememberSaveable{mutableStateOf("blue")}
    fun submit(){if(name.isNotBlank())onAdd(name.trim(),description.trim(),colorKey)}
    BackHandler{onDismiss()}
    Scaffold(
        topBar={
            TopAppBar(
                title={Text(AppStrings.t(lang,"new_project"))},
                actions={TopColorMenu(colorKey){colorKey=it}},
                colors=organizerTopBarColors()
            )
        }
    ){pad->
        LazyColumn(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal=16.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ){
            item{
                OutlinedTextField(
                    name,
                    {name=capitalizeSentences(it)},
                    Modifier.fillMaxWidth(),
                    label={Text(AppStrings.t(lang,"title"))},
                    textStyle=userTextStyle(),
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)
                )
            }
            item{
                OutlinedTextField(
                    description,
                    {description=capitalizeSentences(it)},
                    Modifier.fillMaxWidth().heightIn(min=220.dp),
                    label={Text(AppStrings.t(lang,"details"))},
                    textStyle=userTextStyle(),
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)
                )
            }
            item{
                Button(
                    onClick={submit()},
                    enabled=(parseAmountInput(amount)?:0.0)>0.0,
                    modifier=Modifier.fillMaxWidth(),
                    colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)
                ){
                    Icon(Icons.Default.Save,null)
                    Spacer(Modifier.width(8.dp))
                    Text(AppStrings.t(lang,"add"))
                }
            }
        }
    }
}

@Composable private fun ProjectDetailScreen(db:NoteDatabase,projectId:Long,lang:String,initialTab:Int=0,onBack:()->Unit,onDeleted:()->Unit){
    val context=LocalContext.current
    var revision by remember{mutableIntStateOf(0)}
    val project=remember(projectId,revision){db.getProject(projectId)}
    if(project==null){LaunchedEffect(Unit){onBack()};return}
    var tab by rememberSaveable(projectId,initialTab){mutableIntStateOf(initialTab.coerceIn(0,3))}
    var edit by remember{mutableStateOf(false)}
    var reminder by remember{mutableStateOf(false)}
    BackHandler{onBack()}
    Scaffold(topBar={TopAppBar(title={Text(project.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=userTextStyle(15f,true))},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,null)}},actions={
        IconButton({reminder=true}){Icon(Icons.Default.Notifications,null)}
        IconButton({edit=true}){Icon(Icons.Default.Edit,null)}
        IconButton({ReminderScheduler.cancelAllForTarget(context,"project",project.id,db);AttachmentStore.deleteTarget("project",project.id);db.deleteProject(project.id);onDeleted()}){Icon(Icons.Default.Delete,null)}
    },colors=organizerTopBarColors())}){pad->
        Column(Modifier.padding(pad).fillMaxSize()){
            TabRow(selectedTabIndex=tab){
                listOf("overview","stages","money","files").forEachIndexed{i,key->Tab(selected=tab==i,onClick={tab=i},text={Text(AppStrings.t(lang,key),maxLines=1,overflow=TextOverflow.Ellipsis)})}
            }
            when(tab){
                0->ProjectOverviewTab(db,project,lang,revision,{revision++})
                1->ProjectStagesTab(db,project,lang,revision,{revision++})
                2->ProjectFinanceTab(db,project,lang,revision,{revision++})
                else->ProjectFilesTab(project,lang,revision,{revision++})
            }
        }
    }
    if(edit)ProjectEditDialog(db,project,lang,{edit=false}){edit=false;revision++}
    if(reminder)ReminderEditorDialog(db,lang,"project",project.id,null,{reminder=false}){reminder=false;toast(context,AppStrings.t(lang,"reminder_saved"))}
}

@Composable private fun ProjectOverviewTab(db:NoteDatabase,project:Project,lang:String,revision:Int,onChanged:()->Unit){
    var journalDialog by remember{mutableStateOf(false)}
    var linkDialog by remember{mutableStateOf(false)}
    val journal=remember(revision){db.getProjectJournal(project.id)}
    val linked=remember(revision){db.getLinkedNotes(project.id)}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        item{Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(projectStatusText(lang,project.status),style=MaterialTheme.typography.titleMedium,color=projectStatusColor(project.status),modifier=Modifier.weight(1f));Text("${project.progress}%",style=MaterialTheme.typography.titleMedium)}
            LinearProgressIndicator(progress={project.progress.coerceIn(0,100)/100f},modifier=Modifier.fillMaxWidth())
            if(project.description.isNotBlank())Text(project.description,style=userTextStyle())
            ProjectInfoRow(AppStrings.t(lang,"start_date"),if(project.startAt>0)formatDay(project.startAt) else AppStrings.t(lang,"not_set"))
            ProjectInfoRow(AppStrings.t(lang,"due_date"),if(project.dueAt>0)formatDay(project.dueAt) else AppStrings.t(lang,"not_set"))
            if(project.budget>0)ProjectInfoRow(AppStrings.t(lang,"budget"),MoneyAmounts.display(project.budget),true)
            if(project.client.isNotBlank())ProjectInfoRow(AppStrings.t(lang,"client"),project.client,true)
            if(project.address.isNotBlank())ProjectInfoRow(AppStrings.t(lang,"address"),project.address,true)
            if(project.contact.isNotBlank())ProjectInfoRow(AppStrings.t(lang,"contact"),project.contact,true)
        }}}
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(AppStrings.t(lang,"journal"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));TextButton({journalDialog=true}){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text(AppStrings.t(lang,"add_journal"))}}}
        if(journal.isEmpty())item{Text("—",color=MaterialTheme.colorScheme.onSurfaceVariant)} else items(journal,key={it.id}){j->Card(Modifier.fillMaxWidth(),colors=brandCardColors(2)){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.Top){Column(Modifier.weight(1f)){Text(j.text,style=userTextStyle());Text(formatTime(j.createdAt,lang),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton({db.deleteProjectJournal(j.id);onChanged()}){Icon(Icons.Default.Close,null)}}}}
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(AppStrings.t(lang,"linked_notes"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));TextButton({linkDialog=true}){Icon(Icons.Default.Link,null);Spacer(Modifier.width(4.dp));Text(AppStrings.t(lang,"link_note"))}}}
        if(linked.isEmpty())item{Text("—",color=MaterialTheme.colorScheme.onSurfaceVariant)} else items(linked,key={it.id}){n->Card(Modifier.fillMaxWidth(),colors=noteCardColors(n.colorKey)){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=userTextStyle(15f,true));if(n.body.isNotBlank())Text(n.body,maxLines=2,overflow=TextOverflow.Ellipsis,style=userTextStyle())};IconButton({db.unlinkNoteFromProject(project.id,n.id);onChanged()}){Icon(Icons.Default.Close,null)}}}}
    }
    if(journalDialog)AddProjectJournalDialog(db,project.id,lang,{journalDialog=false}){journalDialog=false;onChanged()}
    if(linkDialog)LinkNoteDialog(db,project.id,lang,{linkDialog=false}){linkDialog=false;onChanged()}
}

@Composable private fun ProjectInfoRow(label:String,value:String,userValue:Boolean=false){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(.42f));Text(value,style=if(userValue)userTextStyle() else MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(.58f))}}

@Composable private fun ProjectStagesTab(db:NoteDatabase,project:Project,lang:String,revision:Int,onChanged:()->Unit){
    var add by remember{mutableStateOf(false)}
    val stages=remember(revision){db.getProjectStages(project.id)}
    Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp)){
        Button({add=true},Modifier.fillMaxWidth()){Icon(Icons.Default.AddTask,null);Spacer(Modifier.width(6.dp));Text(AppStrings.t(lang,"add_stage"))}
        Spacer(Modifier.height(10.dp))
        if(stages.isEmpty())EmptyState(AppStrings.t(lang,"stages")) else LazyColumn(verticalArrangement=Arrangement.spacedBy(5.dp)){
            items(stages,key={it.id}){st->Card(Modifier.fillMaxWidth(),colors=brandCardColors((st.id%2).toInt())){Row(Modifier.padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(st.done,{db.setProjectStageDone(st.id,it);onChanged()});Column(Modifier.weight(1f)){Text(st.title,style=if(st.done)userTextStyle().copy(textDecoration=TextDecoration.LineThrough) else userTextStyle());if(st.dueAt>0)Text("${AppStrings.t(lang,"due_date")}: ${formatDay(st.dueAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton({db.deleteProjectStage(st.id);onChanged()}){Icon(Icons.Default.Delete,null)}}}}
        }
    }
    if(add)AddProjectStageDialog(db,project.id,lang,{add=false}){add=false;onChanged()}
}

@Composable private fun AddProjectStageDialog(db:NoteDatabase,projectId:Long,lang:String,onDismiss:()->Unit,onAdded:()->Unit){
    val context=LocalContext.current
    var title by remember{mutableStateOf("")};var due by remember{mutableLongStateOf(0L)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"add_stage"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(title,{title=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"title"))},textStyle=userTextStyle(),singleLine=true);OutlinedButton({launchDatePicker(context,if(due>0)due else System.currentTimeMillis()){due=it}},Modifier.fillMaxWidth()){Icon(Icons.Default.Event,null);Spacer(Modifier.width(6.dp));Text(if(due>0)formatDay(due) else AppStrings.t(lang,"due_date"))}}},confirmButton={Button({if(title.isNotBlank()){db.addProjectStage(projectId,title.trim(),due);onAdded()}},enabled=(parseAmountInput(amount)?:0.0)>0.0){Text(AppStrings.t(lang,"add"))}},dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}})
}

@Composable private fun ProjectFinanceTab(db:NoteDatabase,project:Project,lang:String,revision:Int,onChanged:()->Unit){
    var add by remember{mutableStateOf(false)}
    val list=remember(revision){db.getProjectMoneyEntries(project.id)}
    val inc=list.filter{it.type=="income"}.sumOf{it.amount};val exp=list.filter{it.type=="expense"}.sumOf{it.amount};val bal=inc-exp
    Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp)){
        Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){ProjectMoneySummaryRow(AppStrings.t(lang,"income"),inc,MaterialTheme.colorScheme.primary);ProjectMoneySummaryRow(AppStrings.t(lang,"expense"),exp,MaterialTheme.colorScheme.error);ProjectMoneySummaryRow(AppStrings.t(lang,"balance"),bal,MaterialTheme.colorScheme.tertiary)}}
        Spacer(Modifier.height(8.dp))
        Button({add=true},Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(6.dp));Text(AppStrings.t(lang,"add"))}
        Spacer(Modifier.height(8.dp))
        if(list.isEmpty())EmptyState(AppStrings.t(lang,"no_money")) else LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)){items(list,key={it.id}){m->MoneyEntryCard(m,lang,if(m.type=="income")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,if(m.type=="income")0 else 1){db.deleteMoneyEntry(it);onChanged()}}}
    }
    if(add)AddProjectMoneyDialog(db,project.id,lang,{add=false}){add=false;onChanged()}
}

@Composable private fun ProjectMoneySummaryRow(label:String,amount:Double,color:Color){Row(Modifier.fillMaxWidth()){Text(label,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text(MoneyAmounts.display(amount),style=MaterialTheme.typography.titleMedium,color=color)}}

@Composable private fun AddProjectMoneyDialog(db:NoteDatabase,projectId:Long,lang:String,onDismiss:()->Unit,onAdded:()->Unit){
    var type by remember{mutableStateOf("expense")};var amount by remember{mutableStateOf("")};var cat by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"project_finance"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row{FilterChip(type=="expense",{type="expense"},{Text(AppStrings.t(lang,"expense"))});Spacer(Modifier.width(8.dp));FilterChip(type=="income",{type="income"},{Text(AppStrings.t(lang,"income"))})}
        OutlinedTextField(amount,{amount=formatAmountInput(it)},label={Text(AppStrings.t(lang,"amount"))},textStyle=userTextStyle(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,isError=amount.isNotBlank()&&((parseAmountInput(amount)?:0.0)<=0.0),supportingText={if(amount.isNotBlank()&&((parseAmountInput(amount)?:0.0)<=0.0))Text(if(lang=="ru")"Введите сумму больше нуля" else "Enter an amount greater than zero")})
        SuggestionTextField(cat,{cat=capitalizeSentences(it)},AppStrings.t(lang,"category"),db.getMoneyCategorySuggestions(cat),{cat=it},{db.hideSuggestion("money_category",it)},Modifier.fillMaxWidth())
        OutlinedTextField(note,{note=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"comment"))},textStyle=userTextStyle())
    }},confirmButton={Button({parseAmountInput(amount)?.takeIf{it>0.0}?.let{db.addMoneyEntry(type,it,cat,projectId,note);onAdded()}},enabled=(parseAmountInput(amount)?:0.0)>0.0){Text(AppStrings.t(lang,"add"))}},dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}})
}

@Composable private fun ProjectFilesTab(project:Project,lang:String,revision:Int,onChanged:()->Unit){
    val context=LocalContext.current
    var localRevision by remember(revision){mutableIntStateOf(revision)}
    val files=remember(localRevision){AttachmentStore.list("project",project.id)}
    var pendingCameraFile by remember{mutableStateOf<File?>(null)}
    val recorder=remember{VoiceRecorder(context)};var recording by remember{mutableStateOf(false)};var startedAt by remember{mutableLongStateOf(0L)};var elapsed by remember{mutableLongStateOf(0L)}
    DisposableEffect(Unit){onDispose{if(recording)recorder.cancel()}}
    LaunchedEffect(recording){while(recording){elapsed=android.os.SystemClock.elapsedRealtime()-startedAt;delay(200)}}
    fun changed(){localRevision++;onChanged()}
    fun startRecording(){runCatching{recorder.start();recording=true;startedAt=android.os.SystemClock.elapsedRealtime();elapsed=0}.onFailure{toast(context,it.message?:AppStrings.t(lang,"error"))}}
    fun stopRecording(){val r=runCatching{recorder.stop()}.getOrNull();recording=false;if(r!=null)runCatching{AttachmentStore.addLocalFile("project",project.id,r.file,"project_voice_${System.currentTimeMillis()}.m4a","audio/mp4",r.durationMs,true)}.onSuccess{changed()}.onFailure{toast(context,it.message?:AppStrings.t(lang,"record_failed"))}}
    val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it)startRecording() else toast(context,AppStrings.t(lang,"permission_mic"))}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{runCatching{AttachmentStore.addFromUri("project",project.id,it)}.onSuccess{changed()}.onFailure{e->toast(context,e.message?:AppStrings.t(lang,"error"))}}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->val f=pendingCameraFile;if(ok&&f!=null)runCatching{AttachmentStore.addLocalFile("project",project.id,f,f.name,"image/jpeg",0,true)}.onSuccess{changed()}.onFailure{toast(context,it.message?:AppStrings.t(lang,"error"))} else f?.delete();pendingCameraFile=null}
    Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton({picker.launch(arrayOf("image/*","application/pdf","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/plain"))},Modifier.weight(1f)){Icon(Icons.Default.AttachFile,null);Spacer(Modifier.width(4.dp));Text(AppStrings.t(lang,"file"),maxLines=1)}
            OutlinedButton({val(f,u)=AttachmentStore.createCameraTarget();pendingCameraFile=f;camera.launch(u)},Modifier.weight(1f)){Icon(Icons.Default.PhotoCamera,null);Spacer(Modifier.width(4.dp));Text(AppStrings.t(lang,"camera"),maxLines=1)}
            Button({if(recording)stopRecording() else if(androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)},Modifier.weight(1f),colors=if(recording)ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)else ButtonDefaults.buttonColors()){Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,null);Spacer(Modifier.width(4.dp));Text(if(recording)humanDuration(elapsed) else AppStrings.t(lang,"microphone"),maxLines=1,overflow=TextOverflow.Ellipsis)}
        }
        Spacer(Modifier.height(10.dp))
        if(files.isEmpty())EmptyState(AppStrings.t(lang,"files")) else {
            val voices=files.filter{it.isVoice}
            val docs=files.filterNot{it.isVoice}
            LazyColumn(verticalArrangement=Arrangement.spacedBy(5.dp)){
                if(voices.isNotEmpty())items(voices,key={it.id}){a->VoiceNoteBlock(a,lang,{AttachmentStore.delete(a);changed()})}
                if(docs.isNotEmpty())item{AttachmentStrip(docs,lang){changed()}}
            }
        }
    }
}

@Composable private fun ProjectEditDialog(db:NoteDatabase,project:Project,lang:String,onDismiss:()->Unit,onSaved:()->Unit){
    val context=LocalContext.current
    var name by remember{mutableStateOf(project.name)};var description by remember{mutableStateOf(project.description)};var status by remember{mutableStateOf(project.status)};var progress by remember{mutableFloatStateOf(project.progress.toFloat())};var startAt by remember{mutableLongStateOf(project.startAt)};var dueAt by remember{mutableLongStateOf(project.dueAt)};var budget by remember{mutableStateOf(if(project.budget>0)MoneyAmounts.edit(project.budget) else "")};var client by remember{mutableStateOf(project.client)};var address by remember{mutableStateOf(project.address)};var contact by remember{mutableStateOf(project.contact)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"edit_project"))},text={LazyColumn(Modifier.heightIn(max=560.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{OutlinedTextField(name,{name=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"title"))},textStyle=userTextStyle(),singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(description,{description=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"details"))},textStyle=userTextStyle(),minLines=3,modifier=Modifier.fillMaxWidth())}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("planned","in_progress","paused","completed").forEach{st->FilterChip(selected=status==st,onClick={status=st},label={Text(projectStatusText(lang,st),maxLines=1,style=MaterialTheme.typography.labelSmall)})}}}
        item{Text("${AppStrings.t(lang,"progress")}: ${progress.toInt()}%",style=MaterialTheme.typography.bodyMedium);Slider(progress,{progress=it},valueRange=0f..100f,steps=19)}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({launchDatePicker(context,if(startAt>0)startAt else System.currentTimeMillis()){startAt=it}},Modifier.weight(1f)){Text(if(startAt>0)formatDay(startAt) else AppStrings.t(lang,"start_date"),maxLines=1,overflow=TextOverflow.Ellipsis)};OutlinedButton({launchDatePicker(context,if(dueAt>0)dueAt else System.currentTimeMillis()){dueAt=it}},Modifier.weight(1f)){Text(if(dueAt>0)formatDay(dueAt) else AppStrings.t(lang,"due_date"),maxLines=1,overflow=TextOverflow.Ellipsis)}}}
        item{OutlinedTextField(budget,{budget=formatAmountInput(it)},label={Text(AppStrings.t(lang,"budget"))},textStyle=userTextStyle(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth(),isError=budget.isNotBlank()&&parseAmountInput(budget)==null,supportingText={if(budget.isNotBlank()&&parseAmountInput(budget)==null)Text(if(lang=="ru")"Введите корректный бюджет" else "Enter a valid budget")})}
        item{OutlinedTextField(client,{client=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"client"))},textStyle=userTextStyle(),singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(address,{address=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"address"))},textStyle=userTextStyle(),singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(contact,{contact=it},label={Text(AppStrings.t(lang,"contact"))},textStyle=userTextStyle(),singleLine=true,modifier=Modifier.fillMaxWidth())}
    }},confirmButton={Button({if(name.isNotBlank()&&(budget.isBlank()||parseAmountInput(budget)!=null)){db.saveProject(project.id,name.trim(),description.trim(),status,progress.toInt(),startAt,dueAt,parseAmountInput(budget)?:0.0,client.trim(),address.trim(),contact.trim(),project.colorKey);onSaved()}},enabled=name.isNotBlank()&&(budget.isBlank()||parseAmountInput(budget)!=null)){Text(AppStrings.t(lang,"save"))}},dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}})
}

@Composable private fun AddProjectJournalDialog(db:NoteDatabase,projectId:Long,lang:String,onDismiss:()->Unit,onAdded:()->Unit){
    var text by remember{mutableStateOf("")}
    val speech=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==Activity.RESULT_OK){val spoken=r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty();text=appendRecognizedText(text,spoken)}}
    AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"add_journal"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(text,{text=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"note"))},textStyle=userTextStyle(),minLines=5,modifier=Modifier.fillMaxWidth());OutlinedButton({runCatching{speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,AppStrings.localeTag(lang))})}},Modifier.fillMaxWidth()){Icon(Icons.Default.KeyboardVoice,null);Spacer(Modifier.width(6.dp));Text(AppStrings.t(lang,"to_text"))}}},confirmButton={Button({if(text.isNotBlank()){db.addProjectJournal(projectId,text.trim());onAdded()}},enabled=(parseAmountInput(amount)?:0.0)>0.0){Text(AppStrings.t(lang,"add"))}},dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}})
}

@Composable private fun LinkNoteDialog(db:NoteDatabase,projectId:Long,lang:String,onDismiss:()->Unit,onChanged:()->Unit){
    val linkedIds=remember{db.getLinkedNotes(projectId).map{it.id}.toSet()}
    val available=remember{db.getNotes().filterNot{it.id in linkedIds}}
    AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"link_note"))},text={if(available.isEmpty())Text(AppStrings.t(lang,"no_notes")) else LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){items(available,key={it.id}){n->Card(Modifier.fillMaxWidth().clickable{db.linkNoteToProject(projectId,n.id);onChanged()},colors=noteCardColors(n.colorKey)){Column(Modifier.padding(10.dp)){Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=MaterialTheme.typography.titleSmall);if(n.body.isNotBlank())Text(n.body,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)}}}}},confirmButton={TextButton(onDismiss){Text(AppStrings.t(lang,"close"))}})
}

private fun projectStatusText(lang:String,status:String)=AppStrings.t(lang,when(status){"in_progress"->"in_progress";"paused"->"paused";"completed"->"completed";else->"planned"})
private fun projectStatusColor(status:String)=when(status){"completed"->Color(0xFF2E7D32);"paused"->Color(0xFFF57C00);"in_progress"->Color(0xFF1565C0);else->Color(0xFF6A1B9A)}
private fun formatDay(ms:Long)=DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
private fun launchDatePicker(context:android.content.Context,initial:Long,onSelected:(Long)->Unit){val c=java.util.Calendar.getInstance().apply{timeInMillis=initial};android.app.DatePickerDialog(context,{_,y,m,d->val out=java.util.Calendar.getInstance().apply{set(y,m,d,9,0,0);set(java.util.Calendar.MILLISECOND,0)};onSelected(out.timeInMillis)},c.get(java.util.Calendar.YEAR),c.get(java.util.Calendar.MONTH),c.get(java.util.Calendar.DAY_OF_MONTH)).show()}

@Composable private fun MoneyScreen(db:NoteDatabase,lang:String,externalRevision:Int,selectedTab:Int,onTabSelected:(Int)->Unit){
    var refresh by remember{mutableIntStateOf(0)}
    val list=remember(refresh,externalRevision){db.getStandaloneMoneyEntries()}
    val income=list.filter{it.type=="income"}
    val expense=list.filter{it.type=="expense"}
    Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=5.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
            listOf("overview","income","expense").forEachIndexed{i,key->
                MoneyTabPill(
                    selected=selectedTab==i,
                    text=AppStrings.t(lang,key),
                    onClick={onTabSelected(i)},
                    modifier=Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        when(selectedTab){
            0->MoneyOverview(list,lang,Modifier.fillMaxSize())
            1->MoneyColumn(AppStrings.t(lang,"income"),income,lang,Modifier.fillMaxSize(),MaterialTheme.colorScheme.primary,0){id->db.deleteMoneyEntry(id);refresh++}
            2->MoneyColumn(AppStrings.t(lang,"expense"),expense,lang,Modifier.fillMaxSize(),MaterialTheme.colorScheme.error,1){id->db.deleteMoneyEntry(id);refresh++}
            else->MoneyOverview(list,lang,Modifier.fillMaxSize())
        }
    }
}

@Composable private fun MoneyOverview(entries:List<MoneyEntry>,lang:String,modifier:Modifier=Modifier){
    val inc=entries.filter{it.type=="income"}.sumOf{it.amount}
    val exp=entries.filter{it.type=="expense"}.sumOf{it.amount}
    val bal=inc-exp
    Column(modifier){
        MoneySummaryRow(inc,exp,bal,lang)
        Spacer(Modifier.height(6.dp))
        Text(AppStrings.t(lang,"recent_operations"),style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if(entries.isEmpty())EmptyState(AppStrings.t(lang,"no_money"))
        else LazyColumn(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
            items(entries,key={it.id}){m->
                MoneyEntryCard(m,lang,if(m.type=="income")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,if(m.type=="income")0 else 1,null)
            }
        }
    }
}

@Composable private fun MoneySummaryRow(inc:Double,exp:Double,bal:Double,lang:String){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        MoneySummaryCard(AppStrings.t(lang,"income"),inc,MaterialTheme.colorScheme.primary,0,Modifier.weight(1f))
        MoneySummaryCard(AppStrings.t(lang,"expense"),exp,MaterialTheme.colorScheme.error,1,Modifier.weight(1f))
        MoneySummaryCard(AppStrings.t(lang,"balance"),bal,MaterialTheme.colorScheme.tertiary,2,Modifier.weight(1f),signed=true)
    }
}

@Composable private fun MoneySummaryCard(title:String,value:Double,valueColor:Color,tone:Int,modifier:Modifier=Modifier,signed:Boolean=false){
    Card(modifier,colors=brandCardColors(tone)){
        Column(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text(title,style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            val prefix=if(signed&&value>0)"+" else if(signed&&value<0)"−" else ""
            Text(prefix+MoneyAmounts.display(kotlin.math.abs(value)),style=MaterialTheme.typography.titleSmall,color=valueColor,maxLines=1)
        }
    }
}

private data class MoneyMonthPoint(val label:String,val income:Double,val expense:Double)

private fun moneyMonthPoints(entries:List<MoneyEntry>,lang:String):List<MoneyMonthPoint>{
    val locale=AppStrings.locale(lang)
    val now=java.util.Calendar.getInstance()
    return (5 downTo 0).map{back->
        val start=java.util.Calendar.getInstance().apply{
            timeInMillis=now.timeInMillis
            set(java.util.Calendar.DAY_OF_MONTH,1);set(java.util.Calendar.HOUR_OF_DAY,0);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)
            add(java.util.Calendar.MONTH,-back)
        }
        val end=start.clone() as java.util.Calendar
        end.add(java.util.Calendar.MONTH,1)
        val monthEntries=entries.filter{it.createdAt>=start.timeInMillis&&it.createdAt<end.timeInMillis}
        MoneyMonthPoint(
            SimpleDateFormat("MMM",locale).format(Date(start.timeInMillis)),
            monthEntries.filter{it.type=="income"}.sumOf{it.amount},
            monthEntries.filter{it.type=="expense"}.sumOf{it.amount}
        )
    }
}

@Composable private fun MoneyAnalytics(entries:List<MoneyEntry>,lang:String,modifier:Modifier=Modifier){
    val inc=entries.filter{it.type=="income"}.sumOf{it.amount}
    val exp=entries.filter{it.type=="expense"}.sumOf{it.amount}
    val bal=inc-exp
    LazyColumn(modifier,verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=4.dp)){
        item{MoneySummaryRow(inc,exp,bal,lang)}
        item{AnalyticsChartCard(entries,lang)}
    }
}

@Composable private fun AnalyticsChartCard(entries:List<MoneyEntry>,lang:String){
    var expanded by rememberSaveable{mutableStateOf(true)}
    val points=remember(entries,lang){moneyMonthPoints(entries,lang)}
    val incomeColor=MaterialTheme.colorScheme.primary
    val expenseColor=MaterialTheme.colorScheme.error
    Card(Modifier.fillMaxWidth().clickable{expanded=!expanded},colors=brandCardColors(0)){
        Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(AppStrings.t(lang,"chart"),style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                Icon(if(expanded)Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null)
            }
            if(expanded){
                val maxValue=(points.maxOfOrNull{maxOf(it.income,it.expense)}?:0.0).coerceAtLeast(1.0)
                Canvas(Modifier.fillMaxWidth().height(138.dp)){
                    val group=size.width/points.size.coerceAtLeast(1)
                    val base=size.height-4.dp.toPx()
                    val stroke=(group*.20f).coerceAtLeast(5.dp.toPx())
                    points.forEachIndexed{i,p->
                        val center=i*group+group/2
                        val incH=(base*(p.income/maxValue)).toFloat()
                        val expH=(base*(p.expense/maxValue)).toFloat()
                        drawLine(incomeColor,Offset(center-group*.13f,base),Offset(center-group*.13f,base-incH),strokeWidth=stroke,cap=StrokeCap.Round)
                        drawLine(expenseColor,Offset(center+group*.13f,base),Offset(center+group*.13f,base-expH),strokeWidth=stroke,cap=StrokeCap.Round)
                    }
                }
                Row(Modifier.fillMaxWidth()){
                    points.forEach{p->Text(p.label,style=MaterialTheme.typography.labelSmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.weight(1f),maxLines=1)}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                    Surface(color=incomeColor,shape=RoundedCornerShape(3.dp),modifier=Modifier.size(10.dp)){}
                    Spacer(Modifier.width(4.dp));Text(AppStrings.t(lang,"income"),style=MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(14.dp))
                    Surface(color=expenseColor,shape=RoundedCornerShape(3.dp),modifier=Modifier.size(10.dp)){}
                    Spacer(Modifier.width(4.dp));Text(AppStrings.t(lang,"expense"),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable private fun MoneyColumn(title:String,entries:List<MoneyEntry>,lang:String,modifier:Modifier=Modifier,amountColor:Color,cardTone:Int,onDelete:(Long)->Unit){
    Column(modifier.fillMaxHeight()){
        Card(Modifier.fillMaxWidth(),colors=brandCardColors(cardTone)){
            Box(Modifier.fillMaxWidth().height(46.dp),contentAlignment=Alignment.Center){
                Text(title,style=MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        if(entries.isEmpty()){
            Spacer(Modifier.weight(1f).fillMaxWidth())
        }else{
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
                items(entries,key={it.id}){m->MoneyEntryCard(m,lang,amountColor,cardTone,onDelete)}
            }
        }
    }
}

@Composable private fun MoneyEntryCard(m:MoneyEntry,lang:String,amountColor:Color,cardTone:Int,onDelete:((Long)->Unit)?){
    Card(Modifier.fillMaxWidth(),colors=noteCardColors(m.colorKey)){
        Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(MoneyAmounts.display(m.amount),style=userTextStyle(15f,true),color=amountColor,modifier=Modifier.weight(1f),maxLines=1)
                if(onDelete!=null)IconButton({onDelete(m.id)},modifier=Modifier.size(30.dp)){Icon(Icons.Default.Delete,null,Modifier.size(18.dp))}
            }
            val label=m.category.ifBlank{m.note.ifBlank{AppStrings.t(lang,"comment")}}
            Text(label,style=userTextStyle(),maxLines=2,overflow=TextOverflow.Ellipsis)
            if(m.category.isNotBlank()&&m.note.isNotBlank())Text(m.note,style=userTextStyle(),maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun AddMoneyDialog(db:NoteDatabase,lang:String,fixedType:String,onDismiss:()->Unit,onAdd:(Double,String,String,String)->Unit){
    var amount by rememberSaveable{mutableStateOf("")}
    var cat by rememberSaveable{mutableStateOf("")}
    var note by rememberSaveable{mutableStateOf("")}
    var colorKey by rememberSaveable{mutableStateOf("blue")}
    fun submit(){parseAmountInput(amount)?.takeIf{it>0.0}?.let{onAdd(it,cat.trim(),note.trim(),colorKey)}}
    BackHandler{onDismiss()}
    Scaffold(
        topBar={
            TopAppBar(
                title={Text(AppStrings.t(lang,fixedType))},
                actions={TopColorMenu(colorKey){colorKey=it}},
                colors=organizerTopBarColors()
            )
        }
    ){pad->
        LazyColumn(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal=16.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ){
            item{
                OutlinedTextField(
                    amount,
                    {amount=formatAmountInput(it)},
                    Modifier.fillMaxWidth(),
                    label={Text(AppStrings.t(lang,"amount"))},
                    isError=amount.isNotBlank()&&((parseAmountInput(amount)?:0.0)<=0.0),
                    supportingText={if(amount.isNotBlank()&&((parseAmountInput(amount)?:0.0)<=0.0))Text(if(lang=="ru")"Введите сумму больше нуля" else "Enter an amount greater than zero")},
                    textStyle=userTextStyle(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                    singleLine=true
                )
            }
            item{
                SuggestionTextField(
                    value=cat,
                    onValueChange={cat=capitalizeSentences(it)},
                    label=AppStrings.t(lang,"category"),
                    suggestions=db.getMoneyCategorySuggestions(cat),
                    onSuggestion={cat=it},
                    onDeleteSuggestion={db.hideSuggestion("money_category",it)},
                    modifier=Modifier.fillMaxWidth()
                )
            }
            item{
                OutlinedTextField(
                    note,
                    {note=capitalizeSentences(it)},
                    Modifier.fillMaxWidth().heightIn(min=180.dp),
                    label={Text(AppStrings.t(lang,"comment"))},
                    textStyle=userTextStyle(),
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)
                )
            }
            item{
                Button(
                    onClick={submit()},
                    enabled=(parseAmountInput(amount)?:0.0)>0.0,
                    modifier=Modifier.fillMaxWidth(),
                    colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)
                ){
                    Icon(Icons.Default.Save,null)
                    Spacer(Modifier.width(8.dp))
                    Text(AppStrings.t(lang,"add"))
                }
            }
        }
    }
}

@Composable private fun RemindersScreen(db:NoteDatabase,lang:String,onBack:()->Unit){
    val context=LocalContext.current
    var refresh by remember{mutableIntStateOf(0)}
    var editing by remember{mutableStateOf<ReminderEntry?>(null)}
    val list=remember(refresh){db.getReminders()}
    BackHandler{onBack()}
    Scaffold(topBar={TopAppBar(title={Text(AppStrings.t(lang,"reminders"))},navigationIcon={IconButton(onBack){Icon(Icons.Default.ArrowBack,null)}},colors=organizerTopBarColors())}){pad->
        if(list.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(),contentAlignment=Alignment.Center){Text(AppStrings.t(lang,"no_reminders"))}
        else LazyColumn(Modifier.padding(pad).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(list,key={it.id}){r->
                Card(Modifier.fillMaxWidth().clickable{editing=r}){
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                            Text(r.title.ifBlank{AppStrings.t(lang,"reminders")},style=userTextStyle(15f,true))
                            if(r.details.isNotBlank())Text(r.details,maxLines=2,overflow=TextOverflow.Ellipsis,style=userTextStyle())
                            Text(reminderSummary(r,lang),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ReminderScheduler.cancel(context,r.id);db.deleteReminder(r.id);refresh++}){Icon(Icons.Default.Delete,null)}
                    }
                }
            }
        }
    }
    editing?.let{r->ReminderEditorDialog(db,lang,r.targetType,r.targetId,r,{editing=null}){editing=null;refresh++}}
}

@Composable private fun ReminderEditorDialog(db:NoteDatabase,lang:String,targetType:String,targetId:Long,existing:ReminderEntry?,onDismiss:()->Unit,onSaved:()->Unit){
    val context=LocalContext.current
    var scheduleType by remember(existing?.id){mutableStateOf(existing?.scheduleType?:"once")}
    var dayMode by remember(existing?.id){mutableStateOf(existing?.dayMode?:"single")}
    var selectedAt by remember(existing?.id){mutableLongStateOf(existing?.triggerAt?:System.currentTimeMillis()+60*60_000L)}
    var baseMinutes by remember(existing?.id){mutableIntStateOf(existing?.baseMinutes?:ReminderTime.minutesOfDay(selectedAt))}
    var daysMask by remember(existing?.id){mutableIntStateOf(existing?.daysMask?:0)}
    val dayLabels=AppStrings.weekdayLabels(lang)
    val canSave=scheduleType=="once" && selectedAt>System.currentTimeMillis() || scheduleType=="weekly" && daysMask!=0
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(AppStrings.t(lang,"remind"))},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(scheduleType=="once",{scheduleType="once"},{Text(AppStrings.t(lang,"once"))},modifier=Modifier.weight(1f))
                FilterChip(scheduleType=="weekly",{scheduleType="weekly"},{Text(AppStrings.t(lang,"weekdays"),maxLines=1,overflow=TextOverflow.Ellipsis)},modifier=Modifier.weight(1f))
            }
            if(scheduleType=="once"){
                OutlinedButton({launchDateTimePicker(context,selectedAt){selectedAt=it;baseMinutes=ReminderTime.minutesOfDay(it)}},Modifier.fillMaxWidth()){
                    Icon(Icons.Default.Event,null);Spacer(Modifier.width(8.dp));Text("${AppStrings.t(lang,"reminder_date_time")}: ${formatDate(selectedAt,lang)}")
                }
            }else{
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    dayLabels.forEachIndexed{i,label->
                        Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.weight(1f)){
                            Text(label,style=MaterialTheme.typography.labelMedium)
                            Checkbox(checked=(daysMask and (1 shl i))!=0,onCheckedChange={checked->daysMask=if(checked) daysMask or (1 shl i) else daysMask and (1 shl i).inv()},modifier=Modifier.size(34.dp))
                        }
                    }
                }
                OutlinedButton({launchTimePicker(context,baseMinutes){baseMinutes=it}},Modifier.fillMaxWidth()){
                    Icon(Icons.Default.Schedule,null);Spacer(Modifier.width(8.dp));Text("${AppStrings.t(lang,"reminder_time")}: %02d:%02d".format(baseMinutes/60,baseMinutes%60))
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(dayMode=="single",{dayMode="single"},{Text(AppStrings.t(lang,"one_signal"))},modifier=Modifier.weight(1f))
                FilterChip(dayMode=="all_day",{dayMode="all_day"},{Text(AppStrings.t(lang,"all_day"))},modifier=Modifier.weight(1f))
            }
            if(dayMode=="all_day") Text(AppStrings.t(lang,"all_day_hint"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }},
        confirmButton={Button(enabled=canSave,onClick={
            val trigger=if(scheduleType=="weekly") ReminderTime.nextWeekly(daysMask,baseMinutes,System.currentTimeMillis()-1) else selectedAt
            val id=if(existing==null) db.addReminder(targetType,targetId,trigger,true,true,"system",scheduleType,daysMask,dayMode,baseMinutes) else existing.id.also{ReminderScheduler.cancel(context,it);db.updateReminder(it,trigger,scheduleType,daysMask,dayMode,baseMinutes)}
            db.getReminder(id)?.let{ReminderScheduler.schedule(context,it)}
            onSaved()
        }){Text(AppStrings.t(lang,"save"))}},
        dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}}
    )
}

private fun reminderSummary(r:ReminderEntry,lang:String):String{
    val mode=if(r.scheduleType=="once") AppStrings.t(lang,"once") else AppStrings.t(lang,"weekdays")
    val signal=if(r.dayMode=="all_day") AppStrings.t(lang,"all_day") else AppStrings.t(lang,"one_signal")
    return "$mode • $signal • ${formatDate(r.triggerAt,lang)}"
}

private fun languagesLabel(lang:String):String=when(AppStrings.normalizeLanguage(lang)){
    "kk"->"Тілдер"
    "ru"->"Языки"
    "zh"->"语言"
    "de"->"Sprachen"
    "es"->"Idiomas"
    "fr"->"Langues"
    "tr"->"Diller"
    else->"Languages"
}

@Composable private fun SettingsScreen(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,buttonScale:Float,onButtonScale:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,projectsEnabled:Boolean,onProjectsEnabled:(Boolean)->Unit){
    val context=LocalContext.current;var msg by remember{mutableStateOf<String?>(null)};var showPin by remember{mutableStateOf(false)};var showDisablePin by remember{mutableStateOf(false)};var showChangePin by remember{mutableStateOf(false)};var languageMenu by remember{mutableStateOf(false)};var archiveBackup by rememberSaveable{mutableStateOf(false)}
    val compactSettings=buttonScale*LocalDensity.current.fontScale<=1.16f
    val onePageSettings=buttonScale*LocalDensity.current.fontScale<=.90f
    val settingsOuterV=if(onePageSettings)0.dp else if(compactSettings)2.dp else 4.dp
    val settingsGap=if(onePageSettings)1.dp else if(compactSettings)3.dp else 4.dp
    val topCardPad=if(onePageSettings)1.dp else if(compactSettings)3.dp else 5.dp
    val controlsCardPad=if(onePageSettings)0.dp else if(compactSettings)2.dp else 4.dp
    val pinCardPad=if(onePageSettings)0.dp else if(compactSettings)2.dp else 4.dp
    val backupCardPad=if(onePageSettings)1.dp else if(compactSettings)3.dp else 5.dp
    val infoCardPad=if(onePageSettings)1.dp else if(compactSettings)3.dp else 5.dp
    val saveBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")){uri->uri?.let{runCatching{val j=BackupManager.createBackup(db,settings);if(archiveBackup)BackupManager.writeArchiveToUri(context,it,j)else BackupManager.writeToUri(context,it,j);settings.lastBackupAt=System.currentTimeMillis()}.onSuccess{msg=AppStrings.t(lang,"backup_ok")}.onFailure{e->msg=e.message}}}
    val openBackup=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{runCatching{val p=BackupManager.restore(BackupManager.readFromUri(context,it),db,lang);p.darkTheme?.let(onDark);p.fontScale?.let(onFont);p.buttonFontScale?.let(onButtonScale);p.boldText?.let(onBold);p.language?.let(onLanguage);p.projectsEnabled?.let(onProjectsEnabled);settings.backupEmail=p.email;settings.backupMode=p.backupMode;ReminderScheduler.rescheduleAll(context)}.onSuccess{msg=AppStrings.t(lang,"restore_ok")}.onFailure{e->msg=e.message}}}
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=settingsOuterV),
        contentPadding=PaddingValues(bottom=if(compactSettings)2.dp else 4.dp),
        verticalArrangement=Arrangement.spacedBy(settingsGap)
    ){
        item{SettingsCard("",verticalPadding=topCardPad,spacing=if(compactSettings)2.dp else 3.dp){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                AppLogo(Modifier.size(if(onePageSettings)34.dp else if(compactSettings)38.dp else 42.dp))
                Spacer(Modifier.width(if(compactSettings)5.dp else 7.dp))
                Text(AppStrings.t(lang,"app"),modifier=Modifier.weight(1.15f),maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.ExtraBold,letterSpacing=.5.sp))
                Spacer(Modifier.width(if(compactSettings)5.dp else 7.dp))
                Box{
                    FilledTonalButton(onClick={languageMenu=true},colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(0.dp),modifier=Modifier.size(if(onePageSettings)36.dp else if(compactSettings)40.dp else 44.dp)){
                        Icon(Icons.Default.Language,null,Modifier.size(if(onePageSettings)19.dp else if(compactSettings)21.dp else 23.dp))
                    }
                    DropdownMenu(expanded=languageMenu,onDismissRequest={languageMenu=false}){
                        AppStrings.supportedLanguages.forEach{code->DropdownMenuItem(text={Text(AppStrings.languageName(code))},leadingIcon={Text(AppStrings.languageFlag(code),fontSize=20.sp)},trailingIcon={if(AppStrings.normalizeLanguage(lang)==code) Icon(Icons.Default.Check,null) else Spacer(Modifier.size(24.dp))},onClick={onLanguage(code);languageMenu=false})}
                    }
                }
            }
        }}
        item{SettingsCard("",verticalPadding=controlsCardPad,spacing=if(compactSettings)0.dp else 1.dp){
            SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark)
            SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold)
            FontScaleRow(AppStrings.t(lang,"button_font"),buttonScale,onButtonScale,compactSettings)
            FontScaleRow(AppStrings.t(lang,"content_font"),fontScale,onFont,compactSettings)
            SettingSwitch(AppStrings.t(lang,"show_projects"),projectsEnabled,onProjectsEnabled)
        }}
        item{SettingsCard("",verticalPadding=pinCardPad,spacing=if(compactSettings)1.dp else 2.dp){val enabled=settings.isPinProtectionEnabled();SettingSwitch(AppStrings.t(lang,"pin"),enabled){want->if(want&&!enabled)showPin=true else if(!want&&enabled)showDisablePin=true};if(enabled)OutlinedButton({showChangePin=true},Modifier.fillMaxWidth().heightIn(min=if(compactSettings)30.dp else 34.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp)){Text(AppStrings.t(lang,"change_pin"))}}}
        item{SettingsCard("",verticalPadding=backupCardPad,spacing=if(onePageSettings)0.dp else if(compactSettings)2.dp else 4.dp){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(onePageSettings)2.dp else 4.dp)){
                OutlinedButton({saveBackup.launch(BackupManager.suggestedFileName(archiveBackup))},Modifier.weight(1.05f).heightIn(min=if(onePageSettings)28.dp else if(compactSettings)32.dp else 36.dp),contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)){Text(AppStrings.t(lang,"save_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
                OutlinedButton({openBackup.launch(arrayOf("application/json","application/zip","text/plain","*/*"))},Modifier.weight(1.05f).heightIn(min=if(onePageSettings)28.dp else if(compactSettings)32.dp else 36.dp),contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)){Text(AppStrings.t(lang,"restore"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(AppStrings.t(lang,"archive_backup"),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                Checkbox(archiveBackup,{archiveBackup=it})
            }
        }}
        item{CloudSyncSettingsCard(db,settings,lang,compactSettings)}
        item{SettingsCard("",verticalPadding=infoCardPad,spacing=if(compactSettings)1.dp else 2.dp){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(if(compactSettings)0.dp else 2.dp)){
                    Text(AppStrings.t(lang,"version"),style=MaterialTheme.typography.bodyMedium)
                    Text(AppStrings.t(lang,"developer_contact"),style=MaterialTheme.typography.bodyMedium)
                    Text(
                        "kairat.pv@gmail.com",
                        color=MaterialTheme.colorScheme.primary,
                        textDecoration=TextDecoration.Underline,
                        style=MaterialTheme.typography.bodyMedium,
                        modifier=Modifier.clickable{
                            runCatching{
                                context.startActivity(Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:kairat.pv@gmail.com")))
                            }.onFailure{toast(context,it.message?:AppStrings.t(lang,"error"))}
                        }
                    )
                    Text(AppStrings.t(lang,"copyright"),style=MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.width(if(compactSettings)6.dp else 10.dp))
                AppLogo(Modifier.size(if(compactSettings)38.dp else 46.dp))
            }
        }}
        msg?.let{item{Text(it,color=MaterialTheme.colorScheme.primary)}}
    }
    if(showPin)PinSetupDialog(lang,{showPin=false}){pin->settings.setPin(pin);settings.setPinProtectionEnabled(true);showPin=false}
    if(showDisablePin)VerifyPinDialog(lang,settings,AppStrings.t(lang,"disable_pin"),{showDisablePin=false}){settings.setPinProtectionEnabled(false);showDisablePin=false}
    if(showChangePin)ChangePinDialog(lang,settings){showChangePin=false}
}
@Composable private fun VerifyPinDialog(lang:String,settings:SettingsStore,title:String,onDismiss:()->Unit,onVerified:()->Unit){
    var pin by remember{mutableStateOf("")};var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column{OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(12)},label={Text(AppStrings.t(lang,"current_pin"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation());if(error)Text(AppStrings.t(lang,"wrong_pin"),color=MaterialTheme.colorScheme.error)}},confirmButton={Button({if(settings.verifyPin(pin))onVerified()else error=true}){Text(AppStrings.t(lang,"confirm"))}},dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}})
}
@Composable private fun ChangePinDialog(lang:String,settings:SettingsStore,onDismiss:()->Unit){
    var current by remember{mutableStateOf("")};var next by remember{mutableStateOf("")};var error by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"change_pin"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(current,{current=it.filter(Char::isDigit).take(12)},label={Text(AppStrings.t(lang,"current_pin"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation());OutlinedTextField(next,{next=it.filter(Char::isDigit).take(12)},label={Text(AppStrings.t(lang,"new_pin"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation());if(error)Text(AppStrings.t(lang,"wrong_pin"),color=MaterialTheme.colorScheme.error)}},confirmButton={Button({if(settings.verifyPin(current)&&next.length>=4){settings.setPin(next);onDismiss()}else error=true}){Text(AppStrings.t(lang,"confirm"))}},dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}})
}
@Composable private fun SettingsCard(title:String,verticalPadding:androidx.compose.ui.unit.Dp=6.dp,spacing:androidx.compose.ui.unit.Dp=4.dp,content: @Composable ColumnScope.() -> Unit){Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(horizontal=10.dp,vertical=verticalPadding),verticalArrangement=Arrangement.spacedBy(spacing)){if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleMedium);content()}}}
@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){
    val scale=LocalButtonTextScale.current
    val compact=scale<=1.16f
    Row(
        Modifier.fillMaxWidth().heightIn(min=48.dp).padding(vertical=4.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Text(label,Modifier.weight(1f),maxLines=2)
        Box(
            Modifier.width(56.dp).heightIn(min=48.dp),
            contentAlignment=Alignment.Center
        ){
            Switch(value,onChange)
        }
    }
}
@Composable private fun FontScaleRow(label:String,value:Float,onChange:(Float)->Unit,compact:Boolean){
    Row(
        Modifier.fillMaxWidth().heightIn(min=48.dp).padding(vertical=4.dp),
        verticalAlignment=Alignment.CenterVertically
    ){
        Text(label,Modifier.weight(1.05f),maxLines=2)
        Box(
            Modifier.weight(.95f).heightIn(min=48.dp),
            contentAlignment=Alignment.Center
        ){
            Slider(
                value,onChange,
                modifier=Modifier.fillMaxWidth(),
                valueRange=.85f..1.45f,
                steps=3
            )
        }
    }
}
@Composable private fun PinSetupDialog(lang:String,onDismiss:()->Unit,onSet:(String)->Unit){var pin by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"set_pin"))},text={OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(12)},label={Text(AppStrings.t(lang,"pin4"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation())},confirmButton={Button({if(pin.length>=4)onSet(pin)}){Text(AppStrings.t(lang,"confirm"))}},dismissButton={TextButton(onDismiss){Text(AppStrings.t(lang,"cancel"))}})}
@Composable private fun AppLogo(modifier:Modifier=Modifier){
    Image(
        painter=painterResource(R.drawable.ic_app_icon_approved),
        contentDescription=null,
        contentScale=ContentScale.Fit,
        modifier=modifier
    )
}
@Composable private fun SuggestionTextField(
    value:String,
    onValueChange:(String)->Unit,
    label:String,
    suggestions:List<String>,
    onSuggestion:(String)->Unit,
    onDeleteSuggestion:(String)->Unit,
    modifier:Modifier=Modifier
){
    var expanded by remember{mutableStateOf(false)}
    var locallyHidden by remember{mutableStateOf(setOf<String>())}
    val visibleSuggestions=suggestions.filterNot{candidate->locallyHidden.any{it.equals(candidate,ignoreCase=true)}}
    ExposedDropdownMenuBox(expanded=expanded&&visibleSuggestions.isNotEmpty(),onExpandedChange={expanded=it}){
        OutlinedTextField(
            value=value,
            onValueChange={onValueChange(it);expanded=true},
            label={Text(label)},
            singleLine=true,
            textStyle=userTextStyle(),
            keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences),
            trailingIcon={if(visibleSuggestions.isNotEmpty())ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)},
            modifier=modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded=expanded&&visibleSuggestions.isNotEmpty(),onDismissRequest={expanded=false}){
            visibleSuggestions.take(8).forEach{item->
                Row(
                    Modifier.fillMaxWidth().padding(start=4.dp,end=8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ){
                    Text(
                        item,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.weight(1f).clickable{onSuggestion(item);expanded=false}.padding(horizontal=8.dp,vertical=12.dp)
                    )
                    Spacer(Modifier.width(22.dp))
                    IconButton(
                        onClick={locallyHidden=locallyHidden+item;onDeleteSuggestion(item)},
                        modifier=Modifier.size(38.dp)
                    ){
                        Icon(Icons.Default.Close,null,Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}
@Composable private fun RowScope.TopIconAction(icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){
    val uiScale=LocalButtonTextScale.current.coerceIn(.85f,1.45f)
    Box(Modifier.weight(.55f),contentAlignment=Alignment.Center){
        FilledTonalIconButton(
            onClick=onClick,
            colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
            modifier=Modifier.size((40f*uiScale).dp)
        ){Icon(icon,AppStrings.t("en","calendar"),Modifier.size((19f*uiScale).dp))}
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RowScope.TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit,wide:Boolean=false){
    val uiScale=LocalButtonTextScale.current.coerceIn(.85f,1.45f)
    val pillHeight=(40f*uiScale).coerceAtLeast(48f).dp
    FilledTonalButton(
        onClick=onClick,
        shape=RoundedCornerShape((19f*uiScale).dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=(7f*uiScale).dp,vertical=0.dp),
        modifier=Modifier.weight(1f).heightIn(min=pillHeight)
    ){
        Text(
            text,
            modifier=Modifier.fillMaxWidth(),
            softWrap=true,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            style=MaterialTheme.typography.labelLarge
        )
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MoneyTabPill(selected:Boolean,text:String,onClick:()->Unit,modifier:Modifier=Modifier){
    val uiScale=LocalButtonTextScale.current.coerceIn(.85f,1.45f)
    val shape=RoundedCornerShape((15f*uiScale).dp)
    val pillHeight=(32f*uiScale).coerceAtLeast(48f).dp
    val content: @Composable RowScope.() -> Unit={
        Text(
            text,
            modifier=Modifier.fillMaxWidth(),
            softWrap=true,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            style=MaterialTheme.typography.labelLarge
        )
    }
    if(selected) Button(
        onClick=onClick,
        shape=shape,
        colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        contentPadding=PaddingValues(horizontal=3.dp,vertical=0.dp),
        modifier=modifier.heightIn(min=pillHeight),
        content=content
    ) else OutlinedButton(
        onClick=onClick,
        shape=shape,
        colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary),
        contentPadding=PaddingValues(horizontal=3.dp,vertical=0.dp),
        modifier=modifier.heightIn(min=pillHeight),
        content=content
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun BottomTextNavPill(selected:Boolean,text:String,onClick:()->Unit,modifier:Modifier=Modifier){
    val uiScale=LocalButtonTextScale.current.coerceIn(.85f,1.45f)
    val shape=RoundedCornerShape((15f*uiScale).dp)
    val pillHeight=(44f*uiScale).coerceAtLeast(48f).dp
    val content: @Composable RowScope.() -> Unit={
        Text(
            text,
            modifier=Modifier.fillMaxWidth(),
            softWrap=true,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            style=MaterialTheme.typography.labelLarge
        )
    }
    if(selected) Button(onClick=onClick,shape=shape,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp),modifier=modifier.heightIn(min=pillHeight),content=content)
    else OutlinedButton(onClick=onClick,shape=shape,colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.primary),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp),modifier=modifier.heightIn(min=pillHeight),content=content)
}

@Composable private fun noteColor(key:String):Color{
    val dark=MaterialTheme.colorScheme.background.luminance()<0.35f
    return when(key){
        "yellow"->if(dark)Color(0xFF5A4700) else Color(0xFFFFF1A8)
        "green"->if(dark)Color(0xFF194A33) else Color(0xFFD8F6E5)
        "orange"->if(dark)Color(0xFF5A3315) else Color(0xFFFFE0B2)
        "pink"->if(dark)Color(0xFF5A2940) else Color(0xFFFFD6E7)
        "purple"->if(dark)Color(0xFF412C66) else Color(0xFFE7D9FF)
        "red"->Color(0xFFFF3B30)
        "bright_yellow"->Color(0xFFFFD60A)
        "cyan"->Color(0xFF00CFE8)
        else->if(dark)Color(0xFF0A4968) else Color(0xFFD9F2FF)
    }
}
@Composable private fun noteCardColors(key:String):CardColors{
    val c=noteColor(key)
    return CardDefaults.cardColors(containerColor=c,contentColor=if(c.luminance()<.48f)Color.White else Color.Black)
}

@Composable private fun brandCardColors(tone:Int=0)=CardDefaults.cardColors(containerColor=when(tone%3){0->MaterialTheme.colorScheme.primaryContainer;1->MaterialTheme.colorScheme.tertiaryContainer;else->MaterialTheme.colorScheme.secondaryContainer})
@Composable private fun organizerTopBarColors()=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.secondaryContainer,titleContentColor=MaterialTheme.colorScheme.onSecondaryContainer,navigationIconContentColor=MaterialTheme.colorScheme.onSecondaryContainer,actionIconContentColor=MaterialTheme.colorScheme.onSecondaryContainer)
private fun syncAndroidAppLocale(context:android.content.Context,lang:String){
    if(Build.VERSION.SDK_INT<33)return
    val tag=AppStrings.localeTag(lang)
    val manager=context.getSystemService(LocaleManager::class.java)
    if(manager.applicationLocales.toLanguageTags()!=tag){manager.applicationLocales=LocaleList.forLanguageTags(tag)}
}

@Composable private fun EmptyState(text:String){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
private fun appendRecognizedText(current:String,recognized:String):String{
    val part=capitalizeSentences(recognized.trim())
    if(part.isBlank()) return current
    return if(current.isBlank()) part else current.trimEnd()+" "+part
}
private fun formatTime(ms:Long,lang:String)=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,AppStrings.locale(lang)).format(Date(ms))
private fun formatDate(ms:Long,lang:String)=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT,AppStrings.locale(lang)).format(Date(ms))
private fun humanDuration(ms:Long):String{val s=(ms/1000).coerceAtLeast(0);return "%02d:%02d".format(s/60,s%60)}
private fun formatAmountInput(input:String):String=MoneyAmounts.formatInput(input)
private fun parseAmountInput(input:String):Double?=MoneyAmounts.parse(input)
private fun capitalizeSentences(input:String):String{
    if(input.isEmpty())return input
    val out=StringBuilder(input.length);var capitalizeNext=true
    input.forEach{ch->
        val c=if(capitalizeNext&&ch.isLetter())ch.uppercaseChar() else ch
        out.append(c)
        if(ch.isLetterOrDigit())capitalizeNext=false
        if(ch=='.'||ch=='!'||ch=='?'||ch=='\n')capitalizeNext=true
    }
    return out.toString()
}
private fun toast(context:android.content.Context,text:String)=Toast.makeText(context,text,Toast.LENGTH_SHORT).show()
