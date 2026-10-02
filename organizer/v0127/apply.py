from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'

u=ui.read_text()

# Settings top controls: keep the controls compact, but give every row a
# comfortable and uniform vertical touch separation so adjacent rows are not
# hit accidentally.
old='''        Modifier.fillMaxWidth().heightIn(min=if(compact)26.dp else 32.dp),\n        verticalAlignment=Alignment.CenterVertically\n'''
new='''        Modifier.fillMaxWidth().heightIn(min=if(compact)38.dp else 44.dp).padding(vertical=4.dp),\n        verticalAlignment=Alignment.CenterVertically\n'''
count=u.count(old)
assert count>=2, f'Expected both settings row helpers, found {count}'
u=u.replace(old,new,2)

# Web card: informational address only. Do not offer to open the web page from
# Settings; show the requested address as a single line.
marker='Text(if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-версия Органайзер Про" else "Organizer Pro Web"'
pos=u.index(marker)
web_start=u.rfind('        item{SettingsCard("",verticalPadding=',0,pos)
info_start=u.index('        item{SettingsCard("",verticalPadding=infoCardPad',pos)
new_web='''        item{SettingsCard("",verticalPadding=if(compactSettings)2.dp else controlsCardPad,spacing=0.dp){
            Text(
                if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-страница: https://organizer-pro.onrender.com" else "Web page: https://organizer-pro.onrender.com",
                style=MaterialTheme.typography.labelSmall,
                maxLines=1,
                overflow=TextOverflow.Ellipsis
            )
        }}
'''
u=u[:web_start]+new_web+u[info_start:]

ui.write_text(u)

# Version bump only. Keep package name/database unchanged.
g=gradle.read_text()
assert 'versionCode = 69' in g
assert 'versionName = "0.9.36"' in g
g=g.replace('versionCode = 69','versionCode = 70',1)
g=g.replace('versionName = "0.9.36"','versionName = "0.9.37"',1)
gradle.write_text(g)
