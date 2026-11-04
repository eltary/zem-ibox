package io.zem.ibox;

import android.content.Context;
import android.graphics.*;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Small cached stills, never a second player or a whole-file download. */
final class MediaThumbnails implements AutoCloseable {
    static final int WIDTH=320,HEIGHT=180;
    private final Context context;
    private final File folder;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LruCache<String,Bitmap> memory=new LruCache<>(8*1024*1024){protected int sizeOf(String key,Bitmap value){return value.getByteCount();}};
    private final Map<String,List<WeakReference<ImageView>>> waiting=new HashMap<>();
    private final Set<String> failed=new LinkedHashSet<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),r->{Thread t=new Thread(r,"Video thumbnails");t.setDaemon(true);return t;});
    private volatile boolean paused,closed;
    private volatile int generation;
    private volatile AutoCloseable active;

    static final class Source {
        final String key,uri,mime,path;
        final long size;
        final JieliSession session;
        final CtpClient owner;
        private Source(String key,String uri,String mime,long size,JieliSession session,CtpClient owner,String path){this.key=key;this.uri=uri;this.mime=mime;this.size=size;this.session=session;this.owner=owner;this.path=path;}
        boolean ready(){return session==null||session.isCurrent(owner)&&session.cardPresent()&&!session.cardFormatting();}
        static Source remote(JieliSession session,MediaItem item){return remote(session,item,session.ctp);}
        static Source remote(JieliSession session,MediaItem item,CtpClient owner){return new Source("dvr-v1|"+session.deviceIdentity()+"|card:"+session.cardContentVersion()+"|"+item.path+"|"+item.time+"|"+item.size,"",item.mime(),item.size,session,owner,item.path);}
        static Source local(JSONObject item){return new Source("phone-v1|"+item.optString("uri")+"|"+item.optLong("size")+"|"+item.optLong("at"),item.optString("uri"),item.optString("mime"),item.optLong("size"),null,null,"");}
    }
    MediaThumbnails(Context context){this.context=context.getApplicationContext();folder=new File(this.context.getCacheDir(),"media-thumbnails");}
    void bind(ImageView view,Source source){
        String tag=source.key+"@"+generation;view.setTag(tag);
        if(closed||paused||!source.ready())return;
        Bitmap cached=memory.get(source.key);if(cached!=null){display(view,cached);return;}
        if(failed.contains(source.key))return;
        List<WeakReference<ImageView>> views=waiting.get(source.key);if(views!=null){views.add(new WeakReference<>(view));return;}
        views=new ArrayList<>();views.add(new WeakReference<>(view));waiting.put(source.key,views);final int request=generation;
        try{worker.execute(()->{
            Bitmap bitmap=null;try{if(valid(request,source))bitmap=load(source,request);}catch(Exception|LinkageError e){if(valid(request,source))Diag.log("THUMBNAIL","Preview unavailable: "+e.getClass().getSimpleName());}
            final Bitmap result=bitmap;main.post(()->{
                if(!valid(request,source))return;
                List<WeakReference<ImageView>> targets=waiting.remove(source.key);
                if(result==null){failed.add(source.key);if(failed.size()>512)failed.remove(failed.iterator().next());return;}
                memory.put(source.key,result);
                if(targets!=null)for(WeakReference<ImageView> target:targets){ImageView image=target.get();if(image!=null&&tag.equals(image.getTag()))display(image,result);}
            });
        });}catch(RejectedExecutionException e){waiting.remove(source.key);}
    }
    private static void display(ImageView view,Bitmap bitmap){view.setPadding(0,0,0,0);view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setImageBitmap(bitmap);}
    private boolean valid(int request,Source source){return !closed&&!paused&&request==generation&&source.ready();}
    void pause(){if(paused)return;paused=true;generation++;worker.getQueue().clear();waiting.clear();AutoCloseable current=active;if(current!=null)try{current.close();}catch(Exception ignored){}}
    void resume(){if(!closed)paused=false;}
    public void close(){closed=true;pause();worker.shutdownNow();memory.evictAll();}
    private Bitmap load(Source source,int request)throws Exception {
        folder.mkdirs();File file=new File(folder,hash(source.key)+".jpg");
        Bitmap bitmap=file.isFile()?BitmapFactory.decodeFile(file.getPath()):null;if(bitmap!=null)return bitmap;
        if(source.session==null){
            CancellationSignal signal=new CancellationSignal();active=signal::cancel;
            try{bitmap=context.getContentResolver().loadThumbnail(Uri.parse(source.uri),new Size(WIDTH,HEIGHT),signal);}catch(Exception ignored){}finally{active=null;}
            if(bitmap==null&&valid(request,source))try(MediaMetadataRetriever retriever=new MediaMetadataRetriever()){
                retriever.setDataSource(context,Uri.parse(source.uri));bitmap=retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,WIDTH,HEIGHT);
            }
        }else{
            try(RemoteData data=new RemoteData(source)){active=data;
                if(source.mime.startsWith("image/")||source.mime.equals("video/x-msvideo"))bitmap=jpeg(data);
                else try(MediaMetadataRetriever retriever=new MediaMetadataRetriever()){
                    retriever.setDataSource(data);bitmap=retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,WIDTH,HEIGHT);
                }
            }finally{active=null;}
        }
        if(bitmap==null||!valid(request,source))return null;
        Bitmap small=fit(bitmap);if(small!=bitmap)bitmap.recycle();
        File temporary=new File(folder,file.getName()+".tmp");try{try(FileOutputStream out=new FileOutputStream(temporary)){small.compress(Bitmap.CompressFormat.JPEG,82,out);}if(!temporary.renameTo(file))temporary.delete();trim();}catch(IOException e){temporary.delete();}return small;
    }
    private static Bitmap fit(Bitmap bitmap){float scale=Math.min(1f,Math.min(WIDTH/(float)bitmap.getWidth(),HEIGHT/(float)bitmap.getHeight()));return scale==1f?bitmap:Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);}
    private static Bitmap jpeg(RemoteData data)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[16384];boolean started=false;int previous=-1;
        for(long position=0;position<2*1024*1024;){int count=data.readAt(position,block,0,block.length);if(count<0)break;position+=count;
            for(int i=0;i<count;i++){int value=block[i]&255;if(!started&&previous==255&&value==216){started=true;bytes.write(255);}if(started)bytes.write(value);
                if(started&&previous==255&&value==217){byte[] encoded=bytes.toByteArray();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);options.inSampleSize=1;while(options.outWidth/options.inSampleSize>WIDTH*2||options.outHeight/options.inSampleSize>HEIGHT*2)options.inSampleSize*=2;options.inJustDecodeBounds=false;return BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);}previous=value;}
        }return null;
    }
    private static String hash(String value)throws Exception{return Io.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private void trim(){File[] files=folder.listFiles((d,n)->n.endsWith(".jpg"));if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());long total=0;for(int i=0;i<files.length;i++){total+=files[i].length();if(i>=200||total>20*1024*1024)files[i].delete();}}

    /** Bounded random access over the captured DVR Wi-Fi, including MP4 metadata at EOF. */
    static final class RemoteData extends MediaDataSource {
        private static final int BLOCK=64*1024;
        private final Source source;
        private final long deadline=SystemClock.elapsedRealtime()+6000;
        private final LinkedHashMap<Long,byte[]> blocks=new LinkedHashMap<>(8,.75f,true);
        private long size,received;
        private volatile boolean closed;
        private volatile HttpURLConnection connection;
        RemoteData(Source source){this.source=source;size=source.size>0?source.size:-1;}
        private void check()throws IOException{if(closed||!source.ready()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Thumbnail cancelled");if(SystemClock.elapsedRealtime()>deadline||received>=4*1024*1024)throw new IOException("Thumbnail read limit");}
        public synchronized int readAt(long position,byte[] buffer,int offset,int count)throws IOException {
            check();if(position<0||offset<0||count<0||offset>buffer.length-count)throw new IOException("Invalid thumbnail read");if(count==0)return 0;if(size>=0&&position>=size)return -1;
            long start=position/BLOCK*BLOCK;byte[] block=blocks.get(start);if(block==null){block=fetch(start);blocks.put(start,block);if(blocks.size()>8)blocks.remove(blocks.keySet().iterator().next());}
            int skip=(int)(position-start);if(skip>=block.length)return -1;int length=Math.min(count,block.length-skip);System.arraycopy(block,skip,buffer,offset,length);return length;
        }
        private byte[] fetch(long start)throws IOException {
            check();HttpURLConnection conn=null;
            try{conn=source.session.http(source.owner,source.path);connection=conn;conn.setConnectTimeout(2000);conn.setReadTimeout(2000);conn.setRequestProperty("Range","bytes="+start+"-"+(start+BLOCK-1));check();int code=conn.getResponseCode();
                if(code==206){String range=conn.getHeaderField("Content-Range");if(range==null||!range.startsWith("bytes "+start+"-"))throw new IOException("Invalid thumbnail range");String total=range.substring(range.lastIndexOf('/')+1);if(!total.equals("*")){long actual=Long.parseLong(total);if(size>=0&&size!=actual)throw new IOException("Video changed");size=actual;}}
                else if(code==200&&start==0){long actual=conn.getContentLengthLong();if(actual>0){if(size>=0&&size!=actual)throw new IOException("Video changed");size=actual;}}
                else throw new IOException("Thumbnail HTTP "+code);
                try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream(BLOCK)){byte[] buffer=new byte[8192];int n;while(out.size()<BLOCK&&(n=in.read(buffer,0,Math.min(buffer.length,BLOCK-out.size())))!=-1){check();out.write(buffer,0,n);received+=n;}return out.toByteArray();}
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}finally{if(conn!=null)conn.disconnect();connection=null;}
        }
        public synchronized long getSize()throws IOException{check();if(size<0)readAt(0,new byte[1],0,1);return size;}
        public void close(){closed=true;HttpURLConnection conn=connection;if(conn!=null)conn.disconnect();}
    }
}
