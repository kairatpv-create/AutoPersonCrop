from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()

# v0.9.15: closed language control is globe-only. Keep flags/names inside dropdown.
old='''                Box(Modifier.weight(.95f)){
                    FilledTonalButton(onClick={languageMenu=true},colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),modifier=Modifier.fillMaxWidth().heightIn(min=36.dp)){
                        Icon(Icons.Default.Language,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(languagesLabel(lang),modifier=Modifier.weight(1f),maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold));Spacer(Modifier.width(2.dp));Icon(Icons.Default.ArrowDropDown,null,Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded=languageMenu,onDismissRequest={languageMenu=false}){
                        AppStrings.supportedLanguages.forEach{code->DropdownMenuItem(text={Text(AppStrings.languageName(code))},leadingIcon={Text(AppStrings.languageFlag(code),fontSize=20.sp)},trailingIcon={if(AppStrings.normalizeLanguage(lang)==code) Icon(Icons.Default.Check,null) else Spacer(Modifier.size(24.dp))},onClick={onLanguage(code);languageMenu=false})}
                    }
                }'''
new='''                Box{
                    FilledTonalButton(onClick={languageMenu=true},colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary),contentPadding=PaddingValues(0.dp),modifier=Modifier.size(44.dp)){
                        Icon(Icons.Default.Language,null,Modifier.size(23.dp))
                    }
                    DropdownMenu(expanded=languageMenu,onDismissRequest={languageMenu=false}){
                        AppStrings.supportedLanguages.forEach{code->DropdownMenuItem(text={Text(AppStrings.languageName(code))},leadingIcon={Text(AppStrings.languageFlag(code),fontSize=20.sp)},trailingIcon={if(AppStrings.normalizeLanguage(lang)==code) Icon(Icons.Default.Check,null) else Spacer(Modifier.size(24.dp))},onClick={onLanguage(code);languageMenu=false})}
                    }
                }'''
assert old in s
s=s.replace(old,new,1)

ui.write_text(s)

# Version bump only; applicationId/database/data stay unchanged.
b=gradle.read_text()
assert 'versionCode = 47' in b and 'versionName = "0.9.14"' in b
b=b.replace('versionCode = 47','versionCode = 48').replace('versionName = "0.9.14"','versionName = "0.9.15"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.14 (47)' in a
a=a.replace('0.9.14 (47)','0.9.15 (48)')
strings.write_text(a)

# Gates.
s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'AppStrings.t(lang,"app"),modifier=Modifier.weight(1.15f)' in s
assert 'FilledTonalButton(onClick={languageMenu=true}' in s
assert 'modifier=Modifier.size(44.dp)' in s
assert 'Icon(Icons.Default.Language,null,Modifier.size(23.dp))' in s
assert 'Text(languagesLabel(lang)' not in s
assert 'Icon(Icons.Default.ArrowDropDown,null,Modifier.size(20.dp))' not in s
assert 'leadingIcon={Text(AppStrings.languageFlag(code),fontSize=20.sp)}' in s
assert 'BottomTextNavPill(section=="settings"' in s
assert 'versionCode = 48' in b and 'versionName = "0.9.15"' in b
assert '0.9.15 (48)' in a
