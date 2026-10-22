#!/usr/bin/env python3
"""Execute recovered Kotlin data/sync code on host SQLite with synthetic-only storage.

Not Android instrumentation: see README for the adapter and networking limits.
Dependencies are supplied explicitly; this script never downloads or uses credentials.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess

p=argparse.ArgumentParser()
p.add_argument('--source',type=Path,required=True)
p.add_argument('--jars',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--suite',choices=['audit','backup-validation','restore-integrity','attachment-validation','attachment-restore','restore-recovery','restore-recovery-probe','import-safety','import-safety-probe','sync-safety','sync-safety-probe'],default='audit')
a=p.parse_args()
source=a.source.resolve()/'app/src/main/java/kz/kairat/organizer'
out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
stubdir=Path(__file__).resolve().parent/'jvm'
files=[source/(n+'.kt') for n in ['Models','AppStrings','NoteDatabase','SettingsStore','BackupManager','AttachmentStore']]
for optional in ['BackupValidation','AttachmentRestoreFiles','RestoreJournal','BackupImportLimits','BackupImportReader','RestoreSafety','SyncSnapshot','SyncStateStore','SyncConflictStore']:
    if (source/(optional+'.kt')).exists():
        files.append(source/(optional+'.kt'))
if a.suite in ('audit','sync-safety','sync-safety-probe'):
    cloud=(source/'CloudSync.kt').read_text()
    cloud=cloud.split('private fun syncText(',1)[0]
    cloud='\n'.join(l for l in cloud.splitlines() if not l.startswith(('import androidx.','import kotlinx.','import android.app.Activity')))
    original='private const val ORGANIZER_SERVER = "https://organizer-pro.onrender.com"'
    assert original in cloud
    cloud=cloud.replace(original,'private val ORGANIZER_SERVER: String get() = System.getProperty("organizer.audit.server")')
    (out/'CloudSyncCore.kt').write_text(cloud)
    files.append(out/'CloudSyncCore.kt')
main={'audit':'AuditMain','backup-validation':'BackupValidationMain','restore-integrity':'RestoreIntegrityMain',
      'attachment-validation':'AttachmentValidationMain','attachment-restore':'AttachmentRestoreMain','restore-recovery':'RestoreRecoveryMain',
      'restore-recovery-probe':'RecoveryProbeMain','import-safety':'ImportSafetyMain','import-safety-probe':'ImportSafetyProbeMain','sync-safety':'SyncSafetyMain','sync-safety-probe':'SyncSafetyProbeMain'}[a.suite]
files += [f for f in sorted(stubdir.glob('*.kt')) if not f.name.endswith('Main.kt') or f.stem==main]
classpath=os.pathsep.join(str(x.resolve()) for x in sorted(a.jars.glob('*.jar')))
assert classpath,'No compiler jars supplied'
manifest={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in files}
(out/'source-sha256.json').write_text(json.dumps(manifest,indent=2))
cmd=['java','-cp',classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',classpath,'-d',str(out/'classes'),*map(str,files)]
java_env=os.environ.copy()
java_env['JAVA_TOOL_OPTIONS']=(java_env.get('JAVA_TOOL_OPTIONS','')+' -XX:-UsePerfData').strip()
with (out/'compile.log').open('w') as log:
    r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,env=java_env)
if r.returncode:
    print((out/'compile.log').read_text());raise SystemExit(r.returncode)
r=subprocess.run(['java','--add-modules','jdk.httpserver','-cp',str(out/'classes')+os.pathsep+classpath,'kz.kairat.organizer.'+main+'Kt'],text=True,capture_output=True,env=java_env)
(out/'results.json').write_text(r.stdout)
(out/'runtime.log').write_text(r.stderr)
print(r.stdout)
if r.stderr:print(r.stderr)
raise SystemExit(r.returncode)
