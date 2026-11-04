"""Run only on the project's emulator-5586, with the local protocol fixture running."""
from pathlib import Path
import json, subprocess, sys, argparse

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'qa/reports/playback'
ADB=Path.home()/'.cache/android-sdk/platform-tools/adb'
SERIAL='emulator-5586'

def adb(*args, **kwargs):
    return subprocess.run([str(ADB),'-s',SERIAL,*args],check=True,**kwargs)

parser=argparse.ArgumentParser();parser.add_argument("--resume",action="store_true");parser.add_argument("--scenario",action="append");parser.add_argument("--mode",action="append");args=parser.parse_args()
OUT.mkdir(parents=True,exist_ok=True)
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
    adb('install','-r',str(ROOT/apk),capture_output=True)
results=json.loads((OUT/"validation.json").read_text()) if "--resume" in sys.argv and (OUT/"validation.json").exists() else []
try:
    for mode,font in [('normal','1.0'),('320-130','1.3'),('320-200','2.0')]:
        if args.mode and mode not in args.mode:continue
        adb('shell','wm','size','reset' if mode=='normal' else '640x1280',capture_output=True)
        adb('shell','wm','density','reset' if mode=='normal' else '320',capture_output=True)
        adb('shell','settings','put','system','font_scale',font,capture_output=True)
        checks=['phone_only','compat_only','rtsp_udp_only','transports_only','regression_only','playback_only','media_only','integration','availability_only','ux_only'] if mode=='normal' else ['phone_only','media_only','regression_only']+(['playback_only'] if mode=='320-200' else [])
        if args.scenario:checks=[c for c in checks if c in args.scenario]
        produced=set()
        for check in checks:
            if any(r["mode"]==mode and r["scenario"]==check and r["passed"] for r in results):continue
            adb('shell','am','force-stop','io.zem.ibox',capture_output=True)
            adb('logcat','-c',capture_output=True)
            instrumentation_args=['shell','am','instrument','-w']+([] if check=='integration' else ['-e',check,'true'])+['io.zem.ibox.test/io.zem.ibox.SmokeTest']
            result=adb(*instrumentation_args,capture_output=True,text=True,errors='replace',timeout=240)
            name=f'{check}-{mode}';(OUT/(name+'.log')).write_text((result.stdout+result.stderr).rstrip()+"\n")
            log=adb('logcat','-d',capture_output=True,text=True,errors='replace').stdout
            (OUT/(name+'-details.log')).write_text('\n'.join(line for line in log.splitlines() if 'Zem iBox/' in line or (' E VLC' in line and any(word in line for word in ['seek','decoder','deadlock'])))+'\n')
            passed='PASS:' in result.stdout and 'AssertionError' not in result.stdout and 'FAIL:' not in result.stdout
            results=[r for r in results if not (r["mode"]==mode and r["scenario"]==check)]
            results.append({'mode':mode,'scenario':check,'passed':passed,'output':result.stdout.strip()})
            (OUT/'validation.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
            print(name,result.stdout.strip(),flush=True)
            if not passed:raise RuntimeError('Check failed: '+name)
            produced.update({'phone_only':['20-phone-player-fit.png','21-phone-library-spacing.png','22-open-in-chooser.png','23-folder-mixplorer.png','24-clock-prompt.png'],'media_only':['11-archive-previews.png','12-player-queue.png','13-phone-previews.png','14-player-speed.png','15-player-landscape.png'],'regression_only':['18-playlist-dates.png','19-short-report.png'],'playback_only':['16-fullhd-visible.png','17-fullhd-landscape.png']}.get(check,[]))
        screenshots=OUT/('ui-'+mode);screenshots.mkdir(exist_ok=True)
        for name in sorted(produced):
            adb('pull','/sdcard/Android/data/io.zem.ibox/files/ui-audit/'+name,str(screenshots/name),capture_output=True)
finally:
    adb('shell','wm','size','reset',capture_output=True)
    adb('shell','wm','density','reset',capture_output=True)
    adb('shell','settings','put','system','font_scale','1.0',capture_output=True)
