#!/usr/bin/env python3
"""Build and audit all architecture packages; outputs are ignored by Git."""
from pathlib import Path
import subprocess,re,json,hashlib,sys
root=Path(__file__).resolve().parents[1]
version=re.search(r"versionName '([^']+)'",(root/'app/build.gradle').read_text()).group(1)
reports=root/'qa/reports/release';reports.mkdir(parents=True,exist_ok=True)
variants=[(abi,abi) for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']]+[('universal','arm64-v8a,armeabi-v7a,x86,x86_64')]
summary=[]
for name,abis in variants:
    with (reports/f'{version}-{name}-build.log').open('w') as log:
        subprocess.run([str(root/'gradlew'),':app:assembleRelease','-Pzem.abis='+abis,'--console=plain'],cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
    result=subprocess.run([sys.executable,str(root/'scripts/audit-release.py'),'--abis',abis,'--variant',name],cwd=root,capture_output=True,text=True,check=True)
    report=json.loads(result.stdout.splitlines()[-1]);summary.append(report)
    print(name,report['bytes'],report['sha256'],flush=True)
release=root/'release'
(release/'SHA256SUMS.txt').write_text(''.join(r['sha256']+'  '+r['file']+'\n' for r in summary))
(release/'release-manifest.json').write_text(json.dumps({'version':version,'versionCode':summary[0]['versionCode'],'packages':summary},ensure_ascii=False,indent=2)+'\n')
print('PASS: five signed and audited APKs',flush=True)
