package io.zem.iboxdrive;
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
}
