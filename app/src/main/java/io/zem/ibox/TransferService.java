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

@android.annotation.SuppressLint("NotificationPermission")
public final class TransferService extends Service {
    public static volatile String progress="";public static volatile boolean active,cancel;
    private final ExecutorService queue=Executors.newSingleThreadExecutor();
    private NotificationManager nm;private static volatile TransferService current;
    private volatile HttpURLConnection connection;private volatile Thread transferThread;
    private volatile String currentId="";private volatile boolean stopItem;private boolean pumping,restartRequested;private final Set<String> revalidate=ConcurrentHashMap.newKeySet();private final Map<Integer,List<MediaItem>> catalogue=new HashMap<>();private long catalogueAt;private CtpClient catalogueOwner;private int latestStartId;
    public static void requestCancel(){cancel=true;TransferService service=current;if(service!=null){DownloadQueue.get(service).cancelPending();service.abortRead();}}
    private void abortRead(){Thread task=transferThread;if(task!=null)task.interrupt();HttpURLConnection http=connection;if(http!=null)new Thread(http::disconnect,"Cancel download").start();}
    private boolean stopping(){return cancel||stopItem||Thread.currentThread().isInterrupted();}
    public static void enqueue(Context c,MediaItem item)throws JSONException,IOException{try{enqueueBatch(c,Collections.singletonList(item));}catch(JSONException|IOException e){throw e;}catch(Exception e){throw new IOException(e);}}
    public static void enqueueBatch(Context c,List<MediaItem> items)throws Exception{
        if(c.checkSelfPermission("android.permission.INTERNET")!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Нет разрешения на доступ к сети");JieliSession source=requireSource();if(items.isEmpty())return;DownloadQueue.get(c).enqueue(deviceKey(source),items);start(c,new Intent(c,TransferService.class));
    }
    static void control(Context c,String id,String action){start(c,new Intent(c,TransferService.class).setAction(action).putExtra("id",id));}
    private static void start(Context c,Intent intent){active=true;try{if(Build.VERSION.SDK_INT>=26)c.startForegroundService(intent);else c.startService(intent);}catch(RuntimeException e){active=false;throw e;}}
    private static JieliSession requireSource()throws IOException{JieliSession s=RoadApp.instance.session;if(!s.connected())throw new IOException("Сначала подключите видеорегистратор");if(!s.cardPresent())throw new IOException(s.cardKnown()?"В видеорегистраторе нет microSD":"Состояние microSD ещё не получено");return s;}
    static String deviceKey(JieliSession s){long version=s.cardContentVersion();return s.deviceIdentity()+(version==0?"":"|card:"+version);}
    public void onCreate(){super.onCreate();current=this;DownloadQueue.get(this).recover();nm=getSystemService(NotificationManager.class);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("downloads","Скачивание записей",NotificationManager.IMPORTANCE_LOW));}
    public synchronized int onStartCommand(Intent intent,int flags,int id){
        latestStartId=id;if(intent==null){stopSelf();return START_NOT_STICKY;}
        startForeground(15,notification("Подготавливаем очередь…",0,0));
        DownloadQueue jobs=DownloadQueue.get(this);String action=intent.getAction(),jobId=intent.getStringExtra("id");
        if("cancel".equals(action))requestCancel();
        else if(jobId!=null){DownloadQueue.Job job=jobs.find(jobId);if(job!=null){
            if("resume".equals(action)){revalidate.add(jobId);catalogueAt=0;if(job.source().equals(deviceKey(RoadApp.instance.session))&&RoadApp.instance.session.connected()&&RoadApp.instance.session.cardPresent())jobs.state(jobId,"queued","");else jobs.state(jobId,"paused","Подключите исходный регистратор с той же microSD");}
            else if(("pause".equals(action)||"cancelItem".equals(action))&&!job.state().equals("done")){jobs.state(jobId,action.equals("pause")?"paused":"cancelled","");if(jobId.equals(currentId)){stopItem=true;abortRead();}}
        }}
        if(intent.getAction()==null||"resume".equals(intent.getAction()))restartRequested=true;if(!pumping){cancel=false;restartRequested=false;pumping=true;active=true;queue.execute(this::pump);}RoadApp.instance.session.changed();return START_NOT_STICKY;
    }
    private void pump(){
        int completed=0,failed=0;
        try{while(!cancel){JieliSession source=RoadApp.instance.session;if(!source.connected()||!source.cardPresent())break;DownloadQueue.Job job=DownloadQueue.get(this).next(deviceKey(source));if(job==null)break;
            currentId=job.id();stopItem=false;transferThread=Thread.currentThread();DownloadQueue.get(this).state(job.id(),"running","");
            PowerManager.WakeLock wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Zem iBox:download");wake.acquire(6*60*60*1000L);
            try{Diag.log("QUEUE ITEM",job.item().time+" "+job.item().path);show(job.item().name,job.done(),job.total());download(job.item(),job.source());DownloadQueue.get(this).state(job.id(),"done","");completed++;}
            catch(Exception e){DownloadQueue.Job latest=DownloadQueue.get(this).find(job.id());if(latest!=null&&latest.state().equals("running")){String state=stopping()?"cancelled":"error";DownloadQueue.get(this).state(job.id(),state,stopping()?"":e.getMessage());if(!stopping())failed++;}if(!stopping())Diag.error("Download "+job.item().path,e);}
            finally{transferThread=null;Thread.interrupted();if(wake.isHeld())wake.release();currentId="";stopItem=false;RoadApp.instance.session.changed();}
        }}finally{synchronized(this){pumping=false;JieliSession source=RoadApp.instance.session;if(restartRequested&&source.connected()&&source.cardPresent()&&DownloadQueue.get(this).next(deviceKey(source))!=null){restartRequested=false;cancel=false;pumping=true;queue.execute(this::pump);return;}active=false;progress=cancel?"Загрузка отменена. Частичная копия сохранена для продолжения":failed>0?"Не удалось скачать файлов: "+failed+". Откройте загрузки для повтора":completed>0?"Сохранено файлов: "+completed:"Очередь остановлена. Продолжить можно в загрузках";stopForeground(STOP_FOREGROUND_REMOVE);nm.cancel(15);if(Build.VERSION.SDK_INT<33||checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED)nm.notify(16,terminalNotification(progress));stopSelfResult(latestStartId);RoadApp.instance.session.changed();}}
    }
    private void download(MediaItem item,String origin)throws Exception {
        JieliSession session=requireSource();final CtpClient owner=session.ctp;if(!origin.equals(deviceKey(session)))throw new IOException("Подключён другой видеорегистратор");
        String identity=deviceKey(session)+"\n"+item.path+"\n"+item.time+"\n"+item.size+"\nlocked="+item.locked();
        String key=DownloadQueue.identity(origin,item);
        if(Library.contains(this,key)){progress="Уже сохранено: "+item.name;DownloadQueue.get(this).progress(currentId,item.size,item.size);Diag.log("QUEUE SKIP",item.path);RoadApp.instance.session.changed();return;}
        File dir=new File(getFilesDir(),"partial");dir.mkdirs();File part=new File(dir,key+".part");
        boolean forced=revalidate.remove(currentId);if(part.length()>0||forced||DownloadQueue.get(this).stale(currentId)){if(forced||catalogueAt==0||catalogueOwner!=owner||SystemClock.elapsedRealtime()-catalogueAt>30000||!catalogue.containsKey(item.camera)){if(forced||catalogueAt==0||catalogueOwner!=owner||SystemClock.elapsedRealtime()-catalogueAt>30000)catalogue.clear();catalogue.put(item.camera,session.files(owner,item.camera));catalogueOwner=owner;catalogueAt=SystemClock.elapsedRealtime();}boolean same=false;for(MediaItem current:catalogue.get(item.camera))if(current.path.equals(item.path)&&current.time.equals(item.time)&&(item.size<=0||current.size==item.size)&&current.locked()==item.locked()){same=true;break;}if(!same)throw new IOException("Оригинал изменился или больше не найден. Обновите архив и выберите запись заново");}
        long expected=item.size;
        Exception last=null;
        for(int attempt=0;attempt<3;attempt++){
            if(stopping())throw new InterruptedIOException("Загрузка отменена");
            HttpURLConnection conn=null;
            try {
                long offset=part.length();if(expected>0&&offset>expected){try(RandomAccessFile reset=new RandomAccessFile(part,"rw")){reset.setLength(0);}offset=0;}
                if(!session.isCurrent(owner)||!session.cardPresent())throw new IOException("Подключение или microSD недоступны. Повторите скачивание после подключения");conn=session.http(owner,item.path);conn.setReadTimeout(1500);connection=conn;if(stopping())throw new InterruptedIOException("Загрузка отменена");if(offset>0){conn.setRequestProperty("Range","bytes="+offset+"-");Diag.log("DOWNLOAD","Resuming from "+offset);}
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
                        if(stopping())throw new InterruptedIOException("Загрузка отменена");
                        if(!session.isCurrent(owner)||!session.cardPresent())throw new IOException("Подключение или microSD недоступны. Частичная копия сохранена");
                        if(expected>0&&done+n>expected)throw new IOException("Получено больше данных, чем размер файла");
                        out.write(buffer,0,n);done+=n;
                        if(System.currentTimeMillis()-lastUi>500){lastUi=System.currentTimeMillis();DownloadQueue.get(this).progress(currentId,done,expected);show(item.name+" · "+bytes(done)+" / "+bytes(expected),done,expected);}
                    }
                    out.getFD().sync();
                }
                if(!session.isCurrent(owner)||!session.cardPresent())throw new IOException("Подключение или microSD недоступны. Частичная копия сохранена");
                if(stopping())throw new InterruptedIOException("Загрузка отменена");
                if(expected>0&&part.length()!=expected)throw new EOFException("Неполный файл: "+part.length()+" / "+expected);
                if(part.length()==0)throw new IOException("Получен пустой файл");
                last=null;break;
            }catch(Exception e){last=e;if(!stopping())Diag.log("DOWNLOAD","Attempt "+(attempt+1)+": "+e.getClass().getSimpleName()+" "+e.getMessage());if(stopping()||!session.isCurrent(owner)||!session.cardPresent())throw e;if(attempt<2)Thread.sleep((attempt+1)*1000L);}
            finally {if(connection==conn)connection=null;if(conn!=null)conn.disconnect();}
        }
        if(last!=null)throw last;
        String hash;try(InputStream in=new FileInputStream(part)){MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){if(stopping())throw new InterruptedIOException("Загрузка отменена");md.update(b,0,n);}hash=Io.hex(md.digest());}
        Diag.log("DOWNLOAD COMPLETE","source="+item.path+" bytes="+part.length()+" local-sha256="+hash);
        if(stopping())throw new InterruptedIOException("Загрузка отменена");
        Uri uri=Library.publish(this,part,item.name,item.mime(),(item.camera==0?"Front/":"Rear/")+(item.locked()?"Закреплённые":"Обычные"),this::stopping);
        Library.add(this,uri,item.name,item.mime(),part.length(),item.path,key,item.date());part.delete();DownloadQueue.get(this).progress(currentId,expected,expected);progress="Сохранено: "+item.name;RoadApp.instance.session.changed();
    }
    static String bytes(long n){if(n<=0)return "—";if(n<1024)return n+" Б";if(n<1048576)return String.format(Locale.getDefault(),"%.0f КБ",n/1024.0);if(n<1073741824)return String.format(Locale.getDefault(),"%.1f МБ",n/1048576.0);return String.format(Locale.getDefault(),"%.1f ГБ",n/1073741824.0);}
    private Notification.Builder notificationBuilder(){return Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"downloads"):new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);}
    private Notification notification(String text,long done,long total){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,DownloadsActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,TransferService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=notificationBuilder().setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Zem iBox").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active).addAction(new Notification.Action.Builder(null,"Отменить",stop).build());
        if(total>0)b.setProgress(100,(int)Math.min(100,done*100/total),false);else if(active)b.setProgress(0,0,true);return b.build();
    }
    private Notification terminalNotification(String text){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,DownloadsActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return notificationBuilder().setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Zem iBox").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setOngoing(false).build();}
    private void show(String text,long done,long total){if(cancel)return;progress=text;if(Build.VERSION.SDK_INT<33||checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED)nm.notify(15,notification(progress,done,total));RoadApp.instance.session.changed();}
    public void onTimeout(int startId,int fgsType){Diag.log("DOWNLOAD","Android dataSync timeout");requestCancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    public void onDestroy(){if(current==this){current=null;if(pumping){stopItem=true;abortRead();DownloadQueue.get(this).recover();}}queue.shutdown();super.onDestroy();}
    public IBinder onBind(Intent intent){return null;}
}
