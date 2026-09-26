from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
db=root/'app/src/main/java/kz/kairat/organizer/NoteDatabase.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()
d=db.read_text()

# v0.9.17: hard separation between standalone Finance and project finance.
# Keep getMoneyEntries() as the complete dataset for backup/export compatibility,
# and add an explicit standalone-only query used by the bottom Finance section.
old='''    fun getMoneyEntries():List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,p.name,m.note,m.created_at,m.color_key FROM money_entries m LEFT JOIN projects p ON p.id=m.project_id ORDER BY m.created_at DESC",null).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))"" else c.getString(5),c.getString(6),c.getLong(7),if(c.isNull(8))"blue" else c.getString(8)))}}
    fun getProjectMoneyEntries(projectId:Long):List<MoneyEntry> = getMoneyEntries().filter{it.projectId==projectId}
'''
new='''    fun getMoneyEntries():List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,p.name,m.note,m.created_at,m.color_key FROM money_entries m LEFT JOIN projects p ON p.id=m.project_id ORDER BY m.created_at DESC",null).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))"" else c.getString(5),c.getString(6),c.getLong(7),if(c.isNull(8))"blue" else c.getString(8)))}}
    fun getStandaloneMoneyEntries():List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,'' AS project_name,m.note,m.created_at,m.color_key FROM money_entries m WHERE m.project_id IS NULL ORDER BY m.created_at DESC",null).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),null,"",c.getString(6),c.getLong(7),if(c.isNull(8))"blue" else c.getString(8)))}}
    fun getProjectMoneyEntries(projectId:Long):List<MoneyEntry> = readableDatabase.rawQuery("SELECT m.id,m.type,m.amount,m.category,m.project_id,p.name,m.note,m.created_at,m.color_key FROM money_entries m LEFT JOIN projects p ON p.id=m.project_id WHERE m.project_id=? ORDER BY m.created_at DESC",arrayOf(projectId.toString())).use{c->buildList{while(c.moveToNext())add(MoneyEntry(c.getLong(0),c.getString(1),c.getDouble(2),c.getString(3),c.getLong(4),if(c.isNull(5))"" else c.getString(5),c.getString(6),c.getLong(7),if(c.isNull(8))"blue" else c.getString(8)))}}
'''
assert old in d
d=d.replace(old,new,1)
db.write_text(d)

# Standalone Finance reads only rows without project_id.
old='val list=remember(refresh,externalRevision){db.getMoneyEntries()}'
new='val list=remember(refresh,externalRevision){db.getStandaloneMoneyEntries()}'
assert old in s
s=s.replace(old,new,1)

# Standalone analytics must never show or calculate project finance.
old='else->MoneyAnalytics(db,list,lang,Modifier.fillMaxSize())'
new='else->MoneyAnalytics(list,lang,Modifier.fillMaxSize())'
assert old in s
s=s.replace(old,new,1)

start=s.index('@Composable private fun MoneyAnalytics(')
end=s.index('@Composable private fun AnalyticsChartCard', start)
replacement='''@Composable private fun MoneyAnalytics(entries:List<MoneyEntry>,lang:String,modifier:Modifier=Modifier){
    val inc=entries.filter{it.type=="income"}.sumOf{it.amount}
    val exp=entries.filter{it.type=="expense"}.sumOf{it.amount}
    val bal=inc-exp
    LazyColumn(modifier,verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=8.dp)){
        item{MoneySummaryRow(inc,exp,bal,lang)}
        item{AnalyticsChartCard(entries,lang)}
    }
}

'''
s=s[:start]+replacement+s[end:]
ui.write_text(s)

# Version bump only. Database schema and stored data stay unchanged.
b=gradle.read_text()
assert 'versionCode = 49' in b and 'versionName = "0.9.16"' in b
b=b.replace('versionCode = 49','versionCode = 50').replace('versionName = "0.9.16"','versionName = "0.9.17"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.16 (49)' in a
a=a.replace('0.9.16 (49)','0.9.17 (50)')
strings.write_text(a)

# Gates: standalone and project finance are now separate at query level.
s=ui.read_text(); d=db.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'getStandaloneMoneyEntries()' in s
assert 'fun getStandaloneMoneyEntries()' in d
assert 'WHERE m.project_id IS NULL' in d
assert 'WHERE m.project_id=?' in d
assert 'MoneyAnalytics(list,lang' in s
assert 'MoneyAnalytics(db,list,lang' not in s
assert 'AppStrings.t(lang,"by_projects")' not in s[s.index('@Composable private fun MoneyAnalytics('):s.index('@Composable private fun AnalyticsChartCard')]
assert 'versionCode = 50' in b and 'versionName = "0.9.17"' in b
assert '0.9.17 (50)' in a
