package io.zem.ibox;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.Collections;
import java.util.concurrent.*;

/** A paused exact seek must decode B-frames too; libVLC's paused seek can retain an earlier picture. */
final class FrameReader implements AutoCloseable {
    interface Result {void ready(Bitmap image,Exception error);}
    private final Context context;private final Uri source;private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());private MediaMetadataRetriever reader;private volatile boolean closed;
    FrameReader(Context context,Uri source){this.context=context.getApplicationContext();this.source=source;}
    void read(long position,double fps,int width,int height,Result result){worker.execute(()->{Bitmap image=null;Exception failure=null;try{
        if(closed)return;if(Build.VERSION.SDK_INT<28){image=LegacyFrameReader.read(context,source,position,fps,width,height,()->closed);}else{if(reader==null){reader=new MediaMetadataRetriever();if("content".equals(source.getScheme())){try(AssetFileDescriptor fd=context.getContentResolver().openAssetFileDescriptor(source,"r")){if(fd==null)throw new IOException("Нет доступа к записи");if(fd.getLength()<0)reader.setDataSource(fd.getFileDescriptor());else reader.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());}}else reader.setDataSource(source.toString(),Collections.emptyMap());}
        image=reader.getScaledFrameAtTime(position*1000,MediaMetadataRetriever.OPTION_CLOSEST,Math.max(1,width),Math.max(1,height));}
        if(image==null)throw new IOException("Декодер не вернул кадр");
    }catch(Exception e){failure=e;if(reader!=null){try{reader.release();}catch(Exception ignored){}reader=null;}}Bitmap decoded=image;Exception error=failure;main.post(()->{if(closed){if(decoded!=null)decoded.recycle();}else result.ready(decoded,error);});});}
    public void close(){if(closed)return;closed=true;worker.execute(()->{if(reader!=null){try{reader.release();}catch(IOException ignored){}reader=null;}});worker.shutdown();}
}
