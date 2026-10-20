"""Local protocol fixture, not evidence of compatibility with a real iBOX."""
import json, struct, socket, threading, time, pathlib, re
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = pathlib.Path(__file__).resolve().parents[1] / 'research'
VIDEO = (ROOT / 'test-clip.mp4').read_bytes()
RAW = (ROOT / 'live.h264').read_bytes()
PCM = (ROOT / 'audio.pcm').read_bytes()
JPEG = (ROOT / 'frame.jpg').read_bytes()
AVI = (ROOT / 'playback-test.avi').read_bytes()
AUD = [m.start() for m in re.finditer(b'\x00\x00(?:\x00)?\x01\x09', RAW)]
FRAMES = [RAW[a:b] for a,b in zip(AUD, AUD[1:]+[len(RAW)])]
def video_frames(width):
    raw=(ROOT/f'live-{width}.h264').read_bytes();aud=[m.start() for m in re.finditer(b'\x00\x00(?:\x00)?\x01\x09',raw)]
    return [raw[a:b] for a,b in zip(aud,aud[1:]+[len(raw)])]
SIZES={640:FRAMES,1280:video_frames(1280),1920:video_frames(1920)}
if not FRAMES:
    raise RuntimeError('Test video must contain Annex B AUD NAL units')
state = {'card': False, 'break_once': True, 'settings': {}, 'ctps': [], 'stream': False, 'camera': 0, 'transport': 'tcp', 'width':640, 'fixed':False,'rear':True,'list_mode':'normal'}
lock = threading.RLock()

def packet(topic, obj):
    t=topic.encode(); b=json.dumps(obj,separators=(',',':')).encode()
    return b'CTP:'+struct.pack('<H',len(t))+t+struct.pack('<I',len(b))+b

def media(kind, seq, data, ts):
    for offset in range(0,len(data),1400):
        payload=data[offset:offset+1400]
        yield struct.pack('<BBHIIII',kind,0,len(payload),seq,len(data),offset,ts)+payload

def control(client):
    state['ctps'].append(client)
    def exact(n):
        b=b''
        while len(b)<n:
            part=client.recv(n-len(b))
            if not part: raise EOFError()
            b+=part
        return b
    def reply(topic, param=None, errno=0):
        p=packet(topic,{'errno':errno,'op':'NOTIFY','param':param or {}})
        # Deliberately fragment frames across TCP reads.
        with lock:
            client.sendall(p[:3]); client.sendall(p[3:9]); client.sendall(p[9:])
    try:
        while True:
            if exact(4)!=b'CTP:': raise RuntimeError('Bad CTP')
            topic=exact(struct.unpack('<H',exact(2))[0]).decode()
            msg=json.loads(exact(struct.unpack('<I',exact(4))[0]))
            p=msg.get('param',{})
            print('CTP',topic,msg,flush=True)
            if topic=='APP_ACCESS':
                reply(topic,{'type':'0','ver':'20938'})
                reply('KEEP_ALIVE_INTERVAL',{'timeout':'6000'})
                reply('SD_STATUS',{'online':str(int(state['card']))})
                reply('VIDEO_CTRL',{'status':'0'})
                reply('VIDEO_MIC',{'mic':'1'})
            elif topic=='PULL_VIDEO_STATUS': reply(topic,{'status':str(int(state['rear']))})
            elif topic=='CTP_KEEP_ALIVE': reply(topic)
            elif topic=='SD_STATUS': reply(topic,{'online':str(int(state['card']))})
            elif topic in ('OPEN_RT_STREAM','OPEN_PULL_RT_STREAM'):
                state['camera']=int(topic=='OPEN_PULL_RT_STREAM');state['width']=int(p.get('w',640)); state['stream']=True
                reply(topic,{'format':'0' if state['transport']=='udp' else '1','w':str(state['width']),'h':p.get('h','480'),'fps':'20','rate':'8000'})
            elif topic in ('CLOSE_RT_STREAM','CLOSE_PULL_RT_STREAM'):
                state['stream']=False; reply(topic,{'status':'1'})
            elif topic in ('FORWARD_MEDIA_FILES_LIST','BEHIND_MEDIA_FILES_LIST'):
                if not state['card']: reply(topic,errno=2)
                else: reply(topic,{'type':'1','path':'/list/rear.json' if topic.startswith('BEHIND') else '/list/front.json'})
            elif topic=='TEST_UNSUPPORTED': reply(topic,errno=6)
            elif topic=='TEST_DROP': client.close(); return
            else:
                if msg.get('op')=='GET': p=state['settings'].get(topic,{'status':'0'})
                else: state['settings'][topic]=p
                reply(topic,p)
    except (EOFError,ConnectionError,OSError): pass
    finally:
        state['stream']=False
        if client in state['ctps']: state['ctps'].remove(client)
        client.close()

def streaming(client):
    seq=0
    try:
        while True:
            if not state['stream'] or state['transport']!='tcp': time.sleep(.01); continue
            start=time.monotonic();frames=SIZES[1920 if state['fixed'] else state['width']];frame=frames[seq%len(frames)]
            # Actual iBOX TCP framing: unused UDP fragment size is zero,
            # complete 32-bit frame length at byte 8. Fragment header and body
            # across writes to exercise stream reads, not datagram assumptions.
            video=struct.pack('<BBHIIII',3,0,0,seq,len(frame),0,seq*50)+frame
            client.sendall(video[:7]);client.sendall(video[7:20]);client.sendall(video[20:276]);client.sendall(video[276:])
            sound=PCM[(seq%80)*800:(seq%80+1)*800]
            if sound:
                client.sendall(struct.pack('<BBHIIII',1,0,0,seq,len(sound),0,seq*50)+sound)
            seq+=1; time.sleep(max(0,.05-(time.monotonic()-start)))
    except OSError: pass
    finally: client.close()

def udp_streaming():
    sender=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);seq=0
    while True:
        if not state['stream'] or state['transport']!='udp': time.sleep(.01);continue
        start=time.monotonic();destination=('127.0.0.1',2224+state['camera'])
        fragments=list(media(2,seq,JPEG,seq*50))
        for p in reversed(fragments): sender.sendto(p,destination)
        sender.sendto(fragments[-1],destination)  # duplicate after completion
        sound=PCM[(seq%80)*800:(seq%80+1)*800]
        for p in media(1,seq,sound,seq*50):sender.sendto(p,destination)
        seq+=1;time.sleep(max(0,.05-(time.monotonic()-start)))

def serve(port, handler):
    server=socket.socket();server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);server.bind(('127.0.0.1',port));server.listen()
    while True:
        c,_=server.accept();threading.Thread(target=handler,args=(c,),daemon=True).start()

class HTTP(BaseHTTPRequestHandler):
    def log_message(self,fmt,*args): print('HTTP',fmt%args,flush=True)
    def do_GET(self):
        if self.path=='/test/reset':
            state['list_mode']='normal';state['rear']=True;state['card']=False;state['break_once']=True;state['transport']='tcp';state['fixed']=False;state['width']=640;data=b'ok'
        elif self.path.startswith('/test/list/'):
            state['list_mode']=self.path.rsplit('/',1)[1];data=b'ok'
        elif self.path.startswith('/test/quality/'):
            state['fixed']=self.path.endswith('/fixed');data=b'ok'
        elif self.path in ('/test/mode/udp','/test/mode/tcp'):
            state['transport']=self.path.rsplit('/',1)[1];data=b'ok'
        elif self.path=='/test/frame.jpg':
            data=JPEG
        elif self.path.startswith('/test/rear/'):
            state['rear']=self.path.endswith('/1');data=b'ok'
        elif self.path.startswith('/test/card/'):
            state['card']=self.path.endswith('/1');data=b'ok'
        elif self.path.startswith('/mnt/spiflash/res/dev_desc.txt'):
            data=json.dumps({'net_type':'1' if state['transport']=='udp' else '0','rts_type':'0' if state['transport']=='udp' else '1','device_type':'1','firmware_version':'SIMULATED','uuid':'local-test-only','forward_support':['0','1','2'],'behind_support':['0','1']}).encode()
        elif self.path.startswith('/list/'):
            mode=state['list_mode']
            if mode=='fail':
                self.send_error(503);return
            if mode=='slow':time.sleep(2)
            if mode=='empty':
                data=b'{"file_list":[]}';self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
            camera=1 if 'rear' in self.path else 0
            data=json.dumps({'file_list':[{'f':f'/storage/sd0/C/DCIM/{camera}/clip.MOV','t':'20261019123456','s':len(VIDEO),'d':4,'y':2,'c':camera}]}).encode()
        elif self.path.startswith('/storage/'):
            data=AVI if self.path.endswith('.avi') else VIDEO
            rng=self.headers.get('Range')
            if rng:
                offset=int(rng.split('=')[1].split('-')[0]);self.send_response(206);self.send_header('Content-Range',f'bytes {offset}-{len(data)-1}/{len(data)}');self.send_header('Content-Length',str(len(data)-offset));self.end_headers();self.wfile.write(data[offset:]);return
            self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers()
            if state['break_once']:
                state['break_once']=False;self.wfile.write(data[:8192]);self.wfile.flush();self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
            self.wfile.write(data);return
        else:
            self.send_error(404);return
        self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)

threading.Thread(target=serve,args=(3333,control),daemon=True).start()
threading.Thread(target=serve,args=(2229,streaming),daemon=True).start()
threading.Thread(target=udp_streaming,daemon=True).start()
print('SIMULATOR READY; H264 frames=',len(FRAMES),flush=True)
ThreadingHTTPServer(('127.0.0.1',8080),HTTP).serve_forever()
