#!/usr/bin/env python3
"""Check tracked files, reachable Git objects and APK entries against a private list."""
from pathlib import Path
import subprocess,json,argparse,zipfile,re,sys
p=argparse.ArgumentParser();p.add_argument('--deny-file',type=Path,required=True);p.add_argument('--history',action='store_true');p.add_argument('--apk-dir',type=Path);a=p.parse_args()
terms=json.loads(a.deny_file.read_text());needles=[(t.encode().lower(),t.encode('utf-16le').lower()) for t in terms]
def found(data):
    # The repository owner's GitHub URL is explicitly allowed. Other text is checked.
    data=re.sub(rb'https?://(?:api[.])?github[.]com/(?:repos/)?[^/\s"<>]+/',b'https://github.com/OWNER/',data,flags=re.I).lower()
    return any(x in data or y in data for x,y in needles)
errors=[];files=subprocess.check_output(['git','ls-files','-z']).split(b'\0');checked=0
for raw in files:
    if not raw:continue
    path=Path(raw.decode());
    if not path.is_file():continue
    checked+=1
    if found(path.read_bytes()):errors.append('tracked file: '+str(path))
blobs=0
if a.history:
    ids=subprocess.check_output(['git','rev-list','--objects','--all']).splitlines()
    proc=subprocess.Popen(['git','cat-file','--batch'],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
    for line in ids:
        ident=line.split(b' ',1)[0];proc.stdin.write(ident+b'\n');proc.stdin.flush();head=proc.stdout.readline().split();size=int(head[2]);data=proc.stdout.read(size);proc.stdout.read(1)
        if head[1]==b'blob':
            blobs+=1
            if found(data):errors.append('reachable blob: '+ident.decode())
        elif head[1] in (b'commit',b'tag') and found(data):errors.append('commit/tag metadata: '+ident.decode())
    proc.stdin.close();proc.wait()
apks=0;entries=0
if a.apk_dir:
    for apk in a.apk_dir.glob('*.apk'):
        apks+=1
        if found(apk.read_bytes()):errors.append('APK container: '+apk.name)
        with zipfile.ZipFile(apk) as archive:
            for name in archive.namelist():
                entries+=1
                if found(archive.read(name)):errors.append('APK entry: '+apk.name+'!'+name)
result={'trackedFiles':checked,'reachableBlobs':blobs,'apks':apks,'apkEntries':entries,'matches':len(errors),'result':'PASS' if not errors else 'FAIL'}
print(json.dumps(result));
if errors:print('\n'.join(errors),file=sys.stderr);sys.exit(1)
