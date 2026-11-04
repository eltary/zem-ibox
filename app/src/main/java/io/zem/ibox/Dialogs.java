package io.zem.ibox;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.util.*;

/** One set of content insets and readable controls for application dialogs. */
final class Dialogs {
    private static final Map<AlertDialog,Boolean> created=new WeakHashMap<>();
    static AlertDialog.Builder builder(Context context){return new Builder(context);}
    private static final class Builder extends AlertDialog.Builder {
        Builder(Context context){super(context);}
        @Override public AlertDialog.Builder setView(View view){return super.setView(content(getContext(),view));}
        @Override public AlertDialog.Builder setMessage(CharSequence message){TextView text=Ui.text(getContext(),message==null?"":message.toString(),16,Ui.INK);return setView(text);}
        @Override public AlertDialog.Builder setMessage(int message){return setMessage(getContext().getText(message));}
        @Override public AlertDialog create(){return prepare(super.create());}
    }
    static <T extends AlertDialog>T prepare(T dialog){created.put(dialog,true);Activity owner=activity(dialog.getContext());if(owner!=null)dialog.setOwnerActivity(owner);dialog.setOnShowListener(d->polish(dialog));return dialog;}
    static <T extends AlertDialog>T show(T dialog){prepare(dialog);dialog.show();return dialog;}
    static List<AlertDialog> visible(Activity owner){List<AlertDialog> result=new ArrayList<>();for(AlertDialog dialog:new ArrayList<>(created.keySet()))if(dialog!=null&&dialog.getOwnerActivity()==owner&&dialog.isShowing())result.add(dialog);return result;}
    static void dismissAll(Activity owner){for(AlertDialog dialog:new ArrayList<>(created.keySet()))if(dialog!=null&&dialog.getOwnerActivity()==owner){dialog.dismiss();created.remove(dialog);}}
    private static Activity activity(Context context){for(int i=0;i<10;i++){if(context instanceof Activity a)return a;if(!(context instanceof ContextWrapper wrapper)||wrapper.getBaseContext()==context)break;context=wrapper.getBaseContext();}return null;}
    static ScrollView content(Context context,View view){
        View current=view;while(current instanceof ScrollView scroll){scroll.setPadding(0,0,0,0);if(scroll.getChildCount()==0)break;current=scroll.getChildAt(0);}if(current instanceof LinearLayout&&current.getBackground()==null)current.setPadding(0,0,0,0);
        ScrollView scroll;if(view instanceof ScrollView existing)scroll=existing;else{scroll=body(context);scroll.addView(view,new ScrollView.LayoutParams(-1,-2));}
        int side=Ui.dp(context,24),vertical=Ui.dp(context,12);scroll.setPadding(side,vertical,side,vertical);scroll.setFillViewport(false);scroll.setClipToPadding(true);return scroll;
    }
    static ScrollView body(Context context){return new Body(context);}
    private static final class Body extends ScrollView {
        private AlertDialog dialog;
        Body(Context context){super(context);}
        @Override protected void onMeasure(int width,int height){
            Rect visible=new Rect();Activity owner=activity(getContext());if(dialog!=null)dialog.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);else if(owner!=null)owner.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);int available=visible.height()>0?visible.height():getResources().getDisplayMetrics().heightPixels;
            int chrome=0;if(dialog!=null)for(String name:new String[]{"topPanel","buttonPanel"}){int id=getResources().getIdentifier(name,"id","android");View panel=dialog.findViewById(id);if(panel!=null&&panel.getVisibility()!=GONE)chrome+=panel.getMeasuredHeight();}
            int cap=Math.min((int)(available*.70f),available-chrome-Ui.dp(getContext(),8));int limit=MeasureSpec.getMode(height)==MeasureSpec.UNSPECIFIED?cap:Math.min(MeasureSpec.getSize(height),cap);super.onMeasure(width,MeasureSpec.makeMeasureSpec(Math.max(0,limit),MeasureSpec.AT_MOST));
        }
    }
    private static void bindBody(View view,AlertDialog dialog){if(view instanceof Body body)body.dialog=dialog;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)bindBody(group.getChildAt(i),dialog);}
    private static void polish(AlertDialog dialog){
        bindBody(dialog.getWindow().getDecorView(),dialog);
        Context context=dialog.getContext();Window window=dialog.getWindow();if(window!=null){int mode=window.getAttributes().softInputMode;window.setSoftInputMode((mode&WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);window.getDecorView().setClipToOutline(true);int maximum=Ui.dp(context,480);window.setLayout(Math.min(maximum,context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,32)),WindowManager.LayoutParams.WRAP_CONTENT);}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){Button button=dialog.getButton(which);if(button==null)continue;button.setAllCaps(false);button.setTextSize(14);button.setMinHeight(Ui.dp(context,48));button.setMinimumHeight(Ui.dp(context,48));button.setMaxLines(3);button.setEllipsize(null);button.setPadding(Ui.dp(context,12),Ui.dp(context,8),Ui.dp(context,12),Ui.dp(context,8));}
        int titleId=context.getResources().getIdentifier("alertTitle","id","android");TextView title=dialog.findViewById(titleId);if(title!=null){title.setTextSize(18);title.setSingleLine(false);title.setMaxLines(Integer.MAX_VALUE);title.setEllipsize(null);}
        compoundControls(dialog.getWindow().getDecorView(),context);
        ListView list=dialog.getListView();if(list!=null){Runnable rows=()->{for(int i=0;i<list.getChildCount();i++)readableRow(list.getChildAt(i),context);};list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener(){public void onChildViewAdded(View parent,View child){readableRow(child,context);}public void onChildViewRemoved(View parent,View child){}});list.post(rows);}
    }
    private static void compoundControls(View root,Context context){if(root instanceof CompoundButton control){control.setMinHeight(Ui.dp(context,48));control.setMinimumHeight(Ui.dp(context,48));control.setTextColor(Ui.INK);}if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)compoundControls(group.getChildAt(i),context);}
    private static void readableRow(View view,Context context){if(view instanceof TextView text){if(text.getMaxLines()!=Integer.MAX_VALUE){text.setSingleLine(false);text.setMaxLines(Integer.MAX_VALUE);text.setEllipsize(null);}text.setMinHeight(Ui.dp(context,48));}else if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)readableRow(group.getChildAt(i),context);}
}
