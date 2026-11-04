#!/usr/bin/env python3
"""Generate synthetic media for QA. Requires ffmpeg and Pillow."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[1];out=root/'qa/generated/fixtures';out.mkdir(parents=True,exist_ok=True)
def ff(*args):subprocess.run(['ffmpeg','-y','-hide_banner','-loglevel','error',*args],check=True)
for width,height in [(640,480),(1280,720),(1920,1080)]:
    name='live.h264' if width==640 else f'live-{width}.h264'
    ff('-f','lavfi','-i',f'testsrc2=size={width}x{height}:rate=20','-t','4','-c:v','libx264','-preset','ultrafast','-tune','zerolatency','-x264-params','aud=1:keyint=20:repeat-headers=1','-pix_fmt','yuv420p','-f','h264',str(out/name))
ff('-f','lavfi','-i','sine=frequency=440:sample_rate=8000','-t','4','-ac','1','-f','s16le',str(out/'audio.pcm'))
ff('-f','lavfi','-i','testsrc2=size=640x480:rate=20','-f','lavfi','-i','sine=frequency=440:sample_rate=8000','-t','4','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac','-ac','1',str(out/'test-clip.mp4'))
ff('-f','lavfi','-i','testsrc2=size=640x480:rate=20','-frames:v','1',str(out/'frame.jpg'))
ff('-f','lavfi','-i','testsrc2=size=640x480:rate=20','-f','lavfi','-i','sine=frequency=440:sample_rate=8000','-t','4','-c:v','mjpeg','-c:a','pcm_s16le','-ac','1',str(out/'playback-test.avi'))
subprocess.run([sys.executable,str(root/'qa/prepare-rtsp-fixture.py')],check=True)
subprocess.run(['bash',str(root/'qa/make-playback-fixture.sh')],check=True)
ff('-i',str(out/'fullhd.MOV'),'-c:v','copy','-c:a','aac','-b:a','32k','-movflags','+faststart',str(out/'fullhd-aac.mp4'))
print('PASS: synthetic fixtures ready')
