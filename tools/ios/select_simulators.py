#!/usr/bin/env python3
"""Choose real available runtimes and create disposable small/large iPhone simulators."""
import json,subprocess,os
from pathlib import Path
def sim(*args):return subprocess.check_output(['xcrun','simctl',*args],text=True)
runtimes=json.loads(sim('list','runtimes','-j'))['runtimes'];types=json.loads(sim('list','devicetypes','-j'))['devicetypes']
available=[r for r in runtimes if r.get('isAvailable') and 'iOS' in r['name']]
runtime=max(available,key=lambda r:tuple(int(x) for x in r['version'].split('.')))
phones=[d for d in types if 'iPhone' in d['name']]
small=[d for d in phones if d['name']=='iPhone SE (3rd generation)']
large=sorted([d for d in phones if 'Pro Max' in d['name']],key=lambda d:tuple(int(x) for x in __import__('re').findall(r'\d+',d['name'])),reverse=True)
def create(candidates,label):
    for device in candidates:
        result=subprocess.run(['xcrun','simctl','create','Organizer QA '+label,device['identifier'],runtime['identifier']],text=True,capture_output=True)
        if result.returncode==0:return {'name':device['name'],'id':result.stdout.strip(),'runtime':runtime['version']}
    raise RuntimeError('No compatible '+label+' iPhone type')
selection={'small':create(small or phones,'small'),'large':create(large or phones,'large')}
Path('ios-evidence').mkdir(exist_ok=True);Path('ios-evidence/simulators.json').write_text(json.dumps(selection,indent=2))
with open(os.environ['GITHUB_ENV'],'a') as f:
    for label,data in selection.items():f.write('IOS_'+label.upper()+'_UDID='+data['id']+'\n')
print(json.dumps(selection,indent=2))
