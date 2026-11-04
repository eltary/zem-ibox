package io.zem.ibox;
import android.content.Context;import org.json.*;
final class PlaybackHistory {
    private final Context c;PlaybackHistory(Context c){this.c=c;}
    private String key(String source){try{return Io.hex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    long position(String source){return c.getSharedPreferences("positions",0).getLong(key(source),0);}
    void save(String source,long position,long length){if(length<=0)return;c.getSharedPreferences("positions",0).edit().putLong(key(source),position<1000||position>=length-1500?0:position).apply();}
    void clear(String source){c.getSharedPreferences("positions",0).edit().remove(key(source)).apply();}
}
