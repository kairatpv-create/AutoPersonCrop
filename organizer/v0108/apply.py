from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
settings=root/'app/src/main/java/kz/kairat/organizer/SettingsStore.kt'
models=root/'app/src/main/java/kz/kairat/organizer/Models.kt'
backup=root/'app/src/main/java/kz/kairat/organizer/BackupManager.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text(); st=settings.read_text(); m=models.read_text(); bk=backup.read_text(); a=strings.read_text(); b=gradle.read_text()

# Persist the feature visibility separately from project data. Existing installs default to visible.
old='''    var boldText: Boolean\n        get() = prefs.getBoolean("bold_text", false)\n        set(v) = prefs.edit().putBoolean("bold_text", v).apply()\n    var backupMode: String'''
new='''    var boldText: Boolean\n        get() = prefs.getBoolean("bold_text", false)\n        set(v) = prefs.edit().putBoolean("bold_text", v).apply()\n    var projectsEnabled: Boolean\n        get() = prefs.getBoolean("projects_enabled", true)\n        set(v) = prefs.edit().putBoolean("projects_enabled", v).apply()\n    var backupMode: String'''
assert old in st
st=st.replace(old,new,1)
settings.write_text(st)

# Carry the Projects visibility preference through backup/restore; older backups remain compatible.
old='''    val boldText: Boolean?,\n    val language: String? = null\n)'''
new='''    val boldText: Boolean?,\n    val language: String? = null,\n    val projectsEnabled: Boolean? = null\n)'''
assert old in m
m=m.replace(old,new,1)
models.write_text(m)

old='''put("boldText",settings.boldText);put("backupMode",settings.backupMode);put("email",settings.backupEmail);put("language",settings.language)'''
new='''put("boldText",settings.boldText);put("projectsEnabled",settings.projectsEnabled);put("backupMode",settings.backupMode);put("email",settings.backupEmail);put("language",settings.language)'''
assert old in bk
bk=bk.replace(old,new,1)
old='''return RestoredPreferences(if(s.has("darkTheme"))s.optBoolean("darkTheme")else null,if(s.has("fontScale"))s.optDouble("fontScale").toFloat()else null,s.optString("backupMode","manual"),s.optString("email",""),if(s.has("boldText"))s.optBoolean("boldText")else null,s.optString("language",null))'''
new='''return RestoredPreferences(if(s.has("darkTheme"))s.optBoolean("darkTheme")else null,if(s.has("fontScale"))s.optDouble("fontScale").toFloat()else null,s.optString("backupMode","manual"),s.optString("email",""),if(s.has("boldText"))s.optBoolean("boldText")else null,s.optString("language",null),if(s.has("projectsEnabled"))s.optBoolean("projectsEnabled")else null)'''
assert old in bk
bk=bk.replace(old,new,1)
backup.write_text(bk)

# OrganizerHome owns the live visibility state so the bottom bar changes immediately.
old='''    var moneyTab by rememberSaveable{mutableIntStateOf(0)}\n    var moneyRevision by remember{mutableIntStateOf(0)}'''
new='''    var moneyTab by rememberSaveable{mutableIntStateOf(0)}\n    var moneyRevision by remember{mutableIntStateOf(0)}\n    var projectsEnabled by rememberSaveable{mutableStateOf(settings.projectsEnabled)}\n    LaunchedEffect(projectsEnabled){if(!projectsEnabled&&section=="projects")section="notes"}'''
assert old in s
s=s.replace(old,new,1)

old='''                    BottomTextNavPill(section=="notes",AppStrings.t(lang,"notes"),{section="notes"},Modifier.weight(1f))\n                    BottomTextNavPill(section=="projects",AppStrings.t(lang,"projects"),{section="projects"},Modifier.weight(1f))\n                    BottomTextNavPill(section=="money",AppStrings.t(lang,"money"),{section="money"},Modifier.weight(1f))'''
new='''                    BottomTextNavPill(section=="notes",AppStrings.t(lang,"notes"),{section="notes"},Modifier.weight(1f))\n                    if(projectsEnabled) BottomTextNavPill(section=="projects",AppStrings.t(lang,"projects"),{section="projects"},Modifier.weight(1f))\n                    BottomTextNavPill(section=="money",AppStrings.t(lang,"money"),{section="money"},Modifier.weight(1f))'''
assert old in s
s=s.replace(old,new,1)

old='''        "projects"->ProjectsScreen(db,lang,projectRevision,onOpen={projectId=it})\n        "money"->MoneyScreen(db,lang,moneyRevision,moneyTab){moneyTab=it}\n        "settings"->SettingsScreen(db,settings,lang,onLanguage,dark,onDark,fontScale,onFont,bold,onBold)'''
new='''        "projects"->if(projectsEnabled) ProjectsScreen(db,lang,projectRevision,onOpen={projectId=it}) else NotesScreen(db,lang,onOpen={readerId=it})\n        "money"->MoneyScreen(db,lang,moneyRevision,moneyTab){moneyTab=it}\n        "settings"->SettingsScreen(db,settings,lang,onLanguage,dark,onDark,fontScale,onFont,bold,onBold,projectsEnabled){enabled->settings.projectsEnabled=enabled;projectsEnabled=enabled;if(!enabled&&section=="projects")section="notes"}'''
assert old in s
s=s.replace(old,new,1)

# Settings toggle and restore hook.
old='''@Composable private fun SettingsScreen(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit){'''
new='''@Composable private fun SettingsScreen(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,projectsEnabled:Boolean,onProjectsEnabled:(Boolean)->Unit){'''
assert old in s
s=s.replace(old,new,1)
old='''p.boldText?.let(onBold);p.language?.let(onLanguage);settings.backupEmail=p.email;settings.backupMode=p.backupMode;ReminderScheduler.rescheduleAll(context)'''
new='''p.boldText?.let(onBold);p.language?.let(onLanguage);p.projectsEnabled?.let(onProjectsEnabled);settings.backupEmail=p.email;settings.backupMode=p.backupMode;ReminderScheduler.rescheduleAll(context)'''
assert old in s
s=s.replace(old,new,1)
old='''item{SettingsCard("",verticalPadding=4.dp,spacing=1.dp){SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark);SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold);Row(Modifier.fillMaxWidth().heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically){Text(AppStrings.t(lang,"font"),Modifier.weight(.72f));Slider(fontScale,onFont,modifier=Modifier.weight(1.28f).heightIn(min=28.dp),valueRange=.85f..1.45f,steps=3)}}}'''
new='''item{SettingsCard("",verticalPadding=4.dp,spacing=1.dp){SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark);SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold);Row(Modifier.fillMaxWidth().heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically){Text(AppStrings.t(lang,"font"),Modifier.weight(.72f));Slider(fontScale,onFont,modifier=Modifier.weight(1.28f).heightIn(min=28.dp),valueRange=.85f..1.45f,steps=3)};SettingSwitch(AppStrings.t(lang,"show_projects"),projectsEnabled,onProjectsEnabled)}}'''
assert old in s
s=s.replace(old,new,1)
ui.write_text(s)

# Localized label for all eight supported languages (kept in the existing override map).
translations={
    'kk':'Жобаларды көрсету',
    'ru':'Показывать проекты',
    'en':'Show projects',
    'zh':'显示项目',
    'de':'Projekte anzeigen',
    'es':'Mostrar proyectos',
    'fr':'Afficher les projets',
    'tr':'Projeleri göster',
}
for code,text in translations.items():
    marker=f'''        "{code}" to mapOf(\n            "version" to '''
    idx=a.find(marker)
    assert idx!=-1, code
    version_start=idx+len(f'''        "{code}" to mapOf(\n            ''')
    line_end=a.find('\n',version_start)
    line=a[version_start:line_end]
    assert '"version" to ' in line and '"show_projects"' not in line
    if line.rstrip().endswith(','):
        line=line.rstrip()+f' "show_projects" to "{text}",'
    else:
        line=line+f', "show_projects" to "{text}",'
    a=a[:version_start]+line+a[line_end:]

# Version bump in all translated version strings.
assert '0.9.17 (50)' in a
a=a.replace('0.9.17 (50)','0.9.18 (51)')
strings.write_text(a)

# Version bump only; applicationId/database stay unchanged.
assert 'versionCode = 50' in b and 'versionName = "0.9.17"' in b
b=b.replace('versionCode = 50','versionCode = 51').replace('versionName = "0.9.17"','versionName = "0.9.18"')
gradle.write_text(b)

# Gates.
s=ui.read_text(); st=settings.read_text(); m=models.read_text(); bk=backup.read_text(); a=strings.read_text(); b=gradle.read_text()
assert 'var projectsEnabled: Boolean' in st and 'prefs.getBoolean("projects_enabled", true)' in st
assert 'val projectsEnabled: Boolean? = null' in m
assert 'put("projectsEnabled",settings.projectsEnabled)' in bk
assert 'if(s.has("projectsEnabled"))s.optBoolean("projectsEnabled")else null' in bk
assert 'if(projectsEnabled) BottomTextNavPill(section=="projects"' in s
assert 'AppStrings.t(lang,"show_projects"),projectsEnabled,onProjectsEnabled' in s
assert 'p.projectsEnabled?.let(onProjectsEnabled)' in s
assert all(f'"show_projects" to "{v}"' in a for v in translations.values())
assert 'versionCode = 51' in b and 'versionName = "0.9.18"' in b
assert '0.9.18 (51)' in a
