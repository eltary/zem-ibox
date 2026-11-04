package io.zem.ibox;
import android.content.Context;
interface LiveSource extends AutoCloseable {
    interface Listener {void status(String text);void frame(FrameAssembler.Frame frame);}
    void start();void mute(boolean muted);boolean muted();boolean hasVideo();int width();int height();int sampleRate();double frameRate();byte[] sps();byte[] pps();void close();void awaitStopped();
    default void previewMode(int mode){}
    default boolean hasAudio(){return false;}
    default PhoneCapture recording(Context context)throws Exception{return new PhoneRecording(context,width(),height(),sampleRate(),sps(),pps(),frameRate());}
}
