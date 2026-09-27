from pathlib import Path
import sys

root=Path(sys.argv[1])
calui=root/'app/src/main/java/kz/kairat/organizer/CalendarUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

c=calui.read_text()

# v0.9.24: calendar stays full-screen but closes only through the Android Back action.
old='''    Popup(
        alignment=Alignment.TopCenter,
        onDismissRequest={},
        properties=PopupProperties(focusable=true,dismissOnBackPress=false,dismissOnClickOutside=false)
    ){'''
new='''    Popup(
        alignment=Alignment.TopCenter,
        onDismissRequest=onDismiss,
        properties=PopupProperties(focusable=true,dismissOnBackPress=true,dismissOnClickOutside=false)
    ){'''
assert old in c
c=c.replace(old,new,1)

old='''                    IconButton(onDismiss){Icon(Icons.Default.Close,null)}
'''
assert old in c
c=c.replace(old,'',1)
calui.write_text(c)

# Version bump only; preserve applicationId, DB schema and all user data.
b=gradle.read_text()
assert 'versionCode = 56' in b and 'versionName = "0.9.23"' in b
b=b.replace('versionCode = 56','versionCode = 57').replace('versionName = "0.9.23"','versionName = "0.9.24"')
gradle.write_text(b)

a=strings.read_text()
assert '0.9.23 (56)' in a
a=a.replace('0.9.23 (56)','0.9.24 (57)')
strings.write_text(a)

# Gates.
c=calui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'onDismissRequest=onDismiss' in c
assert 'dismissOnBackPress=true,dismissOnClickOutside=false' in c
assert 'Icons.Default.Close' not in c
assert 'Modifier.fillMaxSize()' in c
assert 'shape=RoundedCornerShape(0.dp)' in c
assert 'versionCode = 57' in b and 'versionName = "0.9.24"' in b
assert '0.9.24 (57)' in a
