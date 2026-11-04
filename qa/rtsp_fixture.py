"""Local RTSP/TCP fixture for advertised DVRunning2 H264/PCM and MJPEG paths."""
import base64,re,socket,struct,threading,time,pathlib

def nals(frame):
    starts=list(re.finditer(b'\x00\x00(?:\x00)?\x01',frame))
    return [frame[m.end():starts[i+1].start() if i+1<len(starts) else len(frame)] for i,m in enumerate(starts)]

def jpeg_data(jpeg):
    p=2;tables={};width=height=0
    while p+4<len(jpeg):
        if jpeg[p]!=255:raise ValueError('JPEG marker')
        marker=jpeg[p+1];length=int.from_bytes(jpeg[p+2:p+4],'big');body=jpeg[p+4:p+2+length]
        if marker==0xdb:
            q=0
            while q<len(body):ident=body[q];size=64*(2 if ident>>4 else 1);tables[ident&15]=body[q+1:q+1+size];q+=size+1
        if marker==0xc0:height=int.from_bytes(body[1:3],'big');width=int.from_bytes(body[3:5],'big')
        if marker==0xda:return width,height,tables,jpeg[p+2+length:-2]
        p+=2+length
    raise ValueError('JPEG scan not found')

def start(port,frames,jpeg,pcm,state):
    sps=next(n for n in nals(frames[0]) if n[0]&31==7);pps=next(n for n in nals(frames[0]) if n[0]&31==8)
    raw=(pathlib.Path(__file__).parent/"generated/fixtures/rtsp-motion.mjpg").read_bytes()
    jpgs=[jpeg_data(m.group()) for m in re.finditer(b"\xff\xd8.*?\xff\xd9",raw,re.DOTALL)]
    def client(conn):
        send_lock=threading.Lock();playing=threading.Event();done=threading.Event();channels={};uri='';seq={0:0,2:0}
        def reply(cseq,extra='',body=b''):
            data=f'RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\nServer: Zem-Fixture\r\n{extra}Content-Length: {len(body)}\r\n\r\n'.encode()+body
            with send_lock:conn.sendall(data)
        def rtp(channel,pt,payload,stamp,marker):
            num=seq.get(channel,0);seq[channel]=(num+1)&65535;wire=struct.pack('>BBHII',128,pt|(128 if marker else 0),num,stamp&0xffffffff,0x12345678)+payload
            with send_lock:conn.sendall(b'$'+bytes([channel])+len(wire).to_bytes(2,'big')+wire)
        def stream():
            frame=0
            try:
                while not done.is_set():
                    if not playing.wait(.1):continue
                    begin=time.monotonic();h264='/264_pcm_rt/' in uri;channel=channels.get('trackID=0',0);stamp=frame*4500
                    if h264 and 'trackID=0' in channels:
                        units=[n for n in nals(frames[frame%len(frames)]) if n[0]&31 in (1,5,6,7,8)]
                        for j,nal in enumerate(units):
                            if len(nal)<=1200:rtp(channel,96,nal,stamp,j==len(units)-1)
                            else:
                                body=nal[1:]
                                for off in range(0,len(body),1198):
                                    end=off+1198>=len(body);part=bytes([(nal[0]&224)|28,(nal[0]&31)|(128 if off==0 else 0)|(64 if end else 0)])+body[off:off+1198];rtp(channel,96,part,stamp,end and j==len(units)-1)
                    elif 'trackID=0' in channels:
                        width,height,quant,scan=jpgs[frame%len(jpgs)];tables=quant[0]+quant.get(1,quant[0])
                        for off in range(0,len(scan),1100):
                            payload=bytes([0])+off.to_bytes(3,'big')+bytes([1,255,width//8,height//8])
                            if off==0:payload+=bytes([0,0])+len(tables).to_bytes(2,'big')+tables
                            payload+=scan[off:off+1100];rtp(channel,26,payload,stamp,off+1100>=len(scan))
                    if (state.get('audio',True) if state.get('rtsp_audio') is None else state['rtsp_audio']) and 'trackID=1' in channels:
                        data=pcm[(frame%80)*800:(frame%80+1)*800];data=b''.join(data[i:i+2][::-1] for i in range(0,len(data),2));rtp(channels['trackID=1'],97,data,frame*400,True)
                    frame+=1;time.sleep(max(0,.05-(time.monotonic()-begin)))
            except OSError:done.set()
        worker=threading.Thread(target=stream,daemon=True);worker.start()
        try:
            file=conn.makefile('rb')
            while not done.is_set():
                first=file.read(1)
                if not first:break
                if first==b'$':head=file.read(3);file.read(int.from_bytes(head[1:],'big'));continue
                line=first+file.readline();parts=line.decode().strip().split();headers={}
                while True:
                    raw=file.readline()
                    if raw in (b'\r\n',b'\n',b''):break
                    key,value=raw.decode().split(':',1);headers[key.lower()]=value.strip()
                file.read(int(headers.get('content-length','0')));method=parts[0];target=parts[1];cseq=headers.get('cseq','1');print('RTSP',method,target,flush=True)
                if method=='OPTIONS':reply(cseq,'Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n')
                elif method=='DESCRIBE':
                    uri=target;h264='/264_pcm_rt/' in uri;payload=96 if h264 else 26
                    video=f'a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets={base64.b64encode(sps).decode()},{base64.b64encode(pps).decode()}\r\n' if h264 else 'a=rtpmap:26 JPEG/90000\r\n'
                    sdp=f'v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=Zem Fixture\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP {payload}\r\nc=IN IP4 0.0.0.0\r\n{video}a=control:trackID=0\r\n'
                    if (state.get('audio',True) if state.get('rtsp_audio') is None else state['rtsp_audio']):sdp+='m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\na=rtpmap:97 L16/8000/1\r\na=control:trackID=1\r\n'
                    reply(cseq,f'Content-Type: application/sdp\r\nContent-Base: {target}/\r\n',sdp.encode())
                elif method=='SETUP':
                    key='rtsp_audio_setups' if 'trackID=1' in target else 'rtsp_video_setups';state[key]=state.get(key,0)+1;transport=headers.get('transport','');match=re.search(r'interleaved=(\d+)-(\d+)',transport);channel=int(match[1]) if match else len(channels)*2;channels['trackID=1' if 'trackID=1' in target else 'trackID=0']=channel;reply(cseq,f'Session: fixture-1;timeout=30\r\nTransport: RTP/AVP/TCP;unicast;interleaved={channel}-{channel+1}\r\n')
                elif method=='PLAY':reply(cseq,'Session: fixture-1\r\nRange: npt=0.000-\r\n');playing.set()
                elif method=='TEARDOWN':reply(cseq,'Session: fixture-1\r\n');break
                elif method=='PAUSE':playing.clear();reply(cseq,'Session: fixture-1\r\n')
                else:reply(cseq,'Session: fixture-1\r\n')
        except (OSError,ValueError,IndexError):pass
        finally:done.set();conn.close()
    def serve():
        server=socket.socket();server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);server.bind(('127.0.0.1',port));server.listen()
        while True:conn,_=server.accept();threading.Thread(target=client,args=(conn,),daemon=True).start()
    threading.Thread(target=serve,daemon=True).start()
