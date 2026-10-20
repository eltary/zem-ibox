package io.zem.iboxdrive;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.FrameLayout;

/** The container supplies letterboxing; SurfaceView itself must stay transparent. */
final class AspectPreview extends FrameLayout {
    final SurfaceView surface;
    private int videoWidth=640,videoHeight=480;
    AspectPreview(Context context){super(context);setBackgroundColor(Color.BLACK);surface=new SurfaceView(context);addView(surface,new LayoutParams(-1,-1,Gravity.CENTER));}
    void videoSize(int width,int height){if(width>0&&height>0&&(videoWidth!=width||videoHeight!=height)){videoWidth=width;videoHeight=height;requestLayout();}}
    protected void onMeasure(int widthSpec,int heightSpec){
        super.onMeasure(widthSpec,heightSpec);
        int w=Math.max(0,getMeasuredWidth()-getPaddingLeft()-getPaddingRight());
        int h=Math.max(0,getMeasuredHeight()-getPaddingTop()-getPaddingBottom());
        float scale=Math.min(w/(float)videoWidth,h/(float)videoHeight);
        int width=Math.round(videoWidth*scale),height=Math.round(videoHeight*scale);
        surface.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
    }
}
