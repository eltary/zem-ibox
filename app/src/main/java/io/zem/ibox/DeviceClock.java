package io.zem.ibox;

import java.time.*;
import java.time.format.*;

/** Device DATE_TIME contains local calendar time, without a timezone field. */
final class DeviceClock {
    private static final DateTimeFormatter WIRE=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DISPLAY=DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss");
    record Reading(long deviceMillis,long phoneMillis) {
        long differenceSeconds(){return Math.abs(phoneMillis/1000-deviceMillis/1000);}
        boolean needsSync(){return differenceSeconds()>300;}
        String message(long now){long deviceNow=deviceMillis+now-phoneMillis;long minutes=differenceSeconds()/60;String difference=minutes>=1440?(minutes/1440)+" дн. "+(minutes%1440/60)+" ч.":minutes>=60?(minutes/60)+" ч. "+(minutes%60)+" мин.":minutes+" мин.";return "В регистраторе: "+display(deviceNow)+"\nНа телефоне: "+display(now)+"\n\nРазница: "+difference+". Установить время телефона на регистраторе?";}
    }
    static Reading read(String value,long now){try{if(!value.matches("[0-9]{14}"))return null;return new Reading(LocalDateTime.parse(value,WIRE).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),now);}catch(DateTimeException e){return null;}}
    static String wire(long now){return WIRE.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));}
    private static String display(long now){return DISPLAY.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));}
}
