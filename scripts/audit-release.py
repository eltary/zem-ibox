from pathlib import Path
import subprocess,json,re,hashlib,zipfile,shutil
from xml.etree import ElementTree as ET
root=Path(__file__).resolve().parents[1]
sdk=Path.home()/'.cache/android-sdk'
apk=root/'app/build/outputs/apk/release/app-release.apk'
cert=json.loads((root/'docs/signing-certificate.json').read_text())
signature=subprocess.check_output([str(sdk/'build-tools/36.0.0/apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
dn=re.search(r'certificate DN: (.+)',signature).group(1)
assert dict(part.strip().split('=',1) for part in dn.split(','))=={'CN':'Zem','O':'Zem'}
assert not any(word in dn.lower() for word in ['user','redacted-user'])
assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)',signature)==[cert['sha256']]
xml=subprocess.check_output([str(sdk/'cmdline-tools/latest/bin/apkanalyzer'),'manifest','print',str(apk)],text=True)
manifest=ET.fromstring(xml);ns='{http://schemas.android.com/apk/res/android}';application=manifest.find('application')
assert manifest.attrib['package']=='io.zem.iboxdrive'
assert manifest.attrib[ns+'versionCode']=='5' and manifest.attrib[ns+'versionName']=='0.1.4'
assert application.attrib[ns+'label']=='Zem iBox Drive'
assert application.attrib.get(ns+'debuggable','false')=='false'
permissions=sorted(p.attrib[ns+'name'] for p in manifest.findall('uses-permission'))
assert len(permissions)==7
assert len(application.findall('activity'))==2 and len(application.findall('service'))==1
with zipfile.ZipFile(apk) as z:
    assert not any(p.startswith(('lib/','assets/')) for p in z.namelist())
    dex=z.read('classes.dex')
    assert b'Ldev/roadlink/' not in dex
    for source in (root/'app/src/main/java/io/zem/iboxdrive').glob('*.java'):
        assert ('Lio/zem/iboxdrive/'+source.stem+';').encode() in dex,source.name
report={'package':'io.zem.iboxdrive','name':'Zem iBox Drive','version':'0.1.4','versionCode':5,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'certificateSubject':dn,'certificateSha256':cert['sha256'],'permissions':permissions,'modules':20,'privateNamesInCertificate':False,'audit':'PASS'}
(root/'qa/release-0.1.4-audit.json').write_text(json.dumps(report,indent=2)+'\n')
(root/'qa/release-0.1.4-manifest.xml').write_text(xml)
(root/'qa/release-0.1.4-signature.txt').write_text(signature)
shutil.copy2(apk,root/'release/Zem-iBox-Drive-0.1.4.apk')
print(json.dumps(report))
