package io.zem.ibox;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
final class Widgets {
    static LinearLayout column(Context c){return spaced(c,LinearLayout.VERTICAL);}
    static LinearLayout row(Context c){LinearLayout row=spaced(c,LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    static LinearLayout responsive(Context c){return c.getResources().getConfiguration().fontScale>1.3f?column(c):row(c);}
    static LinearLayout.LayoutParams pair(LinearLayout row){return row.getOrientation()==LinearLayout.VERTICAL?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1);}
    static android.widget.ScrollView dialogBody(Context c,android.view.View content){android.widget.ScrollView scroll=Dialogs.body(c);scroll.addView(content);return scroll;}
    private static LinearLayout spaced(Context c,int orientation){LinearLayout row=new LinearLayout(c);row.setOrientation(orientation);GradientDrawable gap=new GradientDrawable();gap.setColor(Color.TRANSPARENT);gap.setSize(Ui.dp(c,10),Ui.dp(c,10));row.setDividerDrawable(gap);row.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);return row;}
}
