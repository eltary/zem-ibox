package io.zem.ibox;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Bounded, audio-only fallback when a firmware's proprietary stream omits PCM. */
final class RtspAudioSource implements AutoCloseable {
    private final Network network;private final URI source;private final Consumer<byte[]> sink;private final Consumer<String> status;
    private volatile boolean closed;private volatile Socket socket;private volatile WifiRtspProxy proxy;
    private int sequence;private volatile String session="";private volatile URI playUri;
    RtspAudioSource(Network network,URI source,Consumer<byte[]> sink,Consumer<String> status){this.network=network;this.source=source;this.sink=sink;this.status=status;}
    void start(){Thread thread=new Thread(this::run,"Recorder audio RTSP");thread.setDaemon(true);thread.start();}
    private void run(){
        for(boolean udp:new boolean[]{true,false}){
            if(closed)return;
            try{receive(udp);return;}catch(IOException|IllegalArgumentException e){if(closed)return;Diag.log("LIVE AUDIO","RTSP "+(udp?"UDP":"TCP")+": "+e.getMessage());}finally{release();}
        }
        if(!closed)status.accept("Звук с регистратора не поступает");
    }
    private void receive(boolean udp)throws IOException {
        sequence=0;session="";proxy=new WifiRtspProxy(network,source,udp);URI local=URI.create(proxy.address());playUri=local;Socket client=new Socket();socket=client;
        client.connect(new InetSocketAddress("127.0.0.1",local.getPort()),2000);client.setSoTimeout(6000);InputStream in=new BufferedInputStream(client.getInputStream());
        request("DESCRIBE",local,"Accept: application/sdp\r\n");Response description=response(in);String base=description.headers.getOrDefault("content-base",local.toString()+"/");RtspAudioTrack track=RtspAudioTrack.parse(description.body,URI.create(base));if(track==null)throw new IOException("Нет поддерживаемой аудиодорожки в SDP");
        request("SETUP",track.control,"Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");Response setup=response(in);session=setup.headers.getOrDefault("session","").split(";",2)[0];if(session.isBlank())throw new IOException("Нет RTSP Session");
        request("PLAY",local,"");response(in);Diag.log("LIVE AUDIO","Audio-only RTSP "+(udp?"UDP":"TCP")+" codec="+track.codec+" payload="+track.payload);client.setSoTimeout(6000);long last=System.nanoTime(),keep=last;boolean heard=false;
        while(!closed){
            try{byte[] packet=WifiRtspProxy.message(in);byte[] pcm=track.pcm(packet);if(pcm!=null&&pcm.length>0){last=System.nanoTime();if(!heard){heard=true;status.accept("Звук регистратора · RTSP");}sink.accept(pcm);}}
            catch(SocketTimeoutException timeout){if(System.nanoTime()-last>6_000_000_000L)throw new IOException("Нет аудиопакетов за 6 секунд");}
            if(System.nanoTime()-last>6_000_000_000L)throw new IOException("Аудиодорожка не передаёт звук");
            if(System.nanoTime()-keep>8_000_000_000L){request("GET_PARAMETER",local,"");keep=System.nanoTime();}
        }
    }
    private void request(String method,URI target,String headers)throws IOException {
        if(closed)throw new IOException("Закрыто");String text=method+" "+target+" RTSP/1.0\r\nCSeq: "+(++sequence)+"\r\nUser-Agent: Zem-iBox\r\n"+(session.isEmpty()?"":"Session: "+session+"\r\n")+headers+"\r\n";Socket current=socket;if(current==null)throw new IOException("Закрыто");current.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));current.getOutputStream().flush();
    }
    private record Response(Map<String,String> headers,String body){}
    private Response response(InputStream in)throws IOException {
        byte[] packet=WifiRtspProxy.message(in);String text=new String(packet,StandardCharsets.ISO_8859_1);if(!text.startsWith("RTSP/1.0 200 "))throw new IOException(text.split("\r\n",2)[0]);int split=text.indexOf("\r\n\r\n");if(split<0)throw new IOException("Неверный RTSP ответ");Map<String,String> headers=new HashMap<>();for(String line:text.substring(0,split).split("\r\n")){int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}return new Response(headers,text.substring(split+4));
    }
    private void release(){
        Socket client;WifiRtspProxy bridge;String id;URI target;int seq;
        synchronized(this){client=socket;socket=null;bridge=proxy;proxy=null;id=session;target=playUri;seq=++sequence;}
        if(client!=null){if(!id.isEmpty()&&target!=null&&client.isConnected()&&!client.isClosed())try{String teardown="TEARDOWN "+target+" RTSP/1.0\r\nCSeq: "+seq+"\r\nSession: "+id+"\r\n\r\n";client.getOutputStream().write(teardown.getBytes(StandardCharsets.ISO_8859_1));client.getOutputStream().flush();Thread.sleep(30);}catch(IOException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}try{client.close();}catch(IOException ignored){}}
        if(bridge!=null)bridge.close();
    }
    public void close(){closed=true;Thread cleanup=new Thread(this::release,"Close recorder audio");cleanup.setDaemon(true);cleanup.start();}
}
