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
    private static volatile boolean enabled=false;
    private static final SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.ROOT);
    public static synchronized void init(Context c) {
        dir=new File(c.getFilesDir(),"logs"); dir.mkdirs();preferences=c.getSharedPreferences("diagnostics",Context.MODE_PRIVATE);enabled=preferences.getBoolean("enabled",false);
        log("APP","START Zem iBox "+BuildConfig.VERSION_NAME+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+" "+Build.MANUFACTURER+" "+Build.MODEL+" ABIs="+Arrays.toString(Build.SUPPORTED_ABIS));
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{error("CRASH "+t.getName(),e);saveCrash("CRASH "+t.getName(),e); if(previous!=null)previous.uncaughtException(t,e);});
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
        try {androidCapture=new ProcessBuilder("logcat","--pid="+android.os.Process.myPid(),"-b","main","-b","crash","-v","threadtime","-T",since,"-f",new File(dir,"android.log").getPath(),"-r","2048","-n","4","*:W","CCodec:I","MPEG4Writer:I").start();}
        catch(Exception e){error("Native Android log capture unavailable",e);}
    }
    private static void stopNativeCapture()throws IOException {
        if(androidCapture!=null){
            androidCapture.destroy();
            try{if(!waitForExit(androidCapture,1000)){if(Build.VERSION.SDK_INT>=26)androidCapture.destroyForcibly();else androidCapture.destroy();if(!waitForExit(androidCapture,1000))throw new IOException("Не удалось остановить запись системного журнала");}}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Очистка логов прервана",e);}
            androidCapture=null;
        }
    }
    private static boolean waitForExit(Process process,long timeout)throws InterruptedException{long end=android.os.SystemClock.elapsedRealtime()+timeout;do{try{process.exitValue();return true;}catch(IllegalThreadStateException running){Thread.sleep(20);}}while(android.os.SystemClock.elapsedRealtime()<end);return false;}
    public static synchronized void clear()throws IOException {
        String since=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.ROOT).format(new Date());
        stopNativeCapture();
        try{
            File[] files=dir.listFiles();if(files==null)throw new IOException("Журнал недоступен");
            for(File f:files)if(f.getName().matches("roadlink(?:\\.[1-9])?\\.log|android\\.log(?:\\.[1-4])?|last-crash.log"))java.nio.file.Files.deleteIfExists(f.toPath());
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
        return s.replaceAll("(?i)(\"(?:pwd|password|token)\"\\s*:\\s*\")[^\"]*","$1[REDACTED]").replaceAll("(?i)(\\b(?:pwd|password|token)\\s*[=:]\\s*)[^\\s,;&\"\\r\\n]+","$1[REDACTED]");
    }
    public static void error(String context,Throwable e) {if(!enabled)return;StringWriter w=new StringWriter();e.printStackTrace(new PrintWriter(w));log("ERROR",context+"\n"+w);}
    static synchronized void saveCrash(String context,Throwable error){if(dir==null||!enabled)return;try{StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));String value=redact(clock.format(new java.util.Date())+" "+context+"\n"+trace);if(value.length()>6000)value=value.substring(0,6000);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(dir,"last-crash.log")),StandardCharsets.UTF_8)){out.write(value);}}catch(IOException ignored){}}
    public static synchronized List<String> copyParts(String state) throws IOException {
        log("COPY","User copied complete diagnostic journal");
        StringBuilder text=new StringBuilder(redact(state)).append('\n');
        for(int i=9;i>=0;i--){File f=new File(dir,i==0?"roadlink.log":"roadlink."+i+".log");if(f.isFile())text.append(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
        text.append("\n=== ANDROID PROCESS / CODEC / NATIVE ERRORS ===\n");
        for(int i=4;i>=0;i--){File f=new File(dir,i==0?"android.log":"android.log."+i);if(f.isFile())try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null)if(usefulNativeLine(line))text.append(redact(line)).append('\n');}}
        List<String> pieces=new ArrayList<>();int pos=0;
        while(pos<text.length()){
            int end=Math.min(pos+100000,text.length());
            if(end<text.length()){int newline=text.lastIndexOf("\n",end);if(newline>pos+50000)end=newline+1;if(end>pos&&Character.isHighSurrogate(text.charAt(end-1)))end--;}
            pieces.add(text.substring(pos,end));pos=end;
        }
        return pieces;
    }
    static boolean usefulNativeLine(String line){return !line.contains("Zem iBox/")&&line.matches(".* [IWEF] (?:VLC|MediaCodec|CCodec[^:]*|Codec2[^:]*|MPEG4Writer|BufferQueue[^:]*|GraphicsTracker|AndroidRuntime|DEBUG|libc|Audio[^:]*)\\s*:.*");}
    /** Fits a single pasted message. The complete journal remains a separate export. */
    public static synchronized String compact(String state)throws IOException {
        ArrayDeque<String> events=new ArrayDeque<>();LinkedHashMap<String,String> latest=new LinkedHashMap<>();
        int stack=0;
        for(int i=9;i>=0;i--){File f=new File(dir,i==0?"roadlink.log":"roadlink."+i+".log");if(!f.isFile())continue;
            String tail;try(RandomAccessFile in=new RandomAccessFile(f,"r")){in.seek(Math.max(0,in.length()-65536));byte[] bytes=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(bytes);tail=new String(bytes,StandardCharsets.UTF_8);}
            for(String line:tail.split("\n")){
                int begin=line.indexOf(" ["),end=begin<0?-1:line.indexOf(']',begin+2);
                if(begin<0||end<0){if(stack-->0)events.addLast(line);continue;}
                stack=0;String tag=line.substring(begin+2,end),message=line.substring(end+1);
                if(tag.equals("COPY")||tag.startsWith("TEST")||tag.equals("TIMING TEST")||message.contains("CTP_KEEP_ALIVE"))continue;
                if(tag.endsWith("STATS")||tag.equals("CODEC FORMAT")||tag.equals("LIVE QUALITY")||tag.equals("DOWNLOAD HTTP")){latest.put(tag,line);continue;}
                if(tag.equals("LIVE TCP")||tag.equals("LIVE UDP")||tag.startsWith("THUMB"))continue;
                if(line.length()>400)line=line.substring(0,400)+"…";
                events.addLast(line);if(tag.equals("ERROR"))stack=5;
                while(events.size()>120)events.removeFirst();
            }
        }
        String prefix="Zem iBox "+BuildConfig.VERSION_NAME+" · короткий отчёт\n"+redact(state)+"\nПоследние ключевые события; полный журнал доступен файлом.\n";
        if(prefix.length()>3500)prefix=prefix.substring(0,3500)+"\n";
        LinkedHashMap<String,String> nativeErrors=new LinkedHashMap<>();
        for(int i=4;i>=0;i--){File f=new File(dir,i==0?"android.log":"android.log."+i);if(!f.isFile())continue;
            try(RandomAccessFile in=new RandomAccessFile(f,"r")){in.seek(Math.max(0,in.length()-24576));byte[] bytes=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(bytes);for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n"))if(usefulNativeLine(line)&&line.matches(".* [WEF] .*")){int start=line.indexOf(" E ");if(start<0)start=line.indexOf(" W ");if(start<0)start=line.indexOf(" F ");int colon=line.indexOf(':',Math.max(0,start));String tag=colon>start?line.substring(start+3,colon).trim():"native";nativeErrors.put(tag,line.length()>400?line.substring(0,400):line);}}
        }
        File crashFile=new File(dir,"last-crash.log");String crash=crashFile.isFile()?"\nПоследний вылет:\n"+new String(java.nio.file.Files.readAllBytes(crashFile.toPath()),StandardCharsets.UTF_8):"";if(crash.length()>1600)crash=crash.substring(0,1600);
        String ending=crash+"\nПоследние показатели:\n"+String.join("\n",latest.values())+"\nПоследние системные ошибки:\n"+String.join("\n",nativeErrors.values());if(ending.length()>4500)ending=ending.substring(0,4500);
        String report;
        do{report=redact(prefix+String.join("\n",events)+ending);if(report.getBytes(StandardCharsets.UTF_8).length<=12288)break;if(events.isEmpty()){ending=ending.substring(0,ending.length()/2);prefix=prefix.substring(0,prefix.length()/2);}else events.removeFirst();}while(true);
        return report;
    }
    public static synchronized android.net.Uri export(Context context,String state)throws IOException {
        File folder=new File(context.getCacheDir(),"shared-diagnostics");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Не удалось подготовить файл логов");
        File[] old=folder.listFiles();if(old!=null){Arrays.sort(old,Comparator.comparingLong(File::lastModified).reversed());for(int i=0;i<old.length;i++)if(i>=2||System.currentTimeMillis()-old[i].lastModified()>86400000L)old[i].delete();}
        File file=new File(folder,"Zem-iBox-"+UUID.randomUUID()+".txt");
        try(Writer out=new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8)){for(String part:copyParts(state))out.write(part);}
        return new android.net.Uri.Builder().scheme("content").authority(context.getPackageName()+".diagnostics").appendPath(file.getName()).build();
    }
    public static synchronized String tail() {
        if(!new File(dir,"roadlink.log").isFile())return enabled?"Журнал пуст":"Запись логов выключена";
        try(RandomAccessFile r=new RandomAccessFile(new File(dir,"roadlink.log"),"r")) {r.seek(Math.max(0,r.length()-50000));byte[] b=new byte[(int)(r.length()-r.getFilePointer())];r.readFully(b);return new String(b,StandardCharsets.UTF_8);}catch(Exception e){return e.toString();}
    }
}
