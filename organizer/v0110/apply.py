from pathlib import Path
import sys
root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
theme=root/'app/src/main/java/kz/kairat/organizer/Theme.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'
s=ui.read_text(); t=theme.read_text(); b=gradle.read_text(); a=strings.read_text()

# v0.9.20: two typography tiers only: interface=15sp, content=14sp.
old='''        displayLarge=TextStyle(fontSize=17.sp,fontWeight=strong),
        displayMedium=TextStyle(fontSize=17.sp,fontWeight=strong),
        displaySmall=TextStyle(fontSize=17.sp,fontWeight=strong),
        headlineLarge=TextStyle(fontSize=17.sp,fontWeight=strong),
        headlineMedium=TextStyle(fontSize=17.sp,fontWeight=strong),
        headlineSmall=TextStyle(fontSize=17.sp,fontWeight=strong),
        titleLarge=TextStyle(fontSize=17.sp,fontWeight=strong),
        titleMedium=TextStyle(fontSize=15.sp,fontWeight=strong),
        titleSmall=TextStyle(fontSize=15.sp,fontWeight=strong),
        bodyLarge=TextStyle(fontSize=14.sp,fontWeight=weight),
        bodyMedium=TextStyle(fontSize=14.sp,fontWeight=weight),
        bodySmall=TextStyle(fontSize=14.sp,fontWeight=weight),
        labelLarge=TextStyle(fontSize=15.sp,fontWeight=strong),
        labelMedium=TextStyle(fontSize=14.sp,fontWeight=weight),
        labelSmall=TextStyle(fontSize=14.sp,fontWeight=weight)'''
new='''        displayLarge=TextStyle(fontSize=15.sp,fontWeight=strong),
        displayMedium=TextStyle(fontSize=15.sp,fontWeight=strong),
        displaySmall=TextStyle(fontSize=15.sp,fontWeight=strong),
        headlineLarge=TextStyle(fontSize=15.sp,fontWeight=strong),
        headlineMedium=TextStyle(fontSize=15.sp,fontWeight=strong),
        headlineSmall=TextStyle(fontSize=15.sp,fontWeight=strong),
        titleLarge=TextStyle(fontSize=15.sp,fontWeight=strong),
        titleMedium=TextStyle(fontSize=15.sp,fontWeight=strong),
        titleSmall=TextStyle(fontSize=15.sp,fontWeight=strong),
        bodyLarge=TextStyle(fontSize=14.sp,fontWeight=weight),
        bodyMedium=TextStyle(fontSize=14.sp,fontWeight=weight),
        bodySmall=TextStyle(fontSize=14.sp,fontWeight=weight),
        labelLarge=TextStyle(fontSize=15.sp,fontWeight=strong),
        labelMedium=TextStyle(fontSize=15.sp,fontWeight=strong),
        labelSmall=TextStyle(fontSize=14.sp,fontWeight=weight)'''
assert old in t
t=t.replace(old,new,1)
theme.write_text(t)

# Non-consuming horizontal swipe observer for main sections, so vertical lists and the settings slider keep working.
imp='import androidx.compose.foundation.text.KeyboardOptions\n'
add='import androidx.compose.foundation.text.KeyboardOptions\nimport androidx.compose.foundation.gestures.awaitEachGesture\nimport androidx.compose.foundation.gestures.awaitFirstDown\nimport androidx.compose.ui.input.pointer.pointerInput\n'
assert imp in s and 'awaitEachGesture' not in s
s=s.replace(imp,add,1)

old='''    var projectsEnabled by rememberSaveable{mutableStateOf(settings.projectsEnabled)}
    LaunchedEffect(projectsEnabled){if(!projectsEnabled&&section=="projects")section="notes"}'''
new='''    var projectsEnabled by rememberSaveable{mutableStateOf(settings.projectsEnabled)}
    val visibleSections=if(projectsEnabled) listOf("notes","projects","money","settings") else listOf("notes","money","settings")
    val swipeDensity=LocalDensity.current.density
    fun switchSectionBySwipe(direction:Int){
        val current=visibleSections.indexOf(section).coerceAtLeast(0)
        val next=(current+direction).coerceIn(0,visibleSections.lastIndex)
        if(next!=current)section=visibleSections[next]
    }
    LaunchedEffect(projectsEnabled){if(!projectsEnabled&&section=="projects")section="notes"}'''
assert old in s
s=s.replace(old,new,1)

old='''    ){pad->Box(Modifier.padding(pad)){when(section){'''
new='''    ){pad->Box(
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
    ){when(section){'''
assert old in s
s=s.replace(old,new,1)

# Compact main Notes screen: search field and cards fit content with ~2 mm vertical breathing room.
old='''    Column(Modifier.fillMaxSize().padding(12.dp)){
        OutlinedTextField(q,{q=it},Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text(AppStrings.t(lang,"search"))},singleLine=true,colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.primaryContainer,unfocusedContainerColor=MaterialTheme.colorScheme.primaryContainer))
        Spacer(Modifier.height(8.dp))
        if(notes.isEmpty()) EmptyState(AppStrings.t(lang,"no_notes"))
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(notes,key={it.id}){n->Card(Modifier.fillMaxWidth().clickable{onOpen(n.id)},colors=noteCardColors(n.colorKey)){Column(Modifier.padding(14.dp)){Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=MaterialTheme.typography.titleMedium);if(n.body.isNotBlank())Text(n.body,maxLines=3,overflow=TextOverflow.Ellipsis);Text(formatTime(n.updatedAt,lang),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
        }
    }'''
new='''    Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp)){
        OutlinedTextField(q,{q=it},Modifier.fillMaxWidth().height(48.dp),leadingIcon={Icon(Icons.Default.Search,null,Modifier.size(20.dp))},placeholder={Text(AppStrings.t(lang,"search"),style=MaterialTheme.typography.bodyMedium)},singleLine=true,colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.primaryContainer,unfocusedContainerColor=MaterialTheme.colorScheme.primaryContainer))
        Spacer(Modifier.height(5.dp))
        if(notes.isEmpty()) EmptyState(AppStrings.t(lang,"no_notes"))
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=4.dp)){
            items(notes,key={it.id}){n->Card(Modifier.fillMaxWidth().clickable{onOpen(n.id)},colors=noteCardColors(n.colorKey)){Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(1.dp)){Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold));if(n.body.isNotBlank())Text(n.body,maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium);Text(formatTime(n.updatedAt,lang),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
        }
    }'''
assert old in s
s=s.replace(old,new,1)

# Compact reader/editor surfaces.
s=s.replace('LazyColumn(Modifier.padding(pad).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp))','LazyColumn(Modifier.padding(pad).padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp))',1)
s=s.replace('Modifier.padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(12.dp)','Modifier.padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(7.dp)',1)

# Compact projects and project internals.
old='''    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        items(projects,key={it.id}){p->
            Card(Modifier.fillMaxWidth().clickable{onOpen(p.id)},colors=noteCardColors(p.colorKey)){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){'''
new='''    LazyColumn(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=4.dp)){
        items(projects,key={it.id}){p->
            Card(Modifier.fillMaxWidth().clickable{onOpen(p.id)},colors=noteCardColors(p.colorKey)){
                Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){'''
assert old in s
s=s.replace(old,new,1)
s=s.replace('Text(p.name,style=MaterialTheme.typography.titleMedium','Text(p.name,style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold)',1)
s=s.replace('LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){','LazyColumn(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){',1)
s=s.replace('Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){','Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){',1)
s=s.replace('Column(Modifier.fillMaxSize().padding(12.dp)){','Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=6.dp)){',3)
s=s.replace('LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){','LazyColumn(verticalArrangement=Arrangement.spacedBy(5.dp)){',2)
s=s.replace('Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){','Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){',1)

# Compact standalone finance while keeping all calculations unchanged.
s=s.replace('Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=8.dp)){','Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=5.dp)){',1)
s=s.replace('Spacer(Modifier.height(8.dp))\n        when(selectedTab){','Spacer(Modifier.height(5.dp))\n        when(selectedTab){',1)
s=s.replace('Spacer(Modifier.height(10.dp))\n        Text(AppStrings.t(lang,"recent_operations")','Spacer(Modifier.height(6.dp))\n        Text(AppStrings.t(lang,"recent_operations")',1)
s=s.replace('Spacer(Modifier.height(6.dp))\n        if(entries.isEmpty())','Spacer(Modifier.height(4.dp))\n        if(entries.isEmpty())',1)
s=s.replace('Column(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=9.dp)','Column(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=6.dp)',1)
s=s.replace('LazyColumn(modifier,verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=8.dp))','LazyColumn(modifier,verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=4.dp))',1)
s=s.replace('Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){','Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){',1)

# Shared settings card padding follows compact standard too.
old='''@Composable private fun SettingsCard(title:String,verticalPadding:androidx.compose.ui.unit.Dp=10.dp,spacing:androidx.compose.ui.unit.Dp=6.dp,content: @Composable ColumnScope.() -> Unit){Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(horizontal=14.dp,vertical=verticalPadding),verticalArrangement=Arrangement.spacedBy(spacing)){if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleMedium);content()}}}'''
new='''@Composable private fun SettingsCard(title:String,verticalPadding:androidx.compose.ui.unit.Dp=6.dp,spacing:androidx.compose.ui.unit.Dp=4.dp,content: @Composable ColumnScope.() -> Unit){Card(Modifier.fillMaxWidth(),colors=brandCardColors(0)){Column(Modifier.padding(horizontal=10.dp,vertical=verticalPadding),verticalArrangement=Arrangement.spacedBy(spacing)){if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleMedium);content()}}}'''
assert old in s
s=s.replace(old,new,1)

assert 'style=MaterialTheme.typography.titleLarge' in s and 'style=MaterialTheme.typography.labelLarge' in s
ui.write_text(s)

assert 'versionCode = 52' in b and 'versionName = "0.9.19"' in b
b=b.replace('versionCode = 52','versionCode = 53').replace('versionName = "0.9.19"','versionName = "0.9.20"')
gradle.write_text(b)
assert '0.9.19 (52)' in a
a=a.replace('0.9.19 (52)','0.9.20 (53)')
strings.write_text(a)

s=ui.read_text(); t=theme.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'titleLarge=TextStyle(fontSize=15.sp' in t
assert 'labelLarge=TextStyle(fontSize=15.sp' in t
assert 'bodyMedium=TextStyle(fontSize=14.sp' in t
assert 'pointerInput(section,projectsEnabled)' in s
assert 'visibleSections=if(projectsEnabled)' in s
assert 'height(48.dp)' in s
assert 'padding(horizontal=10.dp,vertical=6.dp)' in s
assert 'Arrangement.spacedBy(5.dp)' in s
assert 'versionCode = 53' in b and 'versionName = "0.9.20"' in b
assert '0.9.20 (53)' in a
