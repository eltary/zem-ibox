package io.zem.ibox;

/** Receiver clock: smooth Wi-Fi bursts without accumulating unbounded latency. */
final class LiveTiming {
    private double periodUs;
    private long nextUs=-1,windowStartUs,windowFrames;
    private boolean fixed;
    LiveTiming(double fps){periodUs=1_000_000.0/Math.max(1,Math.min(60,fps));}
    void fixedRate(double fps){if(fps>=1&&fps<=60){periodUs=1_000_000.0/fps;fixed=true;}}
    synchronized double fps(){return 1_000_000.0/periodUs;}
    long next(long arrivalUs){return next(arrivalUs,1);}
    synchronized long next(long arrivalUs,long sequenceSteps){
        if(windowStartUs==0)windowStartUs=arrivalUs;
        windowFrames++;
        long elapsed=arrivalUs-windowStartUs;
        // Firmware sometimes ignores requested fps as well as dimensions. Estimate over
        // several seconds, never from one packet gap; VUI timing takes precedence.
        if(!fixed&&elapsed>=3_000_000){double rate=(windowFrames-1)*1_000_000.0/elapsed;
            if(rate>=10&&rate<=60){for(double standard:new double[]{15,20,24,25,30,50,60})if(Math.abs(rate-standard)/standard<.03){rate=standard;break;}periodUs=1_000_000.0/rate;}
            windowStartUs=arrivalUs;windowFrames=1;
        }
        long scheduled=nextUs<0?arrivalUs+160_000:nextUs;
        // TCP can deliver a backlog after an arbitrarily long pause. Arrival gaps
        // must not become extra pauses in MP4 if all source frames are present.
        // Only missing source sequence numbers advance the media timeline.
        if(nextUs>=0&&sequenceSteps>1)scheduled+=Math.round((Math.min(sequenceSteps,3000)-1)*periodUs);
        nextUs=scheduled+Math.round(periodUs);
        return scheduled;
    }
}
