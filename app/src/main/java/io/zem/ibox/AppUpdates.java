package io.zem.ibox;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

final class AppUpdates implements AutoCloseable {
    static final int INSTALL_PERMISSION=64;
    private final Activity activity;private final Runnable changed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;private final String endpoint,testOrigin,installedVersion;private final long installedCode;
    private volatile Task task;private boolean closed,busy,ready,downloading;private long attemptedAt,downloadStarted;
    private UpdateRelease release;private String status="";private long downloaded;
    private AlertDialog dialog,permissionDialog;private TextView message;private ProgressBar progress;
    private static final class Task {volatile boolean cancelled,halted;volatile Thread runner;final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();long lastProgress,received;}
    AppUpdates(Activity a,Runnable onChange){this(a,onChange,UpdateRelease.API,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE);}
    AppUpdates(Activity a,Runnable onChange,String endpoint,String installed,long code){
        activity=a;changed=onChange;this.endpoint=endpoint;installedVersion=installed;installedCode=code;prefs=a.getSharedPreferences("updates",0);
        if(!endpoint.equals(UpdateRelease.API)){
            try{URI u=new URI(endpoint);if(!BuildConfig.DEBUG||!"http".equals(u.getScheme())||!Arrays.asList("127.0.0.1","10.0.2.2").contains(u.getHost())||u.getUserInfo()!=null)throw new IllegalArgumentException("Test endpoint is unavailable");testOrigin=u.getScheme()+"://"+u.getRawAuthority();}catch(URISyntaxException e){throw new IllegalArgumentException(e);}
        }else testOrigin="";
        if(testOrigin.isEmpty())try{String cached=prefs.getString("release","");if(!cached.isEmpty())release=UpdateRelease.parse(new JSONObject(cached),Build.SUPPORTED_ABIS,"");}catch(Exception ignored){}
    }
    String summary(){if(!status.isEmpty())return status;try{if(release!=null)return release.newerThan(installedVersion)?"Доступна версия "+release.version:"Установлена последняя версия";}catch(IOException ignored){}return "Установлена версия "+installedVersion;}
    void autoCheck(){if(BuildConfig.DEBUG||busy||closed)return;long now=System.currentTimeMillis();if(now-prefs.getLong("checkedAt",0)>86400000L&&(attemptedAt==0||SystemClock.elapsedRealtime()-attemptedAt>1800000L))check();}
    void show(){
        if(dialog!=null&&dialog.isShowing()){dialog.dismiss();return;}
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);int pad=Ui.dp(activity,20);content.setPadding(pad,pad/2,pad,pad/2);
        message=new TextView(activity);message.setTextColor(Ui.INK);message.setTextSize(15);content.addView(message);progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);content.addView(progress,new LinearLayout.LayoutParams(-1,Ui.dp(activity,12)));
        ScrollView scroll=Dialogs.body(activity);scroll.addView(content);dialog=Dialogs.builder(activity).setTitle("Обновление приложения").setView(scroll).setPositiveButton("Проверить",null).setNegativeButton("Закрыть",null).setNeutralButton("На GitHub",(d,w)->openRelease()).create();
        dialog.setOnCancelListener(d->cancel());dialog.show();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{if(busy)cancel();else dialog.dismiss();});dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(ready)install();else if(available())download();else check();});render();if(!busy&&!ready)check();
    }
    private boolean available(){try{return release!=null&&release.newerThan(installedVersion);}catch(IOException e){return false;}}
    private void render(){
        changed.run();if(dialog==null||!dialog.isShowing())return;
        String text=busy?summary():"Установлена версия "+installedVersion+"\n\n"+summary();
        if(downloading&&release!=null){
            int percent=(int)Math.min(100,downloaded*100/release.size);
            text+="\n"+(downloaded==0?"0 Б":TransferService.bytes(downloaded))+" из "+TransferService.bytes(release.size)+" · "+percent+"%";
            long elapsed=SystemClock.elapsedRealtime()-downloadStarted;
            if(downloaded>0&&elapsed>0){long speed=downloaded*1000/elapsed;text+="\n"+TransferService.bytes(speed)+"/с";if(speed>0&&downloaded<release.size){long seconds=(release.size-downloaded+speed-1)/speed;text+=" · ещё примерно "+(seconds<60?seconds+" с":(seconds+59)/60+" мин");}}
            else text+="\nПодключаемся к серверу…";
        }else if(available()&&!busy&&!ready)text+="\nРазмер: "+TransferService.bytes(release.size)+"\n\nСкачивание начнётся после нажатия. Установку подтвердит Android.";
        message.setText(text);progress.setVisibility(busy?android.view.View.VISIBLE:android.view.View.GONE);progress.setIndeterminate(busy&&!downloading);if(release!=null)progress.setProgress((int)Math.min(100,downloaded*100/release.size));
        Button action=dialog.getButton(AlertDialog.BUTTON_POSITIVE);action.setText(ready?"Установить":available()?"Скачать и установить":"Проверить ещё раз");action.setEnabled(!busy);action.setVisibility(busy?android.view.View.GONE:android.view.View.VISIBLE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(busy?"Отменить":"Закрыть");dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(busy?android.view.View.GONE:android.view.View.VISIBLE);
    }

    void check(){
        if(closed||busy)return;ready=false;busy=true;downloading=false;status="Проверяем последний релиз GitHub…";attemptedAt=SystemClock.elapsedRealtime();downloaded=0;Task job=new Task();task=job;render();
        worker.execute(()->{job.runner=Thread.currentThread();try{
            String data=new String(read(job,endpoint,2*1024*1024),StandardCharsets.UTF_8);UpdateRelease latest=UpdateRelease.parse(new JSONObject(data),Build.SUPPORTED_ABIS,testOrigin);
            post(job,()->{release=latest;busy=false;status="";if(testOrigin.isEmpty())prefs.edit().putString("release",data).putLong("checkedAt",System.currentTimeMillis()).apply();render();});
        }catch(Exception e){failed(job,"Не удалось проверить обновление. "+reason(e));}finally{job.runner=null;}});
    }
    void download(){
        if(closed||busy||!available())return;UpdateRelease selected=release;Task job=new Task();task=job;busy=true;ready=false;downloading=true;downloaded=0;downloadStarted=SystemClock.elapsedRealtime();status="Скачиваем обновление…";render();
        worker.execute(()->{job.runner=Thread.currentThread();File target=UpdateProvider.apk(activity),folder=new File(target.getParentFile(),"transfer-"+UUID.randomUUID()),partial=new File(folder,"package.part");boolean success=false;try{
            cancelled(job);
            if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("Не удалось создать папку обновления");
            if(!folder.mkdir())throw new IOException("Не удалось создать временную папку обновления");
            target.delete();
            if(target.getParentFile().getUsableSpace()<selected.size+10L*1024*1024)throw new IOException("Недостаточно свободного места на телефоне");
            UpdateDownload.fetch(partial,selected.size,selected.url,new UpdateDownload.Connections(){
                public HttpURLConnection open(String url,long from,long to)throws Exception{return AppUpdates.this.open(job,url,from,to);}
                public void release(HttpURLConnection connection){AppUpdates.release(job,connection);}
                public void abort(){halt(job);}
                public void check()throws InterruptedIOException{cancelled(job);}
            },value->{synchronized(job){job.received=Math.max(job.received,value);long now=SystemClock.elapsedRealtime();if(now-job.lastProgress>=500){long count=job.received;post(job,()->{downloaded=Math.max(downloaded,count);render();});job.lastProgress=now;}}});
            cancelled(job);post(job,()->{downloaded=selected.size;downloading=false;status="Проверяем скачанный APK…";render();});
            MessageDigest digest=MessageDigest.getInstance("SHA-256");try(RandomAccessFile out=new RandomAccessFile(partial,"rw")){out.getFD().sync();}
            try(InputStream in=new FileInputStream(partial)){byte[] block=new byte[128*1024];int n;while((n=in.read(block))!=-1){cancelled(job);digest.update(block,0,n);}}
            if(!selected.digest.equals(Io.hex(digest.digest())))throw new IOException("Контрольная сумма APK не совпала. Установка отменена");
            verify(activity,partial,selected,installedCode);cancelled(job);
            if(!partial.renameTo(target))throw new IOException("Не удалось сохранить обновление");success=true;
            post(job,()->{busy=false;downloading=false;ready=true;downloaded=selected.size;status="Обновление скачано и проверено";render();install();});
        }catch(Exception e){failed(job,"Не удалось скачать обновление. "+reason(e));}finally{partial.delete();folder.delete();if(!success||job.cancelled)target.delete();job.runner=null;}});
    }
    static void verify(Context context,File apk,UpdateRelease expected,long installedCode)throws Exception{
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo candidate=pm.getPackageArchiveInfo(apk.getPath(),flags),current=pm.getPackageInfo(context.getPackageName(),flags);
        if(candidate==null||!context.getPackageName().equals(candidate.packageName))throw new IOException("APK принадлежит другому приложению или повреждён");
        long code=Build.VERSION.SDK_INT>=28?candidate.getLongVersionCode():candidate.versionCode;
        if(code<=installedCode||!expected.version.equals(candidate.versionName))throw new IOException("APK не является новой версией приложения");
        if(candidate.applicationInfo==null||candidate.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new IOException("Для обновления нужна более новая версия Android");
        if(!sameSigners(signatures(current),signatures(candidate)))throw new IOException("Подпись APK отличается от установленного приложения");
        boolean compatible=false;try(ZipFile zip=new ZipFile(apk)){for(String abi:Build.SUPPORTED_ABIS)if(zip.getEntry("lib/"+abi+"/libvlc.so")!=null){compatible=true;break;}}
        if(!compatible)throw new IOException("APK не подходит для архитектуры телефона");
    }
    private static Signature[] signatures(PackageInfo p){if(Build.VERSION.SDK_INT>=28)return p.signingInfo==null?new Signature[0]:p.signingInfo.getApkContentsSigners();return p.signatures==null?new Signature[0]:p.signatures;}
    static boolean sameSigners(Signature[] one,Signature[] two){if(one.length==0||one.length!=two.length)return false;Set<String> a=new HashSet<>(),b=new HashSet<>();for(Signature s:one)a.add(s.toCharsString());for(Signature s:two)b.add(s.toCharsString());return a.equals(b);}
    void install(){
        if(!ready||!UpdateProvider.apk(activity).isFile()||activity.isFinishing())return;
        if(!canInstall())return;
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
            if(permissionDialog!=null&&permissionDialog.isShowing())return;permissionDialog=Dialogs.builder(activity).setTitle("Разрешить установку обновлений").setMessage("Android попросит разрешить установку из Zem iBox. После возвращения откроется системное окно обновления.").setNegativeButton("Позже",null).setPositiveButton("Открыть настройки",(d,w)->{try{activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())),INSTALL_PERMISSION);}catch(ActivityNotFoundException e){status="Не удалось открыть настройки установки. APK можно скачать со страницы GitHub";render();}}).show();return;
        }
        launchInstaller();
    }
    void permissionResult(){if(!ready||closed)return;if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){status="Разрешение на установку не выдано. Можно повторить позже";render();return;}launchInstaller();}
    private boolean canInstall(){if(activity instanceof MainActivity a&&!a.updateScreenVisible()){status="Обновление скачано. Откройте приложение, чтобы начать установку";render();return false;}if(activity instanceof MainActivity a&&!a.canInstallAppUpdate()){status="Дождитесь завершения записи и скачивания файлов перед установкой";render();return false;}return true;}
    private void launchInstaller(){if(!canInstall())return;try{activity.startActivity(UpdateProvider.installer(activity));if(dialog!=null)dialog.dismiss();}catch(ActivityNotFoundException|SecurityException e){status="Android не смог открыть установщик. Можно скачать APK со страницы GitHub";render();}}
    private void openRelease(){try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(UpdateRelease.REPOSITORY+"/releases/latest")));}catch(ActivityNotFoundException ignored){}}
    private HttpURLConnection open(Task job,String address)throws Exception{return open(job,address,-1,-1);}
    private HttpURLConnection open(Task job,String address,long from,long to)throws Exception{
        URL url=new URL(address);
        for(int redirects=0;redirects<=5;redirects++){
            cancelled(job);if(!safe(url))throw new IOException("Недопустимый адрес обновления");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();job.connections.add(c);if(job.cancelled||job.halted){release(job,c);cancelled(job);}c.setConnectTimeout(10000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","Zem-iBox/"+BuildConfig.VERSION_NAME);c.setRequestProperty("Accept",address.equals(endpoint)?"application/vnd.github+json":"application/octet-stream");c.setRequestProperty("Accept-Encoding","identity");if(from>=0)c.setRequestProperty("Range","bytes="+from+"-"+to);
            int code;try{code=c.getResponseCode();}catch(Exception e){release(job,c);throw e;}if(code==200||from>=0&&code==206)return c;
            if(Arrays.asList(301,302,303,307,308).contains(code)){String location=c.getHeaderField("Location");release(job,c);if(location==null)throw new IOException("Пустая ссылка перенаправления");url=new URL(url,location);continue;}
            release(job,c);if(code==403||code==429)throw new IOException("GitHub ограничил частоту запросов. Повторите позже");if(code==404)throw new IOException("Релиз или файл обновления не найден");throw new IOException("GitHub вернул ошибку HTTP "+code);
        }throw new IOException("Слишком много перенаправлений");
    }
    private boolean safe(URL url){if(!testOrigin.isEmpty())return (url.getProtocol()+"://"+url.getAuthority()).equals(testOrigin)&&url.getUserInfo()==null;return "https".equals(url.getProtocol())&&url.getUserInfo()==null&&(url.getPort()==-1||url.getPort()==443)&&Arrays.asList("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com").contains(url.getHost());}
    private byte[] read(Task job,String url,int limit)throws Exception{HttpURLConnection c=open(job,url);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){cancelled(job);if(out.size()+n>limit)throw new IOException("Ответ GitHub слишком большой");out.write(b,0,n);}return out.toByteArray();}finally{release(job,c);}}
    private void post(Task job,Runnable action){main.post(()->{if(!closed&&task==job&&!job.cancelled)action.run();});}
    private void failed(Task job,String text){post(job,()->{busy=false;downloading=false;ready=false;status=text;render();});Diag.log("UPDATE",job.cancelled?"Cancelled":text);}
    private static String reason(Exception e){if(e instanceof SocketTimeoutException)return "Истекло время ожидания. Проверьте доступ к интернету";if(e instanceof UnknownHostException||e instanceof ConnectException)return "Нужен доступ к интернету; Wi-Fi регистратора может работать без него";return e.getMessage()==null?"Повторите попытку позже":e.getMessage();}
    private static void cancelled(Task job)throws InterruptedIOException{if(job.cancelled||job.halted||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Скачивание отменено");}
    private static void release(Task job,HttpURLConnection connection){job.connections.remove(connection);connection.disconnect();}
    private static void halt(Task job){job.halted=true;for(HttpURLConnection c:job.connections)new Thread(c::disconnect,"Cancel app update connection").start();}
    void cancel(){
        if(!busy)return;Task current=task;if(current!=null){current.cancelled=true;halt(current);Thread runner=current.runner;if(runner!=null)runner.interrupt();}
        busy=false;downloading=false;ready=false;downloaded=0;UpdateProvider.apk(activity).delete();status="Скачивание или проверка отменены";render();
    }
    public void close(){closed=true;cancel();if(dialog!=null)dialog.dismiss();if(permissionDialog!=null)permissionDialog.dismiss();worker.shutdownNow();}
}
