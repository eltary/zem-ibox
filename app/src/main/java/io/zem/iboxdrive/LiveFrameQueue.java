package io.zem.iboxdrive;

import java.util.ArrayDeque;

/** Bounded preview queue. H.264 resumes at an IDR after overload, preserving references. */
final class LiveFrameQueue {
    record Entry(FrameAssembler.Frame frame,boolean key,boolean reset,long receivedNanos){}
    private final ArrayDeque<Entry> frames=new ArrayDeque<>();
    private final int capacity;
    private boolean waitingKey,closed;
    volatile long dropped,resyncs;
    LiveFrameQueue(int capacity){this.capacity=capacity;}
    synchronized void offer(FrameAssembler.Frame frame,boolean key){
        if(closed)return;
        long now=System.nanoTime();boolean reset=false;
        if(frames.size()>=capacity||(key&&!frames.isEmpty()&&now-frames.peekFirst().receivedNanos()>200_000_000L)){
            dropped+=frames.size();frames.clear();waitingKey=frame.type()==3;reset=waitingKey;resyncs++;
        }
        if(waitingKey){if(frame.type()==3&&!key){dropped++;return;}reset=frame.type()==3;waitingKey=false;}
        frames.addLast(new Entry(frame,key,reset,now));notifyAll();
    }
    synchronized Entry poll(long millis)throws InterruptedException {if(frames.isEmpty()&&!closed)wait(millis);return frames.pollFirst();}
    synchronized int size(){return frames.size();}
    synchronized void close(){closed=true;frames.clear();notifyAll();}
}
