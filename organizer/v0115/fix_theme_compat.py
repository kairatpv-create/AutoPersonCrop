from pathlib import Path
import sys
root=Path(sys.argv[1])
theme=root/'app/src/main/java/kz/kairat/organizer/Theme.kt'
t=theme.read_text()
marker='''@Composable fun OrganizerTheme(dark:Boolean=isSystemInDarkTheme(),contentScale:Float=1f,buttonScale:Float=1f,bold:Boolean=false,content: @Composable () -> Unit){'''
assert marker in t
compat='''@Composable fun OrganizerTheme(dark:Boolean,fontScale:Float,bold:Boolean,content: @Composable () -> Unit){
    OrganizerTheme(dark,fontScale,fontScale,bold,content)
}

'''
assert compat not in t
t=t.replace(marker,compat+marker,1)
theme.write_text(t)
assert 'OrganizerTheme(dark,fontScale,fontScale,bold,content)' in theme.read_text()
print('theme compatibility ok')
