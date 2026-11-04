package io.zem.ibox;
import android.app.*;
import android.widget.*;
import java.util.*;
import java.util.function.*;
final class TimeWindowPicker {
    static String label(long time){return new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.ROOT).format(new Date(time));}
    static void moment(Activity a,long initial,LongConsumer result){Calendar time=Calendar.getInstance();time.setTimeInMillis(initial);new DatePickerDialog(a,(view,y,m,d)->{time.set(y,m,d);new TimePickerDialog(a,(clock,h,min)->{time.set(Calendar.HOUR_OF_DAY,h);time.set(Calendar.MINUTE,min);time.set(Calendar.SECOND,0);time.set(Calendar.MILLISECOND,0);result.accept(time.getTimeInMillis());},time.get(Calendar.HOUR_OF_DAY),time.get(Calendar.MINUTE),true).show();},time.get(Calendar.YEAR),time.get(Calendar.MONTH),time.get(Calendar.DAY_OF_MONTH)).show();}
    static void show(Activity a,long initialFrom,long initialTo,Consumer<long[]> result){long[] range={initialFrom,initialTo};LinearLayout content=Widgets.column(a);int pad=Ui.dp(a,16);content.setPadding(pad,pad,pad,pad);Button from=Ui.button(a,"От: "+label(range[0]),false),to=Ui.button(a,"До: "+label(range[1]),false);content.addView(from);content.addView(to);TextView error=Ui.text(a,"Время по часам видеорегистратора",13,Ui.MUTED);content.addView(error);from.setOnClickListener(v->moment(a,range[0],value->{range[0]=value;from.setText("От: "+label(value));}));to.setOnClickListener(v->moment(a,range[1],value->{range[1]=value;to.setText("До: "+label(value));}));AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Промежуток времени").setView(Widgets.dialogBody(a,content)).setNegativeButton("Отмена",null).setPositiveButton("Показать",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(range[0]>range[1]){error.setText("Начало должно быть раньше конца");return;}dialog.dismiss();result.accept(new long[]{range[0],range[1]+59999});});}
}
