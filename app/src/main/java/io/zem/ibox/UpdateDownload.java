package io.zem.ibox;

import java.io.*;
import java.net.HttpURLConnection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded parallel HTTP ranges; the caller verifies the complete APK before publishing it. */
final class UpdateDownload {
    interface Connections {
        HttpURLConnection open(String url,long first,long last)throws Exception;
        void release(HttpURLConnection connection);
        void abort();
        void check()throws InterruptedIOException;
    }
    interface Progress {void bytes(long count);}

    static void fetch(File file,long size,String url,Connections network,Progress progress)throws Exception {
        int parts=size>=4L*1024*1024?4:1;
        long step=(size+parts-1)/parts;
        HttpURLConnection first=network.open(url,0,step-1);
        ExecutorService pool=null;
        boolean assigned=false;
        AtomicLong received=new AtomicLong();
        try {
            network.check();
            if(first.getResponseCode()==HttpURLConnection.HTTP_OK){
                // Some servers ignore Range. Read one complete response instead of duplicating it.
                length(first,size);
                copy(file,0,size,first,network,received,progress);
                return;
            }
            range(first,0,step-1,size);
            try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(size);}
            pool=Executors.newFixedThreadPool(parts);
            CompletionService<Void> completed=new ExecutorCompletionService<>(pool);
            String direct=first.getURL().toExternalForm();
            for(int part=0;part<parts;part++){
                long from=part*step,to=Math.min(size-1,from+step-1);
                boolean initial=part==0;
                completed.submit(()->{
                    network.check();
                    HttpURLConnection connection=initial?first:network.open(direct,from,to);
                    try{network.check();range(connection,from,to,size);copy(file,from,to-from+1,connection,network,received,progress);}
                    finally{network.release(connection);}
                    return null;
                });
                if(initial)assigned=true;
            }
            for(int part=0;part<parts;part++)try{completed.take().get();}
            catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception failure)throw failure;throw new IOException("Не удалось скачать часть APK",cause);}
            network.check();
        }catch(Exception e){network.abort();throw e;}
        finally{if(pool!=null)pool.shutdownNow();if(!assigned)network.release(first);}
    }
    private static void range(HttpURLConnection connection,long from,long to,long size)throws IOException {
        if(connection.getResponseCode()!=HttpURLConnection.HTTP_PARTIAL||!("bytes "+from+"-"+to+"/"+size).equals(connection.getHeaderField("Content-Range")))throw new IOException("Сервер вернул неверный диапазон APK");
        length(connection,to-from+1);
    }
    private static void length(HttpURLConnection connection,long expected)throws IOException {
        long actual=connection.getContentLengthLong();
        if(actual>=0&&actual!=expected)throw new IOException("Размер APK отличается от указанного в релизе");
    }
    private static void copy(File file,long offset,long expected,HttpURLConnection connection,Connections network,AtomicLong received,Progress progress)throws Exception {
        try(InputStream in=connection.getInputStream();RandomAccessFile out=new RandomAccessFile(file,"rw")){
            out.seek(offset);byte[] block=new byte[128*1024];long count=0;
            while(true){
                network.check();int n=in.read(block);if(n==-1)break;
                network.check();if(count+n>expected)throw new IOException("APK превышает ожидаемый размер");
                out.write(block,0,n);count+=n;progress.bytes(received.addAndGet(n));
            }
            if(count!=expected)throw new IOException("APK скачан не полностью");
        }
    }
}
