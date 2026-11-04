package io.zem.ibox;

/** Preview playout only. Recording keeps the independent source timeline. */
final class LivePreviewClock {
    private long offset=Long.MIN_VALUE;
    long rebuffers;
    long target(long ptsUs,long now){
        long pts=ptsUs*1000;
        if(offset==Long.MIN_VALUE){offset=now+120_000_000L-pts;}
        long target=pts+offset;
        if(target<now-180_000_000L){offset=now+80_000_000L-pts;target=pts+offset;rebuffers++;}
        return target;
    }
    void reset(){offset=Long.MIN_VALUE;}
}
