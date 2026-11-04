package io.zem.ibox;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

/** Durable jobs stay bound to the original device and card generation. */
final class DownloadQueue extends SQLiteOpenHelper {
    record Job(String id,String source,MediaItem item,String state,long done,long total,String error){}
    private static DownloadQueue instance;
    static synchronized DownloadQueue get(Context c){if(instance==null)instance=new DownloadQueue(c.getApplicationContext());return instance;}
    private DownloadQueue(Context c){super(c,"downloads.db",null,1);setWriteAheadLoggingEnabled(true);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY, source TEXT NOT NULL, item TEXT NOT NULL, state TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', added INTEGER NOT NULL)");}
    public void onOpen(SQLiteDatabase db){super.onOpen(db);if(!db.isReadOnly())db.execSQL("UPDATE jobs SET state='paused', error='Загрузка прервана. Можно продолжить' WHERE state IN ('queued','running')");}
    synchronized boolean stale(String id){try(Cursor c=getReadableDatabase().query("jobs",new String[]{"added"},"id=?",new String[]{id},null,null,null)){return c.moveToFirst()&&System.currentTimeMillis()-c.getLong(0)>30000;}}
    public void onUpgrade(SQLiteDatabase db,int old,int version){}
    synchronized void enqueue(String source,List<MediaItem> items)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(MediaItem item:items){String id=identity(source,item);ContentValues v=new ContentValues();v.put("id",id);v.put("source",source);v.put("item",item.json().toString());v.put("state","queued");v.put("total",item.size);v.put("added",System.currentTimeMillis());
            Job old=find(id);if(old!=null&&(old.state.equals("running")||old.state.equals("queued")))continue;
            if(old!=null)v.put("done",old.done);db.insertWithOnConflict("jobs",null,v,SQLiteDatabase.CONFLICT_REPLACE);}db.setTransactionSuccessful();}finally{db.endTransaction();}}
    static String identity(String source,MediaItem item)throws Exception{return Io.hex(java.security.MessageDigest.getInstance("SHA-256").digest((source+"\n"+item.path+"\n"+item.time+"\n"+item.size+"\nlocked="+item.locked()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    synchronized List<Job> all(){List<Job> result=new ArrayList<>();try(Cursor c=getReadableDatabase().query("jobs",null,null,null,null,null,"added DESC, id")){while(c.moveToNext())try{result.add(read(c));}catch(JSONException e){Diag.error("Download queue",e);}}return result;}
    synchronized Job find(String id){try(Cursor c=getReadableDatabase().query("jobs",null,"id=?",new String[]{id},null,null,null)){return c.moveToFirst()?read(c):null;}catch(JSONException e){throw new IllegalStateException(e);}}
    private Job read(Cursor c)throws JSONException{JSONObject item=new JSONObject(c.getString(c.getColumnIndexOrThrow("item")));return new Job(c.getString(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("source")),new MediaItem(item,item.optInt("c")),c.getString(c.getColumnIndexOrThrow("state")),c.getLong(c.getColumnIndexOrThrow("done")),c.getLong(c.getColumnIndexOrThrow("total")),c.getString(c.getColumnIndexOrThrow("error")));}
    synchronized void state(String id,String state,String error){ContentValues v=new ContentValues();v.put("state",state);v.put("error",error==null?"":error);getWritableDatabase().update("jobs",v,"id=?",new String[]{id});}
    synchronized void progress(String id,long done,long total){ContentValues v=new ContentValues();v.put("done",done);v.put("total",total);getWritableDatabase().update("jobs",v,"id=?",new String[]{id});}
    synchronized void recover(){getWritableDatabase().execSQL("UPDATE jobs SET state='paused', error='Загрузка прервана. Можно продолжить' WHERE state='running'");}
    synchronized void cancelPending(){getWritableDatabase().execSQL("UPDATE jobs SET state='cancelled', error='' WHERE state IN ('queued','running')");}
    synchronized void clearFinished(){getWritableDatabase().delete("jobs","state IN ('done','cancelled')",null);}
    synchronized Job next(String source){List<Job> jobs=all();jobs.sort((a,b)->MediaItem.newestFirst().compare(a.item,b.item));for(Job job:jobs)if(job.source.equals(source)&&job.state.equals("queued"))return job;return null;}
    static String label(String state){return switch(state){case "queued"->"В очереди";case "running"->"Скачивается";case "paused"->"На паузе";case "done"->"Сохранено";case "cancelled"->"Отменено";default->"Не удалось скачать";};}
}
