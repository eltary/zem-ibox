from pathlib import Path
import subprocess,json,sys
root=Path(__file__).resolve().parents[1];out=root/'qa/reports/format';adb=Path.home()/'.cache/android-sdk/platform-tools/adb';out.mkdir(parents=True,exist_ok=True)
def run(*args):return subprocess.run([str(adb),'-s','emulator-5586',*args],capture_output=True,text=True,errors='replace',timeout=180)
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:run('install','-r',str(root/apk)).check_returncode()
ui_only="--ui-only" in sys.argv;results=json.loads((out/"validation.json").read_text()) if ui_only else []
try:
 for mode,font,checks in [('normal','1.0',['format_only','availability_only','integration']),('320-130','1.3',['format_ui_only']),('320-200','2.0',['format_ui_only'])]:
  run('shell','wm','size','reset' if mode=='normal' else '640x1280');run('shell','wm','density','reset' if mode=='normal' else '320');run('shell','settings','put','system','font_scale',font)
  if ui_only:checks=['format_ui_only']
  for check in checks:
   run('shell','am','force-stop','io.zem.ibox');run('logcat','-c');args=['shell','am','instrument','-w']+([] if check=='integration' else ['-e',check,'true'])+['io.zem.ibox.test/io.zem.ibox.SmokeTest'];result=run(*args);name=check+'-'+mode;(out/(name+'.log')).write_text((result.stdout+result.stderr).rstrip()+'\n');log=run('logcat','-d').stdout;(out/(name+'-details.log')).write_text('\n'.join(l for l in log.splitlines() if 'Zem iBox/' in l)+'\n');passed='PASS:' in result.stdout and 'FAIL:' not in result.stdout and 'AssertionError' not in result.stdout;results=[r for r in results if not (r["mode"]==mode and r["scenario"]==check)];results.append(dict(mode=mode,scenario=check,passed=passed,output=result.stdout.strip()));(out/'validation.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n');print(name,result.stdout.strip(),flush=True)
   if check in ('format_only','format_ui_only'):run('pull','/sdcard/Android/data/io.zem.ibox/files/ui-audit/26-format-confirm.png',str(out/('format-confirm-'+mode+'.png')))
   if not passed:raise RuntimeError('Failed '+name)
finally:
 run('shell','wm','size','reset');run('shell','wm','density','reset');run('shell','settings','put','system','font_scale','1.0')
