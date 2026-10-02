from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'

u=ui.read_text()

# Finance: remove Analytics tab from the UI/navigation.
old='''            listOf("overview","income","expense","analytics").forEachIndexed{i,key->\n'''
new='''            listOf("overview","income","expense").forEachIndexed{i,key->\n'''
assert old in u
u=u.replace(old,new,1)

old='''        when(selectedTab){\n            0->MoneyOverview(list,lang,Modifier.fillMaxSize())\n            1->MoneyColumn(AppStrings.t(lang,"income"),income,lang,Modifier.fillMaxSize(),MaterialTheme.colorScheme.primary,0){id->db.deleteMoneyEntry(id);refresh++}\n            2->MoneyColumn(AppStrings.t(lang,"expense"),expense,lang,Modifier.fillMaxSize(),MaterialTheme.colorScheme.error,1){id->db.deleteMoneyEntry(id);refresh++}\n            else->MoneyAnalytics(list,lang,Modifier.fillMaxSize())\n        }\n'''
new='''        when(selectedTab){\n            0->MoneyOverview(list,lang,Modifier.fillMaxSize())\n            1->MoneyColumn(AppStrings.t(lang,"income"),income,lang,Modifier.fillMaxSize(),MaterialTheme.colorScheme.primary,0){id->db.deleteMoneyEntry(id);refresh++}\n            2->MoneyColumn(AppStrings.t(lang,"expense"),expense,lang,Modifier.fillMaxSize(),MaterialTheme.colorScheme.error,1){id->db.deleteMoneyEntry(id);refresh++}\n            else->MoneyOverview(list,lang,Modifier.fillMaxSize())\n        }\n'''
assert old in u
u=u.replace(old,new,1)

u=u.replace('wide=(section=="money" && (moneyTab==0 || moneyTab==3))','wide=(section=="money" && moneyTab==0)',1)

# Finance summary labels (Income / Expense / Balance) must follow the button-text slider.
old='''            Text(title,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)\n'''
new='''            Text(title,style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)\n'''
assert old in u
u=u.replace(old,new,1)

ui.write_text(u)

# Bump Android version only; keep applicationId/database schema unchanged.
g=gradle.read_text()
assert 'versionCode = 67' in g
assert 'versionName = "0.9.34"' in g
g=g.replace('versionCode = 67','versionCode = 68',1)
g=g.replace('versionName = "0.9.34"','versionName = "0.9.35"',1)
gradle.write_text(g)
