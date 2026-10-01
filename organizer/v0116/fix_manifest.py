from pathlib import Path
import sys

root=Path(sys.argv[1])
manifest=root/'app/src/main/AndroidManifest.xml'
m=manifest.read_text()
perm='    <uses-permission android:name="android.permission.INTERNET" />\n'
# Remove the misplaced v0.9.26 permission wherever the first patch put it.
m=m.replace(perm,'')
# Insert inside the root <manifest ...> element, after its opening tag.
start=m.index('<manifest')
end=m.index('>',start)+1
m=m[:end]+'\n'+perm.rstrip('\n')+m[end:]
manifest.write_text(m)
# XML/root sanity gates.
assert m.lstrip().startswith('<?xml') or m.lstrip().startswith('<manifest')
assert m.index('<manifest') < m.index('android.permission.INTERNET')
assert m.count('android.permission.INTERNET')==1
print('manifest permission placement fixed')
