#!/usr/bin/env python3
"""Create a neutral animated road illustration for actual app screenshots."""
from pathlib import Path
from PIL import Image,ImageDraw
import math,subprocess,shutil
root=Path(__file__).resolve().parents[1];out=root/'qa/generated/readme';out.mkdir(parents=True,exist_ok=True)
width,height=960,540
master=out/'demo.mp4'
encoder=subprocess.Popen(['ffmpeg','-y','-hide_banner','-loglevel','error','-f','rawvideo','-pixel_format','rgb24','-video_size',f'{width}x{height}','-framerate','20','-i','-','-c:v','libx264','-preset','fast','-crf','21','-pix_fmt','yuv420p',str(master)],stdin=subprocess.PIPE)
for frame in range(80):
    image=Image.new('RGB',(width,height));draw=ImageDraw.Draw(image)
    for y in range(height):
        t=min(1,y/320);draw.line((0,y,width,y),fill=(int(124+60*t),int(161+34*t),int(177+24*t)))
    draw.ellipse((710,66,770,126),fill=(232,221,181))
    draw.polygon([(0,250),(0,205),(125,140),(255,221),(390,153),(565,260)],fill=(125,155,159))
    draw.polygon([(350,260),(555,183),(670,225),(845,140),(960,214),(960,290)],fill=(104,139,144))
    draw.polygon([(0,260),(260,230),(510,257),(770,226),(960,254),(960,540),(0,540)],fill=(111,142,120))
    draw.polygon([(0,355),(300,267),(465,265),(240,540),(0,540)],fill=(95,126,108))
    draw.polygon([(0,540),(360,300),(469,265),(491,265),(605,300),(960,540)],fill=(169,179,165))
    draw.polygon([(38,540),(381,300),(472,266),(488,266),(581,300),(921,540)],fill=(71,84,89))
    draw.line([(92,540),(391,300),(473,266)],fill=(217,224,210),width=3)
    draw.line([(866,540),(569,300),(487,266)],fill=(217,224,210),width=3)
    for i in range(8):
        distance=((i+frame/32)%8)/8
        near=min(1,distance+.065);y1=270+distance**2*270;y2=270+near**2*270
        half1=1+distance*4;half2=1+near*4
        draw.polygon([(480-half1,y1),(480+half1,y1),(480+half2,y2),(480-half2,y2)],fill=(224,228,211))
    # Fixed illustration, animated road markings: no personal footage or test bars.
    encoder.stdin.write(image.tobytes())
encoder.stdin.close();assert encoder.wait()==0

def ff(*args):subprocess.run(['ffmpeg','-y','-hide_banner','-loglevel','error',*args],check=True)
for w,h in [(640,480),(1280,720),(1920,1080)]:
    name='live.h264' if w==640 else f'live-{w}.h264'
    ff('-i',str(master),'-vf',f'scale={w}:{h}','-c:v','libx264','-preset','ultrafast','-tune','zerolatency','-x264-params','aud=1:keyint=20:repeat-headers=1','-f','h264',str(out/name))
ff('-i',str(master),'-f','lavfi','-i','sine=frequency=440:sample_rate=8000','-t','4','-c:v','copy','-c:a','aac','-ac','1',str(out/'test-clip.mp4'))
ff('-i',str(master),'-frames:v','1',str(out/'frame.jpg'))
ff('-i',str(master),'-f','lavfi','-i','sine=frequency=440:sample_rate=8000','-t','4','-c:v','mjpeg','-c:a','pcm_s16le','-ac','1',str(out/'playback-test.avi'))
shutil.copyfile(root/'qa/generated/fixtures/audio.pcm',out/'audio.pcm')
print('PASS: neutral screenshot media generated')
