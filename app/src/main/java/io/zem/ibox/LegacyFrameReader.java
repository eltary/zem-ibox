package io.zem.ibox;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.function.BooleanSupplier;

/** Old metadata retrievers can return the same B-frame for adjacent seeks. Decode the presentation order. */
final class LegacyFrameReader {
    static Bitmap read(Context context,Uri source,long position,double fps,int width,int height,BooleanSupplier cancelled)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;boolean started=false;
        try{
            if("content".equals(source.getScheme())){try(AssetFileDescriptor fd=context.getContentResolver().openAssetFileDescriptor(source,"r")){if(fd==null)throw new IOException("Нет доступа к записи");if(fd.getLength()<0)extractor.setDataSource(fd.getFileDescriptor());else extractor.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());}}else extractor.setDataSource(source.toString(),Collections.emptyMap());
            MediaFormat format=null;String mime=null;for(int i=0;i<extractor.getTrackCount();i++){MediaFormat candidate=extractor.getTrackFormat(i);String kind=candidate.getString(MediaFormat.KEY_MIME);if(kind!=null&&kind.startsWith("video/")){format=candidate;mime=kind;extractor.selectTrack(i);break;}}
            if(format==null)throw new IOException("В записи нет видеодорожки");long target=position*1000,tolerance=Math.max(1,(long)(500000/Math.max(1,fps)));extractor.seekTo(Math.max(0,target-tolerance),MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int rotation=format.containsKey("rotation-degrees")?format.getInteger("rotation-degrees"):0;format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec=MediaCodec.createDecoderByType(mime);codec.configure(format,null,null,0);codec.start();started=true;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean inputEnded=false;long deadline=SystemClock.elapsedRealtime()+15000;
            while(SystemClock.elapsedRealtime()<deadline){if(cancelled.getAsBoolean())throw new InterruptedIOException("Покадровый просмотр остановлен");
                if(!inputEnded){int input=codec.dequeueInputBuffer(10000);if(input>=0){ByteBuffer bytes=codec.getInputBuffer(input);bytes.clear();int count=extractor.readSampleData(bytes,0);if(count<0){codec.queueInputBuffer(input,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true;}else{codec.queueInputBuffer(input,0,count,extractor.getSampleTime(),0);extractor.advance();}}}
                int output=codec.dequeueOutputBuffer(info,10000);if(output>=0){try{if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&info.presentationTimeUs>=target-tolerance){try(Image image=codec.getOutputImage(output)){if(image==null)throw new IOException("Декодер не открыл изображение кадра");Bitmap result=bitmap(image,codec.getOutputFormat(),width,height);if(rotation!=0){Matrix matrix=new Matrix();matrix.postRotate(rotation);Bitmap turned=Bitmap.createBitmap(result,0,0,result.getWidth(),result.getHeight(),matrix,true);if(turned!=result)result.recycle();result=turned;}return result;}}if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)break;}finally{codec.releaseOutputBuffer(output,false);}}
            }
            throw new IOException("Не удалось декодировать нужный кадр");
        }finally{if(codec!=null){try{if(started)codec.stop();}catch(Exception ignored){}codec.release();}extractor.release();}
    }
    private static Bitmap bitmap(Image image,MediaFormat format,int width,int height)throws IOException{
        if(image.getFormat()!=ImageFormat.YUV_420_888)throw new IOException("Формат кадра не поддерживается");Rect crop=image.getCropRect();double scale=Math.min(1,Math.min(width/(double)crop.width(),height/(double)crop.height()));int w=Math.max(1,(int)Math.round(crop.width()*scale)),h=Math.max(1,(int)Math.round(crop.height()*scale));int[] colors=new int[w*h];Image.Plane[] planes=image.getPlanes();ByteBuffer y=planes[0].getBuffer(),u=planes[1].getBuffer(),v=planes[2].getBuffer();int yBase=y.position(),uBase=u.position(),vBase=v.position();boolean full=format.containsKey("color-range")&&format.getInteger("color-range")==MediaFormat.COLOR_RANGE_FULL,bt709=format.containsKey("color-standard")&&format.getInteger("color-standard")==MediaFormat.COLOR_STANDARD_BT709;
        for(int row=0;row<h;row++){int sy=crop.top+Math.min(crop.height()-1,(int)(row/scale));for(int col=0;col<w;col++){int sx=crop.left+Math.min(crop.width()-1,(int)(col/scale));int yy=Math.max(0,(y.get(yBase+sy*planes[0].getRowStride()+sx*planes[0].getPixelStride())&255)-(full?0:16)),uu=(u.get(uBase+(sy/2)*planes[1].getRowStride()+(sx/2)*planes[1].getPixelStride())&255)-128,vv=(v.get(vBase+(sy/2)*planes[2].getRowStride()+(sx/2)*planes[2].getPixelStride())&255)-128;int base=yy*(full?256:298),red=(base+(full?(bt709?403:359):(bt709?459:409))*vv+128)>>8,green=(base-(full?(bt709?48:88):(bt709?55:100))*uu-(full?(bt709?120:183):(bt709?136:208))*vv+128)>>8,blue=(base+(full?(bt709?475:454):(bt709?541:516))*uu+128)>>8;colors[row*w+col]=Color.rgb(clamp(red),clamp(green),clamp(blue));}}
        return Bitmap.createBitmap(colors,w,h,Bitmap.Config.ARGB_8888);
    }
    private static int clamp(int channel){return Math.max(0,Math.min(255,channel));}
}
