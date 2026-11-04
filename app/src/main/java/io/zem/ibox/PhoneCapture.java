package io.zem.ibox;
interface PhoneCapture {
    default void accept(FrameAssembler.Frame frame){}
    default String warning(){return "";}
    void finish()throws Exception;
}
