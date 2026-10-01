from pathlib import Path
import sys

root=Path(sys.argv[1])
cloud=root/'app/src/main/java/kz/kairat/organizer/CloudSync.kt'
s=cloud.read_text()
old='Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface))'
new='Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer))'
assert old in s
s=s.replace(old,new,1)
cloud.write_text(s)

final=cloud.read_text()
assert 'CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)' in final
assert 'CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)' not in final
