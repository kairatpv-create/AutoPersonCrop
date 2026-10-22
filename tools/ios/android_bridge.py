#!/usr/bin/env python3
"""Compile the real Android data layer with host adapters; never opens application/user storage."""
from pathlib import Path
import argparse, os, subprocess, hashlib, json
p=argparse.ArgumentParser();p.add_argument('--jars',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('arguments',nargs='+');a=p.parse_args()
root=Path(__file__).resolve().parents[2];out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
source=root/'mobile/android/app/src/main/java/kz/kairat/organizer'
names=['Models','AppStrings','NoteDatabase','SettingsStore','BackupManager','AttachmentStore','ProtectedSettings','MoneyAmounts','BackupValidation','AttachmentRestoreFiles','RestoreJournal','BackupImportLimits','BackupImportReader','RestoreSafety','SyncSnapshot','SyncStateStore','SyncConflictStore']
files=[source/(name+'.kt') for name in names]
files += [f for f in sorted((root/'tools/audit/jvm').glob('*.kt')) if not f.name.endswith('Main.kt')]
files += [Path(__file__).with_name('AndroidBridgeMain.kt')]
(out/'source-sha256.json').write_text(json.dumps({str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in files},indent=2))
cp=os.pathsep.join(str(f.resolve()) for f in sorted(a.jars.glob('*.jar')))
subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(out/'classes'),*map(str,files)],check=True,stdout=(out/'compile.log').open('w'),stderr=subprocess.STDOUT)
subprocess.run(['java','-cp',str(out/'classes')+os.pathsep+cp,'kz.kairat.organizer.AndroidBridgeMainKt',*a.arguments],check=True)
