from pathlib import Path
import sys

root = Path(sys.argv[1])
ui = root / 'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle = root / 'app/build.gradle.kts'
strings = root / 'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s = ui.read_text()

# Make creation flows true full-screen screens, matching NoteEditor style.
anchor = '    if(showReminders){RemindersScreen(db,lang){showReminders=false};return}\n'
insert = '''    if(showReminders){RemindersScreen(db,lang){showReminders=false};return}\n    if(showNewProject){\n        AddProjectDialog(\n            lang=lang,\n            onDismiss={showNewProject=false},\n            onAdd={name,description->\n                val id=db.addProject(name,description)\n                showNewProject=false\n                projectRevision++\n                projectId=id\n            }\n        )\n        return\n    }\n    addMoneyType?.let{fixedType->\n        AddMoneyDialog(\n            db=db,\n            lang=lang,\n            fixedType=fixedType,\n            onDismiss={addMoneyType=null},\n            onAdd={amount,cat,note->\n                db.addMoneyEntry(fixedType,amount,cat,null,note)\n                moneyRevision++\n                addMoneyType=null\n            }\n        )\n        return\n    }\n'''
assert anchor in s
s = s.replace(anchor, insert, 1)

old_inline = '''    addMoneyType?.let{fixedType->AddMoneyDialog(db,lang,fixedType,{addMoneyType=null}){amount,cat,note->db.addMoneyEntry(fixedType,amount,cat,null,note);moneyRevision++;addMoneyType=null}}\n    if(showNewProject)AddProjectDialog(lang,{showNewProject=false}){name,description->val id=db.addProject(name,description);showNewProject=false;projectRevision++;projectId=id}\n'''
assert old_inline in s
s = s.replace(old_inline, '', 1)

project_start = s.index('@Composable private fun AddProjectDialog')
project_end = s.index('@Composable private fun ProjectDetailScreen')
project_screen = '''@Composable private fun AddProjectDialog(lang:String,onDismiss:()->Unit,onAdd:(String,String)->Unit){\n    var name by rememberSaveable{mutableStateOf(\"\")}\n    var description by rememberSaveable{mutableStateOf(\"\")}\n    fun submit(){if(name.isNotBlank())onAdd(name.trim(),description.trim())}\n    BackHandler{onDismiss()}\n    Scaffold(\n        topBar={\n            TopAppBar(\n                title={Text(AppStrings.t(lang,\"new_project\"))},\n                navigationIcon={IconButton(onDismiss){Icon(Icons.Default.ArrowBack,null)}},\n                actions={IconButton({submit()}){Icon(Icons.Default.Save,null)}},\n                colors=organizerTopBarColors()\n            )\n        }\n    ){pad->\n        LazyColumn(\n            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal=16.dp,vertical=10.dp),\n            verticalArrangement=Arrangement.spacedBy(12.dp)\n        ){\n            item{\n                OutlinedTextField(\n                    name,\n                    {name=capitalizeSentences(it)},\n                    Modifier.fillMaxWidth(),\n                    label={Text(AppStrings.t(lang,\"title\"))},\n                    singleLine=true,\n                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)\n                )\n            }\n            item{\n                OutlinedTextField(\n                    description,\n                    {description=capitalizeSentences(it)},\n                    Modifier.fillMaxWidth().heightIn(min=220.dp),\n                    label={Text(AppStrings.t(lang,\"details\"))},\n                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)\n                )\n            }\n            item{\n                Button(\n                    onClick={submit()},\n                    modifier=Modifier.fillMaxWidth(),\n                    colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)\n                ){\n                    Icon(Icons.Default.Save,null)\n                    Spacer(Modifier.width(8.dp))\n                    Text(AppStrings.t(lang,\"add\"))\n                }\n            }\n        }\n    }\n}\n\n'''
s = s[:project_start] + project_screen + s[project_end:]

money_start = s.index('@Composable private fun AddMoneyDialog')
money_end = s.index('@Composable private fun RemindersScreen')
money_screen = '''@Composable private fun AddMoneyDialog(db:NoteDatabase,lang:String,fixedType:String,onDismiss:()->Unit,onAdd:(Double,String,String)->Unit){\n    var amount by rememberSaveable{mutableStateOf(\"\")}\n    var cat by rememberSaveable{mutableStateOf(\"\")}\n    var note by rememberSaveable{mutableStateOf(\"\")}\n    fun submit(){parseAmountInput(amount)?.let{onAdd(it,cat.trim(),note.trim())}}\n    BackHandler{onDismiss()}\n    Scaffold(\n        topBar={\n            TopAppBar(\n                title={Text(AppStrings.t(lang,fixedType))},\n                navigationIcon={IconButton(onDismiss){Icon(Icons.Default.ArrowBack,null)}},\n                actions={IconButton({submit()}){Icon(Icons.Default.Save,null)}},\n                colors=organizerTopBarColors()\n            )\n        }\n    ){pad->\n        LazyColumn(\n            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().padding(horizontal=16.dp,vertical=10.dp),\n            verticalArrangement=Arrangement.spacedBy(12.dp)\n        ){\n            item{\n                OutlinedTextField(\n                    amount,\n                    {amount=formatAmountInput(it)},\n                    Modifier.fillMaxWidth(),\n                    label={Text(AppStrings.t(lang,\"amount\"))},\n                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),\n                    singleLine=true\n                )\n            }\n            item{\n                SuggestionTextField(\n                    value=cat,\n                    onValueChange={cat=capitalizeSentences(it)},\n                    label=AppStrings.t(lang,\"category\"),\n                    suggestions=db.getMoneyCategorySuggestions(cat),\n                    onSuggestion={cat=it},\n                    onDeleteSuggestion={db.hideSuggestion(\"money_category\",it)},\n                    modifier=Modifier.fillMaxWidth()\n                )\n            }\n            item{\n                OutlinedTextField(\n                    note,\n                    {note=capitalizeSentences(it)},\n                    Modifier.fillMaxWidth().heightIn(min=180.dp),\n                    label={Text(AppStrings.t(lang,\"comment\"))},\n                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)\n                )\n            }\n            item{\n                Button(\n                    onClick={submit()},\n                    modifier=Modifier.fillMaxWidth(),\n                    colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)\n                ){\n                    Icon(Icons.Default.Save,null)\n                    Spacer(Modifier.width(8.dp))\n                    Text(AppStrings.t(lang,\"add\"))\n                }\n            }\n        }\n    }\n}\n\n'''
s = s[:money_start] + money_screen + s[money_end:]
ui.write_text(s)

b = gradle.read_text()
assert 'versionCode = 37' in b and 'versionName = "0.9.4"' in b
b = b.replace('versionCode = 37', 'versionCode = 38').replace('versionName = "0.9.4"', 'versionName = "0.9.5"')
gradle.write_text(b)

a = strings.read_text()
assert '0.9.4 (37)' in a
a = a.replace('0.9.4 (37)', '0.9.5 (38)')
strings.write_text(a)

# Preservation and behavior gates.
s = ui.read_text()
assert 'if(showNewProject){' in s
assert 'addMoneyType?.let{fixedType->' in s
assert 'BackHandler{onDismiss()}' in s
assert 'title={Text(AppStrings.t(lang,"new_project"))}' in s
assert 'title={Text(AppStrings.t(lang,fixedType))}' in s
assert 'db.addMoneyEntry(fixedType,amount,cat,null,note)' in s
assert 'addMoneyType="income"' in s and 'addMoneyType="expense"' in s
assert 'listOf("overview","income","expense","analytics")' in s
assert 'NoteColorPicker(colorKey,lang)' in s
assert 'syncAndroidAppLocale(context,newLang)' in s
assert 'supportedLanguages = listOf("kk","ru","en","zh","de","es","fr","tr")' in a
assert 'SQLiteOpenHelper(context, "organizer.db", null, 8)' in (root/'app/src/main/java/kz/kairat/organizer/NoteDatabase.kt').read_text()
