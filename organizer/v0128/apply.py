from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'

u=ui.read_text()

# Web page card: two-line informational layout. Keep the complete address
# visible at large UI text sizes by allowing wrapping instead of ellipsis.
old='''        item{SettingsCard("",verticalPadding=if(compactSettings)2.dp else controlsCardPad,spacing=0.dp){
            Text(
                if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-страница: https://organizer-pro.onrender.com" else "Web page: https://organizer-pro.onrender.com",
                style=MaterialTheme.typography.labelSmall,
                maxLines=1,
                overflow=TextOverflow.Ellipsis
            )
        }}
'''
new='''        item{SettingsCard("",verticalPadding=if(compactSettings)6.dp else 8.dp,spacing=3.dp){
            Text(
                if(AppStrings.normalizeLanguage(lang)=="ru") "Веб-страница" else "Web page",
                style=MaterialTheme.typography.titleSmall,
                maxLines=1
            )
            Text(
                "https://organizer-pro.onrender.com",
                modifier=Modifier.fillMaxWidth(),
                style=MaterialTheme.typography.bodySmall,
                maxLines=2,
                softWrap=true,
                overflow=TextOverflow.Visible
            )
        }}
'''
assert old in u
u=u.replace(old,new,1)
ui.write_text(u)

# Version bump only. Keep package name/database unchanged.
g=gradle.read_text()
assert 'versionCode = 70' in g
assert 'versionName = "0.9.37"' in g
g=g.replace('versionCode = 70','versionCode = 71',1)
g=g.replace('versionName = "0.9.37"','versionName = "0.9.38"',1)
gradle.write_text(g)
