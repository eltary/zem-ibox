package io.zem.iboxdrive;
import java.io.*;
final class Io {
    static long copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[128*1024];long count=0;int n;while((n=in.read(b))!=-1){out.write(b,0,n);count+=n;}return count;}
    static String hex(byte[] b){char[] chars="0123456789abcdef".toCharArray(),out=new char[b.length*2];for(int i=0;i<b.length;i++){out[i*2]=chars[(b[i]&255)>>>4];out[i*2+1]=chars[b[i]&15];}return new String(out);}
}
