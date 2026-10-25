package io.zem.ibox;

import android.net.Network;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

/** Private loopback media source. Every device read stays on the captured Wi-Fi. */
final class WifiMediaProxy implements AutoCloseable {
    private static final int CHUNK=2*1024*1024;
    private static final Pattern CONTENT_RANGE=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private final ServerSocket server;
    private final Network network;
    private final URL source;
    private final String token="/"+UUID.randomUUID();
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final Set<HttpURLConnection> upstream=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"Wi-Fi playback relay");t.setDaemon(true);return t;});
    private final AtomicLong bytes=new AtomicLong();
    private volatile boolean closed;
    private volatile Metadata metadata;
    private volatile int lastCode;
    private volatile String lastError="none";
    private record Metadata(long size,String type,boolean ranged){}
    private record Span(long start,long end,long total){}

    WifiMediaProxy(Network network,URL source)throws IOException {
        if(network==null)throw new IOException("Нет подключения к Wi-Fi регистратора");
        this.network=network;this.source=source;server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));
        Thread accept=new Thread(()->{while(!closed)try{Socket client=server.accept();client.setSoTimeout(5000);clients.add(client);try{workers.execute(()->serve(client));}catch(RejectedExecutionException e){clients.remove(client);client.close();}}catch(IOException e){if(!closed)Diag.error("Playback relay",e);}},"Playback loopback");accept.setDaemon(true);accept.start();
    }
    String address(){return "http://127.0.0.1:"+server.getLocalPort()+token;}
    String stats(){return "relayBytes="+bytes.get()+" relayHTTP="+lastCode+" relayActive="+upstream.size()+" relayError="+lastError;}
    private void serve(Socket client){
        long started=SystemClock.elapsedRealtime(),sent=0;String range=null;boolean headersSent=false;
        try{
            InputStream input=client.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int matched=0;int[] delimiter={13,10,13,10};
            while(matched<4){int b=input.read();if(b<0)return;if(header.size()>=8192){reply(client,431,"Header too large",null);return;}header.write(b);matched=b==delimiter[matched]?matched+1:b==13?1:0;}
            String[] lines=new String(header.toByteArray(),StandardCharsets.US_ASCII).split("\r\n"),request=lines[0].split(" ");
            if(request.length!=3||!request[1].equals(token)){reply(client,404,"Not found",null);return;}
            boolean head=request[0].equals("HEAD");if(!head&&!request[0].equals("GET")){reply(client,405,"Method not allowed",null);return;}
            for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0&&lines[i].substring(0,colon).equalsIgnoreCase("Range"))range=lines[i].substring(colon+1).trim();}
            Metadata info=metadata();long[] span=parseRange(range,info.size);if(span==null){reply(client,416,"Range Not Satisfiable",info.size);return;}
            long start=span[0],end=span[1];if(!info.ranged&&start>0){reply(client,502,"Device does not support seeking",null);return;}
            boolean partial=range!=null&&info.ranged;OutputStream output=client.getOutputStream();
            String response="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nConnection: close\r\nContent-Type: "+info.type+"\r\nContent-Length: "+(end-start+1)+"\r\nAccept-Ranges: "+(info.ranged?"bytes":"none")+"\r\n"+(partial?"Content-Range: bytes "+start+"-"+end+"/"+info.size+"\r\n":"")+"\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));output.flush();headersSent=true;
            Diag.log("PLAY HTTP","Serve "+request[0]+" range="+range+" size="+info.size+" bounded="+info.ranged);
            if(!head){byte[] buffer=new byte[64*1024];long position=start;int retries=0;
                while(!closed&&position<=end){long chunkEnd=info.ranged?Math.min(end,position+CHUNK-1):end;HttpURLConnection conn=null;
                    try{conn=open(info.ranged?"bytes="+position+"-"+chunkEnd:null);int code=conn.getResponseCode();lastCode=code;
                        if(info.ranged){Span actual=contentRange(conn);if(code!=206||actual.start!=position||actual.end<chunkEnd||actual.total!=info.size)throw new IOException("Device returned inconsistent video range");}
                        else if(code!=200||conn.getContentLengthLong()!=info.size)throw new IOException("Device video changed");
                        try(InputStream body=conn.getInputStream()){while(!closed&&position<=chunkEnd){int count;
                            try{count=body.read(buffer,0,(int)Math.min(buffer.length,chunkEnd-position+1));if(count<0)throw new EOFException("Truncated device video");}
                            catch(IOException e){if(!info.ranged||retries++>=2)throw e;Diag.log("PLAY HTTP","Resume "+e.getClass().getSimpleName()+" at="+position);break;}
                            // Write failures mean the consumer cancelled/seeked; never retry those upstream.
                            try{output.write(buffer,0,count);}catch(SocketException cancelled){Diag.log("PLAY HTTP","Consumer cancelled/seeked");return;}position+=count;sent+=count;bytes.addAndGet(count);
                        }}
                    }finally{disconnect(conn);}
                }
            }output.flush();lastError="none";
        }catch(IOException e){if(!closed){lastError=e.getClass().getSimpleName();Diag.log("PLAY HTTP","Request ended: "+e.getClass().getSimpleName()+" "+e.getMessage());if(!headersSent)try{reply(client,502,"Device read failed",null);}catch(IOException ignored){}}}
        finally{try{client.close();}catch(IOException ignored){}clients.remove(client);Diag.log("PLAY HTTP","Finished range="+range+" bytes="+sent+" elapsed="+(SystemClock.elapsedRealtime()-started)+"ms");}
    }
    private synchronized Metadata metadata()throws IOException {
        if(metadata!=null)return metadata;HttpURLConnection conn=null;
        try{conn=open("bytes=0-0");int code=conn.getResponseCode();lastCode=code;long size;boolean ranged=code==206;
            if(ranged){Span span=contentRange(conn);if(span.start!=0)throw new IOException("Invalid device video start");size=span.total;}
            else if(code==200)size=conn.getContentLengthLong();else throw new IOException("Device HTTP "+code);
            if(size<=0)throw new IOException("Video size unavailable");String type=conn.getContentType();if(type==null||type.contains("\r")||type.contains("\n"))type="application/octet-stream";
            metadata=new Metadata(size,type,ranged);return metadata;
        }finally{disconnect(conn);}
    }
    private static Span contentRange(HttpURLConnection conn)throws IOException{String value=conn.getHeaderField("Content-Range");Matcher match=CONTENT_RANGE.matcher(value==null?"":value);if(!match.matches())throw new IOException("Invalid device Content-Range");try{long start=Long.parseLong(match.group(1)),end=Long.parseLong(match.group(2)),total=Long.parseLong(match.group(3));if(start>end||end>=total)throw new IOException("Invalid device range bounds");return new Span(start,end,total);}catch(NumberFormatException e){throw new IOException("Invalid device range number",e);}}
    static long[] parseRange(String range,long size){if(range==null)return new long[]{0,size-1};if(!range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))return null;try{String[] bounds=range.substring(6).split("-",-1);long start,end;if(bounds[0].isEmpty()){long suffix=Long.parseLong(bounds[1]);if(suffix<=0)return null;start=Math.max(0,size-suffix);end=size-1;}else{start=Long.parseLong(bounds[0]);end=bounds[1].isEmpty()?size-1:Math.min(size-1,Long.parseLong(bounds[1]));}return start>=size||end<start?null:new long[]{start,end};}catch(NumberFormatException e){return null;}}
    private HttpURLConnection open(String range)throws IOException{if(closed)throw new IOException("Playback closed");HttpURLConnection conn=(HttpURLConnection)network.openConnection(source);upstream.add(conn);if(closed){disconnect(conn);throw new IOException("Playback closed");}conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(6000);conn.setReadTimeout(5000);conn.setRequestProperty("Accept-Encoding","identity");conn.setRequestProperty("Connection","close");if(range!=null)conn.setRequestProperty("Range",range);return conn;}
    private void disconnect(HttpURLConnection conn){if(conn!=null){upstream.remove(conn);conn.disconnect();}}
    private static void reply(Socket client,int status,String message,Long total)throws IOException{byte[] body=message.getBytes(StandardCharsets.UTF_8);OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 "+status+" "+message+"\r\n"+(total==null?"":"Content-Range: bytes */"+total+"\r\n")+"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
    public void close(){closed=true;try{server.close();}catch(IOException ignored){}for(Socket client:clients)try{client.close();}catch(IOException ignored){}for(HttpURLConnection conn:upstream)conn.disconnect();workers.shutdownNow();}
}
