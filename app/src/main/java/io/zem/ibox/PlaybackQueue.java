package io.zem.ibox;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** A private snapshot of the visible video list; large archives never enter Binder extras. */
final class PlaybackQueue {
    static final int MAX_ITEMS=10000;
    static final class Entry {
        final String uri,name,mime,detail;
        Entry(String uri,String name,String mime){this(uri,name,mime,"");}
        Entry(String uri,String name,String mime,String detail){this.uri=uri;this.name=name;this.mime=mime;this.detail=detail;}
        String label(){return name+(detail.isEmpty()?"":"\n"+detail);}
    }
    final List<Entry> items;
    final long connection;
    final int selected;
    private PlaybackQueue(List<Entry> items,long connection,int selected){this.items=Collections.unmodifiableList(items);this.connection=connection;this.selected=selected;}

    static Intent remote(Context context,JieliSession session,List<MediaItem> visible,MediaItem selected)throws Exception {
        CtpClient owner=session.ctp;long connection=session.connectionId();
        if(!session.isCurrent(owner)||!session.cardPresent())throw new IOException("Подключите видеорегистратор с microSD");
        List<Entry> entries=new ArrayList<>();String chosen=session.url(selected.path).toString();
        for(MediaItem item:visible)if(!item.photo())entries.add(new Entry(session.url(item.path).toString(),item.name,item.mime(),item.date()+" · "+item.duration+" с"));
        if(entries.stream().noneMatch(e->e.uri.equals(chosen)))entries.add(new Entry(chosen,selected.name,selected.mime(),selected.date()+" · "+selected.duration+" с"));
        if(!session.isCurrent(owner))throw new IOException("Подключение изменилось. Откройте список заново");
        return write(context,entries,chosen,connection);
    }
    static Intent local(Context context,JSONArray library,String selected)throws Exception {
        List<Entry> entries=new ArrayList<>();
        for(int i=0;i<library.length();i++){JSONObject item=library.optJSONObject(i);if(item!=null&&item.optString("mime").startsWith("video/"))entries.add(new Entry(item.optString("uri"),item.optString("name"),item.optString("mime"),(item.optString("recordedAt").isEmpty()?"Сохранено "+new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.ROOT).format(new Date(item.optLong("at"))):item.optString("recordedAt"))));}
        if(entries.isEmpty())throw new IOException("На телефоне пока нет видео");
        return write(context,entries,selected==null?entries.get(0).uri:selected,0);
    }
    private static Intent write(Context context,List<Entry> entries,String selected,long connection)throws Exception {
        if(entries.size()>MAX_ITEMS)throw new IOException("Сузьте список поиском: для просмотра подряд доступно до 10 000 видео");
        JSONArray items=new JSONArray();int index=-1;
        for(int i=0;i<entries.size();i++){Entry e=entries.get(i);if(e.uri.equals(selected))index=i;items.put(new JSONObject().put("uri",e.uri).put("name",e.name).put("mime",e.mime).put("detail",e.detail));}
        if(index<0)throw new IOException("Видео больше нет в этом списке");
        File folder=new File(context.getCacheDir(),"playback-queues");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Не удалось подготовить список видео");
        File[] old=folder.listFiles();if(old!=null)for(File f:old)if(f.isFile()&&System.currentTimeMillis()-f.lastModified()>24*60*60*1000L)f.delete();
        String name=UUID.randomUUID()+".json";File file=new File(folder,name);
        byte[] data=new JSONObject().put("connection",connection).put("selected",index).put("items",items).toString().getBytes(StandardCharsets.UTF_8);
        if(data.length>4*1024*1024)throw new IOException("Сузьте список поиском перед просмотром подряд");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(data);}
        Entry chosen=entries.get(index);
        return new Intent(context,PlayerActivity.class).putExtra("queue",name).putExtra("uri",chosen.uri).putExtra("name",chosen.name).putExtra("mime",chosen.mime);
    }
    static PlaybackQueue read(Context context,Intent intent,JieliSession session)throws Exception {
        String name=intent.getStringExtra("queue");if(name==null)return null;
        if(!name.matches("[0-9a-f-]{36}\\.json"))throw new IOException("Неверный список видео");
        File file=new File(new File(context.getCacheDir(),"playback-queues"),name);
        if(!file.isFile()||file.length()>4*1024*1024)throw new IOException("Список видео устарел. Откройте его заново");
        JSONObject json=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));JSONArray values=json.getJSONArray("items");long connection=json.getLong("connection");
        if(values.length()==0||values.length()>MAX_ITEMS)throw new IOException("Неверный список видео");
        if(connection!=0&&(connection!=session.connectionId()||!session.connected()||!session.cardPresent()))throw new IOException("Подключение изменилось. Откройте архив заново");
        List<Entry> items=new ArrayList<>();
        for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);String uri=value.getString("uri"),mime=value.getString("mime");
            if(!mime.startsWith("video/"))throw new IOException("В списке ожидаются видео");
            if(connection==0){if(!"content".equals(Uri.parse(uri).getScheme()))throw new IOException("Неверный файл телефона");}else session.url(uri);
            items.add(new Entry(uri,value.optString("name"),mime,value.optString("detail")));
        }
        int index=json.getInt("selected");if(index<0||index>=items.size())throw new IOException("Неверная позиция видео");
        return new PlaybackQueue(items,connection,index);
    }
}
