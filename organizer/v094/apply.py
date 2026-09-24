from pathlib import Path
import sys
root=Path(sys.argv[1])

def edit(rel, pairs):
    p=root/rel; s=p.read_text()
    for old,new in pairs:
        assert old in s, f'{rel}: missing {old[:80]!r}'
        s=s.replace(old,new)
    p.write_text(s)

edit('app/build.gradle.kts',[
 ('versionCode = 36','versionCode = 37'),('versionName = "0.9.3"','versionName = "0.9.4"')])

edit('app/src/main/java/kz/kairat/organizer/AppStrings.kt',[
 ('Мой органайзер','Органайзер Про'),('Менің органайзерім','Органайзер Pro'),('My Organizer','Organizer Pro'),('我的记事管家','整理助手 Pro'),
 ('Mein Organizer','Organizer Pro'),('Mi organizador','Organizador Pro'),('Mon organiseur','Organiseur Pro'),('Organizatörüm','Organizatör Pro'),('0.9.3 (36)','0.9.4 (37)')])

p=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'; s=p.read_text()
for old,new in [
 ('import android.app.Activity\n','import android.app.Activity\nimport android.app.LocaleManager\n'),
 ('import android.net.Uri\n','import android.net.Uri\nimport android.os.Build\nimport android.os.LocaleList\n'),
 ('    var unlocked by rememberSaveable{mutableStateOf(!store.isPinProtectionEnabled())}\n    OrganizerTheme',
  '    var unlocked by rememberSaveable{mutableStateOf(!store.isPinProtectionEnabled())}\n    LaunchedEffect(Unit){syncAndroidAppLocale(context,lang)}\n    OrganizerTheme'),
 ('else OrganizerHome(db,store,lang,onLanguage={lang=it;store.language=it},',
  'else OrganizerHome(db,store,lang,onLanguage={newLang->store.language=newLang;lang=newLang;syncAndroidAppLocale(context,newLang)},'),
 ('    var showAddMoney by rememberSaveable{mutableStateOf(false)}\n    var moneyRevision',
  '    var addMoneyType by rememberSaveable{mutableStateOf<String?>(null)}\n    var moneyTab by rememberSaveable{mutableIntStateOf(0)}\n    var moneyRevision'),
 ('                        "money" -> TopActionPill(AppStrings.t(lang,"create"),Icons.Default.Add,{showAddMoney=true})',
  '                        "money" -> when(moneyTab){\n                            1->TopActionPill(AppStrings.t(lang,"create"),Icons.Default.Add,{addMoneyType="income"})\n                            2->TopActionPill(AppStrings.t(lang,"create"),Icons.Default.Add,{addMoneyType="expense"})\n                            else->Unit\n                        }'),
 ('        "money"->MoneyScreen(db,lang,moneyRevision)','        "money"->MoneyScreen(db,lang,moneyRevision,moneyTab){moneyTab=it}'),
 ('    if(showAddMoney)AddMoneyDialog(db,lang,{showAddMoney=false}){type,amount,cat,note->db.addMoneyEntry(type,amount,cat,null,note);moneyRevision++;showAddMoney=false}',
  '    addMoneyType?.let{fixedType->AddMoneyDialog(db,lang,fixedType,{addMoneyType=null}){amount,cat,note->db.addMoneyEntry(fixedType,amount,cat,null,note);moneyRevision++;addMoneyType=null}}'),
 ('Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp)){\n        Text(AppStrings.t(lang,"note_color")',
  'Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)){\n        Text(AppStrings.t(lang,"note_color")'),
 ('Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){\n                row.forEach{key->',
  'Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){\n                row.forEach{key->'),
 ('shape=RoundedCornerShape(14.dp),','shape=RoundedCornerShape(12.dp),'),
 ('shadowElevation=if(selected==key)3.dp else 0.dp,\n                        modifier=Modifier.weight(1f).height(58.dp).clickable{onSelected(key)}',
  'shadowElevation=if(selected==key)2.dp else 0.dp,\n                        modifier=Modifier.weight(1f).height(40.dp).clickable{onSelected(key)}'),
 ('Column(Modifier.fillMaxSize().padding(horizontal=4.dp,vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){\n                            if(selected==key)Icon(Icons.Default.Check,null,Modifier.size(18.dp))',
  'Row(Modifier.fillMaxSize().padding(horizontal=6.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){\n                            if(selected==key){Icon(Icons.Default.Check,null,Modifier.size(14.dp));Spacer(Modifier.width(3.dp))}'),
 ('@Composable private fun MoneyScreen(db:NoteDatabase,lang:String,externalRevision:Int){\n    var refresh by remember{mutableIntStateOf(0)}\n    var tab by rememberSaveable{mutableIntStateOf(0)}',
  '@Composable private fun MoneyScreen(db:NoteDatabase,lang:String,externalRevision:Int,selectedTab:Int,onTabSelected:(Int)->Unit){\n    var refresh by remember{mutableIntStateOf(0)}'),
 ('TabRow(selectedTabIndex=tab,','TabRow(selectedTabIndex=selectedTab,'),
 ('Tab(selected=tab==i,onClick={tab=i},','Tab(selected=selectedTab==i,onClick={onTabSelected(i)},'),
 ('        when(tab){','        when(selectedTab){'),
 ('            0->MoneyOverview(list,lang,Modifier.fillMaxSize()){id->db.deleteMoneyEntry(id);refresh++}',
  '            0->MoneyOverview(list,lang,Modifier.fillMaxSize())'),
 ('@Composable private fun MoneyOverview(entries:List<MoneyEntry>,lang:String,modifier:Modifier=Modifier,onDelete:(Long)->Unit){',
  '@Composable private fun MoneyOverview(entries:List<MoneyEntry>,lang:String,modifier:Modifier=Modifier){'),
 ('MoneyEntryCard(m,lang,if(m.type=="income")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,if(m.type=="income")0 else 1,onDelete)',
  'MoneyEntryCard(m,lang,if(m.type=="income")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,if(m.type=="income")0 else 1,null)'),
 ('@Composable private fun MoneyEntryCard(m:MoneyEntry,lang:String,amountColor:Color,cardTone:Int,onDelete:(Long)->Unit){',
  '@Composable private fun MoneyEntryCard(m:MoneyEntry,lang:String,amountColor:Color,cardTone:Int,onDelete:((Long)->Unit)?){'),
 ('                IconButton({onDelete(m.id)},modifier=Modifier.size(30.dp)){Icon(Icons.Default.Delete,null,Modifier.size(18.dp))}',
  '                if(onDelete!=null)IconButton({onDelete(m.id)},modifier=Modifier.size(30.dp)){Icon(Icons.Default.Delete,null,Modifier.size(18.dp))}'),
 ('@Composable private fun AddMoneyDialog(db:NoteDatabase,lang:String,onDismiss:()->Unit,onAdd:(String,Double,String,String)->Unit){',
  '@Composable private fun AddMoneyDialog(db:NoteDatabase,lang:String,fixedType:String,onDismiss:()->Unit,onAdd:(Double,String,String)->Unit){'),
 ('    var type by remember{mutableStateOf("expense")};var amount by remember{mutableStateOf("")};var cat by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}',
  '    var amount by remember{mutableStateOf("")};var cat by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}'),
 ('    AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,"money"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){\n        Row{FilterChip(type=="expense",{type="expense"},{Text(AppStrings.t(lang,"expense"))});Spacer(Modifier.width(8.dp));FilterChip(type=="income",{type="income"},{Text(AppStrings.t(lang,"income"))})}',
  '    AlertDialog(onDismissRequest=onDismiss,title={Text(AppStrings.t(lang,fixedType))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){'),
 ('parseAmountInput(amount)?.let{onAdd(type,it,cat,note)}','parseAmountInput(amount)?.let{onAdd(it,cat,note)}'),
]:
    assert old in s, f'UI missing {old[:100]!r}'
    s=s.replace(old,new,1)
anchor='@Composable private fun EmptyState(text:String){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant)}}\n'
helper='private fun syncAndroidAppLocale(context:android.content.Context,lang:String){\n    if(Build.VERSION.SDK_INT<33)return\n    val tag=AppStrings.localeTag(lang)\n    val manager=context.getSystemService(LocaleManager::class.java)\n    if(manager.applicationLocales.toLanguageTags()!=tag){manager.applicationLocales=LocaleList.forLanguageTags(tag)}\n}\n\n'
assert anchor in s
s=s.replace(anchor,helper+anchor)
p.write_text(s)

labels={'values':'Органайзер Про','values-kk':'Органайзер Pro','values-en':'Organizer Pro','values-zh-rCN':'整理助手 Pro','values-de':'Organizer Pro','values-es':'Organizador Pro','values-fr':'Organiseur Pro','values-tr':'Organizatör Pro'}
for folder,label in labels.items():
    f=root/f'app/src/main/res/{folder}/strings.xml'; f.parent.mkdir(parents=True,exist_ok=True)
    f.write_text(f'<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">{label}</string></resources>\n')
(root/'app/src/main/res/xml/locales_config.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n    <locale android:name="kk-KZ"/>\n    <locale android:name="ru-RU"/>\n    <locale android:name="en-US"/>\n    <locale android:name="zh-CN"/>\n    <locale android:name="de-DE"/>\n    <locale android:name="es-ES"/>\n    <locale android:name="fr-FR"/>\n    <locale android:name="tr-TR"/>\n</locale-config>\n')
edit('app/src/main/AndroidManifest.xml',[(
 '        android:label="@string/app_name"\n        android:roundIcon="@drawable/ic_app_icon_approved"',
 '        android:label="@string/app_name"\n        android:localeConfig="@xml/locales_config"\n        android:roundIcon="@drawable/ic_app_icon_approved"')])

ui=(root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt').read_text(); strings=(root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt').read_text(); db=(root/'app/src/main/java/kz/kairat/organizer/NoteDatabase.kt').read_text()
for x in ['addMoneyType="income"','addMoneyType="expense"','MoneyOverview(list,lang,Modifier.fillMaxSize())','height(40.dp).clickable{onSelected(key)}','syncAndroidAppLocale(context,newLang)','LocaleManager::class.java']: assert x in ui
assert '"app" to "Органайзер Про"' in strings and '"app" to "Organizer Pro"' in strings and '0.9.4 (37)' in strings
assert 'SQLiteOpenHelper(context, "organizer.db", null, 8)' in db
assert 'applicationId = "kz.kairat.organizer"' in (root/'app/build.gradle.kts').read_text()
