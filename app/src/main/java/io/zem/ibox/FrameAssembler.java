package io.zem.ibox;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Reassembles the 20-byte Jieli media envelope; tolerates reorder and duplicate datagrams. */
public final class FrameAssembler {
    public record Frame(int type,long sequence,long timestamp,byte[] data) {}
    private static final int MAX=6*1024*1024;
    private static final class Part {byte[] data;BitSet seen;long created;int type;long seq,ts;Part(int size,int type,long seq,long ts){data=new byte[size];seen=new BitSet(size);created=System.nanoTime();this.type=type;this.seq=seq;this.ts=ts;}}
    private final LinkedHashMap<String,Part> pending=new LinkedHashMap<>();
    private final LinkedHashSet<String> completed=new LinkedHashSet<>();
    public long packets,duplicates,invalid,dropped,frames;
    public List<Frame> acceptTcp(byte[] header,byte[] payload)throws IOException {
        int size=TcpMediaReader.bodySize(header);
        if(size!=payload.length)throw new IOException("Incomplete TCP media frame: "+payload.length+"/"+size);
        if(payloadSize(header)!=0){byte[] packet=new byte[20+size];System.arraycopy(header,0,packet,0,20);System.arraycopy(payload,0,packet,20,size);return accept(packet,packet.length);}
        ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);int type=h.get()&127;h.position(4);
        long sequence=Integer.toUnsignedLong(h.getInt());h.position(16);long timestamp=Integer.toUnsignedLong(h.getInt());
        packets++;frames++;return List.of(new Frame(type,sequence,timestamp,payload));
    }
    public List<Frame> accept(byte[] bytes,int size) {
        List<Frame> out=new ArrayList<>();int pos=0;
        pending.entrySet().removeIf(e->{if(System.nanoTime()-e.getValue().created>2_000_000_000L){dropped++;return true;}return false;});
        while(size-pos>=20){
            ByteBuffer b=ByteBuffer.wrap(bytes,pos,20).order(ByteOrder.LITTLE_ENDIAN);
            int type=b.get()&127;b.get();int length=Short.toUnsignedInt(b.getShort());long seq=Integer.toUnsignedLong(b.getInt());int total=b.getInt(),offset=b.getInt();long time=Integer.toUnsignedLong(b.getInt());
            if(type<1||type>8||length<1||total<1||total>MAX||offset<0||offset>total-length||length>size-pos-20){invalid++;break;}
            packets++;String key=type+":"+seq;
            if(completed.contains(key)){duplicates++;pos+=20+length;continue;}
            Part p=pending.get(key);
            if(p==null){while(pending.size()>=6){pending.remove(pending.keySet().iterator().next());dropped++;}p=new Part(total,type,seq,time);pending.put(key,p);}
            if(p.data.length!=total){invalid++;pending.remove(key);break;}
            if(p.seen.nextClearBit(offset)>=offset+length)duplicates++;
            System.arraycopy(bytes,pos+20,p.data,offset,length);p.seen.set(offset,offset+length);
            if(p.seen.nextClearBit(0)>=total){pending.remove(key);completed.add(key);while(completed.size()>32)completed.remove(completed.iterator().next());frames++;out.add(new Frame(type,seq,time,p.data));}
            pos+=20+length;
            // Some firmware appends a 32-byte 0x88 trailer to a UDP datagram.
            boolean trailer=size-pos>=32;for(int i=0;trailer&&i<32;i++)trailer=(bytes[pos+i]&255)==136;
            if(trailer)pos+=32;
        }
        return out;
    }
    public static int payloadSize(byte[] header){return (header[2]&255)|((header[3]&255)<<8);}
}
