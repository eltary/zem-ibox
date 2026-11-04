package io.zem.ibox;
/** Adaptive preview playout. Recording keeps its independent source timeline. */
final class LivePreviewClock {
    private long offset=Long.MIN_VALUE,lastPts,lastArrival;private double jitter;private int mode;private long appliedBuffer;long rebuffers;
    synchronized void mode(int mode){this.mode=Math.max(0,Math.min(2,mode));reset();}
    synchronized void observe(long ptsUs,long arrival){if(lastArrival>0&&ptsUs>lastPts){double variation=Math.min(200_000_000L,Math.abs((arrival-lastArrival)-(ptsUs-lastPts)*1000L));jitter+=(variation-jitter)*.08;}lastPts=ptsUs;lastArrival=arrival;}
    synchronized long bufferNanos(){return mode==1?50_000_000L:mode==2?300_000_000L:(long)Math.max(120_000_000,Math.min(450_000_000,120_000_000+jitter*4));}
    synchronized long target(long ptsUs,long now){long pts=ptsUs*1000,buffer=bufferNanos();if(offset==Long.MIN_VALUE){offset=now+buffer-pts;appliedBuffer=buffer;}else{long shift=Math.max(-300_000L,Math.min(4_000_000L,buffer-appliedBuffer));offset+=shift;appliedBuffer+=shift;}long target=pts+offset;if(target<now-180_000_000L||target>now+800_000_000L){offset=now+buffer-pts;target=pts+offset;rebuffers++;}return target;}
    synchronized void reset(){offset=Long.MIN_VALUE;lastPts=lastArrival=0;jitter=0;}
}
