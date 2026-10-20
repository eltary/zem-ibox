package io.zem.ibox;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Wire format only. No code or binary libraries from the original app. */
public final class CtpCodec {
    public record Frame(String topic, String json) {}
    public static byte[] encode(String topic, String json) throws IOException {
        byte[] t = topic.getBytes(StandardCharsets.UTF_8), j = json.getBytes(StandardCharsets.UTF_8);
        if (t.length < 1 || t.length > 1024 || j.length > 8*1024*1024) throw new IOException("CTP frame too large");
        return ByteBuffer.allocate(10+t.length+j.length).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[]{67,84,80,58}).putShort((short)t.length).put(t).putInt(j.length).put(j).array();
    }
    public static Frame read(InputStream in) throws IOException {
        byte[] h = exact(in,6);
        if (h[0]!=67 || h[1]!=84 || h[2]!=80 || h[3]!=58) throw new IOException("Invalid CTP signature");
        int n = (h[4]&255)|((h[5]&255)<<8);
        if (n<1 || n>1024) throw new IOException("Invalid CTP topic length: "+n);
        String topic = new String(exact(in,n),StandardCharsets.UTF_8);
        int size = ByteBuffer.wrap(exact(in,4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if(size<0 || size>8*1024*1024) throw new IOException("Invalid CTP JSON length: "+size);
        return new Frame(topic,new String(exact(in,size),StandardCharsets.UTF_8));
    }
    static byte[] exact(InputStream in,int n) throws IOException {
        byte[] out = new byte[n]; int p=0;
        while(p<n) { int count=in.read(out,p,n-p); if(count<0) throw new EOFException("CTP connection closed at "+p+"/"+n); if(count==0) continue; p+=count; }
        return out;
    }
}
