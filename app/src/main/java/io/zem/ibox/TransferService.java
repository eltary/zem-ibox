package io.zem.ibox;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@android.annotation.SuppressLint("NotificationPermission")
public final class TransferService extends Service {
    public static volatile String progress="";
    public static volatile boolean active;
    public static volatile boolean cancel;
    private final ExecutorService queue=Executors.newSingleThreadExecutor();
    private final AtomicInteger count=new AtomicInteger();
    private NotificationManager nm;
    private static volatile TransferService current;
    private volatile HttpURLConnection connection;private volatile Thread transferThread;private int latestStartId;
    public static void requestCancel(){cancel=true;TransferService service=current;if(service!=null)service.abortRead();}
    private void abortRead(){Thread task=transferThread;if(task!=null)task.interrupt();HttpURLConnection http=connection;if(http!=null)new Thread(http::disconnect,"Cancel download").start();}
    private volatile int batchTotal,batchFinished,batchFailed;
    public static void enqueue(Context c,MediaItem item)throws JSONException,IOException {
        if(c.checkSelfPermission("android.permission.INTERNET")!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("В тесте C нет разрешений; скачивание недоступно");
        JieliSession source=requireSource();Intent intent=new Intent(c,TransferService.class).putExtra("sourceDevice",deviceKey(source)).putExtra("item",item.json().toString());c.startForegroundService(intent);
    }
    public static void enqueueBatch(Context c,List<MediaItem> source)throws Exception {
        if(c.checkSelfPermission("android.permission.INTERNET")!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("В тесте C нет разрешений; скачивание недоступно");
        requireSource();List<MediaItem> sorted=new ArrayList<>(source);sorted.sort(MediaItem.newestFirst());JSONArray items=new JSONArray();for(MediaItem item:sorted)items.put(item.json());
        JieliSession s=RoadApp.instance.session;JSONObject manifest=new JSONObject().put("device",deviceKey(s)).put("items",items);
        File dir=new File(c.getFilesDir(),"batches");dir.mkdirs();File file=File.createTempFile("batch-",".json",dir);
        try(FileOutputStream out=new FileOutputStream(file)){out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));}
        c.startForegroundService(new Intent(c,TransferService.class).putExtra("batch",file.getName()));
    }
    private static JieliSession requireSource()throws IOException{JieliSession s=RoadApp.instance.session;if(!s.connected())throw new IOException("Сначала подключите видеорегистратор");if(!s.cardPresent())throw new IOException(s.cardKnown()?"В видеорегистраторе нет microSD":"Состояние microSD ещё не получено");return s;}
    private static String deviceKey(JieliSession s){return s.deviceIdentity();}
    public void onCreate(){super.onCreate();current=this;nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("downloads","Скачивание записей",NotificationManager.IMPORTANCE_LOW));}
    public synchronized int onStartCommand(Intent intent,int flags,int id){
        latestStartId=id;
        if(checkSelfPermission("android.permission.INTERNET")!=android.content.pm.PackageManager.PERMISSION_GRANTED){stopSelfResult(id);return START_NOT_STICKY;}
        if(intent!=null&&"cancel".equals(intent.getAction())){requestCancel();if(count.get()==0){active=false;nm.cancel(15);nm.cancel(16);stopSelfResult(id);}return START_NOT_STICKY;}
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        try{
            List<MediaItem> items=new ArrayList<>();String sourceDevice=intent.getStringExtra("sourceDevice");if(sourceDevice==null)sourceDevice=deviceKey(RoadApp.instance.session);
            if(intent.hasExtra("batch")){
                File dir=new File(getFilesDir(),"batches").getCanonicalFile(),file=new File(dir,intent.getStringExtra("batch")).getCanonicalFile();if(!file.getParentFile().equals(dir))throw new IOException("Неверный манифест очереди");
                JSONObject manifest=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));sourceDevice=manifest.getString("device");JSONArray a=manifest.getJSONArray("items");for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);items.add(new MediaItem(j,j.optInt("c")));}file.delete();items.sort(MediaItem.newestFirst());
            }else{JSONObject j=new JSONObject(intent.getStringExtra("item"));items.add(new MediaItem(j,j.optInt("c")));}
            if(items.isEmpty()){stopSelfResult(id);return START_NOT_STICKY;}
            if(cancel&&count.get()>0){progress="Остановка скачивания… Дождитесь завершения перед новой загрузкой";RoadApp.instance.session.changed();return START_NOT_STICKY;}
            if(count.get()==0){nm.cancel(16);cancel=false;batchTotal=0;batchFinished=0;batchFailed=0;}
            batchTotal+=items.size();count.addAndGet(items.size());
            active=true;show("Очередь: "+batchTotal+" · от новых к старым",0,0);startForeground(15,notification("Скачивание папки · от новых к старым",0,0));
            String origin=sourceDevice;
            for(MediaItem item:items){
            queue.execute(()->{
                PowerManager.WakeLock wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Zem iBox:download");wake.acquire(6*60*60*1000L);
                transferThread=Thread.currentThread();
                try{if(cancel)return;if(!origin.equals(deviceKey(RoadApp.instance.session)))throw new IOException("Подключён другой регистратор. Вернись к исходной сети");Diag.log("QUEUE ITEM",(batchFinished+1)+"/"+batchTotal+" "+item.time+" "+item.path);download(item,origin);}
                catch(Exception e){if(cancel){Diag.log("DOWNLOAD","Cancelled; partial copy retained");}else{batchFailed++;Diag.error("Download "+item.path,e);if(!RoadApp.instance.session.connected()||!RoadApp.instance.session.cardPresent()||!origin.equals(deviceKey(RoadApp.instance.session)))cancel=true;progress=e.getMessage()+". Частичная копия сохранена; повторите скачивание для продолжения.";}RoadApp.instance.session.changed();}
                finally{transferThread=null;Thread.interrupted();if(wake.isHeld())wake.release();synchronized(TransferService.this){batchFinished++;if(count.decrementAndGet()==0){active=false;if(cancel)progress="Загрузка отменена. Частичная копия сохранена для продолжения";else if(batchTotal>1)progress=batchFailed>0?"Очередь завершена · ошибок: "+batchFailed+". Повторите скачивание папки":"Папка скачана · файлов: "+batchTotal;Diag.log("QUEUE COMPLETE",progress);stopForeground(STOP_FOREGROUND_REMOVE);nm.cancel(15);if(checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED)nm.notify(16,terminalNotification(progress));stopSelfResult(latestStartId);RoadApp.instance.session.changed();}}}

            });
            }
        }catch(Exception e){Diag.error("Enqueue download",e);stopSelf();}
        return START_NOT_STICKY;
    }
    private void download(MediaItem item,String origin)throws Exception {
        JieliSession session=requireSource();final CtpClient owner=session.ctp;if(!origin.equals(deviceKey(session)))throw new IOException("Подключён другой видеорегистратор");
        String identity=deviceKey(session)+"\n"+item.path+"\n"+item.time+"\n"+item.size+"\nlocked="+item.locked();
        String key=Io.hex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        if(Library.contains(this,key)){progress="Уже сохранено: "+item.name;Diag.log("QUEUE SKIP",item.path);RoadApp.instance.session.changed();return;}
        File dir=new File(getFilesDir(),"partial");dir.mkdirs();File part=new File(dir,key+".part");
        long expected=item.size;
        Exception last=null;
        for(int attempt=0;attempt<3;attempt++){
            if(cancel)throw new InterruptedIOException("Загрузка отменена");
            HttpURLConnection conn=null;
            try {
                long offset=part.length();if(expected>0&&offset>expected){try(RandomAccessFile reset=new RandomAccessFile(part,"rw")){reset.setLength(0);}offset=0;}
                if(!session.isCurrent(owner)||!session.cardPresent())throw new IOException("Подключение или microSD недоступны. Повторите скачивание после подключения");conn=session.http(owner,item.path);conn.setReadTimeout(1500);connection=conn;if(cancel)throw new InterruptedIOException("Загрузка отменена");if(offset>0){conn.setRequestProperty("Range","bytes="+offset+"-");Diag.log("DOWNLOAD","Resuming from "+offset);}
                int code=conn.getResponseCode();Diag.log("DOWNLOAD HTTP",code+" "+conn.getHeaderFields());
                if(code!=200&&code!=206)throw new IOException("HTTP "+code+" при скачивании");
                long length=conn.getContentLengthLong();
                if(code==200){offset=0;if(length>0){if(expected>0&&expected!=length)throw new IOException("Размер файла на регистраторе изменился. Обнови список");expected=length;}}
                else {
                    String range=conn.getHeaderField("Content-Range");
                    if(range==null||!range.startsWith("bytes "+offset+"-"))throw new IOException("Регистратор прислал неверное смещение Range");
                    String total=range.substring(range.lastIndexOf('/')+1);if(!total.equals("*")){long n=Long.parseLong(total);if(expected>0&&expected!=n)throw new IOException("Размер исходного файла изменился");expected=n;}
                }
                if(expected>0&&expected-offset>dir.getUsableSpace()-10*1024*1024)throw new IOException("Недостаточно места на телефоне");
                long done=offset,lastUi=0;
                try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(part,offset>0)){
                    byte[] buffer=new byte[128*1024];int n;
                    while((n=in.read(buffer))!=-1){
                        if(cancel)throw new InterruptedIOException("Загрузка отменена");
                        if(!session.isCurrent(owner)||!session.cardPresent())throw new IOException("Подключение или microSD недоступны. Частичная копия сохранена");
                        if(expected>0&&done+n>expected)throw new IOException("Получено больше данных, чем размер файла");
                        out.write(buffer,0,n);done+=n;
                        if(System.currentTimeMillis()-lastUi>500){lastUi=System.currentTimeMillis();show(item.name+" · "+bytes(done)+" / "+bytes(expected),done,expected);}
                    }
                    out.getFD().sync();
                }
                if(!session.isCurrent(owner)||!session.cardPresent())throw new IOException("Подключение или microSD недоступны. Частичная копия сохранена");
                if(cancel)throw new InterruptedIOException("Загрузка отменена");
                if(expected>0&&part.length()!=expected)throw new EOFException("Неполный файл: "+part.length()+" / "+expected);
                if(part.length()==0)throw new IOException("Получен пустой файл");
                last=null;break;
            }catch(Exception e){last=e;if(!cancel)Diag.log("DOWNLOAD","Attempt "+(attempt+1)+": "+e.getClass().getSimpleName()+" "+e.getMessage());if(cancel||!session.isCurrent(owner)||!session.cardPresent())throw e;if(attempt<2)Thread.sleep((attempt+1)*1000L);}
            finally {if(connection==conn)connection=null;if(conn!=null)conn.disconnect();}
        }
        if(last!=null)throw last;
        String hash;try(InputStream in=new FileInputStream(part)){MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){if(cancel)throw new InterruptedIOException("Загрузка отменена");md.update(b,0,n);}hash=Io.hex(md.digest());}
        Diag.log("DOWNLOAD COMPLETE","source="+item.path+" bytes="+part.length()+" local-sha256="+hash);
        if(cancel)throw new InterruptedIOException("Загрузка отменена");
        Uri uri=Library.publish(this,part,item.name,item.mime(),(item.camera==0?"Front/":"Rear/")+(item.locked()?"Закреплённые":"Обычные"),()->cancel);
        Library.add(this,uri,item.name,item.mime(),part.length(),item.path,key,item.date());part.delete();progress="Сохранено: "+item.name;RoadApp.instance.session.changed();
    }
    static String bytes(long n){if(n<=0)return "—";if(n<1024)return n+" Б";if(n<1048576)return String.format(Locale.getDefault(),"%.0f КБ",n/1024.0);if(n<1073741824)return String.format(Locale.getDefault(),"%.1f МБ",n/1048576.0);return String.format(Locale.getDefault(),"%.1f ГБ",n/1073741824.0);}
    private Notification notification(String text,long done,long total){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,TransferService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,"downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Zem iBox").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active).addAction(new Notification.Action.Builder(null,"Отменить",stop).build());
        if(total>0)b.setProgress(100,(int)Math.min(100,done*100/total),false);else if(active)b.setProgress(0,0,true);return b.build();
    }
    private Notification terminalNotification(String text){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"downloads").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Zem iBox").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setOngoing(false).build();}
    private void show(String text,long done,long total){if(cancel)return;progress=(batchTotal>1?(batchFinished+1)+"/"+batchTotal+" · ":"")+text;if(checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED)nm.notify(15,notification(progress,done,total));RoadApp.instance.session.changed();}
    public void onTimeout(int startId,int fgsType){Diag.log("DOWNLOAD","Android dataSync timeout");requestCancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    public void onDestroy(){if(current==this){current=null;if(count.get()>0){cancel=true;abortRead();}}queue.shutdown();super.onDestroy();}
    public IBinder onBind(Intent intent){return null;}
}
