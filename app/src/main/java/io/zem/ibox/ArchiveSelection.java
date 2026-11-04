package io.zem.ibox;
import java.text.*;
import java.util.*;
final class ArchiveSelection {
    static long timestamp(MediaItem item){try{SimpleDateFormat f=new SimpleDateFormat("yyyyMMddHHmmss",Locale.ROOT);f.setLenient(false);Date date=f.parse(item.time);return date==null?0:date.getTime();}catch(ParseException e){return 0;}}
    static boolean overlaps(MediaItem item,long from,long to){long start=timestamp(item);if(start==0)return false;long end=start+Math.max(1,item.duration)*1000L;return start<=to&&end>from;}
    static List<MediaItem> window(List<MediaItem> source,long from,long to){List<MediaItem> out=new ArrayList<>();for(MediaItem item:source)if(!item.photo()&&overlaps(item,from,to))out.add(item);out.sort(MediaItem.newestFirst());return out;}
    static long size(List<MediaItem> items){long sum=0;for(MediaItem item:items)sum+=Math.max(0,item.size);return sum;}
    static String day(MediaItem item){return item.time.length()==14?item.date().substring(0,10):"Дата неизвестна";}
}
