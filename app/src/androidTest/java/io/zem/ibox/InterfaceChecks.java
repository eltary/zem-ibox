package io.zem.ibox;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.Layout;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/** Visual audit of real screens, dialog bounds, editable content and icon-independent text centering. */
final class InterfaceChecks {
    private final Instrumentation test;private final MainActivity a;private int checks,shots;
    InterfaceChecks(Instrumentation test,MainActivity a){this.test=test;this.a=a;}
    interface Condition {boolean ok()throws Exception;}
    void main(Runnable task){java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();test.runOnMainSync(()->{try{task.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new AssertionError(failure.get().getMessage(),failure.get());}
    void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    void waitFor(Condition condition,int timeout)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<deadline){if(condition.ok())return;Thread.sleep(70);}throw new AssertionError("UI audit timed out");}
    Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    void invoke(Object object,String method,Class<?>[] types,Object...args)throws Exception{Method m=object.getClass().getDeclaredMethod(method,types);m.setAccessible(true);main(()->{try{m.invoke(object,args);}catch(Exception e){throw new RuntimeException(e);}});}
    <T extends View>List<T> views(View root,Class<T> type){List<T> result=new ArrayList<>();if(type.isInstance(root))result.add(type.cast(root));if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)result.addAll(views(group.getChildAt(i),type));return result;}
    View root(Activity activity){return activity.findViewById(android.R.id.content);}
    Button button(View root,String caption){for(Button button:views(root,Button.class))if(caption.equals(button.getText().toString()))return button;return null;}
    Rect rect(View view){int[] xy=new int[2];view.getLocationOnScreen(xy);return new Rect(xy[0],xy[1],xy[0]+view.getWidth(),xy[1]+view.getHeight());}
    int dp(float size){return Math.round(a.getResources().getDisplayMetrics().density*size);}
    void fixture(String state)throws Exception{try(InputStream in=new URL("http://10.0.2.2:8080/test/"+state).openStream()){while(in.read()!=-1){}}}
    void idle()throws Exception{test.waitForIdleSync();Thread.sleep(180);}
    void screenshot(String name,View root)throws Exception{idle();File dir=new File(a.getExternalFilesDir(null),"interface-audit");dir.mkdirs();Bitmap image=test.getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(dir,String.format(Locale.ROOT,"%02d-%s.png",++shots,name)))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();audit(root,name);}
    void audit(View root,String name){main(()->{for(Button button:views(root,Button.class)){if(!button.isShown()||button.getWidth()==0||button.getHeight()==0||button.getText().length()==0)continue;String caption=button.getText().toString();Layout layout=button.getLayout();if(layout==null)continue;
        check(button.getHeight()>=dp(47),name+": short button "+caption);check(layout.getHeight()<=button.getHeight()-button.getCompoundPaddingTop()-button.getCompoundPaddingBottom()+dp(2),name+": caption exceeds button height "+caption);check(layout.getLineCount()<=button.getMaxLines(),name+": clipped caption "+caption);for(int line=0;line<layout.getLineCount();line++)check(layout.getEllipsisCount(line)==0,name+": ellipsized caption "+caption);
        if("action".equals(button.getTag()))for(int line=0;line<layout.getLineCount();line++){float middle=button.getCompoundPaddingLeft()+(layout.getLineLeft(line)+layout.getLineRight(line))/2;check(Math.abs(middle-button.getWidth()/2f)<=dp(1.5f),name+": icon shifts caption "+caption);}
    }});}
    AlertDialog last(Activity owner)throws Exception{waitFor(()->!Dialogs.visible(owner).isEmpty(),3000);List<AlertDialog> active=Dialogs.visible(owner);return active.get(active.size()-1);}
    void dialogsClose(Activity owner){main(()->Dialogs.dismissAll(owner));}
    void dialog(String name,Activity owner)throws Exception{idle();AlertDialog dialog=last(owner);View decor=dialog.getWindow().getDecorView();Rect window=rect(decor);screenshot(name,decor);main(()->{for(EditText input:views(decor,EditText.class))if("input".equals(input.getTag())){Rect bounds=rect(input);check(bounds.left-window.left>=dp(16)&&window.right-bounds.right>=dp(16),name+": editable field touches dialog edge");check(input.getHeight()>=dp(48),name+": editable field too short");}for(int action:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){Button button=dialog.getButton(action);if(button!=null&&button.getVisibility()==View.VISIBLE){Rect visible=new Rect();check(button.getGlobalVisibleRect(visible)&&visible.height()>=button.getHeight()-2,name+": dialog action is clipped");}}});}
    void open(String name,Runnable action)throws Exception{dialogsClose(a);main(action);dialog(name,a);dialogsClose(a);}
    void page(int tab,String name)throws Exception{dialogsClose(a);main(()->a.navigate(tab));idle();if(tab==1)waitFor(()->!a.remoteLoading,7000);screenshot(name,root(a));List<ScrollView> scrolls=views(a.body,ScrollView.class);if(!scrolls.isEmpty()){main(()->scrolls.get(0).fullScroll(View.FOCUS_DOWN));screenshot(name+"-bottom",root(a));}List<ListView> lists=views(a.body,ListView.class);if(!lists.isEmpty()){ListView list=lists.get(0);main(()->list.setSelection(Math.max(0,list.getCount()-1)));screenshot(name+"-bottom",root(a));}}
    String run()throws Exception{
        if(!Set.of("ranchu","goldfish").contains(Build.HARDWARE))throw new AssertionError("Project emulator required");dialogsClose(a);main(a::stopLive);fixture("card/1");a.s.get("SD_STATUS");fixture("list/multi");main(()->{try{a.s.state.put("AP_SSID_INFO",new JSONObject().put("ssid","DVR-demo"));a.s.state.put("VIDEO_MIC",new JSONObject().put("mic","1"));a.s.state.put("DATE_TIME",new JSONObject().put("date",DeviceClock.wire(System.currentTimeMillis())));}catch(Exception e){throw new RuntimeException(e);}});
        page(0,"camera-ready");open("connection-help",()->a.connectionHelp(false));open("smoothness",a::choosePreviewMode);open("transport",a::chooseTransport);open("quality",a::showLiveQuality);page(1,"archive");
        invoke(a.archiveScreen,"filters",new Class<?>[]{});dialog("archive-filters",a);dialogsClose(a);invoke(a.archiveScreen,"incident",new Class<?>[]{});dialog("incident",a);dialogsClose(a);
        if(!a.remote.isEmpty())open("archive-actions",()->a.fileActions(a.remote.get(0)));
        main(()->a.remoteSearch.setText("nothing-matches"));screenshot("archive-no-matches",root(a));main(()->a.remoteSearch.setText(""));
        main(()->TimeWindowPicker.show(a,System.currentTimeMillis()-3600000,System.currentTimeMillis(),range->{}));dialog("time-window",a);dialogsClose(a);
        main(()->TimeWindowPicker.moment(a,System.currentTimeMillis(),value->{}));dialog("date-picker",a);AlertDialog date=last(a);main(()->date.getButton(AlertDialog.BUTTON_POSITIVE).performClick());dialog("time-picker",a);dialogsClose(a);
        page(2,"phone");page(3,"settings-recorder");open("address",a::addressDialog);
        main(a::addressDialog);idle();AlertDialog address=last(a);EditText input=views(address.getWindow().getDecorView(),EditText.class).get(0);main(()->{input.requestFocus();((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});Thread.sleep(500);dialog("address-keyboard",a);Rect focused=new Rect();check(input.getGlobalVisibleRect(focused)&&focused.height()>=input.getHeight()-dp(2),"Keyboard keeps the address field visible");main(()->((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0));dialogsClose(a);
        open("wifi",a::changeWifi);main(a::changeWifi);idle();AlertDialog wifi=last(a);main(()->wifi.getButton(AlertDialog.BUTTON_POSITIVE).performClick());idle();check(wifi.isShowing(),"Invalid Wi-Fi input preserves the dialog");check(views(wifi.getWindow().getDecorView(),EditText.class).stream().anyMatch(v->v.getError()!=null),"Invalid Wi-Fi input has an inline error");screenshot("wifi-validation",wifi.getWindow().getDecorView());dialogsClose(a);
        open("recording-resolution",()->a.videoResolution(0));open("recorder-setting",()->a.editSetting(SettingDef.ALL.get(0)));open("format-card",a::confirmCardFormat);
        main(()->{a.settingsTab=1;a.body.removeAllViews();a.settingsPage();a.stateChanged();});screenshot("settings-app",root(a));List<ScrollView> settings=views(a.body,ScrollView.class);main(()->settings.get(0).fullScroll(View.FOCUS_DOWN));screenshot("settings-app-bottom",root(a));open("about",a::showAbout);open("licenses",()->a.showLicenses(false));open("logs",a::showLogs);open("updates",a::showAppUpdates);
        DownloadsActivity downloads=(DownloadsActivity)test.startActivitySync(new Intent(a,DownloadsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));screenshot("downloads",root(downloads));main(downloads::finish);waitFor(downloads::isDestroyed,3000);
        a.getSharedPreferences("playback",0).edit().putBoolean("software",true).commit();List<MediaItem> files=a.s.files(0);MediaItem sample=files.get(0);File movie=new File(a.getCacheDir(),"interface-preview.mp4");HttpURLConnection fetch=(HttpURLConnection)new URL("http://10.0.2.2:8080"+sample.path).openConnection();fetch.setRequestProperty("Range","bytes=0-");try(InputStream in=fetch.getInputStream();OutputStream out=new FileOutputStream(movie)){Io.copy(in,out);}finally{fetch.disconnect();}Uri uri=Library.publish(a,movie,"Поездка.mp4","video/mp4","Tests");Library.add(a,uri,"Поездка.mp4","video/mp4",movie.length(),"test original");movie.delete();
        PlayerActivity player=(PlayerActivity)test.startActivitySync(PlaybackQueue.local(a,Library.all(a),uri.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitFor(()->Boolean.TRUE.equals(field(player,"haveVideoFrame")),12000);org.videolan.libvlc.MediaPlayer engine=(org.videolan.libvlc.MediaPlayer)field(player,"player");main(()->{if(engine.isPlaying())engine.pause();});screenshot("player",root(player));ScrollView controls=(ScrollView)field(player,"controlScroll");main(()->controls.fullScroll(View.FOCUS_DOWN));screenshot("player-tools",root(player));invoke(player,"showSpeed",new Class<?>[]{});dialog("player-speed",player);dialogsClose(player);invoke(player,"showClip",new Class<?>[]{});dialog("player-clip",player);dialogsClose(player);main(()->player.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));waitFor(()->player.getResources().getConfiguration().orientation==2,5000);Thread.sleep(400);screenshot("player-landscape",root(player));invoke(player,"showSpeed",new Class<?>[]{});dialog("speed-landscape",player);dialogsClose(player);invoke(player,"showClip",new Class<?>[]{});dialog("clip-landscape",player);dialogsClose(player);main(player::finish);waitFor(player::isDestroyed,5000);
        page(2,"phone-with-files");fixture("card/0");a.s.get("SD_STATUS");page(1,"archive-no-card");fixture("card/1");a.s.get("SD_STATUS");main(a.s::disconnect);main(()->a.settingsTab=0);page(0,"camera-offline");page(1,"archive-offline");page(2,"phone-offline");page(3,"settings-offline");
        return "PASS: "+checks+" interface assertions, "+shots+" screenshots on API "+Build.VERSION.SDK_INT+"\n";
    }
}
