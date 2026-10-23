#!/usr/bin/env python3
"""Make named PNGs from our XCTest screenshots, retaining their original test/device metadata."""
from pathlib import Path
import json,re,sys,subprocess,struct
folder=Path(sys.argv[1]);out=folder/'named';out.mkdir(exist_ok=True);result=[]
for test in json.loads((folder/'manifest.json').read_text()):
 for attachment in test.get('attachments',[]):
  name=attachment.get('suggestedHumanReadableName','')
  if not name.startswith(('ru-','en-','launch-','pin-')):continue
  source=folder/attachment['exportedFileName']
  safe=re.sub('[^A-Za-z0-9_-]','-',name).strip('-')
  dest=out/(safe+'-'+source.stem[:8]+'.png')
  data=source.read_bytes()
  if data.startswith(b'\x89PNG\r\n\x1a\n'):dest.write_bytes(data)
  else:
   r=subprocess.run(['sips','-s','format','png',str(source),'--out',str(dest)],capture_output=True)
   if r.returncode:continue
  width,height=struct.unpack('>II',dest.read_bytes()[16:24])
  result.append({'name':name,'file':dest.name,'test':test['testIdentifier'],'deviceId':attachment.get('deviceId'),'width':width,'height':height})
(out/'index.json').write_text(json.dumps(result,indent=2)+'\n');print(f'{len(result)} named PNG screenshots')
