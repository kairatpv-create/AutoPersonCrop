from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()

# v0.9.14: keep the localized app name visibly centered between logo and language selector.
old='Text(AppStrings.t(lang,"app"),modifier=Modifier.weight(1f),maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold,letterSpacing=.4.sp))'
new='Text(AppStrings.t(lang,"app"),modifier=Modifier.weight(1.15f),maxLines=3,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.ExtraBold,letterSpacing=.5.sp,lineHeight=18.sp))'
assert old in s
s=s.replace(old,new,1)

old='''                Box{\n                    FilledTonalButton(onClick={languageMenu=true},colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),modifier=Modifier.heightIn(min=36.dp).widthIn(min=104.dp)){'''
new='''                Box(Modifier.weight(.95f)){\n                    FilledTonalButton(onClick={languageMenu=true},colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),modifier=Modifier.fillMaxWidth().heightIn(min=36.dp)){'''
assert old in s
s=s.replace(old,new,1)

ui.write_text(s)

# Version bump only; applicationId/database/data stay unchanged.
b=gradle.read_text()
assert 'versionCode = 46' in b and 'versionName = "0.9.13"' in b
b=b.replace('versionCode = 46','versionCode = 47').replace('versionName = "0.9.13"','versionName = "0.9.14"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.13 (46)' in a
a=a.replace('0.9.13 (46)','0.9.14 (47)')
strings.write_text(a)

# Gates.
s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'AppStrings.t(lang,"app"),modifier=Modifier.weight(1.15f)' in s
assert 'Box(Modifier.weight(.95f))' in s
assert 'MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.ExtraBold' in s
assert 'BottomTextNavPill(section=="settings"' in s
assert 'versionCode = 47' in b and 'versionName = "0.9.14"' in b
assert '0.9.14 (47)' in a
