package io.zem.iboxdrive;
import java.util.*;
public record SettingDef(String title,String topic,String key,String[] labels,String[] values) {
    static SettingDef s(String name,String topic,String key,String labels,String values){return new SettingDef(name,topic,key,labels.split("\\|"),values.split("\\|"));}
    static SettingDef toggle(String name,String topic,String key){return s(name,topic,key,"Выключено|Включено","0|1");}
    public static final List<SettingDef> ALL=List.of(
        toggle("Запись звука","VIDEO_MIC","mic"),
        s("Цикл записи","VIDEO_LOOP","cyc","Выключен|1 минута|3 минуты|5 минут","0|1|3|5"),
        toggle("HDR / WDR","VIDEO_WDR","wdr"),
        toggle("Датчик движения","MOVE_CHECK","mot"),
        toggle("Дата на видео","VIDEO_DATE","dat"),
        s("G-сенсор","GRA_SEN","gra","Выключен|Низкая чувствительность|Средняя|Высокая","0|1|2|3"),
        toggle("Режим парковки","VIDEO_PAR_CAR","par"),
        toggle("Запись двух камер","DOUBLE_VIDEO","two"),
        s("Экспозиция видео","VIDEO_EXP","exp","−3|−2|−1|0|+1|+2|+3","-3|-2|-1|0|1|2|3"),
        s("Частота освещения","LIGHT_FRE","fre","50 Гц|60 Гц","50|60"),
        s("Отключение экрана","SCREEN_PRO","pro","Выключено|30 секунд|1 минута|2 минуты|3 минуты","0|30|60|120|180"),
        s("Автовыключение","AUTO_SHUTDOWN","aff","Выключено|1 минута|3 минуты|5 минут","0|60|180|300"),
        toggle("Звук кнопок","KEY_VOICE","kvo"),
        toggle("Звук включения","BOARD_VOICE","bvo"),
        s("Язык регистратора","LANGUAGE","lag","Русский|English","16|5"),
        s("Баланс белого","WHITE_BALANCE","wbl","Авто|Дневной свет|Облачно|Вольфрам|Флуоресцент","0|1|2|3|4"),
        s("Разрешение фото","PHOTO_RESO","res","VGA|1 Мп|2 Мп|3 Мп|5 Мп|8 Мп|10 Мп|12 Мп","0|1|2|3|4|5|6|7"),
        s("Качество фото","PHOTO_QUALITY","qua","Низкое|Среднее|Высокое","0|1|2"),
        s("Резкость фото","PHOTO_SHARPNESS","acu","Низкая|Средняя|Высокая","0|1|2"),
        s("ISO фото","PHOTO_ISO","iso","Авто|100|200|400","0|100|200|400"),
        s("Экспозиция фото","PHOTO_EXP","exp","−3|−2|−1|0|+1|+2|+3","-3|-2|-1|0|1|2|3"),
        s("Таймер фото","SELF_TIMER","phm","Выключен|2 секунды|5 секунд|10 секунд","0|2|5|10"),
        toggle("Серийная фотосъёмка","BURST_SHOT","cyt"),
        toggle("Стабилизатор фото","ANTI_TREMOR","sok"),
        toggle("Дата на фото","PHOTO_DATE","dat")
    );
    public String display(String value){for(int i=0;i<values.length;i++)if(values[i].equals(value))return labels[i];return value.isEmpty()?"Не получено":value;}
}
