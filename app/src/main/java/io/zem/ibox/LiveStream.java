package io.zem.ibox;

import android.graphics.*;
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

public final class LiveStream implements AutoCloseable {
    public interface Listener {void status(String text);void frame(FrameAssembler.Frame f);}
    private final JieliSession session;
    private final Surface surface;
    private final Listener listener;
    private final int camera,level;
    private volatile boolean running=true,muted=true;
    private DatagramSocket udp;private Socket tcp;
    private MediaCodec decoder;private AudioTrack audio;
    private volatile int width=640,height=480;
    private int sample=8000,requestedWidth,requestedHeight;
    private long lastSeq=-1,frameCount,start=System.currentTimeMillis(),lastFrame,receivedBytes;
    private volatile long renderedFrames,lastRendered,decoderDrops,previewDelayMs;
    private volatile double receiveFps,renderFps,megabits;
    private final FrameAssembler assembler=new FrameAssembler();
    private final LiveFrameQueue previewQueue=new LiveFrameQueue(8);
    private Thread renderThread;
    private boolean decoderNeedsKey;
    private final Object decodeLock=new Object();
    private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile byte[] sps,pps;
    public LiveStream(JieliSession session,Surface surface,int camera,int level,Listener listener){this.session=session;this.surface=surface;this.camera=camera;this.level=level;this.listener=listener;}
    public void start(){new Thread(this::run,"Live receiver").start();}
    public void mute(boolean value){muted=value;Diag.log("LIVE","mute="+value);}
    public boolean hasVideo(){return running&&renderedFrames>0;}
    public boolean muted(){return muted;}
    private void run(){
        String open=camera==0?"OPEN_RT_STREAM":"OPEN_PULL_RT_STREAM";
        try {
            Network net=session.network;
            boolean useTcp="0".equals(session.description.optString("net_type","1"));
            if(!useTcp){udp=new DatagramSocket(null);udp.setReuseAddress(true);udp.setReceiveBufferSize(2*1024*1024);if(net!=null)net.bindSocket(udp);udp.bind(new InetSocketAddress(camera==0?2224:2225));udp.setSoTimeout(1000);}
            else {tcp=net==null?new Socket():net.getSocketFactory().createSocket();tcp.setReceiveBufferSize(2*1024*1024);tcp.setTcpNoDelay(true);tcp.connect(new InetSocketAddress(session.host,2229),6000);tcp.setSoTimeout(1000);}
            int[] widths={640,1280,1920},heights={480,720,1080};requestedWidth=width=widths[level];requestedHeight=height=heights[level];
            int format=session.description.optInt("rts_type",1);
            JSONObject cameraParams=session.state.get(camera==0?"VIDEO_PARAM":"PULL_VIDEO_PARAM");int fps=cameraParams==null?25:cameraParams.optInt("fps",25);if(fps<1||fps>60)fps=25;
            listener.status("Запуск видеопотока…");
            JSONObject reply=session.put(open,JieliSession.params("format",""+format,"w",""+width,"h",""+height,"fps",""+fps,"rate","8000"));
            width=reply.optInt("w",width);height=reply.optInt("h",height);sample=reply.optInt("rate",8000);
            Diag.log("LIVE","Start camera="+camera+" transport="+(useTcp?"TCP:2229":"UDP:"+(camera==0?2224:2225))+" parameters="+reply);
            renderThread=new Thread(this::renderLoop,"Live decoder");renderThread.start();
            byte[] buffer=new byte[65535];long stats=System.currentTimeMillis(),priorFrames=0,priorRendered=0,priorBytes=0;int sampled=0;TcpMediaReader reader=new TcpMediaReader();
            while(running){
                List<FrameAssembler.Frame> received;
                try{
                    if(useTcp){TcpMediaReader.Packet p=reader.read(tcp.getInputStream());receivedBytes+=20+p.payload().length;if(sampled++<8&&Diag.enabled())Diag.log("LIVE TCP","header="+prefix(p.header(),20)+" payloadBytes="+p.payload().length+" payloadFirst64="+prefix(p.payload(),64));received=assembler.acceptTcp(p.header(),p.payload());}
                    else {DatagramPacket p=new DatagramPacket(buffer,buffer.length);udp.receive(p);if(!p.getAddress().getHostAddress().equals(session.host))continue;int count=p.getLength();receivedBytes+=count;if(sampled++<8&&Diag.enabled())Diag.log("LIVE UDP","length="+count+" first64="+prefix(Arrays.copyOf(buffer,Math.min(count,64)),64));received=assembler.accept(buffer,count);}
                }catch(SocketTimeoutException e){if(System.currentTimeMillis()-Math.max(start,lastFrame)>10000){listener.status("Нет кадров. Экспортируй логи и попробуй другое качество лайва");Diag.log("LIVE","No complete frames for 10s; "+statistics());}continue;}
                for(FrameAssembler.Frame f:received){
                    if(f.type()==1){playAudio(f.data());listener.frame(f);}
                    else if(f.type()==2||f.type()==3){
                        if(lastSeq!=-1&&(int)(f.sequence()-lastSeq)<=0)continue;lastSeq=f.sequence();
                        boolean key=false;if(f.type()==3){NalUnits.Headers headers=NalUnits.headers(f.data());if(headers.sps()!=null)sps=headers.sps();if(headers.pps()!=null)pps=headers.pps();key=headers.key();}
                        listener.frame(f);previewQueue.offer(f,key);frameCount++;lastFrame=System.currentTimeMillis();
                    }
                }
                long now=System.currentTimeMillis();if(now-stats>=1000){double seconds=(now-stats)/1000.0;receiveFps=(frameCount-priorFrames)/seconds;renderFps=(renderedFrames-priorRendered)/seconds;megabits=(receivedBytes-priorBytes)*8/seconds/1000000;stats=now;priorFrames=frameCount;priorRendered=renderedFrames;priorBytes=receivedBytes;if(Diag.enabled())Diag.log("LIVE STATS",statistics()+" sps="+(sps==null?0:sps.length)+" pps="+(pps==null?0:pps.length));listener.status(statusText());}
            }
        }catch(Exception e){if(running){Diag.error("Live stream",e);listener.status("Лайв: "+e.getMessage());}}
        finally {
            running=false;closeSockets();previewQueue.close();
            if(renderThread!=null){renderThread.interrupt();try{renderThread.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            synchronized(decodeLock){if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();decoder=null;}if(audio!=null){audio.release();audio=null;}}
            try{if(session.connected())session.put(camera==0?"CLOSE_RT_STREAM":"CLOSE_PULL_RT_STREAM",JieliSession.params("status","1"));}catch(Exception e){Diag.error("Close live",e);}
            Diag.log("LIVE","Stopped "+statistics());
            stopped.countDown();
        }
    }
    private void renderLoop(){
        try{while(running){LiveFrameQueue.Entry entry=previewQueue.poll(20);if(entry==null){synchronized(decodeLock){if(decoder!=null)drain();}continue;}previewDelayMs=(System.nanoTime()-entry.receivedNanos())/1_000_000;render(entry.frame(),entry.key(),entry.reset());}}
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
                    MediaFormat fmt=MediaFormat.createVideoFormat("video/avc",width,height);fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,6*1024*1024);fmt.setInteger(MediaFormat.KEY_PRIORITY,0);fmt.setInteger("low-latency",1);
                    fmt.setByteBuffer("csd-0",ByteBuffer.wrap(NalUnits.annex(sps)));fmt.setByteBuffer("csd-1",ByteBuffer.wrap(NalUnits.annex(pps)));
                    decoder=MediaCodec.createDecoderByType("video/avc");decoder.configure(fmt,surface,null,0);decoder.start();created=true;decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);Diag.log("CODEC","Started "+decoder.getName()+" "+fmt);
                }
                if(decoderNeedsKey&&!key){decoderDrops++;return;}
                if((reset||decoderNeedsKey)&&!created){decoder.flush();decoderNeedsKey=false;Diag.log("CODEC","Resumed from IDR after preview overload");}
                drain();int i=decoder.dequeueInputBuffer(10000);
                if(i>=0){ByteBuffer b=decoder.getInputBuffer(i);if(b.capacity()<f.data().length)throw new IOException("Кадр превышает буфер декодера");b.clear();b.put(f.data());decoder.queueInputBuffer(i,0,f.data().length,System.nanoTime()/1000,0);}else{decoderDrops++;decoderNeedsKey=true;Diag.log("CODEC","Input busy; waiting for IDR, dropped seq="+f.sequence());}
                drain();
            }
        }
    }
    private void drain(){MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();for(int k=0;k<12;k++){int i=decoder.dequeueOutputBuffer(info,0);if(i>=0){decoder.releaseOutputBuffer(i,System.nanoTime());renderedFrames++;lastRendered=System.currentTimeMillis();}else if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat fmt=decoder.getOutputFormat();int codedWidth=fmt.getInteger(MediaFormat.KEY_WIDTH,width),codedHeight=fmt.getInteger(MediaFormat.KEY_HEIGHT,height);width=fmt.containsKey("crop-right")?fmt.getInteger("crop-right")-fmt.getInteger("crop-left",0)+1:codedWidth;height=fmt.containsKey("crop-bottom")?fmt.getInteger("crop-bottom")-fmt.getInteger("crop-top",0)+1:codedHeight;decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);Diag.log("CODEC FORMAT",fmt.toString());Diag.log("LIVE QUALITY","requested="+requestedWidth+"x"+requestedHeight+" actual="+width+"x"+height+" coded="+codedWidth+"x"+codedHeight);}else break;}}
    private static String prefix(byte[] bytes,int max){StringBuilder hex=new StringBuilder();for(int i=0;i<Math.min(bytes.length,max);i++)hex.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));return hex.toString();}
    private void playAudio(byte[] pcm){
        if(muted||pcm.length==0)return;
        try{if(audio==null){int size=Math.max(32768,AudioTrack.getMinBufferSize(sample,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));audio=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(sample).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();audio.play();Diag.log("AUDIO","PCM mono 16bit rate="+sample);}audio.write(pcm,0,pcm.length,AudioTrack.WRITE_NON_BLOCKING);}catch(Exception e){Diag.error("PCM playback",e);muted=true;}
    }
    public int width(){return width;}public int height(){return height;}public int sampleRate(){return sample;}
    public byte[] sps(){return sps;}public byte[] pps(){return pps;}
    String qualityText(){return width==requestedWidth&&height==requestedHeight?"":"Регистратор передаёт "+width+"×"+height+"; запрос "+requestedWidth+"×"+requestedHeight+" не применён";}
    private String statusText(){if(renderedFrames==0)return frameCount==0?"Ожидание первого кадра…":"Получено кадров: "+frameCount+" · ожидание ключевого кадра";return String.format(Locale.getDefault(),"Лайв · %d×%d · %.1f кадр/с · %.1f Мбит/с",width,height,renderFps,megabits)+(qualityText().isEmpty()?"":"\n"+qualityText());}
    private String statistics(){return "packets="+assembler.packets+" receivedFrames="+frameCount+" renderedFrames="+renderedFrames+" rxFps="+receiveFps+" renderFps="+renderFps+" mbps="+megabits+" queue="+previewQueue.size()+" previewDelayMs="+previewDelayMs+" previewDropped="+previewQueue.dropped+" decoderDropped="+decoderDrops+" resyncs="+previewQueue.resyncs+" invalid="+assembler.invalid+" duplicates="+assembler.duplicates+" incomplete="+assembler.dropped+" lastFrame="+lastFrame+" lastRendered="+lastRendered;}
    private void closeSockets(){if(udp!=null)udp.close();try{if(tcp!=null)tcp.close();}catch(IOException ignored){}}
    public void close(){running=false;closeSockets();previewQueue.close();if(renderThread!=null)renderThread.interrupt();}
    public void awaitStopped(){try{if(!stopped.await(20,TimeUnit.SECONDS))Diag.log("LIVE","Stop did not complete in 20s");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
