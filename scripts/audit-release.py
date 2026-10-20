from pathlib import Path
import subprocess,json,re,hashlib,zipfile,shutil
from xml.etree import ElementTree as ET
root=Path(__file__).resolve().parents[1]
sdk=Path.home()/'.cache/android-sdk'
apk=root/'app/build/outputs/apk/release/app-release.apk'
cert=json.loads((root/'docs/signing-certificate.json').read_text())
gradle=(root/'app/build.gradle').read_text()
version=re.search(r"^\s*versionName '([^']+)'\s*$",gradle,re.M).group(1)
version_code=int(re.search(r'^\s*versionCode (\d+)\s*$',gradle,re.M).group(1))
assert re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)',version)
assert version_code>0
assert ('Zem iBox Drive '+version+' —') in (root/'README.txt').read_text()
assert ('## '+version+' —') in (root/'CHANGELOG.md').read_text()
signature=subprocess.check_output([str(sdk/'build-tools/36.0.0/apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
dn=re.search(r'certificate DN: (.+)',signature).group(1)
assert dict(part.strip().split('=',1) for part in dn.split(','))==dict(part.strip().split('=',1) for part in cert['subject'].split(','))
assert not any(word in dn.lower() for word in ['user','redacted-user'])
assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)',signature)==[cert['sha256']]
xml=subprocess.check_output([str(sdk/'cmdline-tools/latest/bin/apkanalyzer'),'manifest','print',str(apk)],text=True)
manifest=ET.fromstring(xml);ns='{http://schemas.android.com/apk/res/android}';application=manifest.find('application')
assert manifest.attrib['package']=='io.zem.ibox'
assert int(manifest.attrib[ns+'versionCode'])==version_code and manifest.attrib[ns+'versionName']==version
assert application.attrib[ns+'label']=='Zem iBox Drive'
assert application.attrib.get(ns+'debuggable','false')=='false'
permissions=sorted(p.attrib[ns+'name'] for p in manifest.findall('uses-permission'))
assert len(permissions)==7
assert len(application.findall('activity'))==2 and len(application.findall('service'))==1
with zipfile.ZipFile(apk) as z:
    assert not any(p.startswith(('lib/','assets/')) for p in z.namelist())
    dex=z.read('classes.dex')
    assert b'Ldev/roadlink/' not in dex
    for source in (root/'app/src/main/java/io/zem/ibox').glob('*.java'):
        assert ('Lio/zem/ibox/'+source.stem+';').encode() in dex,source.name
report={'package':'io.zem.ibox','name':'Zem iBox Drive','version':version,'versionCode':version_code,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'certificateSubject':dn,'certificateSha256':cert['sha256'],'permissions':permissions,'modules':20,'privateNamesInCertificate':False,'audit':'PASS'}
(root/f'qa/release-{version}-audit.json').write_text(json.dumps(report,indent=2)+'\n')
(root/f'qa/release-{version}-manifest.xml').write_text(xml)
(root/f'qa/release-{version}-signature.txt').write_text(signature)
shutil.copy2(apk,root/f'release/Zem-iBox-Drive-{version}.apk')
print(json.dumps(report))
