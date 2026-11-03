package io.zem.ibox;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Records the actual live frames, including PCM audio; independent of the recorder's SD card. */
public final class PhoneRecording {
    private final Context context;
    private final int width,height,rate;private final double fps;
    private final long start=System.nanoTime();
    private final File file;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256));
    private volatile boolean accepting=true;
    private byte[] sps,pps;
    private int mode,videoTrack=-1,audioTrack=-1;
    private MediaMuxer mp4;private MediaCodec aac,jpegEncoder;
    private boolean muxing,gotKey,audioFailed;
    private long samples,videoFrames,lastVideoPts=-1,videoBaseUs=-1,audioBaseUs=-1;
    private Exception failure;
    private record AudioSample(byte[] data,long pts,int flags){}
    private final List<AudioSample> audioPending=new ArrayList<>();
    public PhoneRecording(Context c,int width,int height,int rate,byte[] sps,byte[] pps,double fps)throws Exception {
        context=c.getApplicationContext();this.width=width;this.height=height;this.rate=rate;this.fps=fps;this.sps=sps;this.pps=pps;file=File.createTempFile("roadlink-live-",".media",c.getCacheDir());Diag.log("LIVE RECORD","Start "+width+"x"+height+" rate="+rate);
    }
    public synchronized void accept(FrameAssembler.Frame f){
        if(!accepting)return;
        long capturedUs=(f.presentationTimeUs()>0?f.presentationTimeUs():System.nanoTime()/1000)-start/1000;
        try{worker.execute(()->{if(failure!=null)return;try{frame(f,capturedUs);}catch(Exception e){failure=e;Diag.error("Live recording",e);}});}catch(RejectedExecutionException e){Diag.log("LIVE RECORD","Queue full: recording stopped to avoid a corrupted file");failure=new IOException("Телефон не успевает записывать лайв");accepting=false;}
    }
    private void frame(FrameAssembler.Frame f,long pts)throws Exception {
        if(mode==0&&(f.type()==2||f.type()==3)){
            mode=f.type();
            {
                mp4=new MediaMuxer(file.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                if(mode==2){MediaFormat vf=MediaFormat.createVideoFormat("video/avc",width,height);vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);vf.setInteger(MediaFormat.KEY_BIT_RATE,Math.max(1500000,width*height*5));vf.setInteger(MediaFormat.KEY_FRAME_RATE,20);vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);jpegEncoder=MediaCodec.createEncoderByType("video/avc");jpegEncoder.configure(vf,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);jpegEncoder.start();}
                try{MediaFormat fmt=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,1);fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);fmt.setInteger(MediaFormat.KEY_BIT_RATE,32000);fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192);aac=MediaCodec.createEncoderByType("audio/mp4a-latm");aac.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);aac.start();}
                catch(Exception e){Diag.error("AAC encoder unavailable",e);audioFailed=true;if(aac!=null){aac.release();aac=null;}}
            }
        }
        if(mode!=2&&mode!=3)return;
        if(f.type()==1&&aac!=null){
            if(audioBaseUs<0)audioBaseUs=Math.max(0,pts);
            drainAudio(false);int index=aac.dequeueInputBuffer(10000);
            if(index>=0){ByteBuffer b=aac.getInputBuffer(index);b.clear();if(f.data().length>b.remaining())throw new IOException("Слишком большой аудиокадр");b.put(f.data());aac.queueInputBuffer(index,0,f.data().length,audioBaseUs+samples*1000000/rate,0);samples+=f.data().length/2;}drainAudio(false);
        }
        if(mode==2&&f.type()==2){encodeJpeg(f.data(),pts);drainVideo(false);}
        maybeStart(pts);
        if(f.type()==3){
            for(byte[] nal:NalUnits.split(f.data())){int n=nal[0]&31;if(n==7)sps=nal;if(n==8)pps=nal;}
            if(videoTrack<0&&sps!=null&&pps!=null){MediaFormat fmt=MediaFormat.createVideoFormat("video/avc",width,height);fmt.setFloat(MediaFormat.KEY_FRAME_RATE,(float)fps);fmt.setByteBuffer("csd-0",ByteBuffer.wrap(NalUnits.annex(sps)));fmt.setByteBuffer("csd-1",ByteBuffer.wrap(NalUnits.annex(pps)));videoTrack=mp4.addTrack(fmt);}
            maybeStart(pts);
            if(!muxing)return;
            boolean key=NalUnits.key(f.data());if(!gotKey&&!key)return;if(!gotKey){videoBaseUs=pts;gotKey=true;}pts-=videoBaseUs;
            byte[] sample=NalUnits.muxSample(f.data());if(sample.length==0)return;
            pts=Math.max(pts,lastVideoPts+1);lastVideoPts=pts;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.set(0,sample.length,pts,key?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);mp4.writeSampleData(videoTrack,ByteBuffer.wrap(sample),info);videoFrames++;
        }
    }
    private void encodeJpeg(byte[] jpeg,long pts)throws Exception {
        drainVideo(false);int input=jpegEncoder.dequeueInputBuffer(10000);if(input<0)throw new IOException("Кодировщик записи JPEG занят");
        Bitmap bmp=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);if(bmp==null)throw new IOException("Повреждён JPEG-кадр");
        if(bmp.getWidth()!=width||bmp.getHeight()!=height){Bitmap scaled=Bitmap.createScaledBitmap(bmp,width,height,true);bmp.recycle();bmp=scaled;}
        int[] pixels=new int[width*height];bmp.getPixels(pixels,0,width,0,0,width,height);bmp.recycle();
        Image image=jpegEncoder.getInputImage(input);if(image==null)throw new IOException("Кодировщик не предоставил YUV-буфер");
        Image.Plane[] planes=image.getPlanes();
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int color=pixels[y*width+x],r=(color>>16)&255,g=(color>>8)&255,b=color&255;int yy=((66*r+129*g+25*b+128)>>8)+16;planes[0].getBuffer().put(y*planes[0].getRowStride()+x*planes[0].getPixelStride(),(byte)yy);}
        for(int y=0;y<height;y+=2)for(int x=0;x<width;x+=2){int r=0,g=0,b=0;for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){int color=pixels[Math.min(height-1,y+dy)*width+Math.min(width-1,x+dx)];r+=(color>>16)&255;g+=(color>>8)&255;b+=color&255;}r/=4;g/=4;b/=4;int u=((-38*r-74*g+112*b+128)>>8)+128,v=((112*r-94*g-18*b+128)>>8)+128;planes[1].getBuffer().put((y/2)*planes[1].getRowStride()+(x/2)*planes[1].getPixelStride(),(byte)u);planes[2].getBuffer().put((y/2)*planes[2].getRowStride()+(x/2)*planes[2].getPixelStride(),(byte)v);}
        image.close();jpegEncoder.queueInputBuffer(input,0,width*height*3/2,pts,0);
    }
    private void drainVideo(boolean ending)throws Exception {
        if(jpegEncoder==null)return;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        for(int k=0;k<(ending?100:8);k++){
            int index=jpegEncoder.dequeueOutputBuffer(info,ending?10000:0);
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(videoTrack<0)videoTrack=mp4.addTrack(jpegEncoder.getOutputFormat());maybeStart((System.nanoTime()-start)/1000);}
            else if(index>=0){if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){boolean key=(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0;if(muxing&&(gotKey||key)){ByteBuffer b=jpegEncoder.getOutputBuffer(index);b.position(info.offset);b.limit(info.offset+info.size);byte[] sample=new byte[info.size];b.get(sample);MediaCodec.BufferInfo copy=new MediaCodec.BufferInfo();if(!gotKey){videoBaseUs=info.presentationTimeUs;gotKey=true;}copy.set(0,sample.length,Math.max(info.presentationTimeUs-videoBaseUs,lastVideoPts+1),info.flags);lastVideoPts=copy.presentationTimeUs;mp4.writeSampleData(videoTrack,ByteBuffer.wrap(sample),copy);videoFrames++;}}boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;jpegEncoder.releaseOutputBuffer(index,false);if(end)break;}
            else if(!ending)break;
        }
    }
    private void maybeStart(long pts){
        if(!muxing&&videoTrack>=0&&(audioTrack>=0||audioFailed||pts>2_000_000)){
            mp4.start();muxing=true;Diag.log("LIVE RECORD","MP4 muxer started audio="+(audioTrack>=0));
            if(audioTrack>=0)for(AudioSample a:audioPending)writeAudio(a);audioPending.clear();
        }
    }
    private void writeAudio(AudioSample a){if(!gotKey||a.pts<videoBaseUs)return;MediaCodec.BufferInfo i=new MediaCodec.BufferInfo();i.set(0,a.data.length,a.pts-videoBaseUs,a.flags);mp4.writeSampleData(audioTrack,ByteBuffer.wrap(a.data),i);}
    private void drainAudio(boolean ending)throws Exception {
        if(aac==null)return;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        for(int k=0;k<(ending?100:8);k++){
            int i=aac.dequeueOutputBuffer(info,ending?10000:0);
            if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(!muxing)audioTrack=mp4.addTrack(aac.getOutputFormat());}
            else if(i>=0){
                if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){ByteBuffer b=aac.getOutputBuffer(i);b.position(info.offset);b.limit(info.offset+info.size);byte[] data=new byte[info.size];b.get(data);AudioSample a=new AudioSample(data,info.presentationTimeUs,0);if(muxing&&audioTrack>=0)writeAudio(a);else if(!muxing&&audioPending.size()<500)audioPending.add(a);}
                boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;aac.releaseOutputBuffer(i,false);if(end)break;
            }else if(!ending)break;
        }
    }
    public void finish()throws Exception {
        Future<?> closing;
        synchronized(this){accepting=false;closing=worker.submit(()->{
            try{
                if(aac!=null){int i=aac.dequeueInputBuffer(10000);if(i>=0)aac.queueInputBuffer(i,0,0,Math.max(0,audioBaseUs)+samples*1000000/rate,MediaCodec.BUFFER_FLAG_END_OF_STREAM);drainAudio(true);}
                if(jpegEncoder!=null){int i=jpegEncoder.dequeueInputBuffer(10000);if(i>=0)jpegEncoder.queueInputBuffer(i,0,0,(System.nanoTime()-start)/1000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);drainVideo(true);}
                if(mp4!=null&&muxing)mp4.stop();
            }catch(Exception e){failure=e;Diag.error("Finalize live recording",e);}
            finally{if(aac!=null){try{aac.stop();}catch(Exception ignored){}aac.release();}if(jpegEncoder!=null){try{jpegEncoder.stop();}catch(Exception ignored){}jpegEncoder.release();}if(mp4!=null)mp4.release();}
        });}
        try{closing.get(20,TimeUnit.SECONDS);}finally{worker.shutdown();}
        if(failure!=null){file.delete();throw failure;}
        if(videoFrames<1){file.delete();throw new IOException("Не получено видеокадров для записи. Логи содержат параметры потока");}
        String extension=".mp4",mime="video/mp4",name="Live-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+extension;
        Uri uri=Library.publish(context,file,name,mime,"Live");Library.add(context,uri,name,mime,file.length(),"live camera");Diag.log("LIVE RECORD","Saved "+name+" frames="+videoFrames+" bytes="+file.length());file.delete();
    }
}
