package io.zem.ibox;

import android.graphics.*;
import android.content.Context;
import android.media.*;
import android.net.Network;
import android.view.Surface;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class LiveStream implements LiveSource {
    public interface Listener extends LiveSource.Listener {}
    private final JieliSession session;private final Network wifi;private final String host;private final JSONObject description;private final boolean tcpTransport;
    private final Surface surface;
    private final LiveSource.Listener listener;
    private final int camera,level;private final CtpClient owner;
    private volatile boolean running=true,muted=true;
    private DatagramSocket udp;private Socket tcp;
    private MediaCodec decoder;private AudioTrack audio;
    private volatile long audioPackets,audioBytes,nativeAudioPackets;private volatile boolean recordingAudio,audioAttempted;private volatile RtspAudioSource audioSource;private volatile String audioMessage="";
    private volatile int width=640,height=480;
    private int sample=8000,requestedWidth,requestedHeight;
    private long lastSeq=-1,frameCount,start=System.currentTimeMillis(),lastFrame,receivedBytes;
    private volatile long renderedFrames,lastRendered,decoderDrops,previewDelayMs;
    private volatile double receiveFps,renderFps,megabits;
    private final FrameAssembler assembler=new FrameAssembler();
    private final LiveFrameQueue previewQueue=new LiveFrameQueue(40);
    private Thread renderThread;
    private LiveTiming timing;
    private boolean decoderNeedsKey;private final LivePreviewClock displayClock=new LivePreviewClock();
    private int pendingOutput=-1;private long pendingTarget;private volatile long presentedFrames,lastPresentationNs,maxPresentationGapMs;
    private final Object decodeLock=new Object();
    private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile byte[] sps,pps;
    public LiveStream(JieliSession session,Surface surface,int camera,int level,LiveSource.Listener listener){this.session=session;this.owner=session.ctp;this.wifi=session.network;this.host=session.host;this.description=session.description;this.tcpTransport=session.liveTransport().equals("tcp");this.surface=surface;this.camera=camera;this.level=level;this.listener=listener;}
    public void start(){new Thread(this::run,"Live receiver").start();}
    public void mute(boolean value){muted=value;Diag.log("LIVE","mute="+value);if(!value)ensureAudio();}
    public boolean hasVideo(){return running&&renderedFrames>0;}
    public boolean muted(){return muted;}
    private void run(){
        String open=camera==0?"OPEN_RT_STREAM":"OPEN_PULL_RT_STREAM";
        try {
            Network net=wifi;
            boolean useTcp=tcpTransport;
            if(!useTcp){udp=new DatagramSocket(null);udp.setReuseAddress(true);udp.setReceiveBufferSize(2*1024*1024);if(net!=null)net.bindSocket(udp);udp.bind(new InetSocketAddress(camera==0?2224:2225));udp.setSoTimeout(1000);}
            else {tcp=net==null?new Socket():net.getSocketFactory().createSocket();tcp.setReceiveBufferSize(2*1024*1024);tcp.setTcpNoDelay(true);tcp.connect(new InetSocketAddress(host,2229),6000);tcp.setSoTimeout(1000);}
            int[] widths={640,1280,1920},heights={480,720,1080};requestedWidth=width=widths[level];requestedHeight=height=heights[level];
            int format=description.optInt("rts_type",1)==0?0:1;
            JSONObject cameraParams=session.state.get(camera==0?"VIDEO_PARAM":"PULL_VIDEO_PARAM");int fps=cameraParams==null?25:cameraParams.optInt("fps",25);if(fps<1||fps>60)fps=25;
            timing=new LiveTiming(fps);
            listener.status("Запуск видеопотока…");
            JSONObject reply=session.command(owner,open,"PUT",JieliSession.params("format",""+format,"w",""+width,"h",""+height,"fps",""+fps,"rate","8000"));
            width=reply.optInt("w",width);height=reply.optInt("h",height);sample=reply.optInt("rate",8000);
            Diag.log("LIVE","Start camera="+camera+" transport="+(useTcp?"TCP:2229":"UDP:"+(camera==0?2224:2225))+" parameters="+reply);
            renderThread=new Thread(this::renderLoop,"Live decoder");renderThread.start();
            byte[] buffer=new byte[65535];long stats=System.currentTimeMillis(),priorFrames=0,priorRendered=0,priorBytes=0;int sampled=0;TcpMediaReader reader=new TcpMediaReader();
            while(running){
                List<FrameAssembler.Frame> received;
                try{
                    if(useTcp){TcpMediaReader.Packet p=reader.read(tcp.getInputStream());receivedBytes+=20+p.payload().length;if(sampled++<8&&Diag.enabled())Diag.log("LIVE TCP","header="+prefix(p.header(),20)+" payloadBytes="+p.payload().length+" payloadFirst64="+prefix(p.payload(),64));received=assembler.acceptTcp(p.header(),p.payload());}
                    else {DatagramPacket p=new DatagramPacket(buffer,buffer.length);udp.receive(p);if(!p.getAddress().getHostAddress().equals(host))continue;int count=p.getLength();receivedBytes+=count;if(sampled++<8&&Diag.enabled())Diag.log("LIVE UDP","length="+count+" first64="+prefix(Arrays.copyOf(buffer,Math.min(count,64)),64));received=assembler.accept(buffer,count);}
                }catch(SocketTimeoutException e){if(System.currentTimeMillis()-Math.max(start,lastFrame)>10000){listener.status("Нет кадров. Экспортируй логи и попробуй другое качество лайва");Diag.log("LIVE","No complete frames for 10s; "+statistics());}continue;}
                for(FrameAssembler.Frame f:received){
                    if(f.type()==1){nativeAudioPackets++;RtspAudioSource extra=audioSource;if(extra!=null){audioSource=null;extra.close();}acceptAudio(f.data());}
                    else if(f.type()==2||f.type()==3){
                        if(lastSeq!=-1&&(int)(f.sequence()-lastSeq)<=0)continue;long sequenceSteps=lastSeq<0?1:(int)(f.sequence()-lastSeq);lastSeq=f.sequence();
                        boolean key=false;if(f.type()==3){NalUnits.Headers headers=NalUnits.headers(f.data());if(headers.sps()!=null){sps=headers.sps();timing.fixedRate(NalUnits.frameRate(sps));}if(headers.pps()!=null)pps=headers.pps();key=headers.key();}
                        long pts=timing.next(System.nanoTime()/1000,sequenceSteps);FrameAssembler.Frame timed=new FrameAssembler.Frame(f.type(),f.sequence(),f.timestamp(),f.data(),pts);listener.frame(timed);previewQueue.offer(timed,key);frameCount++;lastFrame=System.currentTimeMillis();
                    }
                }
                long now=System.currentTimeMillis();if(now-stats>=1000){double seconds=(now-stats)/1000.0;receiveFps=(frameCount-priorFrames)/seconds;renderFps=(renderedFrames-priorRendered)/seconds;megabits=(receivedBytes-priorBytes)*8/seconds/1000000;stats=now;ensureAudio();priorFrames=frameCount;priorRendered=renderedFrames;priorBytes=receivedBytes;if(Diag.enabled())Diag.log("LIVE STATS",statistics()+" clockFps="+timing.fps()+" sps="+(sps==null?0:sps.length)+" pps="+(pps==null?0:pps.length));listener.status(statusText());}
            }
        }catch(Exception e){if(running){Diag.error("Live stream",e);listener.status("Лайв: "+e.getMessage());}}
        finally {
            running=false;RtspAudioSource extra=audioSource;if(extra!=null)extra.close();closeSockets();previewQueue.close();
            if(renderThread!=null){renderThread.interrupt();try{renderThread.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            synchronized(decodeLock){if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();decoder=null;}synchronized(this){if(audio!=null){audio.release();audio=null;}}}
            try{if(session.isCurrent(owner))session.command(owner,camera==0?"CLOSE_RT_STREAM":"CLOSE_PULL_RT_STREAM","PUT",JieliSession.params("status","1"));}catch(Exception e){Diag.error("Close live",e);}
            Diag.log("LIVE","Stopped "+statistics());
            try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            stopped.countDown();
        }
    }
    private void renderLoop(){
        try{while(running){LiveFrameQueue.Entry entry=previewQueue.poll(5);if(entry==null){synchronized(decodeLock){if(decoder!=null)drain();}continue;}previewDelayMs=(System.nanoTime()-entry.receivedNanos())/1_000_000;render(entry.frame(),entry.key(),entry.reset());}}
        catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        catch(Exception e){if(running){Diag.error("Live decoder",e);listener.status("Декодер: "+e.getMessage());running=false;closeSockets();}}
    }
    private void render(FrameAssembler.Frame f,boolean key,boolean reset)throws Exception {
        synchronized(decodeLock){
            if(!running||!surface.isValid())return;
            if(f.type()==2){
                Bitmap bmp=BitmapFactory.decodeByteArray(f.data(),0,f.data().length);if(bmp==null){Diag.log("JPEG","Decode returned null, size="+f.data().length);return;}
                width=bmp.getWidth();height=bmp.getHeight();Canvas c=surface.lockCanvas(null);
                try{c.drawColor(Color.BLACK);float scale=Math.min((float)c.getWidth()/width,(float)c.getHeight()/height);float w=width*scale,h=height*scale;c.drawBitmap(bmp,null,new RectF((c.getWidth()-w)/2,(c.getHeight()-h)/2,(c.getWidth()+w)/2,(c.getHeight()+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));}
                finally{surface.unlockCanvasAndPost(c);bmp.recycle();}
                renderedFrames++;
                lastRendered=System.currentTimeMillis();
            }else{
                boolean created=false;
                if(decoder==null){
                    if(sps==null||pps==null||!key)return;
                    int[] actual=NalUnits.dimensions(sps);if(actual!=null){width=actual[0];height=actual[1];}updateRecordedFrameRate();
                    MediaFormat fmt=MediaFormat.createVideoFormat("video/avc",width,height);fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,Math.max(f.data().length,Math.min(6*1024*1024,Math.max(512*1024,width*height))));fmt.setFloat(MediaFormat.KEY_FRAME_RATE,(float)timing.fps());fmt.setInteger(MediaFormat.KEY_PRIORITY,0);fmt.setInteger("low-latency",1);
                    fmt.setByteBuffer("csd-0",ByteBuffer.wrap(NalUnits.annex(sps)));fmt.setByteBuffer("csd-1",ByteBuffer.wrap(NalUnits.annex(pps)));
                    decoder=MediaCodec.createDecoderByType("video/avc");decoder.configure(fmt,surface,null,0);decoder.start();decoder.setOnFrameRenderedListener((codec,pts,now)->{if(lastPresentationNs>0)maxPresentationGapMs=Math.max(maxPresentationGapMs,(now-lastPresentationNs)/1_000_000);lastPresentationNs=now;presentedFrames++;},new android.os.Handler(android.os.Looper.getMainLooper()));created=true;decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);Diag.log("CODEC","Started "+decoder.getName()+" "+fmt);
                }
                if(decoderNeedsKey&&!key){decoderDrops++;return;}
                if((reset||decoderNeedsKey)&&!created){decoder.flush();pendingOutput=-1;displayClock.reset();decoderNeedsKey=false;Diag.log("CODEC","Resumed from IDR after preview overload");}
                drain();int i=decoder.dequeueInputBuffer(0);long inputDeadline=System.nanoTime()+750_000_000L;
                // Timed Surface output can temporarily hold codec buffers. Keep draining
                // instead of discarding an entire GOP after a single 10ms busy result.
                while(i<0&&running&&System.nanoTime()<inputDeadline){drain();i=decoder.dequeueInputBuffer(2000);if(i<0)Thread.sleep(2);}
                if(!running)return;
                if(i>=0){ByteBuffer b=decoder.getInputBuffer(i);if(b.capacity()<f.data().length)throw new IOException("Кадр превышает буфер декодера");b.clear();b.put(f.data());decoder.queueInputBuffer(i,0,f.data().length,f.presentationTimeUs()>0?f.presentationTimeUs():System.nanoTime()/1000,0);}else{decoderDrops++;decoderNeedsKey=true;Diag.log("CODEC","Input busy; waiting for IDR, dropped seq="+f.sequence());}
                drain();
            }
        }
    }
    private void drain(){
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        for(int k=0;k<12;k++){
            long now=System.nanoTime();
            if(pendingOutput>=0){
                // Retain one decoded buffer until it is due. Enqueuing many future
                // buffers in SurfaceView holds the codec pool and starves its input.
                if(pendingTarget>now+12_000_000L)return;
                decoder.releaseOutputBuffer(pendingOutput,Math.max(now,pendingTarget));pendingOutput=-1;renderedFrames++;lastRendered=System.currentTimeMillis();
            }
            int i=decoder.dequeueOutputBuffer(info,0);
            if(i>=0){pendingOutput=i;pendingTarget=displayClock.target(info.presentationTimeUs,System.nanoTime());}
            else if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat fmt=decoder.getOutputFormat();int codedWidth=fmt.getInteger(MediaFormat.KEY_WIDTH,width),codedHeight=fmt.getInteger(MediaFormat.KEY_HEIGHT,height);width=fmt.containsKey("crop-right")?fmt.getInteger("crop-right")-fmt.getInteger("crop-left",0)+1:codedWidth;height=fmt.containsKey("crop-bottom")?fmt.getInteger("crop-bottom")-fmt.getInteger("crop-top",0)+1:codedHeight;decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);updateRecordedFrameRate();Diag.log("CODEC FORMAT",fmt.toString());Diag.log("LIVE QUALITY","requested="+requestedWidth+"x"+requestedHeight+" actual="+width+"x"+height+" coded="+codedWidth+"x"+codedHeight);}else break;
        }
    }
    private void updateRecordedFrameRate(){try{JSONObject recording=session.state.get("VIDEO_CTRL"),event=session.state.get("VIDEO_FINISH");if(recording==null||!"1".equals(recording.optString("status"))||event==null)return;JSONObject file=new JSONObject(event.optString("desc"));if(file.optInt("c",-1)==camera&&file.optInt("w")==width&&file.optInt("h")==height){int fps=file.optInt("p");if(fps>=10&&fps<=60){timing.sourceRate(fps,1);Diag.log("LIVE TIMING","Recording metadata rate="+fps+" fps");}}}catch(org.json.JSONException ignored){}}
    private static String prefix(byte[] bytes,int max){StringBuilder hex=new StringBuilder();for(int i=0;i<Math.min(bytes.length,max);i++)hex.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));return hex.toString();}
    private void acceptAudio(byte[] pcm){if(!running)return;audioPackets++;audioBytes+=pcm.length;playAudio(pcm);listener.frame(new FrameAssembler.Frame(1,audioPackets,0,pcm,System.nanoTime()/1000));}
    private synchronized void ensureAudio(){
        if(!running||audioAttempted||nativeAudioPackets>0||wifi==null||!session.isCurrent(owner)||System.currentTimeMillis()-start<1200)return;audioAttempted=true;audioMessage="Проверяем звук регистратора…";
        audioSource=new RtspAudioSource(wifi,java.net.URI.create(StreamCapabilities.rtspUrl(host,description,camera,level)),pcm->{if(nativeAudioPackets==0&&running&&session.isCurrent(owner))acceptAudio(pcm);},message->{if(running){audioMessage=message;Diag.log("LIVE AUDIO",message);}});audioSource.start();
    }
    public PhoneCapture recording(Context context)throws Exception {recordingAudio=true;ensureAudio();PhoneRecording capture=new PhoneRecording(context,width(),height(),sampleRate(),sps(),pps(),frameRate());return new PhoneCapture(){public void accept(FrameAssembler.Frame frame){capture.accept(frame);}public String warning(){return capture.warning();}public void finish()throws Exception{recordingAudio=false;capture.finish();}};}
    private synchronized void playAudio(byte[] pcm){
        if(!running||muted||pcm.length==0)return;
        try{if(audio==null){int size=Math.max(sample*2/5,AudioTrack.getMinBufferSize(sample,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));audio=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(sample).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();audio.play();Diag.log("AUDIO","PCM mono 16bit rate="+sample);}audio.write(pcm,0,pcm.length,AudioTrack.WRITE_NON_BLOCKING);}catch(Exception e){Diag.error("PCM playback",e);muted=true;}
    }
    public double frameRate(){return timing==null?25:timing.fps();}
    public int width(){return width;}public int height(){return height;}public int sampleRate(){return sample;}
    public byte[] sps(){return sps;}public byte[] pps(){return pps;}
    String qualityText(){return width==requestedWidth&&height==requestedHeight?"":"Регистратор передаёт "+width+"×"+height+"; запрос "+requestedWidth+"×"+requestedHeight+" не применён";}
    private String statusText(){if(renderedFrames==0)return frameCount==0?"Ожидание первого кадра…":"Получено кадров: "+frameCount+" · ожидание ключевого кадра";return String.format(Locale.getDefault(),"Лайв · %d×%d · %.1f кадр/с · %.1f Мбит/с",width,height,renderFps,megabits)+(qualityText().isEmpty()?"":"\n"+qualityText())+((!muted||recordingAudio)&&audioPackets==0&&!audioMessage.isEmpty()?"\n"+audioMessage:"");}
    private String statistics(){return "packets="+assembler.packets+" receivedFrames="+frameCount+" renderedFrames="+renderedFrames+" rxFps="+receiveFps+" renderFps="+renderFps+" mbps="+megabits+" queue="+previewQueue.size()+" previewDelayMs="+previewDelayMs+" previewDropped="+previewQueue.dropped+" decoderDropped="+decoderDrops+" presentedFrames="+presentedFrames+" maxPresentationGapMs="+maxPresentationGapMs+" rebuffers="+displayClock.rebuffers+" resyncs="+previewQueue.resyncs+" invalid="+assembler.invalid+" duplicates="+assembler.duplicates+" incomplete="+assembler.dropped+" audioPackets="+audioPackets+" audioBytes="+audioBytes+" nativeAudioPackets="+nativeAudioPackets+" audioFallback="+audioAttempted+" lastFrame="+lastFrame+" lastRendered="+lastRendered;}
    private void closeSockets(){if(udp!=null)udp.close();try{if(tcp!=null)tcp.close();}catch(IOException ignored){}}
    public void close(){running=false;RtspAudioSource extra=audioSource;if(extra!=null)extra.close();closeSockets();previewQueue.close();if(renderThread!=null)renderThread.interrupt();}
    public void awaitStopped(){try{if(!stopped.await(20,TimeUnit.SECONDS))Diag.log("LIVE","Stop did not complete in 20s");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
