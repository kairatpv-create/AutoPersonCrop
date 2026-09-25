from pathlib import Path
import sys

root=Path(sys.argv[1])
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

s=ui.read_text()

# Finance tabs: restore full available width, reduce only vertical height, preserve existing font size and centering.
old='''                MoneyTabPill(\n                    selected=selectedTab==i,\n                    text=AppStrings.t(lang,key),\n                    onClick={onTabSelected(i)},\n                    modifier=Modifier.width(58.dp)\n                )\n'''
new='''                MoneyTabPill(\n                    selected=selectedTab==i,\n                    text=AppStrings.t(lang,key),\n                    onClick={onTabSelected(i)},\n                    modifier=Modifier.weight(1f)\n                )\n'''
assert old in s
s=s.replace(old,new,1)
assert s.count('modifier=modifier.height(42.dp)')>=2
s=s.replace('modifier=modifier.height(42.dp)','modifier=modifier.height(32.dp)',2)

# Note editor action row: restore proportional widths, reduce only height; keep font sizes unchanged.
assert 'Box(Modifier.width(76.dp))' in s
s=s.replace('Box(Modifier.width(76.dp))','Box(Modifier.weight(1f))',1)
old='modifier=Modifier.fillMaxWidth().height(52.dp),\n                            contentPadding=PaddingValues(horizontal=4.dp)'
new='modifier=Modifier.fillMaxWidth().height(36.dp),\n                            contentPadding=PaddingValues(horizontal=6.dp)'
assert old in s
s=s.replace(old,new,1)
assert s.count('modifier=Modifier.width(76.dp).height(52.dp)')>=2
s=s.replace('modifier=Modifier.width(76.dp).height(52.dp)','modifier=Modifier.weight(1f).height(36.dp)',2)

ui.write_text(s)

# Version bump only; applicationId/database/schema are untouched.
b=gradle.read_text()
assert 'versionCode = 44' in b and 'versionName = "0.9.11"' in b
b=b.replace('versionCode = 44','versionCode = 45').replace('versionName = "0.9.11"','versionName = "0.9.12"')
gradle.write_text(b)
a=strings.read_text()
assert '0.9.11 (44)' in a
a=a.replace('0.9.11 (44)','0.9.12 (45)')
strings.write_text(a)

# Final compact settings/title/centering pass.
finalizer=Path(__file__).with_name('finalize.py')
assert finalizer.exists()
exec(compile(finalizer.read_text(),str(finalizer),'exec'))

# Gates.
s=ui.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'modifier=Modifier.weight(1f)' in s
assert 'modifier=modifier.height(32.dp)' in s
assert 'Modifier.weight(1f).height(36.dp)' in s
assert 'languagesLabel(lang)' in s
assert 'Text(AppStrings.languageFlag(lang))' not in s
assert 'versionCode = 45' in b and 'versionName = "0.9.12"' in b
assert '0.9.12 (45)' in a
