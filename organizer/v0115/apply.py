from pathlib import Path
import sys
root=Path(sys.argv[1])
base=root/'app/src/main/java/kz/kairat/organizer'
ui=base/'OrganizerUi.kt'
theme=base/'Theme.kt'
settings=base/'SettingsStore.kt'
backup=base/'BackupManager.kt'
models=base/'Models.kt'
strings=base/'AppStrings.kt'
gradle=root/'app/build.gradle.kts'

# SettingsStore: keep legacy fontScale as content scale and add independent button scale.
s=settings.read_text()
old='''    var fontScale: Float
        get() = prefs.getFloat("font_scale", 1f)
        set(v) = prefs.edit().putFloat("font_scale", v.coerceIn(.85f, 1.45f)).apply()
'''
new='''    var fontScale: Float
        get() = prefs.getFloat("content_font_scale", prefs.getFloat("font_scale", 1f))
        set(v) {
            val value=v.coerceIn(.85f,1.45f)
            prefs.edit().putFloat("content_font_scale",value).putFloat("font_scale",value).apply()
        }
    var buttonFontScale: Float
        get() = prefs.getFloat("button_font_scale", prefs.getFloat("font_scale", 1f))
        set(v) = prefs.edit().putFloat("button_font_scale", v.coerceIn(.85f, 1.45f)).apply()
'''
assert old in s
settings.write_text(s.replace(old,new,1))

# Theme: independent content and button text scales, one global bold toggle.
t=theme.read_text()
t=t.replace('import androidx.compose.runtime.CompositionLocalProvider\n','import androidx.compose.runtime.CompositionLocalProvider\nimport androidx.compose.runtime.staticCompositionLocalOf\n',1)
start=t.index('@Composable fun OrganizerTheme')
new_theme='''val LocalButtonTextScale=staticCompositionLocalOf{1f}
val LocalContentTextScale=staticCompositionLocalOf{1f}

@Composable fun OrganizerTheme(dark:Boolean=isSystemInDarkTheme(),contentScale:Float=1f,buttonScale:Float=1f,bold:Boolean=false,content: @Composable () -> Unit){
    val density=LocalDensity.current
    val contentTextScale=contentScale.coerceIn(.85f,1.45f)
    val buttonTextScale=buttonScale.coerceIn(.85f,1.45f)
    val weight=if(bold)FontWeight.SemiBold else FontWeight.Normal
    val strong=if(bold)FontWeight.Bold else FontWeight.SemiBold
    val contentSize=(14f*contentTextScale).sp
    val contentTitle=(15f*contentTextScale).sp
    val buttonSize=(15f*buttonTextScale).sp
    val type=Typography(
        displayLarge=TextStyle(fontSize=contentTitle,fontWeight=strong),
        displayMedium=TextStyle(fontSize=contentTitle,fontWeight=strong),
        displaySmall=TextStyle(fontSize=contentTitle,fontWeight=strong),
        headlineLarge=TextStyle(fontSize=contentTitle,fontWeight=strong),
        headlineMedium=TextStyle(fontSize=contentTitle,fontWeight=strong),
        headlineSmall=TextStyle(fontSize=contentTitle,fontWeight=strong),
        titleLarge=TextStyle(fontSize=contentTitle,fontWeight=strong),
        titleMedium=TextStyle(fontSize=contentTitle,fontWeight=strong),
        titleSmall=TextStyle(fontSize=contentTitle,fontWeight=strong),
        bodyLarge=TextStyle(fontSize=contentSize,fontWeight=weight),
        bodyMedium=TextStyle(fontSize=contentSize,fontWeight=weight),
        bodySmall=TextStyle(fontSize=contentSize,fontWeight=weight),
        labelLarge=TextStyle(fontSize=buttonSize,fontWeight=strong),
        labelMedium=TextStyle(fontSize=buttonSize,fontWeight=strong),
        labelSmall=TextStyle(fontSize=contentSize,fontWeight=weight)
    )
    CompositionLocalProvider(
        LocalDensity provides Density(density.density,1f),
        LocalButtonTextScale provides buttonTextScale,
        LocalContentTextScale provides contentTextScale
    ){MaterialTheme(colorScheme=if(dark)Dark else Light,typography=type,content=content)}
}
'''
t=t[:start]+new_theme
theme.write_text(t)

# Models: retain legacy restored fontScale and add buttonFontScale at end for backward compatibility.
m=models.read_text()
old='''    val language: String? = null,
    val projectsEnabled: Boolean? = null
)'''
new='''    val language: String? = null,
    val projectsEnabled: Boolean? = null,
    val buttonFontScale: Float? = null
)'''
assert old in m
models.write_text(m.replace(old,new,1))

# Backup: store both independent values, retain legacy fontScale for old backups.
b=backup.read_text()
assert 'root.put("version",5)' in b
b=b.replace('root.put("version",5)','root.put("version",6)',1)
old='''root.put("settings",JSONObject().apply{put("darkTheme",settings.darkTheme);put("fontScale",settings.fontScale);put("boldText",settings.boldText);put("projectsEnabled",settings.projectsEnabled);put("backupMode",settings.backupMode);put("email",settings.backupEmail);put("language",settings.language)})'''
new='''root.put("settings",JSONObject().apply{put("darkTheme",settings.darkTheme);put("fontScale",settings.fontScale);put("contentFontScale",settings.fontScale);put("buttonFontScale",settings.buttonFontScale);put("boldText",settings.boldText);put("projectsEnabled",settings.projectsEnabled);put("backupMode",settings.backupMode);put("email",settings.backupEmail);put("language",settings.language)})'''
assert old in b
b=b.replace(old,new,1)
old='''        val s=root.optJSONObject("settings")?:JSONObject(); return RestoredPreferences(if(s.has("darkTheme"))s.optBoolean("darkTheme")else null,if(s.has("fontScale"))s.optDouble("fontScale").toFloat()else null,s.optString("backupMode","manual"),s.optString("email",""),if(s.has("boldText"))s.optBoolean("boldText")else null,s.optString("language",null),if(s.has("projectsEnabled"))s.optBoolean("projectsEnabled")else null)'''
new='''        val s=root.optJSONObject("settings")?:JSONObject()
        val restoredContentScale=when{
            s.has("contentFontScale")->s.optDouble("contentFontScale").toFloat()
            s.has("fontScale")->s.optDouble("fontScale").toFloat()
            else->null
        }
        val restoredButtonScale=when{
            s.has("buttonFontScale")->s.optDouble("buttonFontScale").toFloat()
            s.has("fontScale")->s.optDouble("fontScale").toFloat()
            else->null
        }
        return RestoredPreferences(if(s.has("darkTheme"))s.optBoolean("darkTheme")else null,restoredContentScale,s.optString("backupMode","manual"),s.optString("email",""),if(s.has("boldText"))s.optBoolean("boldText")else null,s.optString("language",null),if(s.has("projectsEnabled"))s.optBoolean("projectsEnabled")else null,restoredButtonScale)'''
assert old in b
backup.write_text(b.replace(old,new,1))

# AppStrings: add two labels in all 8 languages and bump visible version.
a=strings.read_text()
assert a.count('0.9.24 (57)')==8
a=a.replace('0.9.24 (57)','0.9.25 (58)')
repls={
'"show_projects" to "Жобаларды көрсету",':'"show_projects" to "Жобаларды көрсету", "button_font" to "Түймелер мәтінінің өлшемі", "content_font" to "Терезелердегі мәтін өлшемі",',
'"show_projects" to "Показывать проекты",':'"show_projects" to "Показывать проекты", "button_font" to "Размер текста кнопок", "content_font" to "Размер текста в окнах",',
'"show_projects" to "Show projects",':'"show_projects" to "Show projects", "button_font" to "Button text size", "content_font" to "Window text size",',
'"show_projects" to "显示项目",':'"show_projects" to "显示项目", "button_font" to "按钮文字大小", "content_font" to "窗口文字大小",',
'"show_projects" to "Projekte anzeigen",':'"show_projects" to "Projekte anzeigen", "button_font" to "Schaltflächentextgröße", "content_font" to "Textgröße in Fenstern",',
'"show_projects" to "Mostrar proyectos",':'"show_projects" to "Mostrar proyectos", "button_font" to "Tamaño del texto de botones", "content_font" to "Tamaño del texto en ventanas",',
'"show_projects" to "Afficher les projets",':'"show_projects" to "Afficher les projets", "button_font" to "Taille du texte des boutons", "content_font" to "Taille du texte des fenêtres",',
'"show_projects" to "Projeleri göster",':'"show_projects" to "Projeleri göster", "button_font" to "Düğme metni boyutu", "content_font" to "Pencere metni boyutu",',
}
for old,new in repls.items():
    assert old in a, old
    a=a.replace(old,new,1)
strings.write_text(a)

# UI state and wiring.
u=ui.read_text()
old='''    var dark by rememberSaveable{mutableStateOf(store.darkTheme)}; var font by rememberSaveable{mutableFloatStateOf(store.fontScale)}; var bold by rememberSaveable{mutableStateOf(store.boldText)}; var lang by rememberSaveable{mutableStateOf(store.language)}'''
new='''    var dark by rememberSaveable{mutableStateOf(store.darkTheme)}; var font by rememberSaveable{mutableFloatStateOf(store.fontScale)}; var buttonFont by rememberSaveable{mutableFloatStateOf(store.buttonFontScale)}; var bold by rememberSaveable{mutableStateOf(store.boldText)}; var lang by rememberSaveable{mutableStateOf(store.language)}'''
assert old in u; u=u.replace(old,new,1)
old='''    OrganizerTheme(dark,font,bold){'''
new='''    OrganizerTheme(dark,font,buttonFont,bold){'''
assert old in u; u=u.replace(old,new,1)
old='''        else OrganizerHome(db,store,lang,onLanguage={newLang->store.language=newLang;lang=newLang;syncAndroidAppLocale(context,newLang)},dark=dark,onDark={dark=it;store.darkTheme=it},fontScale=font,onFont={font=it;store.fontScale=it},bold=bold,onBold={bold=it;store.boldText=it},onLock={unlocked=false})'''
new='''        else OrganizerHome(db,store,lang,onLanguage={newLang->store.language=newLang;lang=newLang;syncAndroidAppLocale(context,newLang)},dark=dark,onDark={dark=it;store.darkTheme=it},fontScale=font,onFont={font=it;store.fontScale=it},buttonScale=buttonFont,onButtonScale={buttonFont=it;store.buttonFontScale=it},bold=bold,onBold={bold=it;store.boldText=it},onLock={unlocked=false})'''
assert old in u; u=u.replace(old,new,1)
old='''@Composable private fun OrganizerHome(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,onLock:()->Unit){'''
new='''@Composable private fun OrganizerHome(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,buttonScale:Float,onButtonScale:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,onLock:()->Unit){'''
assert old in u; u=u.replace(old,new,1)
u=u.replace('vertical=(6f*fontScale.coerceIn(.85f,1.45f)).dp','vertical=(6f*buttonScale.coerceIn(.85f,1.45f)).dp',1)
u=u.replace('vertical=(4f*fontScale.coerceIn(.85f,1.45f)).dp','vertical=(4f*buttonScale.coerceIn(.85f,1.45f)).dp',1)
old='''        "settings"->SettingsScreen(db,settings,lang,onLanguage,dark,onDark,fontScale,onFont,bold,onBold,projectsEnabled){enabled->settings.projectsEnabled=enabled;projectsEnabled=enabled;if(!enabled&&section=="projects")section="notes"}'''
new='''        "settings"->SettingsScreen(db,settings,lang,onLanguage,dark,onDark,fontScale,onFont,buttonScale,onButtonScale,bold,onBold,projectsEnabled){enabled->settings.projectsEnabled=enabled;projectsEnabled=enabled;if(!enabled&&section=="projects")section="notes"}'''
assert old in u; u=u.replace(old,new,1)

old='''@Composable private fun SettingsScreen(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,projectsEnabled:Boolean,onProjectsEnabled:(Boolean)->Unit){'''
new='''@Composable private fun SettingsScreen(db:NoteDatabase,settings:SettingsStore,lang:String,onLanguage:(String)->Unit,dark:Boolean,onDark:(Boolean)->Unit,fontScale:Float,onFont:(Float)->Unit,buttonScale:Float,onButtonScale:(Float)->Unit,bold:Boolean,onBold:(Boolean)->Unit,projectsEnabled:Boolean,onProjectsEnabled:(Boolean)->Unit){'''
assert old in u; u=u.replace(old,new,1)
u=u.replace('val compactSettings=fontScale<=1.16f','val compactSettings=kotlin.math.max(fontScale,buttonScale)<=1.16f',1)
old='''p.darkTheme?.let(onDark);p.fontScale?.let(onFont);p.boldText?.let(onBold);p.language?.let(onLanguage);p.projectsEnabled?.let(onProjectsEnabled);settings.backupEmail=p.email'''
new='''p.darkTheme?.let(onDark);p.fontScale?.let(onFont);p.buttonFontScale?.let(onButtonScale);p.boldText?.let(onBold);p.language?.let(onLanguage);p.projectsEnabled?.let(onProjectsEnabled);settings.backupEmail=p.email'''
assert old in u; u=u.replace(old,new,1)
old='''        item{SettingsCard("",verticalPadding=controlsCardPad,spacing=if(compactSettings)0.dp else 1.dp){SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark);SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold);Row(Modifier.fillMaxWidth().heightIn(min=if(compactSettings)28.dp else 32.dp),verticalAlignment=Alignment.CenterVertically){Text(AppStrings.t(lang,"font"),Modifier.weight(.78f),maxLines=2);Slider(fontScale,onFont,modifier=Modifier.weight(1.22f).heightIn(min=if(compactSettings)26.dp else 28.dp),valueRange=.85f..1.45f,steps=3)};SettingSwitch(AppStrings.t(lang,"show_projects"),projectsEnabled,onProjectsEnabled)}}'''
new='''        item{SettingsCard("",verticalPadding=controlsCardPad,spacing=if(compactSettings)0.dp else 1.dp){
            SettingSwitch(AppStrings.t(lang,"dark"),dark,onDark)
            SettingSwitch(AppStrings.t(lang,"bold"),bold,onBold)
            FontScaleRow(AppStrings.t(lang,"button_font"),buttonScale,onButtonScale,compactSettings)
            FontScaleRow(AppStrings.t(lang,"content_font"),fontScale,onFont,compactSettings)
            SettingSwitch(AppStrings.t(lang,"show_projects"),projectsEnabled,onProjectsEnabled)
        }}'''
assert old in u; u=u.replace(old,new,1)

# Replace SettingSwitch scale and add reusable two-slider row.
old='''@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){
    val scale=LocalDensity.current.fontScale
    Row(Modifier.fillMaxWidth().heightIn(min=if(scale<=1.16f)32.dp else 36.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),maxLines=2);Switch(value,onChange)}
}'''
new='''@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){
    val scale=LocalContentTextScale.current
    Row(Modifier.fillMaxWidth().heightIn(min=if(scale<=1.16f)32.dp else 36.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),maxLines=2);Switch(value,onChange)}
}
@Composable private fun FontScaleRow(label:String,value:Float,onChange:(Float)->Unit,compact:Boolean){
    Row(Modifier.fillMaxWidth().heightIn(min=if(compact)30.dp else 34.dp),verticalAlignment=Alignment.CenterVertically){
        Text(label,Modifier.weight(.88f),maxLines=2)
        Slider(value,onChange,modifier=Modifier.weight(1.12f).heightIn(min=if(compact)26.dp else 30.dp),valueRange=.85f..1.45f,steps=3)
    }
}'''
assert old in u; u=u.replace(old,new,1)

# Button sizes no longer follow content scale.
u=u.replace('val uiScale=LocalDensity.current.fontScale.coerceIn(.85f,1.45f)','val uiScale=LocalButtonTextScale.current.coerceIn(.85f,1.45f)',4)
# Top action text is a button label, not a content title.
start=u.index('@Composable private fun RowScope.TopActionPill')
end=u.index('@OptIn(ExperimentalFoundationApi::class)\n@Composable private fun MoneyTabPill',start)
block=u[start:end]
assert 'style=MaterialTheme.typography.titleLarge' in block
block=block.replace('style=MaterialTheme.typography.titleLarge','style=MaterialTheme.typography.labelLarge',1)
u=u[:start]+block+u[end:]
ui.write_text(u)

# Version bump only; package ID and database schema stay untouched.
g=gradle.read_text()
assert 'versionCode = 57' in g and 'versionName = "0.9.24"' in g
g=g.replace('versionCode = 57','versionCode = 58').replace('versionName = "0.9.24"','versionName = "0.9.25"')
gradle.write_text(g)

# Assertions.
assert 'buttonFontScale' in settings.read_text()
assert 'LocalButtonTextScale' in theme.read_text()
assert 'buttonFontScale: Float? = null' in models.read_text()
assert 'contentFontScale' in backup.read_text() and 'buttonFontScale' in backup.read_text()
assert 'AppStrings.t(lang,"button_font")' in ui.read_text()
assert 'AppStrings.t(lang,"content_font")' in ui.read_text()
assert ui.read_text().count('LocalButtonTextScale.current.coerceIn(.85f,1.45f)')==4
assert 'OrganizerTheme(dark,font,buttonFont,bold)' in ui.read_text()
assert '0.9.25 (58)' in strings.read_text()
assert 'root.put("version",6)' in backup.read_text()
assert 'versionCode = 58' in gradle.read_text() and 'versionName = "0.9.25"' in gradle.read_text()
print('ok')
