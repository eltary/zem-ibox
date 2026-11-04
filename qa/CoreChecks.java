package io.zem.ibox;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.net.SocketTimeoutException;
public final class CoreChecks {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static byte[] datagram(int type,int seq,int size,int offset,byte[] payload){return ByteBuffer.allocate(20+payload.length).order(ByteOrder.LITTLE_ENDIAN).put((byte)type).put((byte)0).putShort((short)payload.length).putInt(seq).putInt(size).putInt(offset).putInt(123).put(payload).array();}
    static void rejected(RunnableX r)throws Exception{try{r.run();throw new AssertionError("Malformed input accepted");}catch(IOException expected){checks++;}}
    interface RunnableX {void run()throws Exception;}
    static byte[] wholeTcp(int type,int seq,byte[] data)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(datagram(type,seq,data.length,0,new byte[0]));out.write(data);return out.toByteArray();}
    public static void main(String[] args)throws Exception {
        byte[] golden={67,84,80,58,1,0,88,2,0,0,0,123,125};
        check(Arrays.equals(CtpCodec.encode("X","{}"),golden),"Golden little-endian frame");
        InputStream fragmented=new FilterInputStream(new ByteArrayInputStream(golden)){public int read(byte[] b,int p,int n)throws IOException{return super.read(b,p,Math.min(n,1));}};
        check(CtpCodec.read(fragmented).topic().equals("X"),"Fragmented TCP read");
        ByteArrayOutputStream two=new ByteArrayOutputStream();two.write(golden);two.write(golden);InputStream coalesced=new ByteArrayInputStream(two.toByteArray());CtpCodec.read(coalesced);check(CtpCodec.read(coalesced).json().equals("{}"),"Coalesced TCP messages");
        rejected(()->CtpCodec.read(new ByteArrayInputStream(Arrays.copyOf(golden,12))));
        byte[] bad=golden.clone();bad[0]=0;rejected(()->CtpCodec.read(new ByteArrayInputStream(bad)));
        byte[] tooLong=golden.clone();Arrays.fill(tooLong,7,11,(byte)127);rejected(()->CtpCodec.read(new ByteArrayInputStream(tooLong)));
        FrameAssembler a=new FrameAssembler();byte[] end=datagram(0x83,9,8,4,new byte[]{5,6,7,8});byte[] begin=datagram(3,9,8,0,new byte[]{1,2,3,4});
        check(a.accept(end,end.length).isEmpty(),"Wait for missing prefix");check(a.accept(end,end.length).isEmpty(),"Duplicate does not complete frame");
        var frames=a.accept(begin,begin.length);check(frames.size()==1&&Arrays.equals(frames.get(0).data(),new byte[]{1,2,3,4,5,6,7,8}),"Out of order reassembly and high type bit");check(a.duplicates==1,"Duplicate accounting");
        byte[] corrupt=datagram(3,10,8,6,new byte[]{1,2,3,4});check(a.accept(corrupt,corrupt.length).isEmpty()&&a.invalid==1,"Reject overflowing slice");
        byte[] huge=datagram(3,11,Integer.MAX_VALUE,0,new byte[]{1});check(a.accept(huge,huge.length).isEmpty(),"Reject oversized allocation");
        byte[] trailer=Arrays.copyOf(begin,begin.length+32);Arrays.fill(trailer,begin.length,trailer.length,(byte)136);new FrameAssembler().accept(trailer,trailer.length);checks++;
        byte[] nal={0,0,0,1,0x67,1,2,0,0,1,0x68,3,0,0,0,1,0x65,4};check(NalUnits.split(nal).size()==3&&NalUnits.key(nal),"Mixed Annex B prefixes and IDR");
        check(Arrays.equals(NalUnits.muxSample(nal),new byte[]{0,0,0,1,0x65,4}),"MediaMuxer sample excludes config NALs and uses four-byte start codes");
        long prior=a.frames;a.accept(begin,begin.length);check(a.frames==prior,"Late duplicate cannot create another complete frame");
        byte[] iboxHeader=HexFormat.of().parseHex("030000000000000000b200000000000000000000");
        check(TcpMediaReader.bodySize(iboxHeader)==45568,"Actual iBOX header uses 32-bit total with zero UDP length");
        byte[] iboxBody=new byte[45568],prefix=HexFormat.of().parseHex("00000001674d401e92540501e8800000000168ee3c8000000001658880400063ff");System.arraycopy(prefix,0,iboxBody,0,prefix.length);
        byte[] largeBody=new byte[120000];Arrays.fill(largeBody,(byte)42);byte[] pcm=new byte[800];
        ByteArrayOutputStream wire=new ByteArrayOutputStream();wire.write(iboxHeader);wire.write(iboxBody);wire.write(wholeTcp(3,1,largeBody));wire.write(wholeTcp(1,-1,pcm));
        TcpMediaReader tcp=new TcpMediaReader();InputStream shortReads=new FilterInputStream(new ByteArrayInputStream(wire.toByteArray())){public int read(byte[] b,int p,int n)throws IOException{return super.read(b,p,Math.min(n,137));}};
        var first=tcp.read(shortReads);check(Arrays.equals(first.header(),iboxHeader)&&Arrays.equals(first.payload(),iboxBody),"Short TCP reads reconstruct the complete actual iBOX frame");
        FrameAssembler ta=new FrameAssembler();var tf=ta.acceptTcp(first.header(),first.payload()).get(0);check(tf.type()==3&&tf.sequence()==0&&tf.data().length==45568&&ta.invalid==0,"iBOX frame reaches decoder without UDP fragment rejection");
        var second=tcp.read(shortReads);check(Arrays.equals(second.payload(),largeBody),"TCP frames above 65535 bytes are not truncated");
        var sound=tcp.read(shortReads);check(ta.acceptTcp(sound.header(),sound.payload()).get(0).sequence()==4294967295L,"Coalesced video and PCM retain frame boundaries and unsigned sequence");
        rejected(()->tcp.read(shortReads));
        byte[] small=wholeTcp(3,17,new byte[40]);InputStream timeouts=new FilterInputStream(new ByteArrayInputStream(small)){int calls;public int read(byte[] b,int p,int n)throws IOException{int c=calls++;if(c==2||c==11)throw new SocketTimeoutException("fixture pause");return super.read(b,p,Math.min(n,3));}};
        TcpMediaReader resilient=new TcpMediaReader();TcpMediaReader.Packet resumed=null;int timeoutCount=0;while(resumed==null)try{resumed=resilient.read(timeouts);}catch(SocketTimeoutException e){timeoutCount++;}
        check(timeoutCount==2&&resumed.payload().length==40,"TCP timeout in header and payload preserves partially read bytes");
        byte[] hugeTcp=iboxHeader.clone();ByteBuffer.wrap(hugeTcp).order(ByteOrder.LITTLE_ENDIAN).putInt(8,7*1024*1024);rejected(()->TcpMediaReader.bodySize(hugeTcp));
        byte[] offsetTcp=iboxHeader.clone();offsetTcp[12]=1;rejected(()->TcpMediaReader.bodySize(offsetTcp));
        rejected(()->new TcpMediaReader().read(new ByteArrayInputStream(Arrays.copyOf(small,small.length-1))));
        FrameAssembler fragmentedTcp=new FrameAssembler();check(fragmentedTcp.acceptTcp(Arrays.copyOf(end,20),Arrays.copyOfRange(end,20,end.length)).isEmpty(),"Legacy TCP fragment waits for missing prefix");
        check(fragmentedTcp.acceptTcp(Arrays.copyOf(begin,20),Arrays.copyOfRange(begin,20,begin.length)).get(0).data().length==8,"Legacy TCP fragments remain supported");
        var headers=NalUnits.headers(nal);check(headers.key()&&Arrays.equals(headers.sps(),new byte[]{0x67,1,2})&&Arrays.equals(headers.pps(),new byte[]{0x68,3}),"NAL metadata extracts only SPS/PPS and detects IDR");
        LiveFrameQueue queue=new LiveFrameQueue(2);FrameAssembler.Frame pframe=new FrameAssembler.Frame(3,2,0,new byte[]{1});queue.offer(pframe,false);queue.offer(pframe,false);queue.offer(pframe,false);check(queue.size()==0&&queue.dropped==3,"Preview overload clears stale H264 references");queue.offer(pframe,false);check(queue.size()==0,"Dependent H264 frames are skipped until next IDR");queue.offer(new FrameAssembler.Frame(3,9,0,new byte[]{5}),true);LiveFrameQueue.Entry resumedFrame=queue.poll(1);check(resumedFrame.key()&&resumedFrame.reset()&&resumedFrame.frame().sequence()==9,"Preview resumes at IDR after overload");
        queue.offer(new FrameAssembler.Frame(2,1,0,new byte[]{1}),false);queue.offer(new FrameAssembler.Frame(2,2,0,new byte[]{2}),false);queue.offer(new FrameAssembler.Frame(2,3,0,new byte[]{3}),false);check(queue.size()==1&&queue.poll(1).frame().sequence()==3,"JPEG preview keeps fresh frame after overload");queue.close();queue.offer(pframe,true);check(queue.size()==0,"Closed preview queue rejects frames");
        LiveFrameQueue memoryQueue=new LiveFrameQueue(40);byte[] large=new byte[6*1024*1024];memoryQueue.offer(new FrameAssembler.Frame(3,1,0,large),true);memoryQueue.offer(new FrameAssembler.Frame(3,2,0,large),false);memoryQueue.offer(new FrameAssembler.Frame(3,3,0,large),false);check(memoryQueue.size()==0&&memoryQueue.resyncs==1,"Preview byte budget bounds large frames independently of the frame-count limit");memoryQueue.close();
        LivePreviewClock display=new LivePreviewClock();long previewNow=10_000_000_000L;long t0=display.target(100_000,previewNow);check(t0-previewNow==120_000_000L,"Preview has a small bounded initial jitter buffer");check(display.target(133_333,previewNow)-t0==33_333_000L,"Burst frames retain their source spacing");long recovered=display.target(166_666,previewNow+2_000_000_000L);check(recovered-previewNow-2_000_000_000L==80_000_000L&&display.rebuffers==1,"A real network gap rebases preview without changing recording timestamps");
        java.net.URI base=java.net.URI.create("rtsp://127.0.0.1:8888/token/XXX.sd/");String audioSdp="v=0\r\nm=video 0 RTP/AVP 96\r\na=control:trackID=0\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 L16/8000/1\r\na=control:trackID=1\r\n";RtspAudioTrack at=RtspAudioTrack.parse(audioSdp,base);check(at!=null&&at.control.toString().endsWith("trackID=1"),"Audio fallback selects only the recorder audio control, never video");check(RtspAudioTrack.parse(audioSdp.replace("L16/8000/1","L16/48000/2"),base)==null,"Unsupported audio format is not labelled as 8kHz mono PCM");check(RtspAudioTrack.parse(audioSdp.replace("trackID=1","rtsp://10.0.0.2:554/evil"),base)==null,"SDP cannot redirect audio to another host");byte[] audioWire=new byte[20];audioWire[0]='$';audioWire[3]=16;audioWire[4]=(byte)128;audioWire[5]=97;audioWire[16]=1;audioWire[17]=2;audioWire[18]=3;audioWire[19]=4;check(Arrays.equals(at.pcm(audioWire),new byte[]{2,1,4,3}),"Audio-only RTSP converts real L16 samples to little endian");check(at.pcm(audioWire)==null,"Duplicate audio RTP is discarded");audioWire[1]=1;check(at.pcm(audioWire)==null,"RTCP never becomes recorded audio");
        LiveTiming timing=new LiveTiming(20);timing.fixedRate(20);long last=0,origin=0;
        for(int i=0;i<160;i++){long arrival=10_000_000L+(i/4)*200_000L;long time=timing.next(arrival);if(i==0)origin=time;else check(time-last==50000,"Burst arrivals retain uniform 20 fps timestamps");check(time-arrival<=400000,"Live jitter buffer stays bounded");last=time;}
        check(last-origin==159*50000L,"Burst timeline duration follows the source cadence");long afterGap=timing.next(22_000_000L,41);check(afterGap-last>1_000_000,"Missing source frames retain a gap in the media timeline");
        LiveTiming backlog=new LiveTiming(20);backlog.fixedRate(20);long firstTime=backlog.next(10_000_000);check(backlog.next(12_000_000)-firstTime==50000,"A TCP backlog after a long arrival pause does not add a false MP4 freeze");
        LiveTiming measured=new LiveTiming(25);for(int i=0;i<300;i++)measured.next(30_000_000L+i*1_000_000L/30);check(Math.abs(measured.fps()-30)<.01,"Firmware ignoring requested fps is measured over a stable window");
        LiveTiming variable=new LiveTiming(25);for(int i=0;i<200;i++)variable.next(50_000_000L+i*1_000_000L/19);check(Math.abs(variable.fps()-19)<.1,"A measured 19 fps stream is not forced to 20 fps and periodic rebuffering");
        check(NalUnits.frameRate(HexFormat.of().parseHex("674d4029925403c0112f2a"))==0,"Real SPS without VUI does not invent a fixed source frame rate");
        long now=1_793_775_240_000L;check(!new DeviceClock.Reading(now-300000,now).needsSync(),"Exactly five minutes does not trigger a clock prompt");check(new DeviceClock.Reading(now-301000,now).needsSync()&&new DeviceClock.Reading(now+301000,now).needsSync(),"Clock prompt covers past and future offsets over five minutes");check(DeviceClock.read("20260230010101",now)==null&&DeviceClock.read("not-a-date",now)==null,"Invalid device calendars do not trigger a guessed time write");check(Math.abs(DeviceClock.read(DeviceClock.wire(now),now).deviceMillis()-now)<1000,"Local device wire clock round-trips without timezone drift");
        LiveTiming priority=new LiveTiming(25);priority.sourceRate(30,1);check(Math.abs(priority.fps()-30)<.01,"Actual recording metadata overrides requested fps");priority.fixedRate(20);priority.sourceRate(30,1);check(Math.abs(priority.fps()-20)<.01,"SPS VUI keeps priority over recording metadata");
        java.util.List<FrameAssembler.Frame> rtpFrames=new java.util.ArrayList<>();RtpJpeg jpegRtp=new RtpJpeg(26,97,rtpFrames::add);
        byte[] pcmRtp=new byte[20];pcmRtp[0]='$';pcmRtp[3]=16;pcmRtp[4]=(byte)128;pcmRtp[5]=97;pcmRtp[16]=1;pcmRtp[17]=2;pcmRtp[18]=3;pcmRtp[19]=4;jpegRtp.accept(pcmRtp);check(rtpFrames.size()==1&&Arrays.equals(rtpFrames.get(0).data(),new byte[]{2,1,4,3}),"RTSP L16 network order becomes recorder PCM little endian");
        for(int n=0;n<16;n++)jpegRtp.accept(Arrays.copyOf(pcmRtp,n));check(rtpFrames.size()==1,"Truncated RTP packets do not reach the recorder");byte[] unsupported=new byte[24];unsupported[0]='$';unsupported[3]=20;unsupported[4]=(byte)128;unsupported[5]=26;unsupported[20]=2;rejected(()->jpegRtp.accept(unsupported));
        byte[] tooShortQuant=new byte[28];tooShortQuant[0]='$';tooShortQuant[3]=24;tooShortQuant[4]=(byte)128;tooShortQuant[5]=26;tooShortQuant[21]=(byte)255;tooShortQuant[27]=(byte)128;rejected(()->jpegRtp.accept(tooShortQuant));
        System.out.println("PASS: "+checks+" protocol and timing checks");
    }
}
