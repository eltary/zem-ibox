package io.zem.ibox;
import org.json.*;
import java.util.Locale;
public final class MediaItem {
    public final String path,name,time; public final long size; public final int camera,duration,type;
    public MediaItem(JSONObject j,int camera){path=j.optString("f");name=path.substring(path.lastIndexOf('/')+1);time=j.optString("t");size=j.optLong("s",0);duration=j.optInt("d",0);type=j.optInt("y",1);this.camera=camera;}
    public boolean photo(){String n=name.toLowerCase(Locale.ROOT);return n.endsWith(".jpg")||n.endsWith(".jpeg");}
    public String mime(){return photo()?"image/jpeg":name.toLowerCase(Locale.ROOT).endsWith(".avi")?"video/x-msvideo":name.toLowerCase(Locale.ROOT).endsWith(".mov")?"video/quicktime":"video/mp4";}
    public boolean locked(){String p=path.toUpperCase(Locale.ROOT);return type==2||p.matches(".*/(LOCK|LOCKED|PROTECTED|EVENT|EMR|SOS|RO)/.*");}
    public String orderKey(){return time.matches("[0-9]{14}")?time:name;}
    public static java.util.Comparator<MediaItem> newestFirst(){return java.util.Comparator.comparing(MediaItem::orderKey).reversed().thenComparing(i->i.path);}
    public String date(){return time.length()==14?time.substring(6,8)+"."+time.substring(4,6)+"."+time.substring(0,4)+"  "+time.substring(8,10)+":"+time.substring(10,12)+":"+time.substring(12,14):time;}
    public JSONObject json()throws JSONException{return new JSONObject().put("f",path).put("t",time).put("s",size).put("d",duration).put("y",type).put("c",camera);}
}
