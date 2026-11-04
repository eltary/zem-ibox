"""Generate moving, baseline JPEG frames with the standard RFC 2435 Huffman tables.
Run with Pillow installed in the QA virtual environment. No vendor video is used.
"""
from pathlib import Path
from io import BytesIO
import subprocess,re
from PIL import Image
root=Path(__file__).resolve().parents[1]
raw=subprocess.check_output(['ffmpeg','-hide_banner','-loglevel','error','-i',str(root/'research/test-clip.mp4'),'-t','4','-vf','scale=640:480','-r','20','-c:v','mjpeg','-an','-f','image2pipe','-'])
frames=[]
for match in re.finditer(b'\xff\xd8.*?\xff\xd9',raw,re.DOTALL):
    image=Image.open(BytesIO(match.group()));out=BytesIO();image.save(out,format='JPEG',quality=80,subsampling=2,optimize=False);frames.append(out.getvalue())
assert len(frames)==80
out=root/'qa/playback-0.0.13/generated/rtsp-motion.mjpg';out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(b''.join(frames));print(out,len(frames))
