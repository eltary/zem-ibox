package io.zem.ibox;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.*;
/** Shared file rows use one card surface and an independent menu touch target. */
class MediaRows extends BaseAdapter {
    private final Context c;private final MediaThumbnails thumbnails;private final int count;private final IntFunction<String> label;private final IntConsumer open,menu;private final IntFunction<MediaThumbnails.Source> preview;private IntFunction<String> section=i->"";
    MediaRows(Context c,MediaThumbnails thumbnails,int count,IntFunction<String> label,IntConsumer open,IntConsumer menu,IntFunction<MediaThumbnails.Source> preview){this.c=c;this.thumbnails=thumbnails;this.count=count;this.label=label;this.open=open;this.menu=menu;this.preview=preview;}
    MediaRows sections(IntFunction<String> section){this.section=section;return this;}public int getCount(){return count;}public Object getItem(int p){return p;}public long getItemId(int p){return p;}
    public View getView(int p,View old,ViewGroup parent){Row row=old instanceof Row?(Row)old:new Row();String value=label.apply(p),group=section.apply(p);int split=value.indexOf('\n');row.group.setText(group);row.group.setVisibility(group.isEmpty()?View.GONE:View.VISIBLE);row.title.setText(split<0?value:value.substring(0,split));row.detail.setText(split<0?"":value.substring(split+1));row.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);row.image.setPadding(Ui.dp(c,10),Ui.dp(c,10),Ui.dp(c,10),Ui.dp(c,10));row.image.setImageDrawable(Ui.icon(c,value.toLowerCase(Locale.ROOT).contains(".jpg")?"photo":"play",22,Ui.ACCENT));thumbnails.bind(row.image,preview.apply(p));row.open.setContentDescription("Открыть: "+value.replace('\n',' '));row.open.setOnClickListener(v->open.accept(p));row.open.setOnLongClickListener(v->{menu.accept(p);return true;});row.more.setContentDescription("Действия с файлом: "+value.replace('\n',' '));row.more.setOnClickListener(v->menu.accept(p));return row;}
    private final class Row extends LinearLayout {
        final TextView group,title,detail;final ImageView image;final LinearLayout open;final Button more;
        Row(){super(c);setOrientation(VERTICAL);group=Ui.section(c,"");addView(group);LinearLayout card=Widgets.row(c);card.setPadding(Ui.dp(c,10),Ui.dp(c,10),Ui.dp(c,10),Ui.dp(c,10));card.setBackground(Ui.panel(c,Ui.CARD,17));addView(card);open=Widgets.row(c);open.setFocusable(true);open.setClickable(true);open.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2452d9ca),null,Ui.panel(c,Color.WHITE,10)));card.addView(open,new LayoutParams(0,-2,1));image=new ImageView(c);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setClipToOutline(true);open.addView(image,new LayoutParams(Ui.dp(c,72),Ui.dp(c,54)));LinearLayout labels=Widgets.column(c);labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);title=Ui.text(c,"",14,Ui.INK);title.setMaxLines(3);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));detail=Ui.text(c,"",12,Ui.MUTED);detail.setMaxLines(3);labels.addView(title);labels.addView(detail);open.addView(labels,new LayoutParams(0,-2,1));more=Ui.iconButton(c,"menu","Действия с файлом");card.addView(more,new LayoutParams(Ui.dp(c,48),Ui.dp(c,48)));}
    }
}
