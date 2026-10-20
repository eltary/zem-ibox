package io.zem.iboxdrive;

import java.io.*;
import java.nio.*;

/** Keeps TCP framing intact across short reads and socket timeouts. */
final class TcpMediaReader {
    record Packet(byte[] header,byte[] payload) {}
    private byte[] header=new byte[20],payload;
    private int headerRead,payloadRead;
    static int bodySize(byte[] header)throws IOException {
        if(header.length!=20)throw new IOException("Invalid TCP media header size");
        ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int type=h.get()&127;h.get();int fragment=Short.toUnsignedInt(h.getShort());
        h.getInt();int total=h.getInt(),offset=h.getInt();
        // iBOX TCP sends one whole frame: the UDP payload-length field is zero.
        int size=fragment==0?total:fragment;
        if(type<1||type>8||total<1||total>6*1024*1024||size<1||offset<0||offset>total-size||(fragment==0&&offset!=0))
            throw new IOException("Invalid TCP media header: type="+type+" fragment="+fragment+" total="+total+" offset="+offset);
        return size;
    }
    Packet read(InputStream in)throws IOException {
        while(headerRead<header.length){int n=in.read(header,headerRead,header.length-headerRead);if(n<0)throw new EOFException("TCP media header closed at "+headerRead+"/20");headerRead+=n;}
        if(payload==null)payload=new byte[bodySize(header)];
        while(payloadRead<payload.length){int n=in.read(payload,payloadRead,payload.length-payloadRead);if(n<0)throw new EOFException("TCP media frame closed at "+payloadRead+"/"+payload.length);payloadRead+=n;}
        Packet result=new Packet(header,payload);header=new byte[20];payload=null;headerRead=payloadRead=0;return result;
    }
}
