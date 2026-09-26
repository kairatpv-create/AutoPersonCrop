from pathlib import Path
import base64, zlib, subprocess, shutil, sys

org = Path('organizer')
build = org / 'buildsrc0106'
if build.exists():
    shutil.rmtree(build)
build.mkdir(parents=True)

encoded = b''.join((org/'v090tar'/f'part{i:02d}').read_bytes() for i in range(4))
archive = org/'MyOrganizer-v0.9.0-source.tar.xz'
archive.write_bytes(base64.b64decode(encoded))
subprocess.run(['tar','-xJf',str(archive),'-C',str(build)], check=True)
root = build/'MyOrganizer-v0.9.0-source'
assert (root/'app/build.gradle.kts').exists()

# Restore v0.9.1 payload exactly as the historical workflow did.
appstrings = zlib.decompress(base64.b64decode((org/'v091/appstrings.zlib.b64').read_text())).decode()
(root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt').write_text(appstrings)
apply091 = zlib.decompress(base64.b64decode((org/'v091/apply.zlib.b64').read_text())).decode()
apply091_path = org/'apply_v091.py'
apply091_path.write_text(apply091)
subprocess.run([sys.executable,str(apply091_path),str(root)],check=True)

def move_to(name:str):
    global root
    dst=build/name
    shutil.move(str(root),str(dst))
    root=dst

def apply(rel:str):
    subprocess.run([sys.executable,str(org/rel),str(root)],check=True)

move_to('MyOrganizer-v0.9.1-source')
apply('v092/apply.py')
ui=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
s=ui.read_text()
old='MoneyEntryCard(m,lang,if(m.type=="income")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error){'
new='MoneyEntryCard(m,lang,if(m.type=="income")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,if(m.type=="income")0 else 1){'
assert old in s
ui.write_text(s.replace(old,new,1))
move_to('MyOrganizer-v0.9.2-source')

stages=[
    ('v093/apply.py','MyOrganizer-v0.9.3-source'),
    ('v094/apply.py','Organizer-Pro-v0.9.4-source'),
    ('v095/apply.py','Organizer-Pro-v0.9.5-source'),
    ('v096/apply.py','Organizer-Pro-v0.9.6-source'),
    ('v097/apply.py','Organizer-Pro-v0.9.7-source'),
    ('v098/apply.py','Organizer-Pro-v0.9.8-source'),
    ('v099/apply.py','Organizer-Pro-v0.9.9-source'),
    ('v0100/apply.py','Organizer-Pro-v0.9.10-source'),
    ('v0101/apply.py','Organizer-Pro-v0.9.11-source'),
    ('v0102/apply.py','Organizer-Pro-v0.9.12-source'),
    ('v0103/apply.py','Organizer-Pro-v0.9.13-source'),
    ('v0104/apply.py','Organizer-Pro-v0.9.14-source'),
    ('v0105/apply.py','Organizer-Pro-v0.9.15-source'),
    ('v0106/apply.py','Organizer-Pro-v0.9.16-source'),
]
for patch,dst in stages:
    apply(patch)
    move_to(dst)

print(root)
