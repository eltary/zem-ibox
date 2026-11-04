package io.zem.ibox;

import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.util.*;

public final class Library {
    private static final String PREF="library";
    static final java.util.concurrent.ExecutorService IO=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Phone library");t.setDaemon(true);return t;});
    static void requireReadable(Context c,Uri uri)throws IOException {
        try(android.content.res.AssetFileDescriptor file=c.getContentResolver().openAssetFileDescriptor(uri,"r")){if(file==null)throw new FileNotFoundException("Файл удалён или больше недоступен");}
    }
    static boolean reconcile(Context c)throws Exception {
        JSONArray snapshot=all(c);Set<String> missing=new HashSet<>();
        for(int i=0;i<snapshot.length();i++){String uri=snapshot.getJSONObject(i).optString("uri");try{requireReadable(c,Uri.parse(uri));}catch(FileNotFoundException e){missing.add(uri);}catch(SecurityException e){Diag.log("LIBRARY","No permission for "+uri);}catch(IOException e){Diag.log("LIBRARY","Cannot check "+uri+": "+e.getClass().getSimpleName());}}
        return forget(c,missing);
    }
    static synchronized boolean forget(Context c,Set<String> uris)throws Exception {
        if(uris.isEmpty())return false;JSONArray current=all(c),kept=new JSONArray();
        for(int i=0;i<current.length();i++){JSONObject item=current.getJSONObject(i);if(!uris.contains(item.optString("uri")))kept.put(item);}
        if(kept.length()==current.length())return false;c.getSharedPreferences(PREF,0).edit().putString("items",kept.toString()).commit();Diag.log("LIBRARY","Removed missing entries: "+(current.length()-kept.length()));RoadApp.instance.session.changed();return true;
    }
    public static synchronized JSONArray all(Context c){try{return new JSONArray(c.getSharedPreferences(PREF,0).getString("items","[]"));}catch(Exception e){return new JSONArray();}}
    public static synchronized void add(Context c,Uri uri,String name,String mime,long size,String origin)throws Exception {
        add(c,uri,name,mime,size,origin,"");
    }
    public static synchronized void add(Context c,Uri uri,String name,String mime,long size,String origin,String identity)throws Exception {
        add(c,uri,name,mime,size,origin,identity,"");
    }
    public static synchronized void add(Context c,Uri uri,String name,String mime,long size,String origin,String identity,String recordedAt)throws Exception {
        JSONArray list=all(c),next=new JSONArray();next.put(new JSONObject().put("uri",uri.toString()).put("name",name).put("mime",mime).put("size",size).put("origin",origin).put("at",System.currentTimeMillis()));
        next.getJSONObject(0).put("identity",identity).put("recordedAt",recordedAt);
        for(int i=0;i<list.length();i++)next.put(list.get(i));c.getSharedPreferences(PREF,0).edit().putString("items",next.toString()).apply();RoadApp.instance.session.changed();
    }
    public static synchronized boolean contains(Context c,String identity){
        JSONArray list=all(c);for(int i=0;i<list.length();i++){JSONObject item=list.optJSONObject(i);if(identity.equals(item.optString("identity")))try(android.content.res.AssetFileDescriptor fd=c.getContentResolver().openAssetFileDescriptor(Uri.parse(item.getString("uri")),"r")){if(fd!=null&&(fd.getLength()<0||fd.getLength()==item.optLong("size")))return true;}catch(Exception ignored){}}
        return false;
    }
    public static Uri publish(Context c,File file,String name,String mime,String camera)throws Exception {
        return publish(c,file,name,mime,camera,()->false);
    }
    public static Uri publish(Context c,File file,String name,String mime,String camera,java.util.function.BooleanSupplier cancelled)throws Exception {
        if(!canPublish(c))throw new SecurityException("Для сохранения в Загрузки разрешите доступ к файлам");
        if(android.os.Build.VERSION.SDK_INT<29)return publishLegacy(c,file,name,mime,camera,cancelled);
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/Zem iBox Drive/"+camera);values.put(MediaStore.MediaColumns.IS_PENDING,1);
        ContentResolver cr=c.getContentResolver();Uri uri=cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("Не удалось создать файл в Загрузках");
        boolean success=false;
        try{try(InputStream in=new FileInputStream(file);OutputStream out=cr.openOutputStream(uri,"w")){if(out==null)throw new IOException("Нет доступа к файлу");byte[] buffer=new byte[128*1024];int n;while((n=in.read(buffer))!=-1){if(cancelled.getAsBoolean())throw new InterruptedIOException("Загрузка отменена");out.write(buffer,0,n);}if(cancelled.getAsBoolean())throw new InterruptedIOException("Загрузка отменена");}values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);cr.update(uri,values,null,null);success=true;Diag.log("STORAGE","Published "+uri+" "+name+" bytes="+file.length());return uri;}
        finally{if(!success)cr.delete(uri,null,null);}
    }
    static boolean canPublish(Context c){return android.os.Build.VERSION.SDK_INT>=29||c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    @SuppressWarnings("deprecation")
    private static Uri publishLegacy(Context c,File source,String name,String mime,String camera,java.util.function.BooleanSupplier cancelled)throws Exception {
        File root=new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),"Zem iBox Drive").getCanonicalFile();
        File folder=new File(root,camera).getCanonicalFile();if(!folder.toPath().startsWith(root.toPath()))throw new IOException("Неверная папка записи");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Не удалось создать папку в Загрузках");
        String safe=new File(name).getName();if(safe.isEmpty()||safe.equals(".")||safe.equals(".."))throw new IOException("Неверное имя файла");
        int dot=safe.lastIndexOf('.');String stem=dot>0?safe.substring(0,dot):safe,extension=dot>0?safe.substring(dot):"";
        File target=null;for(int i=0;i<10000;i++){File candidate=new File(folder,stem+(i==0?"":" ("+i+")")+extension);if(candidate.createNewFile()){target=candidate;break;}}
        if(target==null)throw new IOException("Не удалось выбрать имя файла");
        File partial=new File(folder,".zem-"+UUID.randomUUID()+".partial");Uri uri=null;boolean success=false;
        try{
            try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(partial)){byte[] bytes=new byte[128*1024];int n;while((n=in.read(bytes))!=-1){if(cancelled.getAsBoolean())throw new InterruptedIOException("Загрузка отменена");out.write(bytes,0,n);}}
            if(cancelled.getAsBoolean())throw new InterruptedIOException("Загрузка отменена");
            if(!partial.renameTo(target))throw new IOException("Не удалось завершить сохранение файла");
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DATA,target.getPath());values.put(MediaStore.MediaColumns.DISPLAY_NAME,target.getName());values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.SIZE,target.length());
            Uri collection=mime.startsWith("image/")?MediaStore.Images.Media.EXTERNAL_CONTENT_URI:MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            uri=c.getContentResolver().insert(collection,values);if(uri==null)throw new IOException("Не удалось добавить файл в медиатеку");
            success=true;Diag.log("STORAGE","Published "+uri+" "+target.getName()+" bytes="+target.length());return uri;
        }finally{partial.delete();if(!success){if(uri!=null)c.getContentResolver().delete(uri,null,null);target.delete();}}
    }

}
