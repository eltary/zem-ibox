package io.zem.ibox;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** A private loopback endpoint; only its fixed device URL is fetched over the selected Wi-Fi. */
final class WifiMediaProxy implements AutoCloseable {
    private static final int[] HEADER_END={13,10,13,10};
    private final ServerSocket server;
    private final Network network;
    private final URL source;
    private final String token="/"+UUID.randomUUID();
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final Set<HttpURLConnection> upstream=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"Wi-Fi playback relay");t.setDaemon(true);return t;});
    private volatile boolean closed;
    WifiMediaProxy(Network network,URL source)throws IOException {
        if(network==null)throw new IOException("Нет подключения к Wi-Fi регистратора");
        this.network=network;this.source=source;server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));
        Thread accept=new Thread(()->{while(!closed)try{Socket client=server.accept();client.setSoTimeout(5000);clients.add(client);try{workers.execute(()->serve(client));}catch(RejectedExecutionException e){clients.remove(client);client.close();}}catch(IOException e){if(!closed)Diag.error("Playback relay",e);}},"Playback loopback");accept.setDaemon(true);accept.start();
    }
    String address(){return "http://127.0.0.1:"+server.getLocalPort()+token;}
    private void serve(Socket client){
        HttpURLConnection conn=null;
        try(client){
            InputStream input=client.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int matched=0;
            while(matched<4){int b=input.read();if(b<0)return;if(header.size()>=8192){reply(client,431,"Header too large");return;}header.write(b);int expected=HEADER_END[matched];matched=b==expected?matched+1:b==13?1:0;}
            String[] lines=new String(header.toByteArray(),StandardCharsets.US_ASCII).split("\r\n");String[] request=lines[0].split(" ");
            if(request.length!=3||!request[1].equals(token)){reply(client,404,"Not found");return;}
            boolean head=request[0].equals("HEAD");if(!head&&!request[0].equals("GET")){reply(client,405,"Method not allowed");return;}
            String range=null;for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0&&lines[i].substring(0,colon).equalsIgnoreCase("Range"))range=lines[i].substring(colon+1).trim();}
            if(range!=null&&!range.matches("bytes=[0-9]+-[0-9]*")){reply(client,416,"Invalid range");return;}
            conn=(HttpURLConnection)network.openConnection(source);upstream.add(conn);conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(6000);conn.setReadTimeout(10000);conn.setRequestProperty("Accept-Encoding","identity");if(range!=null)conn.setRequestProperty("Range",range);
            int code=conn.getResponseCode();if(code!=200&&code!=206){reply(client,502,"Device HTTP "+code);return;}
            StringBuilder response=new StringBuilder("HTTP/1.1 ").append(code).append(code==206?" Partial Content\r\n":" OK\r\n").append("Connection: close\r\n");
            for(String name:new String[]{"Content-Type","Content-Length","Content-Range","Accept-Ranges"}){String value=conn.getHeaderField(name);if(value!=null&&!value.contains("\r")&&!value.contains("\n"))response.append(name).append(": ").append(value).append("\r\n");}
            OutputStream output=client.getOutputStream();output.write(response.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
            if(!head)try(InputStream body=conn.getInputStream()){byte[] buffer=new byte[64*1024];int count;while(!closed&&(count=body.read(buffer))!=-1)output.write(buffer,0,count);}output.flush();
            Diag.log("PLAY HTTP","Wi-Fi relay HTTP="+code+" range="+range);
        }catch(IOException e){if(!closed)Diag.log("PLAY HTTP","Request ended: "+e.getClass().getSimpleName());}
        finally{clients.remove(client);if(conn!=null){upstream.remove(conn);conn.disconnect();}}
    }
    private static void reply(Socket client,int status,String message)throws IOException{byte[] body=message.getBytes(StandardCharsets.UTF_8);client.getOutputStream().write(("HTTP/1.1 "+status+" Error\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(body);}
    public void close(){closed=true;try{server.close();}catch(IOException ignored){}for(Socket client:clients)try{client.close();}catch(IOException ignored){}for(HttpURLConnection conn:upstream)conn.disconnect();workers.shutdownNow();}
}
