package io.zem.ibox;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import android.view.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.*;

/** Alternative transport used by DVRunning2, with native demux and the same Wi-Fi owner. */
final class RtspLive implements LiveSource {
    private final JieliSession session;private final CtpClient owner;private final SurfaceView output;private final int camera,quality;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile boolean closed,muted=true,hasVideo;private boolean retriedUdp;private int previewMode;private volatile boolean hasAudio;private long openedNanos;private volatile int width=640,height=480;
    private LibVLC engine;private MediaPlayer player;private WifiRtspProxy proxy;private Capture capture;private volatile RtpJpeg jpegCapture;private volatile int jpegPayload=26,audioPayload=-1;private volatile Exception jpegFailure;
    private final View.OnLayoutChangeListener layout=(v,l,t,r,b,ol,ot,or,ob)->{if(player!=null&&r>l&&b>t)player.getVLCVout().setWindowSize(r-l,b-t);};
    RtspLive(JieliSession session,SurfaceView output,int camera,int quality,Listener listener){this.session=session;owner=session.ctp;this.output=output;this.camera=camera;this.quality=quality;this.listener=listener;}
    private void ui(Runnable action){if(Looper.myLooper()==Looper.getMainLooper())action.run();else main.post(action);}
    public void start(){ui(()->open(false));}
    private void retryUdp(){if(closed||retriedUdp)return;retriedUdp=true;main.removeCallbacks(stats);output.removeOnLayoutChangeListener(layout);if(player!=null){player.setEventListener(null);player.stop();player.detachViews();player.release();player=null;}if(proxy!=null)proxy.close();if(engine!=null){engine.release();engine=null;}Diag.log("RTSP","TCP не дал изображения; один повтор по Wi-Fi UDP");open(true);}
    private void open(boolean udpTransport){if(closed)return;openedNanos=System.nanoTime();hasVideo=false;Diag.log("RTSP","Start camera="+camera+" quality="+quality+" transport="+(udpTransport?"UDP":"TCP")+" path="+URI.create(StreamCapabilities.rtspUrl(session.host,session.description,camera,quality)).getPath());try{if(!session.isCurrent(owner)||!output.getHolder().getSurface().isValid())throw new IOException("Источник видео изменился");
        proxy=new WifiRtspProxy(session.network,URI.create(StreamCapabilities.rtspUrl(session.host,session.description,camera,quality)),udpTransport);proxy.requireUdp=()->ui(this::retryUdp);proxy.observer=this::capturePacket;engine=new LibVLC(RoadApp.instance,new ArrayList<>(Arrays.asList("--no-video-title-show")));player=new MediaPlayer(engine);
        player.setUseOrientationFromBounds(true);player.getVLCVout().setVideoSurface(output.getHolder().getSurface(),output.getHolder());player.getVLCVout().setWindowSize(Math.max(1,output.getWidth()),Math.max(1,output.getHeight()));
        player.getVLCVout().attachViews((v,w,h,vw,vh,sn,sd)->{if(vw>0&&vh>0){width=vw;height=vh;}});output.addOnLayoutChangeListener(layout);
        Media media=new Media(engine,Uri.parse(proxy.address()));media.setHWDecoderEnabled(true,false);media.addOption(":rtsp-tcp");media.addOption(":network-caching="+(previewMode==1?100:previewMode==2?650:350));player.setMedia(media);media.release();
        player.setEventListener(event->{if(event.type==MediaPlayer.Event.RecordChanged){Capture current=capture;if(current!=null&&!event.getRecording())current.finished(event.getRecordPath());}
            if(closed)return;if(event.type==MediaPlayer.Event.EncounteredError){if(!hasVideo&&!retriedUdp&&(proxy==null||proxy.lastStatus<400||proxy.lastStatus==461)){ui(this::retryUdp);return;}listener.status("RTSP "+new String[]{"640×480","1280×720","1920×1080"}[quality]+" не открылся"+(proxy==null?"":" · "+proxy.failureText())+". Выберите другое качество или Jieli");Diag.log("RTSP","Native playback error");close();}else if(event.type==MediaPlayer.Event.EndReached)listener.status("Поток RTSP завершён");});
        player.setVolume(muted?0:100);player.play();listener.status("Подключаем RTSP…");main.post(stats);
    }catch(Exception|LinkageError e){Diag.error("RTSP start",e);listener.status("RTSP: "+e.getMessage());close();}}
    private final Runnable stats=new Runnable(){public void run(){if(closed||player==null)return;if(!session.isCurrent(owner)){close();return;}IMedia media=player.getMedia();try{IMedia.Stats s=media==null?null:media.getStats();hasVideo=s!=null&&s.displayedPictures>0;hasAudio=s!=null&&s.decodedAudio>0;IMedia.VideoTrack track=player.getCurrentVideoTrack();if(track!=null&&track.width>0){width=track.width;height=track.height;}listener.status(hasVideo?"Лайв RTSP · "+width+"×"+height:"RTSP · ожидание изображения…");if(s!=null)Diag.log("RTSP STATS","decoded="+s.decodedVideo+" displayed="+s.displayedPictures+" lost="+s.lostPictures+" packets="+(proxy==null?0:proxy.mediaPackets));if(!hasVideo&&System.nanoTime()-openedNanos>6_000_000_000L){if(!retriedUdp&&(proxy==null||proxy.lastStatus<400||proxy.lastStatus==461)){retryUdp();return;}listener.status("Нет изображения RTSP "+new String[]{"640×480","1280×720","1920×1080"}[quality]+" · "+(proxy==null?"нет ответа":proxy.failureText())+". Выберите другое качество или Jieli");close();return;}}finally{if(media!=null)media.release();}main.postDelayed(this,1000);}};
    public void previewMode(int mode){previewMode=mode;}public boolean hasAudio(){return hasAudio;}
    public void mute(boolean value){muted=value;ui(()->{if(player!=null)player.setVolume(value?0:100);});}public boolean muted(){return muted;}public boolean hasVideo(){return !closed&&hasVideo;}
    public int width(){return width;}public int height(){return height;}public int sampleRate(){return 8000;}public double frameRate(){return 25;}public byte[] sps(){return null;}public byte[] pps(){return null;}
    private void capturePacket(byte[] packet){if(packet.length>0&&packet[0]!='$'){String text=new String(packet,java.nio.charset.StandardCharsets.ISO_8859_1);java.util.regex.Matcher jpeg=java.util.regex.Pattern.compile("(?im)^a=rtpmap:([0-9]+) JPEG/90000").matcher(text),audio=java.util.regex.Pattern.compile("(?im)^a=rtpmap:([0-9]+) L16/8000/1").matcher(text);if(jpeg.find())jpegPayload=Integer.parseInt(jpeg.group(1));if(audio.find())audioPayload=Integer.parseInt(audio.group(1));}RtpJpeg current=jpegCapture;if(current!=null)try{current.accept(packet);}catch(Exception e){jpegFailure=e;jpegCapture=null;Diag.error("RTSP capture",e);}}
    public PhoneCapture recording(Context context)throws Exception {if(session.description.optInt("rts_type",1)==0){if(!hasVideo())throw new IOException("Дождитесь изображения RTSP");PhoneRecording recording=new PhoneRecording(context,width,height,8000,null,null,25);jpegFailure=null;jpegCapture=new RtpJpeg(jpegPayload,audioPayload,recording::accept);return ()->{jpegCapture=null;Exception error=jpegFailure;try{recording.finish();}catch(Exception e){if(error==null)throw e;}if(error!=null)throw error;};}if(player==null||!hasVideo())throw new IOException("Дождитесь изображения RTSP");Capture next=new Capture(context);if(!player.record(next.folder.getPath())){next.folder.delete();throw new IOException("Запись этого RTSP-потока недоступна");}capture=next;return next;}
    public void close(){closed=true;ui(()->{main.removeCallbacks(stats);output.removeOnLayoutChangeListener(layout);try{if(player!=null){player.stop();player.detachViews();player.release();player=null;}}catch(Exception e){Diag.log("RTSP","Stop: "+e.getClass().getSimpleName());}finally{if(capture!=null)capture.finished(null);if(proxy!=null)proxy.close();if(engine!=null){engine.release();engine=null;}stopped.countDown();}});}
    public void awaitStopped(){try{stopped.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private final class Capture implements PhoneCapture {
        final Context context;final File folder;final CountDownLatch done=new CountDownLatch(1);volatile String result;
        Capture(Context context)throws IOException {this.context=context.getApplicationContext();folder=new File(context.getCacheDir(),"rtsp-record-"+UUID.randomUUID());if(!folder.mkdirs())throw new IOException("Не удалось подготовить запись RTSP");}
        void finished(String path){if(path!=null)result=path;done.countDown();}
        public void finish()throws Exception {ui(()->{if(player!=null)player.record(null);else finished(null);});if(!done.await(8,TimeUnit.SECONDS))throw new IOException("Не удалось завершить запись RTSP");
            File file=result==null?null:new File(result);if(file==null||!file.isFile()){File[] files=folder.listFiles(f->f.isFile()&&f.length()>0);if(files!=null&&files.length==1)file=files[0];}if(file==null||!file.isFile()||file.length()==0)throw new IOException("RTSP не предоставил файл записи");
            if(!file.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile()))throw new IOException("Неверный путь записи RTSP");String extension=file.getName().substring(file.getName().lastIndexOf('.')).toLowerCase(Locale.ROOT);String mime=extension.equals(".avi")?"video/x-msvideo":extension.equals(".mkv")?"video/x-matroska":extension.equals(".ts")?"video/mp2t":"video/mp4";
            String name="Live-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+extension;android.net.Uri uri=Library.publish(context,file,name,mime,"Live");Library.add(context,uri,name,mime,file.length(),"RTSP live");file.delete();folder.delete();Diag.log("LIVE RECORD","Saved RTSP "+name);
        }
    }
}
