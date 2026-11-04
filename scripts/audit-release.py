from pathlib import Path
import subprocess,json,re,hashlib,zipfile,shutil,struct,argparse,os
from xml.etree import ElementTree as ET
root=Path(__file__).resolve().parents[1]
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'.cache/android-sdk')))
parser=argparse.ArgumentParser();parser.add_argument('--apk',type=Path,default=root/'app/build/outputs/apk/release/app-release.apk');parser.add_argument('--abis',default='arm64-v8a,armeabi-v7a');parser.add_argument('--variant',default='');args=parser.parse_args();apk=args.apk;expected_abis=set(args.abis.split(','));assert expected_abis and expected_abis<={'arm64-v8a','armeabi-v7a','x86','x86_64'};variant=args.variant or 'universal' if len(expected_abis)>1 else args.variant or next(iter(expected_abis))
cert=json.loads((root/'docs/signing-certificate.json').read_text())
gradle=(root/'app/build.gradle').read_text()
version=re.search(r"^\s*versionName '([^']+)'\s*$",gradle,re.M).group(1)
version_code=int(re.search(r'^\s*versionCode (\d+)\s*$',gradle,re.M).group(1))
assert re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)',version)
assert version_code>0
assert not re.search(r'(?<![\w.])'+re.escape(version)+r'(?![\w.])',(root/'README.md').read_text())
assert ('## '+version+' —') in (root/'CHANGELOG.md').read_text()
signature=subprocess.check_output([str(sdk/'build-tools/36.0.0/apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
dn=re.search(r'certificate DN: (.+)',signature).group(1)
assert dict(part.strip().split('=',1) for part in dn.split(','))==dict(part.strip().split('=',1) for part in cert['subject'].split(','))
assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)',signature)==[cert['sha256']]
xml=subprocess.check_output([str(sdk/'cmdline-tools/latest/bin/apkanalyzer'),'manifest','print',str(apk)],text=True)
manifest=ET.fromstring(xml);ns='{http://schemas.android.com/apk/res/android}';application=manifest.find('application')
assert manifest.attrib['package']=='io.zem.ibox'
assert int(manifest.attrib[ns+'versionCode'])==version_code and manifest.attrib[ns+'versionName']==version
assert application.attrib[ns+'label']=='Zem iBox'
assert application.attrib.get(ns+'debuggable','false')=='false'
permissions=sorted(p.attrib[ns+'name'] for p in manifest.findall('uses-permission'))
assert len(permissions)==10
assert "android.permission.REQUEST_INSTALL_PACKAGES" in permissions
assert int(manifest.find('uses-sdk').attrib[ns+'minSdkVersion'])==24
for permission in manifest.findall('uses-permission'):
    if permission.attrib[ns+'name'] in {'android.permission.READ_EXTERNAL_STORAGE','android.permission.WRITE_EXTERNAL_STORAGE'}:assert permission.attrib[ns+'maxSdkVersion']=='28'
assert len(application.findall('activity'))==3 and len(application.findall('service'))==1
assert {x.attrib[ns+'name'] for x in application.findall('activity')}=={'io.zem.ibox.MainActivity','io.zem.ibox.PlayerActivity','io.zem.ibox.DownloadsActivity'}
for x in application.findall('activity'):
    if x.attrib[ns+'name']!='io.zem.ibox.MainActivity':assert x.attrib[ns+'exported']=='false'
providers=application.findall('provider');assert len(providers)==2
expected_providers={'io.zem.ibox.DiagnosticProvider':'io.zem.ibox.diagnostics','io.zem.ibox.UpdateProvider':'io.zem.ibox.updates'}
for provider in providers:
    assert provider.attrib[ns+'name'] in expected_providers
    assert provider.attrib[ns+'exported']=='false' and provider.attrib[ns+'grantUriPermissions']=='true'
    assert provider.attrib[ns+'authorities']==expected_providers[provider.attrib[ns+'name']]
def native_alignment(data,abi):
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
    if abi=='arm64-v8a':assert min(values)>=16384
    return min(values)
provenance=json.loads((root/'docs/libvlc-provenance.json').read_text())
cached=list((Path.home()/'.gradle/caches/modules-2/files-2.1/org.videolan.android/libvlc-all/3.7.7').rglob('*.aar'));aar=cached[0] if cached else root/'qa/generated/dependencies/libvlc-3.7.7.aar'
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
        assert abi in expected_abis and library in expected_libs,name
        data=z.read(name)
        assert data==upstream.read('jni/'+abi+'/'+library),name
        native.append({'path':name,'sha256':hashlib.sha256(data).hexdigest(),'minLoadAlignment':native_alignment(data,abi),'compressed':z.getinfo(name).compress_type!=0})
    assert {x['path'].split('/')[1] for x in native}==expected_abis
    assert len(native)==3*len(expected_abis)
    assets={p for p in z.namelist() if p.startswith('assets/')}
    own_assets={'assets/libvlc-LICENSE.txt','assets/third-party-notices.txt','assets/libvlc-LICENSE-ru.txt','assets/third-party-notices-ru.txt','assets/desugar-LICENSE.txt','assets/desugar-ASSEMBLY-EXCEPTION.txt','assets/desugar-ADDITIONAL-LICENSE-INFO.txt','assets/zem-ibox-LICENSE.txt','assets/zem-ibox-LICENSE-ru.txt'}
    vlc_assets={p for p in upstream.namelist() if p.startswith('assets/') and not p.endswith('/')}
    assert assets==own_assets|vlc_assets,assets
    for name in own_assets:assert z.read(name)==(root/'app/src/main'/name).read_bytes()
    for name in vlc_assets:assert z.read(name)==upstream.read(name)
subprocess.run([str(sdk/'build-tools/36.0.0/zipalign'),'-c','-P','16','4',str(apk)],check=True)
report={'package':'io.zem.ibox','name':'Zem iBox','version':version,'versionCode':version_code,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'certificateSubject':dn,'certificateSha256':cert['sha256'],'permissions':permissions,'modules':len(modules),'libvlc':provenance['artifact'],'nativeLibraries':native,'bundledUpstreamAssets':len(vlc_assets),'privateNamesInCertificate':False,'audit':'PASS'}
out=root/'qa/reports/release';out.mkdir(parents=True,exist_ok=True)
report['variant']=variant;report['abis']=sorted(expected_abis)
(out/f'{version}-{variant}-audit.json').write_text(json.dumps(report,indent=2)+'\n')
(out/f'{version}-{variant}-manifest.xml').write_text(xml)
(out/f'{version}-{variant}-signature.txt').write_text(signature)
release=root/'release';release.mkdir(exist_ok=True);filename=f'Zem-iBox-{version}-{variant}.apk';shutil.copy2(apk,release/filename);report['file']=filename
(out/f'{version}-{variant}-audit.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
