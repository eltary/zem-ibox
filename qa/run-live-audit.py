"""Live audio/clock/default-player regression. Uses only emulator-5586."""
from pathlib import Path
import subprocess,json,argparse
root=Path(__file__).resolve().parents[1];out=root/'qa/live-0.0.15';out.mkdir(exist_ok=True);adb=Path.home()/'.cache/android-sdk/platform-tools/adb'
def run(*args,**kwargs):return subprocess.run([str(adb),'-s','emulator-5586',*args],capture_output=True,text=True,errors='replace',timeout=240,**kwargs)
p=argparse.ArgumentParser();p.add_argument('--scenario',action='append');p.add_argument('--resume',action='store_true');a=p.parse_args()
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:run('install','-r',str(root/apk)).check_returncode()
results=json.loads((out/'validation.json').read_text()) if a.resume and (out/'validation.json').exists() else []
try:
 for mode,font,checks in [('normal','1.0',['live_fix_only','integration','phone_only','compat_only','rtsp_udp_only']),('320-130','1.3',['live_ui_only']),('320-200','2.0',['live_ui_only'])]:
  run('shell','wm','size','reset' if mode=='normal' else '640x1280');run('shell','wm','density','reset' if mode=='normal' else '320');run('shell','settings','put','system','font_scale',font)
  for check in checks:
   if a.scenario and check not in a.scenario:continue
   if any(v['mode']==mode and v['scenario']==check and v['passed'] for v in results):continue
   run('shell','am','force-stop','io.zem.ibox');run('logcat','-c');args=['shell','am','instrument','-w']+([] if check=='integration' else ['-e',check,'true'])+['io.zem.ibox.test/io.zem.ibox.SmokeTest'];result=run(*args);name=check+'-'+mode;(out/(name+'.log')).write_text((result.stdout+result.stderr).rstrip()+'\n');log=run('logcat','-d').stdout;(out/(name+'-details.log')).write_text('\n'.join(l for l in log.splitlines() if 'Zem iBox/' in l)+'\n');passed='PASS:' in result.stdout and 'FAIL:' not in result.stdout and 'AssertionError' not in result.stdout;results=[v for v in results if not (v['mode']==mode and v['scenario']==check)];results.append(dict(mode=mode,scenario=check,passed=passed,output=result.stdout.strip()));(out/'validation.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n');print(name,result.stdout.strip(),flush=True)
   if check in ['live_fix_only','live_ui_only']:run('pull','/sdcard/Android/data/io.zem.ibox/files/ui-audit/27-live-sound.png',str(out/('live-sound-'+mode+'.png')))
   if check=='live_fix_only':run('pull','/sdcard/Android/data/io.zem.ibox/files/ui-audit/28-rtsp-fullhd.png',str(out/'rtsp-fullhd.png'))
   if not passed:raise RuntimeError('Failed '+name)
finally:
 run('shell','wm','size','reset');run('shell','wm','density','reset');run('shell','settings','put','system','font_scale','1.0')
