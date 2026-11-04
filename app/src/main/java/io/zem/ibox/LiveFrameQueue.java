package io.zem.ibox;

import java.util.ArrayDeque;

/** Bounded preview queue. H.264 resumes at an IDR after overload, preserving references. */
final class LiveFrameQueue {
    record Entry(FrameAssembler.Frame frame,boolean key,boolean reset,long receivedNanos){}
    private final ArrayDeque<Entry> frames=new ArrayDeque<>();
    private final int capacity;private long bytes;private static final long MAX_BYTES=16*1024*1024;
    private boolean waitingKey,closed;
    volatile long dropped,resyncs;
    LiveFrameQueue(int capacity){this.capacity=capacity;}
    synchronized void offer(FrameAssembler.Frame frame,boolean key){
        if(closed)return;
        long now=System.nanoTime();boolean reset=false;
        if(frames.size()>=capacity||bytes+frame.data().length>MAX_BYTES||(key&&!frames.isEmpty()&&now-frames.peekFirst().receivedNanos()>800_000_000L)){
            dropped+=frames.size();frames.clear();bytes=0;waitingKey=frame.type()==3;reset=waitingKey;resyncs++;
        }
        if(waitingKey){if(frame.type()==3&&!key){dropped++;return;}reset=frame.type()==3;waitingKey=false;}
        frames.addLast(new Entry(frame,key,reset,now));bytes+=frame.data().length;notifyAll();
    }
    synchronized Entry poll(long millis)throws InterruptedException {if(frames.isEmpty()&&!closed)wait(millis);Entry entry=frames.pollFirst();if(entry!=null)bytes-=entry.frame().data().length;return entry;}
    synchronized int size(){return frames.size();}
    synchronized void close(){closed=true;frames.clear();bytes=0;notifyAll();}
}
