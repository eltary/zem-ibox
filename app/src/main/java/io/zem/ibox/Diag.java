package io.zem.ibox;

import android.content.*;
import android.os.Build;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class Diag {
    private static File dir;
    private static Process androidCapture;
    private static SharedPreferences preferences;
    private static volatile boolean enabled=true;
    private static final SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.ROOT);
    public static synchronized void init(Context c) {
        dir=new File(c.getFilesDir(),"logs"); dir.mkdirs();preferences=c.getSharedPreferences("diagnostics",Context.MODE_PRIVATE);enabled=preferences.getBoolean("enabled",true);
        log("APP","START Zem iBox "+BuildConfig.VERSION_NAME+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+" "+Build.MANUFACTURER+" "+Build.MODEL+" ABIs="+Arrays.toString(Build.SUPPORTED_ABIS));
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{error("CRASH "+t.getName(),e); if(previous!=null)previous.uncaughtException(t,e);});
        if(enabled)startNativeCapture("1");
    }
    public static boolean enabled(){return enabled;}
    public static synchronized void setEnabled(boolean value)throws IOException {
        if(enabled==value)return;
        if(!preferences.edit().putBoolean("enabled",value).commit())throw new IOException("Не удалось сохранить настройку записи логов");
        if(!value){log("APP","Diagnostic recording disabled by user");enabled=false;stopNativeCapture();}
        else{enabled=true;startNativeCapture(new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.ROOT).format(new Date()));log("APP","Diagnostic recording enabled by user. Zem iBox "+BuildConfig.VERSION_NAME);}
    }
    private static void startNativeCapture(String since){
        try {androidCapture=new ProcessBuilder("logcat","--pid="+android.os.Process.myPid(),"-b","main","-b","crash","-v","threadtime","-T",since,"-f",new File(dir,"android.log").getPath(),"-r","2048","-n","4").start();}
        catch(Exception e){error("Native Android log capture unavailable",e);}
    }
    private static void stopNativeCapture()throws IOException {
        if(androidCapture!=null){
            androidCapture.destroy();
            try{if(!androidCapture.waitFor(1,java.util.concurrent.TimeUnit.SECONDS)){androidCapture.destroyForcibly();if(!androidCapture.waitFor(1,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Не удалось остановить запись системного журнала");}}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Очистка логов прервана",e);}
            androidCapture=null;
        }
    }
    public static synchronized void clear()throws IOException {
        String since=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.ROOT).format(new Date());
        stopNativeCapture();
        try{
            File[] files=dir.listFiles();if(files==null)throw new IOException("Журнал недоступен");
            for(File f:files)if(f.getName().matches("roadlink(?:\\.[1-9])?\\.log|android\\.log(?:\\.[1-4])?"))java.nio.file.Files.deleteIfExists(f.toPath());
            log("APP","Logs cleared by user. Zem iBox "+BuildConfig.VERSION_NAME+" "+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT);
        }finally{if(enabled)startNativeCapture(since);}
    }
    public static synchronized void log(String tag,String value) {
        if(dir==null||!enabled)return;
        value=redact(value);
        Log.i("Zem iBox/"+tag,value.length()>3500?value.substring(0,3500):value);
        try {
            File active=new File(dir,"roadlink.log");
            if(active.length()>2*1024*1024) {
                new File(dir,"roadlink.9.log").delete();
                for(int i=8;i>=1;i--)new File(dir,"roadlink."+i+".log").renameTo(new File(dir,"roadlink."+(i+1)+".log"));
                active.renameTo(new File(dir,"roadlink.1.log"));
            }
            try(FileOutputStream out=new FileOutputStream(active,true)) {out.write((clock.format(new Date())+" ["+tag+"] "+value+"\n").getBytes(StandardCharsets.UTF_8));}
        } catch(IOException e){Log.e("Zem iBox/LOG", "Log write failed",e);}
    }
    static String redact(String s) {
        return s.replaceAll("(?i)(\"(?:pwd|password|token)\"\\s*:\\s*\")[^\"]*","$1[REDACTED]");
    }
    public static void error(String context,Throwable e) {if(!enabled)return;StringWriter w=new StringWriter();e.printStackTrace(new PrintWriter(w));log("ERROR",context+"\n"+w);}
    public static synchronized List<String> copyParts(String state) throws IOException {
        log("COPY","User copied complete diagnostic journal");
        StringBuilder text=new StringBuilder(redact(state)).append('\n');
        for(int i=9;i>=0;i--){File f=new File(dir,i==0?"roadlink.log":"roadlink."+i+".log");if(f.isFile())text.append(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
        text.append("\n=== ANDROID PROCESS / CODEC / NATIVE ERRORS ===\n");
        for(int i=4;i>=0;i--){File f=new File(dir,i==0?"android.log":"android.log."+i);if(f.isFile())text.append(redact(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)));}
        List<String> pieces=new ArrayList<>();int pos=0;
        while(pos<text.length()){
            int end=Math.min(pos+100000,text.length());
            if(end<text.length()){int newline=text.lastIndexOf("\n",end);if(newline>pos+50000)end=newline+1;if(end>pos&&Character.isHighSurrogate(text.charAt(end-1)))end--;}
            pieces.add(text.substring(pos,end));pos=end;
        }
        return pieces;
    }
    public static synchronized String tail() {
        if(!new File(dir,"roadlink.log").isFile())return enabled?"Журнал пуст":"Запись логов выключена";
        try(RandomAccessFile r=new RandomAccessFile(new File(dir,"roadlink.log"),"r")) {r.seek(Math.max(0,r.length()-50000));byte[] b=new byte[(int)(r.length()-r.getFilePointer())];r.readFully(b);return new String(b,StandardCharsets.UTF_8);}catch(Exception e){return e.toString();}
    }
}
