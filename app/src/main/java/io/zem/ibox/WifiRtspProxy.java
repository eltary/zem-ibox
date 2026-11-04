package io.zem.ibox;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** RTSP-over-TCP bridge. Native code sees loopback; DVR sockets use captured Wi-Fi. */
final class WifiRtspProxy implements AutoCloseable {
    private final Network network;private final URI source;private final ServerSocket server;private final boolean udpTransport;
    private final String token="/"+UUID.randomUUID()+"/",remoteBase,localBase;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();private final Semaphore slots=new Semaphore(2);
    private volatile boolean closed;volatile int lastStatus;volatile long mediaPackets;private volatile String bridgeError="";
    volatile java.util.function.Consumer<byte[]> observer;volatile Runnable requireUdp;
    private static final Pattern RTSP=Pattern.compile("rtsp://[^/\\s]+/?",Pattern.CASE_INSENSITIVE);
    WifiRtspProxy(Network network,URI source)throws IOException {this(network,source,false);}
    WifiRtspProxy(Network network,URI source,boolean udpTransport)throws IOException {
        this.udpTransport=udpTransport;
        if(network==null)throw new IOException("Нет Wi-Fi регистратора");this.network=network;this.source=source;
        server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",0));remoteBase="rtsp://"+source.getHost()+":"+(source.getPort()<0?554:source.getPort())+"/";localBase="rtsp://127.0.0.1:"+server.getLocalPort()+token;
        Thread accept=new Thread(()->{while(!closed)try{Socket client=server.accept();if(!slots.tryAcquire()){client.close();continue;}sockets.add(client);Thread worker=new Thread(()->serve(client),"Wi-Fi RTSP bridge");worker.setDaemon(true);worker.start();}catch(IOException e){if(!closed)Diag.log("RTSP","Accept ended: "+e.getClass().getSimpleName());}},"RTSP loopback");accept.setDaemon(true);accept.start();
    }
    String address(){return localBase+source.getRawPath().replaceFirst("^/","");}
    private final class UdpTrack implements AutoCloseable {
        final DatagramSocket rtp,rtcp;final int channel;
        UdpTrack(int channel)throws IOException {this.channel=channel;DatagramSocket first=null,second=null;
            for(int attempt=0;attempt<20;attempt++){try{first=new DatagramSocket(null);network.bindSocket(first);first.bind(new InetSocketAddress(0));int port=first.getLocalPort();if((port&1)!=0||port==65535){first.close();first=null;continue;}second=new DatagramSocket(null);network.bindSocket(second);second.bind(new InetSocketAddress(port+1));break;}catch(IOException e){if(first!=null)first.close();if(second!=null)second.close();first=null;second=null;}}
            if(first==null||second==null)throw new IOException("Нет свободной пары UDP портов RTSP");rtp=first;rtcp=second;rtp.setReceiveBufferSize(2*1024*1024);rtp.setSoTimeout(1000);rtcp.setSoTimeout(1000);
        }
        public void close(){rtp.close();rtcp.close();}
    }
    private void serve(Socket client){Socket remote=null;List<UdpTrack> tracks=new CopyOnWriteArrayList<>();Map<String,UdpTrack> pending=new ConcurrentHashMap<>();Object clientWrite=new Object(),remoteWrite=new Object();
        try{client.setSoTimeout(udpTransport?0:30000);client.setTcpNoDelay(true);BufferedInputStream input=new BufferedInputStream(client.getInputStream());
            byte[] first=message(input);String request=new String(first,StandardCharsets.ISO_8859_1);String[] line=request.split("\r\n",2)[0].split(" ");
            if(line.length<2||!line[1].startsWith(localBase)){client.getOutputStream().write("RTSP/1.0 403 Forbidden\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));return;}
            remote=network.getSocketFactory().createSocket();sockets.add(remote);remote.setTcpNoDelay(true);remote.connect(new InetSocketAddress(source.getHost(),source.getPort()<0?554:source.getPort()),6000);remote.setSoTimeout(udpTransport?0:30000);Socket upstream=remote;
            Diag.log("RTSP CONTROL",line[0]+" "+source.getPath()+" via "+(udpTransport?"UDP":"TCP"));OutputStream out=upstream.getOutputStream();out.write(rewrite(first,true));out.flush();
            Thread requests=new Thread(()->{try{while(!closed){byte[] packet=message(input);if(packet[0]!='$'){String method=new String(packet,StandardCharsets.ISO_8859_1).split(" ",2)[0];Diag.log("RTSP CONTROL",method+" via "+(udpTransport?"UDP":"TCP"));}
                if(udpTransport&&packet[0]=='$'){int channel=packet[1]&255;for(UdpTrack track:tracks)if(channel==track.channel||channel==track.channel+1){DatagramSocket socket=channel==track.channel?track.rtp:track.rtcp;if(socket.isConnected())socket.send(new DatagramPacket(packet,4,packet.length-4));}continue;}
                if(udpTransport){String text=new String(packet,StandardCharsets.ISO_8859_1);if(text.startsWith("SETUP ")){Matcher channels=Pattern.compile("(?i)interleaved=([0-9]+)-([0-9]+)").matcher(text);if(!channels.find())throw new IOException("RTSP requires interleaved client transport");int channel=Integer.parseInt(channels.group(1));if(channel>254||Integer.parseInt(channels.group(2))!=channel+1||tracks.size()>=4)throw new IOException("Invalid RTSP channels");UdpTrack track=new UdpTrack(channel);tracks.add(track);pending.put(header(text,"CSeq"),track);text=text.replaceAll("(?im)^Transport:[^\r\n]*","Transport: RTP/AVP;unicast;client_port="+track.rtp.getLocalPort()+"-"+track.rtcp.getLocalPort());packet=text.getBytes(StandardCharsets.ISO_8859_1);}}
                synchronized(remoteWrite){upstream.getOutputStream().write(rewrite(packet,true));upstream.getOutputStream().flush();}
            }}catch(IOException error){if(!closed){bridgeError=error.getClass().getSimpleName()+": "+error.getMessage();Diag.log("RTSP","Request side ended: "+bridgeError);}}finally{closeSocket(client);closeSocket(upstream);for(UdpTrack track:tracks)track.close();}},"RTSP requests");requests.setDaemon(true);requests.start();
            InputStream response=new BufferedInputStream(upstream.getInputStream());OutputStream destination=client.getOutputStream();
            while(!closed){byte[] packet=message(response);
                if(packet[0]!='$'){String text=new String(packet,StandardCharsets.ISO_8859_1);
                    String[] status=text.split("[ \r\n]",3);if(status.length>1)try{lastStatus=Integer.parseInt(status[1]);}catch(NumberFormatException ignored){}Diag.log("RTSP CONTROL","Reply="+lastStatus+" cseq="+header(text,"CSeq")+" transport="+header(text,"Transport"));
                    if(!udpTransport&&text.startsWith("RTSP/1.0 461")){Runnable fallback=requireUdp;if(fallback!=null){fallback.run();return;}}
                    UdpTrack track=pending.remove(header(text,"CSeq"));if(track!=null){if(!text.startsWith("RTSP/1.0 200")){track.close();}else{Matcher ports=Pattern.compile("(?i)server_port=([0-9]+)-([0-9]+)").matcher(header(text,"Transport"));if(!ports.find())throw new IOException("RTSP server did not provide UDP ports");int rtp=Integer.parseInt(ports.group(1)),rtcp=Integer.parseInt(ports.group(2));if(rtp<1||rtcp<1||rtp>65535||rtcp>65535)throw new IOException("Invalid RTSP UDP ports");track.rtp.connect(upstream.getInetAddress(),rtp);track.rtcp.connect(upstream.getInetAddress(),rtcp);
                        text=text.replaceAll("(?im)^Transport:[^\r\n]*","Transport: RTP/AVP/TCP;unicast;interleaved="+track.channel+"-"+(track.channel+1));packet=text.getBytes(StandardCharsets.ISO_8859_1);
                        relayUdp(track,track.rtp,track.channel,destination,clientWrite,client);relayUdp(track,track.rtcp,track.channel+1,destination,clientWrite,client);
                    }}
                }
                observed(packet);synchronized(clientWrite){destination.write(rewrite(packet,false));destination.flush();}
            }
        }catch(IOException e){if(!closed){bridgeError=e.getClass().getSimpleName()+": "+e.getMessage();Diag.log("RTSP","Bridge ended: "+bridgeError);}}
        finally{closeSocket(client);closeSocket(remote);for(UdpTrack track:tracks)track.close();slots.release();}
    }
    String failureText(){return lastStatus>=400?"ответ "+lastStatus:mediaPackets==0?"видеопакеты не поступают":"пакеты поступают, декодер не выдал изображение";}
    private void observed(byte[] packet){if(packet.length>0&&packet[0]=='$')mediaPackets++;java.util.function.Consumer<byte[]> tap=observer;if(tap!=null)tap.accept(packet);}
    private void relayUdp(UdpTrack track,DatagramSocket socket,int channel,OutputStream destination,Object lock,Socket client){Thread worker=new Thread(()->{try{byte[] buffer=new byte[65535];while(!closed&&!socket.isClosed()){DatagramPacket packet=new DatagramPacket(buffer,buffer.length);try{socket.receive(packet);}catch(SocketTimeoutException timeout){continue;}int n=packet.getLength();byte[] framed=new byte[n+4];framed[0]='$';framed[1]=(byte)channel;framed[2]=(byte)(n>>8);framed[3]=(byte)n;System.arraycopy(buffer,0,framed,4,n);observed(framed);synchronized(lock){destination.write(framed);destination.flush();}}}catch(IOException e){if(!closed&&!socket.isClosed())closeSocket(client);}},"RTSP UDP relay");worker.setDaemon(true);worker.start();}
    private static String header(String text,String name){Matcher m=Pattern.compile("(?im)^"+Pattern.quote(name)+": *([^\r\n]*)").matcher(text);return m.find()?m.group(1).trim():"";}
    private byte[] rewrite(byte[] bytes,boolean toDevice)throws IOException {
        if(bytes.length>0&&bytes[0]=='$')return bytes;
        String message=new String(bytes,StandardCharsets.ISO_8859_1);int split=message.indexOf("\r\n\r\n");if(split<0)throw new IOException("Invalid RTSP message");
        String head=message.substring(0,split),body=message.substring(split+4);
        if(toDevice){String[] request=head.split("\r\n",2)[0].split(" ");if(request.length<2||!request[1].equals("*")&&!request[1].startsWith(localBase))throw new IOException("Invalid RTSP destination");head=head.replace(localBase,remoteBase);body=body.replace(localBase,remoteBase);}
        else{head=RTSP.matcher(head).replaceAll(Matcher.quoteReplacement(localBase));body=RTSP.matcher(body).replaceAll(Matcher.quoteReplacement(localBase));}
        int length=body.getBytes(StandardCharsets.ISO_8859_1).length;
        if(head.toLowerCase(Locale.ROOT).contains("content-length:"))head=head.replaceAll("(?im)^Content-Length:[^\r\n]*","Content-Length: "+length);
        return (head+"\r\n\r\n"+body).getBytes(StandardCharsets.ISO_8859_1);
    }
    static byte[] message(InputStream in)throws IOException {
        int first=in.read();if(first<0)throw new EOFException();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(first);
        if(first=='$'){byte[] header=exact(in,3);out.write(header);int n=((header[1]&255)<<8)|(header[2]&255);out.write(exact(in,n));return out.toByteArray();}
        int matched=first==13?1:0;int[] end={13,10,13,10};while(matched<4){int value=in.read();if(value<0)throw new EOFException();out.write(value);if(out.size()>32768)throw new IOException("RTSP header too large");matched=value==end[matched]?matched+1:value==13?1:0;}
        String head=new String(out.toByteArray(),StandardCharsets.ISO_8859_1);Matcher length=Pattern.compile("(?im)^Content-Length: *([0-9]+) *\r?$",Pattern.CASE_INSENSITIVE).matcher(head);int n=0;if(length.find()){try{n=Integer.parseInt(length.group(1));}catch(NumberFormatException e){throw new IOException("Invalid RTSP length");}if(n>1024*1024)throw new IOException("RTSP body too large");}out.write(exact(in,n));return out.toByteArray();
    }
    private static byte[] exact(InputStream in,int n)throws IOException {byte[] data=new byte[n];int p=0;while(p<n){int got=in.read(data,p,n-p);if(got<0)throw new EOFException();p+=got;}return data;}
    private void closeSocket(Socket socket){if(socket!=null){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}}
    public void close(){closed=true;try{server.close();}catch(IOException ignored){}for(Socket socket:sockets)closeSocket(socket);}
}
