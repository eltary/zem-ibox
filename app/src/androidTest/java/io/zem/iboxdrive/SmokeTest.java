package io.zem.iboxdrive;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.nio.*;
import java.util.concurrent.atomic.*;

public final class SmokeTest extends Instrumentation {
    private int assertions;
    private MainActivity activity;
    interface Condition {boolean ok()throws Exception;}
    void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);Diag.log("TEST PASS",message);}
    void waitFor(Condition c,int ms)throws Exception{long end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){if(c.ok())return;Thread.sleep(100);}throw new AssertionError("Condition timed out after "+ms+"ms");}
    Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void invoke(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);runOnMainSync(()->{try{m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}});}
    public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    public void onStart(){
        Bundle result=new Bundle();
        try{
            try(InputStream in=new URL("http://10.0.2.2:8080/test/reset").openStream()){while(in.read()!=-1){}}
            Intent launch=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("test_host","10.0.2.2");activity=(MainActivity)startActivitySync(launch);
            JieliSession s=((RoadApp)activity.getApplication()).session;waitFor(s::connected,15000);
            Diag.setEnabled(true);
            activity.getSharedPreferences("library",0).edit().clear().commit();
            check(!s.cardPresent(),"Connected without SD card");check(s.description.optString("firmware_version").equals("SIMULATED"),"Fetched device capabilities");
            invoke("startLive",new Class<?>[]{});Field count=LiveStream.class.getDeclaredField("renderedFrames");count.setAccessible(true);waitFor(()->field("live")!=null&&count.getLong(field("live"))>15,12000);
            LiveStream stream=(LiveStream)field("live");check(count.getLong(stream)>15,"H264 live renders without SD card");
            Bitmap screen=getUiAutomation().takeScreenshot();int[] location=new int[2],extent=new int[2];runOnMainSync(()->{try{SurfaceView view=(SurfaceView)field("surface");view.getLocationOnScreen(location);extent[0]=view.getWidth();extent[1]=view.getHeight();}catch(Exception e){throw new RuntimeException(e);}});Set<Integer> colors=new HashSet<>();for(int y=extent[1]/3;y<extent[1]*2/3;y+=9)for(int x=extent[0]/3;x<extent[0]*2/3;x+=9)colors.add(screen.getPixel(location[0]+x,location[1]+y));screen.recycle();check(colors.size()>8,"Video is visibly displayed in the activity, not covered by a SurfaceView background");
            check(stream.sps()!=null&&stream.pps()!=null,"Captured H264 SPS/PPS");stream.mute(false);
            FrameAssembler assembly=(FrameAssembler)getPrivate(stream,"assembler");check(assembly.packets>15&&assembly.invalid==0,"Actual iBOX TCP framing produces complete frames without invalid packets");
            int[] aspect=new int[2];runOnMainSync(()->{try{SurfaceView v=(SurfaceView)field("surface");aspect[0]=v.getWidth();aspect[1]=v.getHeight();}catch(Exception e){throw new RuntimeException(e);}});check(Math.abs(aspect[0]/(double)aspect[1]-4.0/3)<.03,"Preview surface preserves source aspect ratio: "+aspect[0]+"x"+aspect[1]+", stream="+stream.width()+"x"+stream.height());
            invoke("selectLiveQuality",new Class<?>[]{int.class},1);waitFor(()->field("live")!=null&&((LiveStream)field("live")).width()==1280&&count.getLong(field("live"))>8,12000);check(activity.getPreferences(0).getInt("quality.0",-1)==1,"720p selection sends request, decodes changed stream and persists preference");
            invoke("selectLiveQuality",new Class<?>[]{int.class},2);waitFor(()->field("live")!=null&&((LiveStream)field("live")).height()==1080&&count.getLong(field("live"))>8,12000);check(((LiveStream)field("live")).width()==1920,"1080p uses visible crop height instead of coded 1088");
            try(InputStream in=new URL("http://10.0.2.2:8080/test/quality/fixed").openStream()){while(in.read()!=-1){}}
            invoke("selectLiveQuality",new Class<?>[]{int.class},0);waitFor(()->field("live")!=null&&((LiveStream)field("live")).width()==1920&&count.getLong(field("live"))>8,12000);check(((LiveStream)field("live")).qualityText().contains("не применён"),"Firmware ignoring requested quality is reported using actual stream dimensions");
            try(InputStream in=new URL("http://10.0.2.2:8080/test/quality/auto").openStream()){while(in.read()!=-1){}}invoke("selectLiveQuality",new Class<?>[]{int.class},0);waitFor(()->field("live")!=null&&((LiveStream)field("live")).width()==640&&count.getLong(field("live"))>8,10000);
            invoke("stopLive",new Class<?>[]{});s.worker.submit(()->{}).get();Field cameraField=MainActivity.class.getDeclaredField("camera");cameraField.setAccessible(true);cameraField.setInt(activity,1);invoke("startLive",new Class<?>[]{});Thread.sleep(2000);check(count.getLong(field("live"))>10,"Rear TCP live starts after switching from front camera");invoke("stopLive",new Class<?>[]{});s.worker.submit(()->{}).get();cameraField.setInt(activity,0);invoke("startLive",new Class<?>[]{});Thread.sleep(1800);stream=(LiveStream)field("live");stream.mute(false);
            final boolean[] spacing={false};runOnMainSync(()->{try{LinearLayout liveBody=(LinearLayout)field("body"),buttons=(LinearLayout)liveBody.getChildAt(3);View left=buttons.getChildAt(0),right=buttons.getChildAt(1),next=liveBody.getChildAt(4);spacing[0]=right.getLeft()-left.getRight()>=6*activity.getResources().getDisplayMetrics().density&&next.getTop()-buttons.getBottom()>=6*activity.getResources().getDisplayMetrics().density;}catch(Exception e){throw new RuntimeException(e);}});check(spacing[0],"Visible spacing separates adjacent buttons and rows");
            int before=Library.all(activity).length();invoke("snapshot",new Class<?>[]{});waitFor(()->Library.all(activity).length()>before,10000);check(true,"Live snapshot published via MediaStore");
            invoke("togglePhoneRecording",new Class<?>[]{Button.class},new Button(activity));Thread.sleep(4000);invoke("stopPhoneRecording",new Class<?>[]{});
            waitFor(()->Library.all(activity).length()>before+1,15000);JSONObject recording=Library.all(activity).getJSONObject(0);Uri recorded=Uri.parse(recording.getString("uri"));MediaExtractor extractor=new MediaExtractor();extractor.setDataSource(activity,recorded,null);
            boolean video=false,audio=false;for(int i=0;i<extractor.getTrackCount();i++){String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);video|="video/avc".equals(mime);audio|="audio/mp4a-latm".equals(mime);}extractor.release();check(video&&audio,"Live MP4 contains AVC video and AAC audio");
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();retriever.setDataSource(activity,recorded);Bitmap frame=retriever.getFrameAtTime(1_000_000);check(frame!=null,"Recorded MP4 can be decoded");if(frame!=null)frame.recycle();retriever.release();
            invoke("stopLive",new Class<?>[]{});s.worker.submit(()->{}).get();
            try(InputStream in=new URL("http://10.0.2.2:8080/test/mode/udp").openStream()){while(in.read()!=-1){}}
            s.description=new JSONObject(s.readText("/mnt/spiflash/res/dev_desc.txt",100000));
            byte[] jpegBytes;try(InputStream in=new URL("http://10.0.2.2:8080/test/frame.jpg").openStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){Io.copy(in,out);jpegBytes=out.toByteArray();}
            // The WSL emulator NAT redirects UDP to its disabled Ethernet adapter.
            // Inject datagrams on loopback; the control connection remains on Wi-Fi.
            android.net.Network wifi=s.network;s.network=null;s.host="127.0.0.1";
            AtomicBoolean inject=new AtomicBoolean(true);AtomicInteger targetPort=new AtomicInteger(2224);
            Thread injector=new Thread(()->{try(DatagramSocket socket=new DatagramSocket()) {int sequence=0;while(inject.get()){for(int offset=((jpegBytes.length-1)/1400)*1400;offset>=0;offset-=1400){int n=Math.min(1400,jpegBytes.length-offset);byte[] packet=ByteBuffer.allocate(20+n).order(ByteOrder.LITTLE_ENDIAN).put((byte)2).put((byte)0).putShort((short)n).putInt(sequence).putInt(jpegBytes.length).putInt(offset).putInt(sequence*50).put(jpegBytes,offset,n).array();socket.send(new DatagramPacket(packet,packet.length,InetAddress.getByName("127.0.0.1"),targetPort.get()));}byte[] sound=new byte[800];byte[] audioPacket=ByteBuffer.allocate(820).order(ByteOrder.LITTLE_ENDIAN).put((byte)1).put((byte)0).putShort((short)800).putInt(sequence).putInt(800).putInt(0).putInt(sequence*50).put(sound).array();socket.send(new DatagramPacket(audioPacket,audioPacket.length,InetAddress.getByName("127.0.0.1"),targetPort.get()));sequence++;Thread.sleep(50);}}catch(Exception e){Diag.error("Test UDP injector",e);}},"Test UDP fixture");injector.start();
            invoke("startLive",new Class<?>[]{});Thread.sleep(2500);LiveStream jpegStream=(LiveStream)field("live");check(count.getLong(jpegStream)>10,"UDP JPEG live handles out of order datagrams");
            int jpegBefore=Library.all(activity).length();invoke("togglePhoneRecording",new Class<?>[]{Button.class},new Button(activity));Thread.sleep(3500);invoke("stopPhoneRecording",new Class<?>[]{});waitFor(()->Library.all(activity).length()>jpegBefore,15000);
            Uri jpegRecording=Uri.parse(Library.all(activity).getJSONObject(0).getString("uri"));MediaMetadataRetriever jpegReader=new MediaMetadataRetriever();jpegReader.setDataSource(activity,jpegRecording);Bitmap jpegFrame=jpegReader.getFrameAtTime(1000000);check(jpegFrame!=null,"JPEG live is transcoded to a playable MP4");if(jpegFrame!=null)jpegFrame.recycle();jpegReader.release();
            invoke("stopLive",new Class<?>[]{});s.worker.submit(()->{}).get();Field camera=MainActivity.class.getDeclaredField("camera");camera.setAccessible(true);camera.setInt(activity,1);targetPort.set(2225);invoke("startLive",new Class<?>[]{});Thread.sleep(2000);check(count.getLong((LiveStream)field("live"))>10,"Rear camera live receives on UDP 2225");invoke("toggleFullScreen",new Class<?>[]{});Thread.sleep(1500);waitFor(()->field("live")!=null&&count.getLong(field("live"))>5,7000);check(activity.getResources().getConfiguration().orientation==2,"Fullscreen live rotates to landscape");invoke("toggleFullScreen",new Class<?>[]{});Thread.sleep(1500);waitFor(()->field("live")!=null&&count.getLong(field("live"))>5,7000);check(activity.getResources().getConfiguration().orientation==1,"Fullscreen returns to portrait and resumes live");invoke("stopLive",new Class<?>[]{});s.worker.submit(()->{}).get();inject.set(false);injector.join(1000);s.host="10.0.2.2";s.network=wifi;
            try(InputStream in=new URL("http://10.0.2.2:8080/test/card/1").openStream()){while(in.read()!=-1){}}
            s.get("SD_STATUS");List<MediaItem> front=s.files(0),rear=s.files(1);check(front.size()==1&&rear.size()==1&&front.get(0).camera!=rear.get(0).camera,"Lists both cameras");
            int downloadsBefore=Library.all(activity).length();runOnMainSync(()->{try{TransferService.enqueue(activity,front.get(0));}catch(Exception e){throw new RuntimeException(e);}});waitFor(()->Library.all(activity).length()>downloadsBefore,25000);
            JSONObject downloaded=Library.all(activity).getJSONObject(0);Uri download=Uri.parse(downloaded.getString("uri"));check(downloaded.getLong("size")==front.get(0).size,"Download size matches original after forced disconnect and Range retry");
            byte[] actual;try(InputStream in=activity.getContentResolver().openInputStream(download);ByteArrayOutputStream out=new ByteArrayOutputStream()){Io.copy(in,out);actual=out.toByteArray();}byte[] source;try(InputStream in=new URL("http://10.0.2.2:8080/storage/verification").openStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){Io.copy(in,out);source=out.toByteArray();}check(Arrays.equals(actual,source),"Downloaded bytes equal source bytes");
            try{s.get("TEST_UNSUPPORTED");throw new AssertionError("Missing firmware error");}catch(CtpClient.DeviceException e){check(e.code==6,"Firmware errors exposed with original error code");}
            try{s.put("FILES_DELETE",JieliSession.params("path_0","/never-delete"));throw new AssertionError("Deletion command escaped guard");}catch(SecurityException expected){check(true,"File deletion blocked before writing to socket");}
            try{s.put("FORMAT",null);throw new AssertionError("Format command escaped guard");}catch(SecurityException expected){check(true,"SD format blocked before writing to socket");}
            List<MediaItem> batch=new ArrayList<>();long fixtureSize=front.get(0).size;
            batch.add(new MediaItem(new JSONObject().put("f","/storage/sd0/C/DCIM/0/old.MOV").put("t","20200101000000").put("s",fixtureSize).put("y",1),0));
            batch.add(new MediaItem(new JSONObject().put("f","/storage/sd0/C/DCIM/0/EMR/new.MOV").put("t","20261019180000").put("s",fixtureSize).put("y",2),0));
            batch.add(new MediaItem(new JSONObject().put("f","/storage/sd0/C/DCIM/0/middle.MOV").put("t","20250101000000").put("s",fixtureSize).put("y",1),0));
            int batchBefore=Library.all(activity).length();runOnMainSync(()->{try{TransferService.enqueueBatch(activity,batch);}catch(Exception e){throw new RuntimeException(e);}});waitFor(()->Library.all(activity).length()==batchBefore+3,25000);
            JSONArray completed=Library.all(activity);check(completed.getJSONObject(0).getString("name").equals("old.MOV")&&completed.getJSONObject(2).getString("name").equals("new.MOV"),"Whole folder queue downloads newest first despite unsorted input");
            try(android.database.Cursor cursor=activity.getContentResolver().query(Uri.parse(completed.getJSONObject(2).getString("uri")),new String[]{android.provider.MediaStore.MediaColumns.RELATIVE_PATH},null,null,null)){check(cursor!=null&&cursor.moveToFirst()&&cursor.getString(0).contains("Закреплённые"),"Pinned recordings saved to a separate phone folder");}
            waitFor(()->!TransferService.active,3000);runOnMainSync(()->{try{TransferService.enqueueBatch(activity,batch);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(600);waitFor(()->!TransferService.active,5000);check(Library.all(activity).length()==batchBefore+3,"Repeated folder download skips existing complete originals");
            Diag.log("TEST","{\"pwd\":\"do-not-leak\"}");Diag.log("LONG TEST","BEGIN_FULL_LOG\n"+"0123456789".repeat(25000)+"\nEND_FULL_LOG");List<String> parts=Diag.copyParts(s.report());String full=String.join("",parts);check(parts.size()>1&&full.contains("BEGIN_FULL_LOG")&&full.contains("END_FULL_LOG")&&!full.contains("do-not-leak"),"Complete clipboard log splitting and password redaction");
            invoke("showLogs",new Class<?>[]{});invoke("copyLogs",new Class<?>[]{});final String[] clipboard={""};runOnMainSync(()->{android.content.ClipboardManager cb=activity.getSystemService(android.content.ClipboardManager.class);clipboard[0]=cb.getPrimaryClip().getItemAt(0).coerceToText(activity).toString();});check(clipboard[0].startsWith("Zem iBox Drive"),"Copy logs button writes diagnostic text to clipboard");
            int savedBeforeClear=Library.all(activity).length();invoke("clearLogs",new Class<?>[]{TextView.class},new TextView(activity));s.worker.submit(()->{}).get();waitFor(()->field("clipboardParts")==null,5000);Thread.sleep(400);String cleared=String.join("",Diag.copyParts(s.report()));check(!cleared.contains("BEGIN_FULL_LOG")&&!cleared.contains("END_FULL_LOG")&&cleared.contains("Logs cleared by user"),"Clear logs removes app and Android history without replaying old native logs");check(Library.all(activity).length()==savedBeforeClear,"Clearing diagnostic logs preserves downloaded media");
            Diag.log("AFTER CLEAR","new-log-entry");check(String.join("",Diag.copyParts(s.report())).contains("new-log-entry"),"Diagnostic logging continues after clearing");
            Intent chooser=MediaOpen.chooser(recorded,"video/mp4"),view=chooser.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class);check(Intent.ACTION_CHOOSER.equals(chooser.getAction())&&view!=null&&Intent.ACTION_VIEW.equals(view.getAction())&&recorded.equals(view.getData())&&view.getComponent()==null&&(view.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0&&view.getClipData()!=null,"Downloaded video opens external system chooser with readable content URI");Intent photoView=MediaOpen.chooser(recorded,"image/jpeg").getParcelableExtra(Intent.EXTRA_INTENT,Intent.class);check("image/jpeg".equals(photoView.getType()),"Downloaded photo chooser uses image MIME for galleries");
            Diag.setEnabled(false);File logs=new File(activity.getFilesDir(),"logs");Thread.sleep(100);Map<String,Long> sizes=logSizes(logs);Diag.log("DISABLED","must-not-be-written");Diag.error("DISABLED",new IOException("must-not-be-written"));s.get("VIDEO_MIC");Thread.sleep(200);check(!Diag.enabled()&&!activity.getSharedPreferences("diagnostics",0).getBoolean("enabled",true)&&logSizes(logs).equals(sizes),"Disabling logs persists preference and stops both app/native file growth");check(!String.join("",Diag.copyParts(s.report())).contains("must-not-be-written"),"Disabled logger does not retain new errors or commands");Diag.clear();check(logSizes(logs).isEmpty()&&!Diag.enabled(),"Clearing while logging disabled does not restart capture");Diag.setEnabled(true);Diag.log("RE-ENABLED","capture-resumed");check(String.join("",Diag.copyParts(s.report())).contains("capture-resumed"),"Logging can resume after being disabled and cleared");
            result.putString("stream","PASS: "+assertions+" Android integration assertions\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){Diag.error("INSTRUMENTATION",e);result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
    Object getPrivate(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    Map<String,Long> logSizes(File dir){Map<String,Long> result=new TreeMap<>();for(File f:Objects.requireNonNull(dir.listFiles()))result.put(f.getName(),f.length());return result;}
}
