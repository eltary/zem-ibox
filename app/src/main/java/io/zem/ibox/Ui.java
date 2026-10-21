package io.zem.ibox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Shared visual language for camera, library, player and settings. */
final class Ui {
    static final int BG=0xff080f16,CARD=0xff121f29,RAISED=0xff192b36,LINE=0xff253943;
    static final int INK=0xffeaf4f6,MUTED=0xff8aa3b0,ACCENT=0xff55dfc1,CYAN=0xff66cce5;
    private Ui(){}
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable panel(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(color!=Color.TRANSPARENT)d.setStroke(dp(c,1),color==BG?0xff172b36:LINE);return d;}
    static GradientDrawable background(){return new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff10232f,BG,0xff070d13});}
    static Drawable buttonBackground(Context c,boolean selected,String role){
        GradientDrawable d;
        if(selected&&"action".equals(role)){d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff76edd4,0xff42c9c9});d.setStroke(dp(c,1),0xff9bf3df);}
        else {d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{selected?0xff1c3e45:RAISED,selected?0xff123039:CARD});d.setStroke(dp(c,1),selected?0xff38777b:LINE);}
        d.setCornerRadius(dp(c,"segment".equals(role)?12:16));
        return new RippleDrawable(ColorStateList.valueOf(0x24ffffff),d,null);
    }
    static TextView text(Context c,String label,int size,int color){TextView t=new TextView(c);t.setText(label);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(0,dp(c,3),0,dp(c,3));t.setFontFeatureSettings("kern");return t;}
    static Button button(Context c,String label,boolean selected){Button b=new Button(c);b.setText(label);b.setTag("action");b.setAllCaps(false);b.setTextSize(13);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinHeight(dp(c,48));b.setMinWidth(0);b.setMinimumWidth(0);b.setMaxLines(2);b.setEllipsize(TextUtils.TruncateAt.END);b.setPadding(dp(c,14),dp(c,5),dp(c,14),dp(c,5));android.animation.StateListAnimator states=new android.animation.StateListAnimator();states.addState(new int[]{-android.R.attr.state_enabled},android.animation.ObjectAnimator.ofFloat(b,"alpha",.38f).setDuration(100));states.addState(new int[]{android.R.attr.state_pressed},android.animation.ObjectAnimator.ofFloat(b,"alpha",.78f).setDuration(80));states.addState(new int[]{},android.animation.ObjectAnimator.ofFloat(b,"alpha",1f).setDuration(80));b.setStateListAnimator(states);select(b,selected);return b;}
    static void select(Button b,boolean selected){String role=b.getTag() instanceof String?(String)b.getTag():"action";b.setSelected(selected);b.setTextColor(selected&&"action".equals(role)?BG:selected?ACCENT:INK);b.setBackground(buttonBackground(b.getContext(),selected,role));}
    static void leading(Button b,String icon){b.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(b.getContext(),icon,19,b.isSelected()?BG:ACCENT),null,null,null);b.setCompoundDrawablePadding(dp(b.getContext(),9));}
    static Button iconButton(Context c,String icon,String description){return new IconButton(c,icon,description);}
    static void setIcon(Button button,String name){if(button instanceof IconButton icon){icon.glyph=icon(button.getContext(),name,22,button.isSelected()?BG:INK);button.invalidate();}}
    private static final class IconButton extends Button {
        private Drawable glyph;
        IconButton(Context c,String name,String description){super(c);glyph=icon(c,name,22,INK);setContentDescription(description);setMinHeight(dp(c,48));setMinWidth(0);setMinimumWidth(0);setPadding(0,0,0,0);setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),panel(c,0xff12232e,16),null));setStateListAnimator(null);}
        protected void onDraw(Canvas canvas){super.onDraw(canvas);int n=dp(getContext(),22);glyph.setBounds((getWidth()-n)/2,(getHeight()-n)/2,(getWidth()+n)/2,(getHeight()+n)/2);glyph.draw(canvas);}
    }
    static Button nav(Context c,String title,String name,boolean active){Button b=button(c,title,false);b.setTag("navigation");b.setTextSize(12);b.setMaxLines(2);b.setPadding(dp(c,2),dp(c,8),dp(c,2),dp(c,6));b.setGravity(Gravity.CENTER);b.setTextColor(active?ACCENT:MUTED);boolean wide=c.getResources().getConfiguration().fontScale>1.5f||(c.getResources().getConfiguration().screenWidthDp<360&&c.getResources().getConfiguration().fontScale>1.15f);if(wide){b.setPadding(dp(c,8),dp(c,8),dp(c,8),dp(c,8));b.setCompoundDrawables(icon(c,name,20,active?ACCENT:MUTED),null,null,null);}else b.setCompoundDrawables(null,icon(c,name,22,active?ACCENT:MUTED),null,null);b.setCompoundDrawablePadding(dp(c,5));b.setBackground(active?panel(c,0xff173038,15):new RippleDrawable(ColorStateList.valueOf(0x20ffffff),panel(c,Color.TRANSPARENT,15),null));b.setSelected(active);return b;}
    static TextView section(Context c,String label){TextView t=text(c,label.toUpperCase(java.util.Locale.ROOT),12,MUTED);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setLetterSpacing(.13f);t.setPadding(dp(c,2),dp(c,12),0,dp(c,3));return t;}
    static LinearLayout heading(Context c,String title,String subtitle){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);TextView t=text(c,title,26,INK);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));l.addView(t);if(subtitle!=null){TextView s=text(c,subtitle,12,MUTED);l.addView(s);}return l;}
    static LinearLayout card(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));l.setBackground(panel(c,CARD,20));return l;}
    static void switchStyle(Switch toggle){Context c=toggle.getContext();toggle.setTextColor(INK);toggle.setTextSize(14);toggle.setMinHeight(dp(c,56));toggle.setPadding(dp(c,14),dp(c,6),dp(c,14),dp(c,6));toggle.setBackground(panel(c,CARD,16));toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,MUTED}));toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xff295d59,LINE}));}
    static CharSequence settingValue(String title,String value){android.text.SpannableString label=new android.text.SpannableString(title+"\n"+value);int start=title.length()+1;label.setSpan(new android.text.style.ForegroundColorSpan(MUTED),start,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);label.setSpan(new android.text.style.RelativeSizeSpan(.86f),start,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return label;}
    static EditText input(Context c,String hint,String value){EditText e=new EditText(c);e.setText(value);e.setHint(hint);e.setTextSize(14);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setPadding(dp(c,16),dp(c,13),dp(c,16),dp(c,13));e.setBackground(panel(c,CARD,15));e.setMinHeight(dp(c,48));return e;}
    static Drawable icon(Context c,String name,int size,int color){Icon glyph=new Icon(name,color);int n=dp(c,size);glyph.setBounds(0,0,n,n);return glyph;}
    private static final class Icon extends Drawable {
        private final String name;private int color;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(String name,int color){this.name=name;this.color=color;}
        private void lines(Canvas c,float... xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p);}
        private void box(Canvas c,float l,float t,float r,float b,float radius){c.drawRoundRect(l,t,r,b,radius,radius,p);}
        public void draw(Canvas c){int checkpoint=c.save();Rect b=getBounds();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(name){
                case "camera":box(c,3,6,21,20,3);c.drawCircle(12,13,4,p);lines(c,8,6,9,3,15,3,16,6);break;
                case "archive":box(c,4,7,20,21,2);box(c,3,3,21,7,1);lines(c,9,12,15,12);break;
                case "folder":lines(c,3,8,3,5,10,5,12,8,21,8,21,20,3,20,3,8);break;
                case "settings":c.drawCircle(12,12,4,p);for(int a=0;a<8;a++){double angle=a*Math.PI/4;c.drawLine(12+(float)Math.cos(angle)*8,12+(float)Math.sin(angle)*8,12+(float)Math.cos(angle)*10,12+(float)Math.sin(angle)*10,p);}c.drawCircle(12,12,8,p);break;
                case "wifi":c.drawArc(2,4,22,23,224,92,false,p);c.drawArc(6,9,18,22,222,96,false,p);c.drawArc(10,15,14,20,220,100,false,p);p.setStyle(Paint.Style.FILL);c.drawCircle(12,21,1,p);break;
                case "volume":lines(c,3,10,7,10,12,6,12,20,7,16,3,16,3,10);c.drawArc(11,7,21,19,-60,120,false,p);break;
                case "quality":box(c,3,4,21,20,3);lines(c,7,9,7,15,11,12,7,9);lines(c,15,9,18,9,18,15,15,15);break;
                case "expand":lines(c,9,3,3,3,3,9);lines(c,15,3,21,3,21,9);lines(c,3,15,3,21,9,21);lines(c,21,15,21,21,15,21);break;
                case "photo":box(c,3,3,21,21,3);c.drawCircle(8,8,1.5f,p);lines(c,3,17,9,12,13,16,16,13,21,18);break;
                case "record":c.drawCircle(12,12,8,p);p.setStyle(Paint.Style.FILL);c.drawCircle(12,12,3,p);break;
                case "play":lines(c,9,5,19,12,9,19,9,5);break;
                case "external":lines(c,14,3,21,3,21,10);lines(c,21,3,10,14);lines(c,10,5,4,5,4,21,20,21,20,14);break;
                case "rotate":c.drawArc(3,3,21,21,-100,280,false,p);lines(c,9,2,14,3,12,8);break;
                case "previous":lines(c,6,5,6,19);lines(c,18,5,9,12,18,19,18,5);break;
                case "next":lines(c,18,5,18,19);lines(c,6,5,15,12,6,19,6,5);break;
                case "pause":lines(c,8,5,8,19);lines(c,16,5,16,19);break;
                case "shield":lines(c,12,3,21,7,20,15,17,19,12,22,7,19,4,15,3,7,12,3);lines(c,8,12,11,15,16,9);break;
                case "download":lines(c,12,3,12,16);lines(c,7,11,12,16,17,11);lines(c,4,17,4,21,20,21,20,17);break;
                case "refresh":c.drawArc(4,4,20,20,45,285,false,p);lines(c,16,3,20,5,20,10);break;
                case "back":lines(c,14,5,7,12,14,19);break;
                case "chevron":lines(c,9,6,15,12,9,18);break;
                case "down":lines(c,6,9,12,15,18,9);break;
                case "close":lines(c,6,6,18,18);lines(c,18,6,6,18);break;
                case "menu":p.setStyle(Paint.Style.FILL);for(int y=5;y<=19;y+=7)c.drawCircle(12,y,1.5f,p);break;
                case "sd":lines(c,7,3,20,3,20,21,4,21,4,7,7,3);for(int x=9;x<=17;x+=4)lines(c,x,6,x,10);box(c,8,14,16,18,1);break;
                case "info":c.drawCircle(12,12,9,p);lines(c,12,11,12,17);p.setStyle(Paint.Style.FILL);c.drawCircle(12,7,1,p);break;
                case "clock":c.drawCircle(12,12,9,p);lines(c,12,6,12,12,16,14);break;
                case "search":c.drawCircle(10,10,6,p);lines(c,15,15,21,21);break;
                default:box(c,4,3,20,21,3);lines(c,8,8,16,8);lines(c,8,12,16,12);break;
            }c.restoreToCount(checkpoint);
        }
        public void setAlpha(int alpha){color=(color&0xffffff)|(alpha<<24);invalidateSelf();}
        public void setColorFilter(ColorFilter f){p.setColorFilter(f);invalidateSelf();}
        public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        public int getIntrinsicWidth(){return getBounds().width();}public int getIntrinsicHeight(){return getBounds().height();}
    }
}
