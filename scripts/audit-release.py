from pathlib import Path
import subprocess,json,re,hashlib,zipfile,shutil,struct
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
assert ('Zem iBox '+version+' —') in (root/'README.txt').read_text()
assert ('Версия '+version) in (root/'README.md').read_text()
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
assert application.attrib[ns+'label']=='Zem iBox'
assert application.attrib.get(ns+'debuggable','false')=='false'
permissions=sorted(p.attrib[ns+'name'] for p in manifest.findall('uses-permission'))
assert len(permissions)==7
assert len(application.findall('activity'))==2 and len(application.findall('service'))==1
providers=application.findall('provider');assert len(providers)==1
assert providers[0].attrib[ns+'name']=='io.zem.ibox.DiagnosticProvider' and providers[0].attrib[ns+'exported']=='false' and providers[0].attrib[ns+'grantUriPermissions']=='true'
assert providers[0].attrib[ns+'authorities']=='io.zem.ibox.diagnostics'
def native_alignment(data):
    assert data[:4]==b'\x7fELF' and data[5]==1
    bits=64 if data[4]==2 else 32
    offset=struct.unpack_from('<Q' if bits==64 else '<I',data,32 if bits==64 else 28)[0]
    size,count=struct.unpack_from('<HH',data,54 if bits==64 else 42)
    values=[]
    for i in range(count):
        at=offset+i*size
        if struct.unpack_from('<I',data,at)[0]==1:
            values.append(struct.unpack_from('<Q' if bits==64 else '<I',data,at+(48 if bits==64 else 28))[0])
    assert values
    if bits==64:assert min(values)>=16384
    return min(values)
provenance=json.loads((root/'docs/libvlc-provenance.json').read_text())
aar=root/'qa/libvlc-3.7.7.aar'
assert hashlib.sha256(aar.read_bytes()).hexdigest()==provenance['sha256']
native=[]
modules=list((root/'app/src/main/java/io/zem/ibox').glob('*.java'))
with zipfile.ZipFile(apk) as z,zipfile.ZipFile(aar) as upstream:
    dex=b''.join(z.read(p) for p in z.namelist() if re.fullmatch(r'classes[0-9]*\.dex',p))
    assert b'Ldev/roadlink/' not in dex
    for source in modules:
        assert ('Lio/zem/ibox/'+source.stem+';').encode() in dex,source.name
    expected_libs={'libvlc.so','libvlcjni.so','libc++_shared.so'}
    for name in z.namelist():
        if not name.startswith('lib/'):continue
        _,abi,library=name.split('/')
        assert abi in {'arm64-v8a','armeabi-v7a'} and library in expected_libs,name
        data=z.read(name)
        assert data==upstream.read('jni/'+abi+'/'+library),name
        native.append({'path':name,'sha256':hashlib.sha256(data).hexdigest(),'minLoadAlignment':native_alignment(data),'compressed':z.getinfo(name).compress_type!=0})
    assert {x['path'].split('/')[1] for x in native}=={'arm64-v8a','armeabi-v7a'}
    assert len(native)==6
    assets={p for p in z.namelist() if p.startswith('assets/')}
    own_assets={'assets/libvlc-LICENSE.txt','assets/third-party-notices.txt','assets/libvlc-LICENSE-ru.txt','assets/third-party-notices-ru.txt'}
    vlc_assets={p for p in upstream.namelist() if p.startswith('assets/') and not p.endswith('/')}
    assert assets==own_assets|vlc_assets,assets
    for name in own_assets:assert z.read(name)==(root/'app/src/main'/name).read_bytes()
    for name in vlc_assets:assert z.read(name)==upstream.read(name)
subprocess.run([str(sdk/'build-tools/36.0.0/zipalign'),'-c','-P','16','4',str(apk)],check=True)
report={'package':'io.zem.ibox','name':'Zem iBox','version':version,'versionCode':version_code,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'certificateSubject':dn,'certificateSha256':cert['sha256'],'permissions':permissions,'modules':len(modules),'libvlc':provenance['artifact'],'nativeLibraries':native,'bundledUpstreamAssets':len(vlc_assets),'privateNamesInCertificate':False,'audit':'PASS'}
(root/f'qa/release-{version}-audit.json').write_text(json.dumps(report,indent=2)+'\n')
(root/f'qa/release-{version}-manifest.xml').write_text(xml)
(root/f'qa/release-{version}-signature.txt').write_text(signature)
shutil.copy2(apk,root/f'release/Zem-iBox-{version}.apk')
print(json.dumps(report))
