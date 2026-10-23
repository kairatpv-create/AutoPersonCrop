#!/usr/bin/env python3
"""Inspect a real Xcode archive; never manufactures an IPA or a signing result."""
from pathlib import Path
import argparse,plistlib,json,hashlib,subprocess
p=argparse.ArgumentParser();p.add_argument('archive',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
app=a.archive/'Products/Applications/OrganizerPro.app'
info=plistlib.loads((app/'Info.plist').read_bytes())
assert info['CFBundleIdentifier']=='com.organizerpro.mobile'
assert info['UIDeviceFamily']==[1]
assert info['MinimumOSVersion']=='17.0'
assert int(info['DTXcode'])>=2600 and float(info['DTSDKName'].removeprefix('iphoneos'))>=26
assert info.get('ITSAppUsesNonExemptEncryption') is False
assert 'NSAppTransportSecurity' not in info
assert (app/'PrivacyInfo.xcprivacy').is_file()
assert not (app/'Fixtures').exists()
binary=app/info['CFBundleExecutable'];data=binary.read_bytes()
for marker in [b'ORGANIZER_UI_FIXTURE',b'ORGANIZER_UI_IMPORT',b'-ui-testing',b'-test-session',b'-test-pin']:
 assert marker not in data, 'DEBUG-only test hook leaked into Release'
architecture=subprocess.check_output(['xcrun','lipo','-info',str(binary)],text=True).strip()
assert 'arm64' in architecture
signature=subprocess.run(['codesign','--verify','--deep','--strict',str(app)],capture_output=True,text=True)
result={'bundleIdentifier':info['CFBundleIdentifier'],'version':info['CFBundleShortVersionString'],'build':info['CFBundleVersion'],'minimumOS':info['MinimumOSVersion'],'sdk':info['DTSDKName'],'xcode':info['DTXcode'],'architecture':architecture,'binarySHA256':hashlib.sha256(data).hexdigest(),'debugHooksAbsent':True,'privacyManifestPresent':True,'signatureVerified':signature.returncode==0,'archiveSignedForDistribution':(app/'embedded.mobileprovision').exists() and signature.returncode==0,'appStoreUploaded':False}
a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
