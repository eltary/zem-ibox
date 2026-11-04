package io.zem.ibox;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** RFC 2435 JPEG/PCM depacketization for phone capture; playback remains native. */
final class RtpJpeg {
    private final Consumer<FrameAssembler.Frame> output;private final int jpegPayload,audioPayload;
    private final Map<Integer,byte[]> quantizers=new HashMap<>();private ByteArrayOutputStream image;
    private long timestamp=-1,baseTimestamp=-1,baseUs;private int scanBytes;
    private static final byte[] HUFFMAN=hex("ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa");
    private static final int[] ZIGZAG={0,1,8,16,9,2,3,10,17,24,32,25,18,11,4,5,12,19,26,33,40,48,41,34,27,20,13,6,7,14,21,28,35,42,49,56,57,50,43,36,29,22,15,23,30,37,44,51,58,59,52,45,38,31,39,46,53,60,61,54,47,55,62,63};
    private static final int[] LUMA={16,11,10,16,24,40,51,61,12,12,14,19,26,58,60,55,14,13,16,24,40,57,69,56,14,17,22,29,51,87,80,62,18,22,37,56,68,109,103,77,24,35,55,64,81,104,113,92,49,64,78,87,103,121,120,101,72,92,95,98,112,100,103,99};
    private static final int[] CHROMA={17,18,24,47,99,99,99,99,18,21,26,66,99,99,99,99,24,26,56,99,99,99,99,99,47,66,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99,99};
    RtpJpeg(int jpegPayload,int audioPayload,Consumer<FrameAssembler.Frame> output){this.jpegPayload=jpegPayload;this.audioPayload=audioPayload;this.output=output;}
    synchronized void accept(byte[] packet)throws IOException {
        if(packet.length<16||packet[0]!='$')return;int start=4,end=packet.length;if((packet[start]&192)!=128)return;int flags=packet[start]&255,pt=packet[start+1]&127;boolean last=(packet[start+1]&128)!=0;long ts=u32(packet,start+4);int sequence=u16(packet,start+2);int pos=start+12+(flags&15)*4;
        if((flags&16)!=0){if(pos+4>end)return;pos+=4+4*u16(packet,pos+2);}if((flags&32)!=0){int padding=packet[end-1]&255;if(padding==0||padding>end-pos)return;end-=padding;}if(pos>end)return;
        if(pt==audioPayload){int size=end-pos;if((size&1)!=0)return;byte[] pcm=new byte[size];for(int i=0;i<size;i+=2){pcm[i]=packet[pos+i+1];pcm[i+1]=packet[pos+i];}output.accept(new FrameAssembler.Frame(1,sequence,ts,pcm));return;}
        if(pt!=jpegPayload||pos+8>end)return;int offset=((packet[pos+1]&255)<<16)|u16(packet,pos+2),type=packet[pos+4]&255,q=packet[pos+5]&255,w=(packet[pos+6]&255)*8,h=(packet[pos+7]&255)*8;pos+=8;if(w==0)w=2048;if(h==0)h=2048;int restart=0;
        if(type>=64&&type<=127){if(pos+4>end)return;restart=u16(packet,pos);pos+=4;type-=64;}if(type>1)throw new IOException("Неподдерживаемый тип RTP/JPEG: "+type);
        if(offset==0){byte[] quant;
            if(q>=128){if(pos+4>end)return;int precision=packet[pos+1]&255,n=u16(packet,pos+2);pos+=4;if(precision!=0||n!=0&&n!=64&&n!=128||pos+n>end)throw new IOException("Неподдерживаемые таблицы RTP/JPEG");if(n>0){quant=Arrays.copyOfRange(packet,pos,pos+n);if(n==64){quant=Arrays.copyOf(quant,128);System.arraycopy(quant,0,quant,64,64);}quantizers.put(q,quant);}else quant=quantizers.get(q);pos+=n;}
            else{if(q==0||q>99)throw new IOException("Неверное качество RTP/JPEG");quant=quantizers.get(q);if(quant==null){quant=new byte[128];int scale=q<50?5000/q:200-2*q;for(int i=0;i<64;i++){quant[i]=(byte)Math.max(1,Math.min(255,(LUMA[ZIGZAG[i]]*scale+50)/100));quant[64+i]=(byte)Math.max(1,Math.min(255,(CHROMA[ZIGZAG[i]]*scale+50)/100));}quantizers.put(q,quant);}}
            if(quant==null){image=null;return;}image=new ByteArrayOutputStream(65536);image.write(255);image.write(216);
            for(int table=0;table<2;table++){image.write(new byte[]{(byte)255,(byte)219,0,67,(byte)table});image.write(quant,table*64,64);}if(restart>0)image.write(new byte[]{(byte)255,(byte)221,0,4,(byte)(restart>>8),(byte)restart});
            image.write(new byte[]{(byte)255,(byte)192,0,17,8,(byte)(h>>8),(byte)h,(byte)(w>>8),(byte)w,3,1,(byte)(type==0?33:34),0,2,17,1,3,17,1});image.write(HUFFMAN);image.write(new byte[]{(byte)255,(byte)218,0,12,3,1,0,2,17,3,17,0,63,0});timestamp=ts;scanBytes=0;
        }
        if(image==null||timestamp!=ts||scanBytes!=offset){image=null;return;}if(image.size()+end-pos>6*1024*1024){image=null;throw new IOException("RTP/JPEG кадр слишком большой");}image.write(packet,pos,end-pos);scanBytes+=end-pos;
        if(last){image.write(255);image.write(217);if(baseTimestamp<0){baseTimestamp=ts;baseUs=System.nanoTime()/1000;}long pts=baseUs+((ts-baseTimestamp)&0xffffffffL)*1000000/90000;output.accept(new FrameAssembler.Frame(2,sequence,ts,image.toByteArray(),pts));image=null;}
    }
    private static int u16(byte[] b,int p){return ((b[p]&255)<<8)|(b[p+1]&255);}private static long u32(byte[] b,int p){return ((long)u16(b,p)<<16)|u16(b,p+2);}
    private static byte[] hex(String value){byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out;}
}
