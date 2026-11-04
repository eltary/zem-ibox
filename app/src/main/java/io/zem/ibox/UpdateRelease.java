package io.zem.ibox;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Validates public GitHub release metadata before an update can be offered. */
final class UpdateRelease {
    static final String REPOSITORY="https://github.com/eltary/zem-ibox";
    static final String API="https://api.github.com/repos/eltary/zem-ibox/releases/latest";
    final String version,name,url,digest;final long size;
    UpdateRelease(String v,String n,String u,String d,long s){version=v;name=n;url=u;digest=d;size=s;}
    static long version(String input)throws IOException{
        String s=input.startsWith("v")?input.substring(1):input;
        if(!s.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]?)\\.(?:0|[1-9][0-9]?)"))throw new IOException("Некорректный номер версии");
        try{String[] p=s.split("\\.");return Math.addExact(Math.multiplyExact(Long.parseLong(p[0]),10000),Integer.parseInt(p[1])*100L+Integer.parseInt(p[2]));}catch(ArithmeticException|NumberFormatException e){throw new IOException("Некорректный номер версии",e);}
    }
    boolean newerThan(String installed)throws IOException{return version(version)>version(installed);}
    static UpdateRelease parse(JSONObject json,String[] abis,String testOrigin)throws Exception{
        if(json.optBoolean("draft")||json.optBoolean("prerelease"))throw new IOException("Пробные версии не устанавливаются автоматически");
        String tag=json.getString("tag_name"),version=tag.startsWith("v")?tag.substring(1):tag;version(version);
        JSONArray assets=json.getJSONArray("assets");List<String> variants=new ArrayList<>(Arrays.asList(abis));variants.add("universal");
        for(String abi:variants){
            if(!Arrays.asList("arm64-v8a","armeabi-v7a","x86","x86_64","universal").contains(abi))continue;
            String name="Zem-iBox-"+version+"-"+abi+".apk";JSONObject selected=null;
            for(int i=0;i<assets.length();i++){JSONObject asset=assets.getJSONObject(i);if(name.equals(asset.optString("name"))){if(selected!=null)throw new IOException("Неоднозначный файл обновления");selected=asset;}}
            if(selected==null)continue;
            long size=selected.getLong("size");if(size<=0||size>512L*1024*1024)throw new IOException("Некорректный размер APK");
            String url=selected.getString("browser_download_url");URI uri=new URI(url);
            boolean fixture=!testOrigin.isEmpty()&&url.equals(testOrigin+"/"+name);
            if(!fixture&&!url.equals(REPOSITORY+"/releases/download/"+tag+"/"+name))throw new IOException("Ссылка APK не принадлежит релизу Zem iBox");
            String digest=selected.optString("digest");if(!digest.matches("sha256:[0-9a-fA-F]{64}"))throw new IOException("GitHub ещё не подготовил контрольную сумму APK. Повторите проверку позже");
            return new UpdateRelease(version,name,url,digest.substring(7).toLowerCase(Locale.ROOT),size);
        }
        throw new IOException("В релизе нет APK для этого телефона");
    }
}
