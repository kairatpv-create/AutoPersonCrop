from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()

# v0.9.13: adaptive controls when the user increases app font scale.
# Keep compact minimum heights at normal scale, but never force content into a fixed-height box.
repls = [
    ('modifier=modifier.height(32.dp)', 'modifier=modifier.heightIn(min=32.dp)'),
    ('modifier=Modifier.fillMaxWidth().height(36.dp),', 'modifier=Modifier.fillMaxWidth().heightIn(min=36.dp),'),
    ('modifier=Modifier.weight(1f).height(36.dp)', 'modifier=Modifier.weight(1f).heightIn(min=36.dp)'),
    ('modifier=Modifier.fillMaxWidth().height(52.dp)', 'modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)'),
    ('Modifier.fillMaxWidth().height(38.dp)', 'Modifier.fillMaxWidth().heightIn(min=38.dp)'),
    ('Modifier.height(36.dp).widthIn(min=104.dp,max=132.dp)', 'Modifier.heightIn(min=36.dp).widthIn(min=104.dp)'),
    ('Modifier.fillMaxWidth().height(32.dp)', 'Modifier.fillMaxWidth().heightIn(min=32.dp)'),
    ('modifier=Modifier.weight(1.28f).height(28.dp)', 'modifier=Modifier.weight(1.28f).heightIn(min=28.dp)'),
    ('Modifier.fillMaxWidth().height(34.dp)', 'Modifier.fillMaxWidth().heightIn(min=34.dp)'),
    ('Modifier.fillMaxWidth().height(36.dp)', 'Modifier.fillMaxWidth().heightIn(min=36.dp)'),
    ('Modifier.fillMaxWidth().height(36.dp),verticalAlignment=Alignment.CenterVertically', 'Modifier.fillMaxWidth().heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically'),
]
for old,new in repls:
    if old in s:
        s=s.replace(old,new)

# Top settings title and language selector: allow additional lines/height rather than clipping at large fonts.
old='Text(AppStrings.t(lang,"app"),modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold,letterSpacing=.4.sp))'
new='Text(AppStrings.t(lang,"app"),modifier=Modifier.weight(1f),maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold,letterSpacing=.4.sp))'
assert old in s
s=s.replace(old,new,1)
old='Text(languagesLabel(lang),modifier=Modifier.weight(1f),maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold))'
new='Text(languagesLabel(lang),modifier=Modifier.weight(1f),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold))'
assert old in s
s=s.replace(old,new,1)

# Backup actions: allow text to wrap and let the button grow with it.
s=s.replace('Text(AppStrings.t(lang,"save_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)',
            'Text(AppStrings.t(lang,"save_backup"),maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center)',1)
s=s.replace('Text(AppStrings.t(lang,"restore"),maxLines=1,textAlign=androidx.compose.ui.text.style.TextAlign.Center)',
            'Text(AppStrings.t(lang,"restore"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)',1)
s=s.replace('Text(AppStrings.t(lang,"send_backup"),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)',
            'Text(AppStrings.t(lang,"send_backup"),maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center)',1)

# Settings switch rows also expand with labels on large text.
old='@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onChange)}}'
assert old in s
new='@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),maxLines=2);Switch(value,onChange)}}'
s=s.replace(old,new,1)

ui.write_text(s)

# Version bump only; keep applicationId/database schema/data untouched.
b=gradle.read_text()
assert 'versionCode = 45' in b and 'versionName = "0.9.12"' in b
b=b.replace('versionCode = 45','versionCode = 46').replace('versionName = "0.9.12"','versionName = "0.9.13"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.12 (45)' in a
a=a.replace('0.9.12 (45)','0.9.13 (46)')
strings.write_text(a)

# Gates.
s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'heightIn(min=32.dp)' in s
assert 'heightIn(min=36.dp)' in s
assert 'heightIn(min=38.dp)' in s
assert 'heightIn(min=52.dp)' in s
assert 'maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center' in s
assert 'BottomTextNavPill(section=="settings"' in s
assert 'versionCode = 46' in b and 'versionName = "0.9.13"' in b
assert '0.9.13 (46)' in a
