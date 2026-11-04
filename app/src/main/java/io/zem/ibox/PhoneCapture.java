package io.zem.ibox;
interface PhoneCapture {
    default void accept(FrameAssembler.Frame frame){}
    void finish()throws Exception;
}
