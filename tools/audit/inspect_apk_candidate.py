#!/usr/bin/env python3
"""Verify the locally signed candidate has the exact tested staging payload and update identity.

Uses androguard for the compiled Android manifest. No device or user data is accessed.
Signature cryptography is independently verified by SignOrganizerApk/ApkVerifier.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

p=argparse.ArgumentParser()
p.add_argument('--staging',type=Path,required=True)
p.add_argument('--signed',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--version-name',required=True)
p.add_argument('--version-code',type=int,required=True)
a=p.parse_args()
from loguru import logger
logger.remove()
from androguard.core.apk import APK

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def payload(path):
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
        return {i.filename:hashlib.sha256(archive.read(i)).hexdigest() for i in archive.infolist()}
assert payload(a.staging)==payload(a.signed),'Signing changed the tested APK payload'
apk=APK(str(a.signed))
identity={'package':apk.get_package(),'versionName':apk.get_androidversion_name(),'versionCode':int(apk.get_androidversion_code()),
          'minSdk':int(apk.get_min_sdk_version()),'targetSdk':int(apk.get_target_sdk_version()),
          'debuggable':apk.get_attribute_value('application','debuggable')=='true'}
assert identity=={'package':'kz.kairat.organizer','versionName':a.version_name,'versionCode':a.version_code,'minSdk':26,'targetSdk':36,'debuggable':False},identity
native=[]
with a.signed.open('rb') as raw,zipfile.ZipFile(a.signed) as archive:
    for item in archive.infolist():
        if item.is_dir():continue
        raw.seek(item.header_offset);header=raw.read(30)
        assert header[:4]==b'PK\x03\x04'
        name_len,extra_len=struct.unpack_from('<HH',header,26)
        offset=item.header_offset+30+name_len+extra_len
        if item.compress_type==zipfile.ZIP_STORED:assert offset%4==0,(item.filename,'4-byte alignment')
        if item.filename.endswith('.so'):
            assert item.compress_type==zipfile.ZIP_STORED and offset%16384==0,(item.filename,'16-KiB ZIP alignment')
            data=archive.read(item);assert data[:4]==b'\x7fELF'
            endian='<' if data[5]==1 else '>'
            if data[4]==2:
                phoff=struct.unpack_from(endian+'Q',data,32)[0];entsize,count=struct.unpack_from(endian+'HH',data,54)
                segments=[struct.unpack_from(endian+'IIQQQQQQ',data,phoff+i*entsize) for i in range(count)]
                loads=[(x[2],x[3],x[7]) for x in segments if x[0]==1]
            else:
                phoff=struct.unpack_from(endian+'I',data,28)[0];entsize,count=struct.unpack_from(endian+'HH',data,42)
                segments=[struct.unpack_from(endian+'IIIIIIII',data,phoff+i*entsize) for i in range(count)]
                loads=[(x[1],x[2],x[7]) for x in segments if x[0]==1]
            assert loads and all(align>=16384 and (vaddr-offset)%16384==0 for offset,vaddr,align in loads),(item.filename,'16-KiB ELF alignment')
            native.append({'path':item.filename,'zipOffset':offset,'loadAlignment':min(x[2] for x in loads)})
report={'identity':identity,'sha256':sha(a.signed),'bytes':a.signed.stat().st_size,'stagingSha256':sha(a.staging),
        'testedPayloadUnchanged':True,'nativeLibraries':native,'zipAndElf16KiBChecks':True,
        'realPhoneTest':False,'storePublication':False}
a.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
