package io.zem.iboxdrive;

import org.json.*;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

public final class CtpClient implements AutoCloseable {
    private final Socket socket;
    private final ConcurrentHashMap<String,CompletableFuture<JSONObject>> waiting=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final BiConsumer<String,JSONObject> listener;
    private volatile long lastRx=System.currentTimeMillis();
    public CtpClient(Socket socket,BiConsumer<String,JSONObject> listener)throws IOException {
        this.socket=socket;this.listener=listener;
        new Thread(this::receive,"CTP receive").start();
    }
    private void receive(){
        try {
            while(!closed){
                CtpCodec.Frame f=CtpCodec.read(socket.getInputStream());lastRx=System.currentTimeMillis();
                Diag.log("CTP RX",f.topic()+" "+f.json());
                JSONObject j=f.json().isEmpty()?new JSONObject():new JSONObject(f.json());
                listener.accept(f.topic(),j);
                CompletableFuture<JSONObject> pending=waiting.get(f.topic());if(pending!=null)pending.complete(j);
            }
        }catch(Exception e){if(!closed){Diag.error("CTP reader",e);close();listener.accept("__DISCONNECTED",new JSONObject());}}
    }
    public JSONObject request(String topic,String op,JSONObject params,int timeoutSeconds)throws Exception {
        if(closed)throw new IOException("Соединение с регистратором закрыто");
        CompletableFuture<JSONObject> f=new CompletableFuture<>();
        if(waiting.putIfAbsent(topic,f)!=null)throw new IOException("Команда уже выполняется: "+topic);
        try {
            send(topic,op,params);
            JSONObject answer=f.get(timeoutSeconds,TimeUnit.SECONDS);int code=answer.optInt("errno",0);
            if(code!=0)throw new DeviceException(code,topic,answer.optString("msg"));
            return answer.optJSONObject("param")==null?new JSONObject():answer.getJSONObject("param");
        }catch(TimeoutException e){Diag.error("CTP timeout "+topic,e);throw new IOException("Регистратор не ответил на "+topic,e);}
        finally {waiting.remove(topic,f);}
    }
    public synchronized void send(String topic,String op,JSONObject params)throws Exception {
        if(!BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS&&java.util.Set.of("FILES_DELETE","FORMAT","FORMAT_TF_CARD").contains(topic.trim().toUpperCase(java.util.Locale.ROOT))){Diag.log("BLOCKED","Destructive command disabled during testing: "+topic);throw new SecurityException("Удаление файлов и форматирование карты заблокированы на время проверки Zem iBox Drive");}
        if(closed)throw new IOException("Соединение закрыто");
        JSONObject j=new JSONObject().put("op",op);if(params!=null)j.put("param",params);
        String value=j.toString();Diag.log("CTP TX",topic+" "+value);
        socket.getOutputStream().write(CtpCodec.encode(topic,value));socket.getOutputStream().flush();
    }
    public long lastRx(){return lastRx;}
    public boolean isOpen(){return !closed;}
    public void close(){
        if(closed)return;closed=true;
        try{socket.close();}catch(IOException ignored){}
        for(CompletableFuture<JSONObject> f:waiting.values())f.completeExceptionally(new IOException("Соединение закрыто"));waiting.clear();
    }
    public static final class DeviceException extends IOException {
        public final int code;
        DeviceException(int code,String topic,String msg){super(message(code)+" ["+topic+", "+code+"]"+(msg.isEmpty()?"":" "+msg));this.code=code;}
        static String message(int c){return switch(c){case 1->"Ошибка карты памяти";case 2->"Карта памяти не установлена";case 3->"Регистратор отклонил подключение. Закрой DVRunning2";case 5->"Несовместимая версия протокола";case 6->"Функция не поддерживается прошивкой";case 7->"Регистратор в режиме USB";case 8->"Сначала останови запись на регистраторе";case 9->"Регистратор занят просмотром";case 11->"Не удалось открыть файл";case 15,16->"Задняя камера недоступна";case 17->"Регистратор не смог открыть видеопоток";default->"Ошибка регистратора";};}
    }
}
