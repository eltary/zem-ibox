package io.zem.ibox;
import org.json.*;
final class StreamCapabilities {
    static JSONArray levels(JSONObject description,String key){Object value=description.opt(key);if(value instanceof JSONArray)return (JSONArray)value;if(value instanceof String)try{return new JSONArray((String)value);}catch(JSONException ignored){}return null;}
    static boolean rtsp(JSONObject description){JSONArray a=levels(description,"rtsp_forward_support");return a!=null&&a.length()>0;}
    static String automatic(JSONObject description){String net=description.optString("net_type","");if(net.equals("0"))return "tcp";if(net.equals("1"))return "udp";return rtsp(description)?"rtsp":"udp";}
    static String rtspUrl(String host,JSONObject description,int camera,int quality){int port=description.optInt("rtsp_port",554);if(port<1||port>65535)port=554;String level=new String[]{"sd","hd","fhd"}[Math.max(0,Math.min(2,quality))];boolean h264=description.optInt("rts_type",1)!=0;return "rtsp://"+host+":"+port+(h264?"/264_pcm_rt/":"/avi_pcm_rt/")+(camera==1?"rear":h264?"XXX":"front")+"."+level;}
}
