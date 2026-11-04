package io.zem.ibox;

import android.app.*;
import android.content.*;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class UpdateChecks {
    private final Instrumentation test;private MainActivity activity;private int checks;
    UpdateChecks(Instrumentation test){this.test=test;}
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    interface Condition{boolean ok()throws Exception;}
    private void waitFor(Condition condition,int millis)throws Exception{long end=System.currentTimeMillis()+millis;while(System.currentTimeMillis()<end){if(condition.ok())return;Thread.sleep(70);}throw new AssertionError("Update condition timed out");}
    private Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private void main(Runnable action){test.runOnMainSync(action);}
    private void reject(Throwing action,String label)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException e){check(true,label);return;}throw new AssertionError(label);}
    interface Throwing{void run()throws Exception;}
    String run(boolean uiOnly)throws Exception{
        activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try(Fixture fixture=new Fixture(new File(activity.getApplicationInfo().sourceDir))){
            if(!uiOnly){
                check(UpdateRelease.version("0.0.99")<UpdateRelease.version("0.1.0"),"Version carry");check(UpdateRelease.version("1.0.0")>UpdateRelease.version("0.99.99"),"Major version carry");reject(()->UpdateRelease.version("0.100.0"),"Reject malformed version");
                JSONObject metadata=fixture.metadata();UpdateRelease selected=UpdateRelease.parse(metadata,new String[]{"x86_64","x86"},fixture.origin);check(selected.name.endsWith("-x86_64.apk"),"Select native architecture");
                JSONObject universal=fixture.metadata("universal");check(UpdateRelease.parse(universal,new String[]{"arm64-v8a"},fixture.origin).name.endsWith("-universal.apk"),"Universal fallback");
                check(selected.newerThan("0.0.17")&&!selected.newerThan(BuildConfig.VERSION_NAME),"Offer newer versions only");
                JSONObject bad=fixture.metadata();bad.put("prerelease",true);JSONObject pre=bad;reject(()->UpdateRelease.parse(pre,Build.SUPPORTED_ABIS,fixture.origin),"Reject prerelease");
                bad=fixture.metadata();bad.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://example.invalid/update.apk");JSONObject offsite=bad;reject(()->UpdateRelease.parse(offsite,Build.SUPPORTED_ABIS,fixture.origin),"Reject foreign APK URL");
                bad=fixture.metadata();bad.getJSONArray("assets").getJSONObject(0).put("digest","");JSONObject checksum=bad;reject(()->UpdateRelease.parse(checksum,Build.SUPPORTED_ABIS,fixture.origin),"Require release checksum");
                bad=fixture.metadata();bad.getJSONArray("assets").getJSONObject(0).put("size",Long.MAX_VALUE);JSONObject oversized=bad;reject(()->UpdateRelease.parse(oversized,Build.SUPPORTED_ABIS,fixture.origin),"Reject oversized package");
                Signature first=new Signature(new byte[]{1,2}),second=new Signature(new byte[]{3,4});check(AppUpdates.sameSigners(new Signature[]{first},new Signature[]{first})&&!AppUpdates.sameSigners(new Signature[]{first},new Signature[]{second})&&!AppUpdates.sameSigners(new Signature[0],new Signature[0]),"Reject missing or different signing certificates");
                AppUpdates.verify(activity,fixture.apk,selected,BuildConfig.VERSION_CODE-1);check(true,"Real APK certificate and package verification");
                reject(()->AppUpdates.verify(activity,fixture.apk,selected,BuildConfig.VERSION_CODE),"Reject a downgrade or same versionCode");
                File other=new File(test.getContext().getApplicationInfo().sourceDir);reject(()->AppUpdates.verify(activity,other,selected,0),"Reject another application");
                Intent intent=UpdateProvider.installer(activity);check("content".equals(intent.getData().getScheme())&&(intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0&&intent.getClipData()!=null,"Installer receives a granted content URI");
                reject(()->activity.getContentResolver().openFileDescriptor(UpdateProvider.uri(activity).buildUpon().appendPath("private.txt").build(),"r"),"Provider rejects unrelated files");
                // Intercept the installer; a test must not replace the running app.
                IntentFilter filter=new IntentFilter(Intent.ACTION_VIEW);filter.addDataType("application/vnd.android.package-archive");Instrumentation.ActivityMonitor monitor=test.addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);
                try{
                    Map<String,Long> timings=new HashMap<>();
                    for(String mode:new String[]{"good","noRanges","paced","pacedNoRanges","badHash","short","wrongRange","http403","badjson","slow","stalled"}){
                        fixture.mode=mode;fixture.maxActive.set(0);fixture.requests.set(0);AppUpdates updates=new AppUpdates(activity,()->{},fixture.origin+"/latest","0.0.17",BuildConfig.VERSION_CODE-1);
                        try{
                            main(updates::check);waitFor(()->!((Boolean)field(updates,"busy")),8000);
                            if(mode.equals("http403")||mode.equals("badjson")){check(updates.summary().contains("Не удалось"),"Metadata error is recoverable: "+mode);continue;}
                            long started=SystemClock.elapsedRealtime();main(updates::download);
                            if(mode.equals("slow")||mode.equals("stalled")){
                                main(updates::show);
                                if(mode.equals("slow"))waitFor(()->((Long)field(updates,"downloaded"))>0,8000);else waitFor(()->fixture.active.get()>0,8000);
                                AlertDialog download=(AlertDialog)field(updates,"dialog");Button cancel=download.getButton(AlertDialog.BUTTON_NEGATIVE);
                                check(cancel.isEnabled()&&cancel.getText().toString().equals("Отменить"),"A downloading update exposes Cancel: "+mode);
                                long clicked=SystemClock.elapsedRealtime();main(cancel::performClick);
                                check(SystemClock.elapsedRealtime()-clicked<700&&!((Boolean)field(updates,"busy"))&&download.isShowing(),"Cancellation is immediate and confirms the result: "+mode);
                                waitFor(()->fixture.active.get()==0&&!hasPartial(),2500);
                                check(!UpdateProvider.apk(activity).exists(),"Cancellation closes even a stalled socket and removes partial APK: "+mode);
                                if(mode.equals("slow")){fixture.mode="good";main(updates::download);waitFor(()->!((Boolean)field(updates,"busy")),25000);check((Boolean)field(updates,"ready"),"A new download succeeds immediately after cancellation without stale callbacks");}
                            }
                            else{waitFor(()->!((Boolean)field(updates,"busy")),25000);long elapsed=SystemClock.elapsedRealtime()-started;timings.put(mode,elapsed);boolean ready=(Boolean)field(updates,"ready");check(ready==Arrays.asList("good","noRanges","paced","pacedNoRanges").contains(mode),"Only a complete verified APK becomes installable: "+mode);
                                if(mode.equals("good"))check(fixture.maxActive.get()>=3,"APK ranges really download concurrently");
                                if(mode.equals("noRanges"))check(fixture.requests.get()==1,"A server ignoring Range receives only one full download");
                                if(mode.equals("pacedNoRanges"))check(timings.get("paced")<elapsed*.75+500,"Parallel ranges improve transfer time on a paced connection: "+timings.get("paced")+" vs "+elapsed+" ms");
                                if(ready){try(ParcelFileDescriptor fd=activity.getContentResolver().openFileDescriptor(UpdateProvider.uri(activity),"r")){check(fd!=null&&fd.getStatSize()==fixture.apk.length(),"Verified package can be read by the installer URI");}reject(()->activity.getContentResolver().openFileDescriptor(UpdateProvider.uri(activity),"rw"),"APK provider is read-only");
                                    Field saving=MainActivity.class.getDeclaredField("phoneSaving");saving.setAccessible(true);saving.setBoolean(activity,true);try{main(updates::install);check(updates.summary().startsWith("Дождитесь"),"Installation waits for phone recording finalization");}finally{saving.setBoolean(activity,false);}
                                    Field visible=MainActivity.class.getDeclaredField("visible");visible.setAccessible(true);visible.setBoolean(activity,false);try{main(updates::install);check(updates.summary().contains("Откройте приложение"),"A finished background download never launches the installer");}finally{visible.setBoolean(activity,true);}
                                    if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){AlertDialog permission=(AlertDialog)field(updates,"permissionDialog");check(permission!=null&&permission.isShowing(),"Unknown-source permission is explained before opening Android settings");}
                                }else check(!UpdateProvider.apk(activity).exists(),"A failed update removes the temporary package: "+mode);
                            }
                        }finally{main(updates::close);}
                    }
                }finally{test.removeMonitor(monitor);}
            }
            if(!uiOnly){AppUpdates actual=new AppUpdates(activity,()->{});try{main(actual::check);waitFor(()->!((Boolean)field(actual,"busy")),30000);check(!actual.summary().contains("Не удалось"),"Real public GitHub API is readable over HTTPS");}finally{main(actual::close);}}
            fixture.mode="good";AppUpdates ui=new AppUpdates(activity,()->{},fixture.origin+"/latest","0.0.17",BuildConfig.VERSION_CODE-1);
            try{main(ui::show);waitFor(()->!((Boolean)field(ui,"busy")),8000);AlertDialog dialog=(AlertDialog)field(ui,"dialog");check(dialog.isShowing()&&dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().equals("Скачать и установить"),"Update UI offers an explicit download action");
                test.waitForIdleSync();Thread.sleep(300);for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){Button button=dialog.getButton(which);android.graphics.Rect rect=new android.graphics.Rect();check(button.getGlobalVisibleRect(rect)&&rect.height()>=button.getHeight()-2&&rect.width()>=button.getWidth()-2,"Update dialog action is fully visible: "+which);}
                File folder=new File(activity.getExternalFilesDir(null),"ui-audit");folder.mkdirs();android.graphics.Bitmap image=test.getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(folder,"app-update.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
                fixture.mode="slow";main(ui::download);waitFor(()->((Long)field(ui,"downloaded"))>0,8000);test.waitForIdleSync();Thread.sleep(400);test.waitForIdleSync();
                Button cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);android.graphics.Rect rect=new android.graphics.Rect();check(cancel.getText().toString().equals("Отменить")&&cancel.isEnabled()&&cancel.getGlobalVisibleRect(rect)&&rect.height()>=cancel.getHeight()-2,"Cancel stays visible and enabled while downloading");
                TextView text=(TextView)field(ui,"message");check(text.getText().toString().contains("/с")&&text.getText().toString().contains("%"),"Download shows bytes, percentage and transfer speed");
                image=test.getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(folder,"app-update-download.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            }finally{main(ui::close);}
        }finally{main(activity::finish);}
        return "PASS: "+checks+" update assertions on API "+Build.VERSION.SDK_INT+"\n";
    }
    private boolean hasPartial(){File parent=UpdateProvider.apk(activity).getParentFile();File[] folders=parent.listFiles();if(folders!=null)for(File folder:folders)if(new File(folder,"package.part").exists())return true;return false;}
    private static final class Fixture implements AutoCloseable{
        final File apk;final ServerSocket server;final String origin,sha;volatile String mode="good";volatile boolean closed;
        final AtomicInteger active=new AtomicInteger(),maxActive=new AtomicInteger(),requests=new AtomicInteger();
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        Fixture(File file)throws Exception{apk=file;MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}sha=Io.hex(hash.digest());server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));origin="http://127.0.0.1:"+server.getLocalPort();Thread thread=new Thread(()->{while(!closed)try{Socket socket=server.accept();sockets.add(socket);Thread request=new Thread(()->serve(socket));request.setDaemon(true);request.start();}catch(IOException e){if(!closed)throw new RuntimeException(e);}});thread.setDaemon(true);thread.start();}
        JSONObject metadata()throws Exception{return metadata("x86_64");}
        JSONObject metadata(String abi)throws Exception{String name="Zem-iBox-"+BuildConfig.VERSION_NAME+"-"+abi+".apk";return new JSONObject().put("tag_name","v"+BuildConfig.VERSION_NAME).put("draft",false).put("prerelease",false).put("assets",new JSONArray().put(new JSONObject().put("name",name).put("browser_download_url",origin+"/"+name).put("size",apk.length()).put("digest","sha256:"+(mode.equals("badHash")?"0".repeat(64):sha))));}
        private void serve(Socket socket){boolean transfer=false;String current=mode;try(socket){
            socket.setSoTimeout(10000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String request=reader.readLine();if(request==null)return;
            String path=request.split(" ")[1],line,range="";while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("range:"))range=line.substring(6).trim();
            OutputStream out=socket.getOutputStream();
            if(path.equals("/latest")){byte[] data=(current.equals("badjson")?"invalid json":metadata().toString()).getBytes(StandardCharsets.UTF_8);String code=current.equals("http403")?"403 Forbidden":"200 OK";out.write(("HTTP/1.1 "+code+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);return;}
            transfer=true;requests.incrementAndGet();maxActive.accumulateAndGet(active.incrementAndGet(),Math::max);
            long from=0,to=apk.length()-1;boolean ranged=!range.isEmpty()&&!current.equals("noRanges")&&!current.equals("pacedNoRanges");
            if(ranged){String[] positions=range.substring("bytes=".length()).split("-");from=Long.parseLong(positions[0]);to=Long.parseLong(positions[1]);}
            String headers="HTTP/1.1 "+(ranged?"206 Partial Content":"200 OK")+"\r\nContent-Length: "+(to-from+1)+"\r\nConnection: close\r\n";
            if(ranged)headers+="Content-Range: bytes "+(current.equals("wrongRange")?from+1:from)+"-"+to+"/"+apk.length()+"\r\n";
            out.write((headers+"\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
            if(current.equals("stalled")){while(socket.getInputStream().read()!=-1){}return;}
            try(RandomAccessFile in=new RandomAccessFile(apk,"r")){in.seek(from);byte[] bytes=new byte[32768];long left=to-from+1;while(left>0){int n=in.read(bytes,0,(int)Math.min(left,bytes.length));if(n==-1)break;out.write(bytes,0,n);out.flush();left-=n;if(current.equals("short"))break;if(current.equals("slow"))Thread.sleep(55);else if(current.startsWith("paced"))Thread.sleep(12);}}
        }catch(Exception ignored){}finally{if(transfer)active.decrementAndGet();sockets.remove(socket);}}
        public void close()throws IOException{closed=true;server.close();for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}}
    }
}
