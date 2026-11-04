package io.zem.ibox;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Only recorder audio. Never SETUP a second video track for the Jieli preview. */
final class RtspAudioTrack {
    private int lastSequence=-1;
    final int payload;final String codec;final URI control;
    private RtspAudioTrack(int payload,String codec,URI control){this.payload=payload;this.codec=codec;this.control=control;}
    static RtspAudioTrack parse(String sdp,URI base){
        for(String section:sdp.split("(?m)(?=^m=)")){
            if(!section.startsWith("m=audio "))continue;
            String[] fields=section.split("[\\r\\n]",2)[0].trim().split(" +");if(fields.length<4)continue;
            Map<Integer,String> codecs=new HashMap<>();codecs.put(0,"PCMU");codecs.put(8,"PCMA");
            Matcher map=Pattern.compile("(?im)^a=rtpmap:([0-9]+) (L16|PCMU|PCMA)/8000(?:/1)?\\s*$").matcher(section);while(map.find())codecs.put(Integer.parseInt(map.group(1)),map.group(2).toUpperCase(Locale.ROOT));
            Matcher path=Pattern.compile("(?im)^a=control:([^\\r\\n]+)").matcher(section);if(!path.find())continue;String value=path.group(1).trim();if(value.equals("*"))continue;
            for(int i=3;i<fields.length;i++)try{int pt=Integer.parseInt(fields[i]);if(codecs.containsKey(pt)){URI control=base.resolve(value);if(!Objects.equals(base.getHost(),control.getHost())||base.getPort()!=control.getPort()||!"rtsp".equalsIgnoreCase(control.getScheme()))return null;return new RtspAudioTrack(pt,codecs.get(pt),control);}}catch(IllegalArgumentException ignored){}
        }
        return null;
    }
    byte[] pcm(byte[] framed){
        if(framed.length<16||framed[0]!='$'||(framed[1]&255)!=0)return null;
        int p=4;if((framed[p]&192)!=128||(framed[p+1]&127)!=payload)return null;int end=framed.length;
        int header=12+4*(framed[p]&15);if(p+header>end)return null;
        if((framed[p]&16)!=0){if(p+header+4>end)return null;int words=((framed[p+header+2]&255)<<8)|(framed[p+header+3]&255);header+=4+words*4;if(p+header>end)return null;}
        if((framed[p]&32)!=0){int padding=framed[end-1]&255;if(padding<1||padding>end-p-header)return null;end-=padding;}
        int sequence=((framed[6]&255)<<8)|(framed[7]&255);if(lastSequence>=0&&(short)(sequence-lastSequence)<=0)return null;lastSequence=sequence;
        p+=header;if(codec.equals("L16")){if(((end-p)&1)!=0)return null;byte[] pcm=new byte[end-p];for(int i=0;p<end;p+=2,i+=2){pcm[i]=framed[p+1];pcm[i+1]=framed[p];}return pcm;}
        byte[] pcm=new byte[(end-p)*2];for(int i=0;p<end;p++,i+=2){int value=framed[p]&255,sample;
            if(codec.equals("PCMU")){value=~value&255;sample=(((value&15)<<3)+132)<<((value>>4)&7);sample=(value&128)!=0?132-sample:sample-132;}
            else{value^=85;int segment=(value>>4)&7;sample=(value&15)<<4;sample+=segment==0?8:264;if(segment>1)sample<<=segment-1;if((value&128)==0)sample=-sample;}
            pcm[i]=(byte)sample;pcm[i+1]=(byte)(sample>>8);
        }return pcm;
    }
}
