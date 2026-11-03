package io.zem.ibox;

import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.util.*;

public final class Library {
    private static final String PREF="library";
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
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/Zem iBox Drive/"+camera);values.put(MediaStore.MediaColumns.IS_PENDING,1);
        ContentResolver cr=c.getContentResolver();Uri uri=cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("Не удалось создать файл в Загрузках");
        boolean success=false;
        try{try(InputStream in=new FileInputStream(file);OutputStream out=cr.openOutputStream(uri,"w")){if(out==null)throw new IOException("Нет доступа к файлу");byte[] buffer=new byte[128*1024];int n;while((n=in.read(buffer))!=-1){if(cancelled.getAsBoolean())throw new InterruptedIOException("Загрузка отменена");out.write(buffer,0,n);}if(cancelled.getAsBoolean())throw new InterruptedIOException("Загрузка отменена");}values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);cr.update(uri,values,null,null);success=true;Diag.log("STORAGE","Published "+uri+" "+name+" bytes="+file.length());return uri;}
        finally{if(!success)cr.delete(uri,null,null);}
    }
}
