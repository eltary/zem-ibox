package io.zem.ibox;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.*;

/** Platform API differences shared by both screens. */
final class Platform {
    private static final int LAYOUT=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    static void insets(Activity activity,View root,int left,int top,int right,int bottom){
        if(Build.VERSION.SDK_INT<30)activity.getWindow().getDecorView().setSystemUiVisibility(LAYOUT);
        root.setOnApplyWindowInsetsListener((v,i)->{Rect b=bars(i);v.setPadding(left+b.left,top+b.top,right+b.right,bottom+b.bottom);return i;});
    }
    private static Rect bars(WindowInsets insets){
        if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());return new Rect(b.left,b.top,b.right,b.bottom);}
        Rect b=new Rect(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
        if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){DisplayCutout c=insets.getDisplayCutout();b.left=Math.max(b.left,c.getSafeInsetLeft());b.top=Math.max(b.top,c.getSafeInsetTop());b.right=Math.max(b.right,c.getSafeInsetRight());b.bottom=Math.max(b.bottom,c.getSafeInsetBottom());}
        return b;
    }
    static void fullscreen(Activity activity,boolean full){
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=activity.getWindow().getInsetsController();if(c!=null){if(full)c.hide(WindowInsets.Type.systemBars());else c.show(WindowInsets.Type.systemBars());}}
        else activity.getWindow().getDecorView().setSystemUiVisibility(LAYOUT|(full?View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY:0));
    }
}
