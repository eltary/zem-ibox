package io.zem.ibox;
import java.nio.ByteBuffer;
import java.util.*;
final class NalUnits {
    record Headers(byte[] sps,byte[] pps,boolean key){}
    static Headers headers(byte[] data){
        byte[] sps=null,pps=null;boolean key=false;int begin=-1;
        for(int i=0;i<data.length;){int n=startLength(data,i);if(n>0){if(begin>=0&&i>begin){int type=data[begin]&31;if(type==7)sps=Arrays.copyOfRange(data,begin,i);if(type==8)pps=Arrays.copyOfRange(data,begin,i);if(type==5)key=true;}begin=i+n;i+=n;}else i++;}
        if(begin>=0&&begin<data.length){int type=data[begin]&31;if(type==7)sps=Arrays.copyOfRange(data,begin,data.length);if(type==8)pps=Arrays.copyOfRange(data,begin,data.length);if(type==5)key=true;}
        if(begin<0){for(int i=0;i+4<=data.length;){int length=ByteBuffer.wrap(data,i,4).getInt();i+=4;if(length<1||length>data.length-i)break;int type=data[i]&31;if(type==7)sps=Arrays.copyOfRange(data,i,i+length);if(type==8)pps=Arrays.copyOfRange(data,i,i+length);if(type==5)key=true;i+=length;}}
        return new Headers(sps,pps,key);
    }
    static int startLength(byte[] b,int i){if(i+3<b.length&&b[i]==0&&b[i+1]==0&&b[i+2]==0&&b[i+3]==1)return 4;if(i+2<b.length&&b[i]==0&&b[i+1]==0&&b[i+2]==1)return 3;return 0;}
    static List<byte[]> split(byte[] b){
        List<byte[]> list=new ArrayList<>();int begin=-1;
        for(int i=0;i<b.length;){int n=startLength(b,i);if(n>0){if(begin>=0&&i>begin)list.add(Arrays.copyOfRange(b,begin,i));begin=i+n;i+=n;}else i++;}
        if(begin>=0&&begin<b.length)list.add(Arrays.copyOfRange(b,begin,b.length));
        if(list.isEmpty()){int i=0;while(i+4<=b.length){int n=ByteBuffer.wrap(b,i,4).getInt();i+=4;if(n<1||n>b.length-i){list.clear();break;}list.add(Arrays.copyOfRange(b,i,i+n));i+=n;}}
        return list;
    }
    static byte[] annex(byte[] nal){byte[] b=new byte[nal.length+4];b[3]=1;System.arraycopy(nal,0,b,4,nal.length);return b;}
    static boolean key(byte[] b){return headers(b).key();}
    static byte[] muxSample(byte[] data){
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        for(byte[] n:split(data)){int type=n[0]&31;if(type!=1&&type!=5)continue;byte[] part=annex(n);out.write(part,0,part.length);}
        return out.toByteArray();
    }
    static int[] dimensions(byte[] sps){
        try{Bits b=bits(sps);int profile=b.read(8);b.read(16);b.ue();int chroma=1,separate=0;
            if(Set.of(100,110,122,244,44,83,86,118,128,138,139,134,135).contains(profile)){chroma=b.ue();if(chroma==3)separate=b.read(1);b.ue();b.ue();b.read(1);if(b.read(1)!=0)for(int i=0;i<(chroma==3?12:8);i++)if(b.read(1)!=0){int last=8,next=8;for(int j=0;j<(i<6?16:64);j++){if(next!=0)next=(last+b.se()+256)%256;last=next==0?last:next;}}}
            b.ue();int poc=b.ue();if(poc==0)b.ue();else if(poc==1){b.read(1);b.se();b.se();int n=b.ue();if(n>256)return null;for(int i=0;i<n;i++)b.se();}
            b.ue();b.read(1);int w=(b.ue()+1)*16,h=b.ue()+1,frame=b.read(1);h*=16*(2-frame);if(frame==0)b.read(1);b.read(1);
            if(b.read(1)!=0){int left=b.ue(),right=b.ue(),top=b.ue(),bottom=b.ue(),array=separate==1?0:chroma;int x=array==0?1:array==3?1:2,y=(array==0?1:array==1?2:1)*(2-frame);w-=(left+right)*x;h-=(top+bottom)*y;}
            return w>0&&h>0&&w<=8192&&h<=4320?new int[]{w,h}:null;
        }catch(RuntimeException invalid){return null;}
    }
    private static Bits bits(byte[] sps){java.io.ByteArrayOutputStream rbsp=new java.io.ByteArrayOutputStream();int zeros=0;for(int i=1;i<sps.length;i++){int v=sps[i]&255;if(zeros>=2&&v==3){zeros=0;continue;}rbsp.write(v);zeros=v==0?zeros+1:0;}return new Bits(rbsp.toByteArray());}
    static double frameRate(byte[] sps){
        try{
            java.io.ByteArrayOutputStream rbsp=new java.io.ByteArrayOutputStream();int zeros=0;
            for(int i=1;i<sps.length;i++){int v=sps[i]&255;if(zeros>=2&&v==3){zeros=0;continue;}rbsp.write(v);zeros=v==0?zeros+1:0;}
            Bits b=new Bits(rbsp.toByteArray());int profile=b.read(8);b.read(16);b.ue();int chroma=1;
            if(Set.of(100,110,122,244,44,83,86,118,128,138,139,134,135).contains(profile)){chroma=b.ue();if(chroma==3)b.read(1);b.ue();b.ue();b.read(1);if(b.read(1)!=0)for(int i=0;i<(chroma==3?12:8);i++)if(b.read(1)!=0){int last=8,next=8;for(int j=0;j<(i<6?16:64);j++){if(next!=0)next=(last+b.se()+256)%256;last=next==0?last:next;}}}
            b.ue();int poc=b.ue();if(poc==0)b.ue();else if(poc==1){b.read(1);b.se();b.se();int n=b.ue();if(n>256)return 0;for(int i=0;i<n;i++)b.se();}
            b.ue();b.read(1);b.ue();b.ue();int frame=b.read(1);if(frame==0)b.read(1);b.read(1);if(b.read(1)!=0){b.ue();b.ue();b.ue();b.ue();}
            if(b.read(1)==0)return 0;
            if(b.read(1)!=0&&b.read(8)==255){b.read(16);b.read(16);}if(b.read(1)!=0)b.read(1);
            if(b.read(1)!=0){b.read(3);b.read(1);if(b.read(1)!=0)b.read(24);}if(b.read(1)!=0){b.ue();b.ue();}
            if(b.read(1)==0)return 0;long units=b.u32(),scale=b.u32();b.read(1);double fps=scale/(2.0*units);return fps>=1&&fps<=60?fps:0;
        }catch(RuntimeException invalid){return 0;}
    }
    private static final class Bits {
        final byte[] data;int bit;
        Bits(byte[] data){this.data=data;}
        int read(int n){if(n>31||bit+n>data.length*8)throw new IllegalArgumentException();int v=0;while(n-->0){v=(v<<1)|((data[bit/8]>>(7-bit%8))&1);bit++;}return v;}
        long u32(){return ((long)read(16)<<16)|read(16);}
        int ue(){int n=0;while(read(1)==0)if(++n>24)throw new IllegalArgumentException();return (1<<n)-1+(n==0?0:read(n));}
        int se(){int n=ue();return (n&1)==0?-n/2:(n+1)/2;}
    }

}
