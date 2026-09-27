from pathlib import Path
import sys
root=Path(sys.argv[1])
here=Path(__file__).parent
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
cal=root/'app/src/main/java/kz/kairat/organizer/CalendarSystem.kt'
calui=root/'app/src/main/java/kz/kairat/organizer/CalendarUi.kt'
main=root/'app/src/main/java/kz/kairat/organizer/MainActivity.kt'
rem=root/'app/src/main/java/kz/kairat/organizer/ReminderSystem.kt'
manifest=root/'app/src/main/AndroidManifest.xml'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

cal.write_text((here/'CalendarSystem.kt').read_text())
calui.write_text((here/'CalendarUi.kt').read_text())

# Main UI: icon-only calendar button in the top bar, floating popup, and project-stage deep link.
s=ui.read_text()
old='''@Composable private fun OrganizerHome(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,onLock:()->Unit){
    var section by rememberSaveable{mutableStateOf("notes")}'''
new='''@Composable private fun OrganizerHome(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,onLock:()->Unit){
    val context=LocalContext.current
    var section by rememberSaveable{mutableStateOf("notes")}'''
assert old in s
s=s.replace(old,new,1)
old='''    var projectId by rememberSaveable{mutableStateOf<Long?>(null)}
    var showNewProject by rememberSaveable{mutableStateOf(false)}'''
new='''    var projectId by rememberSaveable{mutableStateOf<Long?>(null)}
    var projectInitialTab by rememberSaveable{mutableIntStateOf(0)}
    var showCalendar by rememberSaveable{mutableStateOf(false)}
    var calendarPermissionRevision by remember{mutableIntStateOf(0)}
    val hasCalendarPermission=remember(calendarPermissionRevision){OrganizerCalendarRepository.hasCalendarPermission(context)}
    val calendarPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        calendarPermissionRevision++
        if(granted)BirthdayScheduler.rescheduleAll(context)
    }
    var showNewProject by rememberSaveable{mutableStateOf(false)}'''
assert old in s
s=s.replace(old,new,1)
old='''    projectId?.let{id->ProjectDetailScreen(db,id,lang,onBack={projectId=null;projectRevision++},onDeleted={projectId=null;projectRevision++});return}'''
new='''    projectId?.let{id->ProjectDetailScreen(db,id,lang,projectInitialTab,onBack={projectId=null;projectInitialTab=0;projectRevision++},onDeleted={projectId=null;projectInitialTab=0;projectRevision++});return}'''
assert old in s
s=s.replace(old,new,1)
old='''                projectRevision++
                projectId=id'''
new='''                projectRevision++
                projectInitialTab=0
                projectId=id'''
assert old in s
s=s.replace(old,new,1)
old='''                    if(section!="settings") AppLogo(Modifier.size((36f*fontScale.coerceIn(.85f,1.45f)).dp))
                    Spacer(Modifier.weight(1f))
                    if(section!="settings") TopActionPill(AppStrings.t(lang,"reminders"),Icons.Default.Notifications,{showReminders=true},wide=(section=="money" && (moneyTab==0 || moneyTab==3)))'''
new='''                    if(section!="settings") AppLogo(Modifier.size((36f*fontScale.coerceIn(.85f,1.45f)).dp))
                    Spacer(Modifier.weight(1f))
                    TopIconAction(Icons.Default.CalendarMonth){
                        showCalendar=true
                        if(hasCalendarPermission)BirthdayScheduler.rescheduleAll(context) else calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }
                    if(section!="settings") TopActionPill(AppStrings.t(lang,"reminders"),Icons.Default.Notifications,{showReminders=true},wide=(section=="money" && (moneyTab==0 || moneyTab==3)))'''
assert old in s
s=s.replace(old,new,1)
old='''        "projects"->if(projectsEnabled) ProjectsScreen(db,lang,projectRevision,onOpen={projectId=it}) else NotesScreen(db,lang,onOpen={readerId=it})'''
new='''        "projects"->if(projectsEnabled) ProjectsScreen(db,lang,projectRevision,onOpen={projectInitialTab=0;projectId=it}) else NotesScreen(db,lang,onOpen={readerId=it})'''
assert old in s
s=s.replace(old,new,1)
old='''        else->NotesScreen(db,lang,onOpen={readerId=it})
    }}}
}'''
new='''        else->NotesScreen(db,lang,onOpen={readerId=it})
    }}}
    if(showCalendar)OrganizerCalendarPopup(
        db=db,lang=lang,hasSystemCalendarAccess=hasCalendarPermission,refreshKey=calendarPermissionRevision,
        onRequestCalendarAccess={calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)},
        onDismiss={showCalendar=false},
        onOpenProject={id,stages->showCalendar=false;projectInitialTab=if(stages)1 else 0;projectId=id}
    )
}'''
assert old in s
s=s.replace(old,new,1)
old='''@Composable private fun ProjectDetailScreen(db:NoteDatabase,projectId:Long,lang:String,onBack:()->Unit,onDeleted:()->Unit){'''
new='''@Composable private fun ProjectDetailScreen(db:NoteDatabase,projectId:Long,lang:String,initialTab:Int=0,onBack:()->Unit,onDeleted:()->Unit){'''
assert old in s
s=s.replace(old,new,1)
old='''    var tab by rememberSaveable{mutableIntStateOf(0)}'''
new='''    var tab by rememberSaveable(projectId,initialTab){mutableIntStateOf(initialTab.coerceIn(0,3))}'''
assert old in s
s=s.replace(old,new,1)
marker='''@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit,wide:Boolean=false){'''
insert='''@Composable private fun TopIconAction(icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){
    val uiScale=LocalDensity.current.fontScale.coerceIn(.85f,1.45f)
    FilledTonalIconButton(
        onClick=onClick,
        colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),
        modifier=Modifier.size((40f*uiScale).dp)
    ){Icon(icon,AppStrings.t("en","calendar"),Modifier.size((19f*uiScale).dp))}
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TopActionPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit,wide:Boolean=false){'''
assert marker in s
s=s.replace(marker,insert,1)
ui.write_text(s)

# Main activity reschedules birthday-only alerts whenever calendar access exists.
mn=main.read_text()
old='''        ReminderNotifications.ensureChannel(this)
        ReminderScheduler.rescheduleAll(this)
    }'''
new='''        ReminderNotifications.ensureChannel(this)
        ReminderScheduler.rescheduleAll(this)
        if(OrganizerCalendarRepository.hasCalendarPermission(this)) BirthdayScheduler.rescheduleAll(this)
    }'''
assert old in mn
mn=mn.replace(old,new,1)
main.write_text(mn)

# Boot/package/exact-alarm receiver also restores birthday alarms; normal reminder behavior remains unchanged.
r=rem.read_text()
old='''class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){ReminderNotifications.ensureChannel(context);ReminderScheduler.rescheduleAll(context)}}'''
new='''class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){ReminderNotifications.ensureChannel(context);ReminderScheduler.rescheduleAll(context);if(OrganizerCalendarRepository.hasCalendarPermission(context))BirthdayScheduler.rescheduleAll(context)}}'''
assert old in r
r=r.replace(old,new,1)
rem.write_text(r)

# Read-only access to Android/Google-synced calendars. No calendar write permission is requested.
m=manifest.read_text()
old='''    <uses-permission android:name="android.permission.RECORD_AUDIO" />'''
new='''    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.READ_CALENDAR" />'''
assert old in m
m=m.replace(old,new,1)
old='''        <receiver android:name=".ReminderReceiver" android:exported="false" />'''
new='''        <receiver android:name=".ReminderReceiver" android:exported="false" />
        <receiver android:name=".BirthdayReceiver" android:exported="false" />'''
assert old in m
m=m.replace(old,new,1)
manifest.write_text(m)

# Calendar strings in all eight supported languages, kept separate from the older maps.
a=strings.read_text()
marker='''    private val maps = mapOf("ru" to ru, "kk" to kk, "en" to en, "zh" to zh, "de" to de, "es" to es, "fr" to fr, "tr" to tr)'''
extras='''    private val calendarExtras = mapOf(
        "ru" to mapOf("calendar" to "Календарь","calendar_permission" to "Разрешите доступ к календарю телефона, чтобы показать события Google Календаря, праздники и дни рождения.","allow" to "Разрешить","today" to "Сегодня","no_events_day" to "На этот день событий нет","birthdays" to "Дни рождения","birthday_tomorrow" to "Завтра день рождения","birthday_notice" to "Дни рождения: уведомление за 1 день в 09:00","project_stage" to "Этап проекта","project_due" to "Срок проекта","all_day" to "Весь день"),
        "kk" to mapOf("calendar" to "Күнтізбе","calendar_permission" to "Google Күнтізбе оқиғаларын, мерекелер мен туған күндерді көрсету үшін телефон күнтізбесіне рұқсат беріңіз.","allow" to "Рұқсат беру","today" to "Бүгін","no_events_day" to "Бұл күнге оқиға жоқ","birthdays" to "Туған күндер","birthday_tomorrow" to "Ертең туған күн","birthday_notice" to "Туған күндер: 1 күн бұрын сағат 09:00-де ескерту","project_stage" to "Жоба кезеңі","project_due" to "Жоба мерзімі","all_day" to "Күні бойы"),
        "en" to mapOf("calendar" to "Calendar","calendar_permission" to "Allow calendar access to show Google Calendar events, holidays and birthdays.","allow" to "Allow","today" to "Today","no_events_day" to "No events for this day","birthdays" to "Birthdays","birthday_tomorrow" to "Birthday tomorrow","birthday_notice" to "Birthdays: alert 1 day before at 09:00","project_stage" to "Project stage","project_due" to "Project due date","all_day" to "All day"),
        "zh" to mapOf("calendar" to "日历","calendar_permission" to "允许访问手机日历，以显示 Google 日历事件、节假日和生日。","allow" to "允许","today" to "今天","no_events_day" to "当天没有事件","birthdays" to "生日","birthday_tomorrow" to "明天有生日","birthday_notice" to "生日：提前 1 天 09:00 提醒","project_stage" to "项目阶段","project_due" to "项目截止日期","all_day" to "全天"),
        "de" to mapOf("calendar" to "Kalender","calendar_permission" to "Kalenderzugriff erlauben, um Google-Kalender-Termine, Feiertage und Geburtstage anzuzeigen.","allow" to "Erlauben","today" to "Heute","no_events_day" to "Keine Termine an diesem Tag","birthdays" to "Geburtstage","birthday_tomorrow" to "Morgen ist Geburtstag","birthday_notice" to "Geburtstage: Hinweis 1 Tag vorher um 09:00","project_stage" to "Projektphase","project_due" to "Projektfrist","all_day" to "Ganztägig"),
        "es" to mapOf("calendar" to "Calendario","calendar_permission" to "Permite el acceso al calendario para mostrar eventos de Google Calendar, festivos y cumpleaños.","allow" to "Permitir","today" to "Hoy","no_events_day" to "No hay eventos este día","birthdays" to "Cumpleaños","birthday_tomorrow" to "Cumpleaños mañana","birthday_notice" to "Cumpleaños: aviso 1 día antes a las 09:00","project_stage" to "Etapa del proyecto","project_due" to "Fecha límite del proyecto","all_day" to "Todo el día"),
        "fr" to mapOf("calendar" to "Calendrier","calendar_permission" to "Autorisez l’accès au calendrier pour afficher les événements Google Agenda, les jours fériés et les anniversaires.","allow" to "Autoriser","today" to "Aujourd’hui","no_events_day" to "Aucun événement ce jour","birthdays" to "Anniversaires","birthday_tomorrow" to "Anniversaire demain","birthday_notice" to "Anniversaires : alerte 1 jour avant à 09:00","project_stage" to "Étape du projet","project_due" to "Échéance du projet","all_day" to "Toute la journée"),
        "tr" to mapOf("calendar" to "Takvim","calendar_permission" to "Google Takvim etkinliklerini, tatilleri ve doğum günlerini göstermek için takvim erişimine izin verin.","allow" to "İzin ver","today" to "Bugün","no_events_day" to "Bu gün için etkinlik yok","birthdays" to "Doğum günleri","birthday_tomorrow" to "Yarın doğum günü","birthday_notice" to "Doğum günleri: 1 gün önce saat 09:00’da bildirim","project_stage" to "Proje aşaması","project_due" to "Proje son tarihi","all_day" to "Tüm gün")
    )

    private val maps = mapOf("ru" to ru, "kk" to kk, "en" to en, "zh" to zh, "de" to de, "es" to es, "fr" to fr, "tr" to tr)'''
assert marker in a
a=a.replace(marker,extras,1)
old='''    fun t(lang:String,key:String):String { val code=normalizeLanguage(lang); return v093Extras[code]?.get(key) ?: maps[code]?.get(key) ?: en[key] ?: ru[key] ?: key }'''
new='''    fun t(lang:String,key:String):String { val code=normalizeLanguage(lang); return calendarExtras[code]?.get(key) ?: v093Extras[code]?.get(key) ?: maps[code]?.get(key) ?: en[key] ?: ru[key] ?: key }'''
assert old in a
a=a.replace(old,new,1)
assert '0.9.21 (54)' in a
a=a.replace('0.9.21 (54)','0.9.22 (55)')
strings.write_text(a)

b=gradle.read_text()
assert 'versionCode = 54' in b and 'versionName = "0.9.21"' in b
b=b.replace('versionCode = 54','versionCode = 55').replace('versionName = "0.9.21"','versionName = "0.9.22"')
gradle.write_text(b)

# Gates: calendar is a top button/popup, Google/system calendar is read-only, only birthdays are scheduled, and project deadlines are live-linked.
s=ui.read_text(); c=cal.read_text(); cu=calui.read_text(); mn=main.read_text(); r=rem.read_text(); m=manifest.read_text(); a=strings.read_text(); b=gradle.read_text()
assert 'TopIconAction(Icons.Default.CalendarMonth)' in s
assert 'OrganizerCalendarPopup(' in s
assert 'projectInitialTab=if(stages)1 else 0' in s
assert 'READ_CALENDAR' in m and 'WRITE_CALENDAR' not in m
assert 'CalendarContract.Instances.CONTENT_URI' in c
assert 'kind=if(birthday)"birthday" else "system"' in c
assert 'db.getAllProjectStages()' in c and 'db.getProjects()' in c
assert 'add(Calendar.DAY_OF_YEAR,-1)' in c and 'set(Calendar.HOUR_OF_DAY,9)' in c
assert 'filter{it.kind=="birthday"}' in c
assert 'BirthdayReceiver' in m
assert 'BirthdayScheduler.rescheduleAll(this)' in mn
assert 'BirthdayScheduler.rescheduleAll(context)' in r
assert 'fillMaxHeight(.72f)' in cu
assert 'birthday_notice' in a
assert 'versionCode = 55' in b and 'versionName = "0.9.22"' in b
assert '0.9.22 (55)' in a
