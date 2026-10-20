package io.zem.ibox;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    static final int BG=Ui.BG,CARD=Ui.CARD,INK=Ui.INK,MUTED=Ui.MUTED,ACCENT=Ui.ACCENT;
    private JieliSession s;
    private LinearLayout root,body,controlBody,cameraBar;
    private int controlTab,settingsTab;
    private Button audioButton,phoneRecordButton;
    private Switch rearSwitch;
    private final List<View> cardActions=new ArrayList<>();
    private boolean visible;
    private TextView connection,card,transfer,liveStatus,deviceLabel,sourceBadge,idleTitle,idleHint;
    private View previewPlaceholder;
    private Button connectButton,copyButton,recordButton,remoteDownload;
    private SurfaceView surface;
    private AspectPreview preview;
    private LiveStream live;
    private volatile boolean wantsLive;
    private boolean sound,fullScreen,resumeLive;
    private int tab=0,camera=0,quality=0;
    private volatile long liveGeneration;
    private String search="";
    private int filter=0;
    private int localCount=-1;
    private List<MediaItem> remote=new ArrayList<>();
    private ListView fileList;
    private TextView remoteEmpty;
    private List<String> clipboardParts;
    private int clipboardIndex;
    private String note="";
    private final Runnable observer=this::stateChanged;
    private final Map<String,TextView> settingViews=new HashMap<>();
    private interface Work {void run()throws Exception;}
    public void onCreate(Bundle saved){
        super.onCreate(saved);s=((RoadApp)getApplication()).session;
        if(saved!=null){tab=saved.getInt("tab");camera=saved.getInt("camera");quality=saved.getInt("quality");}
        else quality=getPreferences(0).getInt("quality."+camera,0);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        root=column();root.setBackground(Ui.background());root.setPadding(dp(16),dp(12),dp(16),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());root.setPadding(dp(16)+bars.left,dp(6)+bars.top,dp(16)+bars.right,bars.bottom+dp(4));return insets;});
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);root.setFocusableInTouchMode(true);setContentView(root);build();s.listen(observer);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
        if(BuildConfig.DEBUG&&getIntent().hasExtra("test_host"))s.worker.execute(()->{try{s.connect(getIntent().getStringExtra("test_host"));}catch(Exception e){Diag.error("Test connect",e);}});
    }
    private void build(){
        root.removeAllViews();previewPlaceholder=null;sourceBadge=null;idleTitle=null;idleHint=null;deviceLabel=null;settingViews.clear();recordButton=null;connectButton=null;audioButton=null;phoneRecordButton=null;cameraBar=null;controlBody=null;fileList=null;remoteEmpty=null;rearSwitch=null;remoteDownload=null;cardActions.clear();
        if(fullScreen){fullScreenPage();return;}
        LinearLayout title=row();ImageView mark=new ImageView(this);mark.setImageResource(io.zem.ibox.R.drawable.ic_launcher_art);mark.setBackground(Ui.panel(this,0xff06141d,12));mark.setClipToOutline(true);mark.setContentDescription(null);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);title.addView(mark,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout branding=new LinearLayout(this);branding.setOrientation(LinearLayout.VERTICAL);branding.setGravity(Gravity.CENTER_VERTICAL);TextView brand=text("Zem iBox",20,INK);brand.setMaxLines(1);brand.setHorizontallyScrolling(false);brand.setEllipsize(TextUtils.TruncateAt.END);brand.setAutoSizeTextTypeUniformWithConfiguration(14,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));branding.addView(brand);TextView tagline=text("ТВОЯ ДОРОГА В КАДРЕ",8,MUTED);tagline.setLetterSpacing(.12f);tagline.setSingleLine();tagline.setEllipsize(TextUtils.TruncateAt.END);if(getResources().getConfiguration().screenWidthDp>=360&&getResources().getConfiguration().fontScale<=1.15f)branding.addView(tagline);title.addView(branding,new LinearLayout.LayoutParams(0,dp(54),1));
        connection=text("",10,MUTED);connection.setSingleLine();connection.setEllipsize(TextUtils.TruncateAt.END);connection.setGravity(Gravity.CENTER);connection.setPadding(dp(9),dp(6),dp(9),dp(6));title.addView(connection,new LinearLayout.LayoutParams(dp(88),dp(32)));
        Button menu=Ui.iconButton(this,"menu","Меню приложения");menu.setOnClickListener(v->appMenu(menu));title.addView(menu,new LinearLayout.LayoutParams(dp(44),dp(44)));root.addView(title);
        card=text("",12,MUTED);card.setVisibility(View.GONE);
        transfer=text("",11,ACCENT);transfer.setPadding(dp(12),dp(9),dp(12),dp(9));transfer.setBackground(Ui.panel(this,0xff123039,12));transfer.setOnClickListener(v->{if(TransferService.active)new AlertDialog.Builder(this).setMessage("Остановить скачивание? Частичная копия сохранится для продолжения.").setNegativeButton("Продолжить",null).setPositiveButton("Остановить",(d,w)->TransferService.cancel=true).show();});root.addView(transfer);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout navigation=row();navigation.setPadding(dp(4),dp(5),dp(4),dp(4));navigation.setBackground(Ui.panel(this,0xff101d26,22));String[] labels={"Камера","Архив","Телефон","Настройки"},icons={"camera","archive","folder","settings"};
        for(int i=0;i<labels.length;i++){final int page=i;Button b=Ui.nav(this,labels[i],icons[i],tab==i);b.setContentDescription("Вкладка: "+labels[i]);b.setOnClickListener(v->navigate(page));navigation.addView(b,new LinearLayout.LayoutParams(0,dp(60),1));}root.addView(navigation);
        if(tab==0)livePage();else if(tab==1)remotePage();else if(tab==2)localPage();else settingsPage();stateChanged();
    }
    private void navigate(int page){if(tab==page)return;resumeLive=false;stopLive();tab=page;if(!s.rearAvailable())camera=0;build();}
    private void appMenu(View anchor){PopupMenu popup=new PopupMenu(this,anchor);popup.getMenu().add("Wi-Fi регистратора");if(s.connected())popup.getMenu().add("Отключиться");popup.getMenu().add("Логи и диагностика");popup.getMenu().add("О приложении");popup.setOnMenuItemClickListener(item->{String label=item.getTitle().toString();if(label.startsWith("Wi-Fi"))openWifi();else if(label.equals("Отключиться")){resumeLive=false;stopLive();s.disconnect();}else if(label.startsWith("Логи"))showLogs();else showAbout();return true;});popup.show();}
    private void showAbout(){new AlertDialog.Builder(this).setTitle("Zem iBox "+BuildConfig.VERSION_NAME).setMessage("Просмотр и управление совместимыми регистраторами Jieli / DVRunning2.\n\nВозможности зависят от устройства и прошивки. Встроенный плеер использует libVLC 3.7.7 (VideoLAN).").setPositiveButton("Закрыть",null).setNeutralButton("Лицензии",(d,n)->{try{String notices,license;try(InputStream in=getAssets().open("third-party-notices.txt")){notices=new String(assetBytes(in),java.nio.charset.StandardCharsets.UTF_8);}try(InputStream in=getAssets().open("libvlc-LICENSE.txt")){license=new String(assetBytes(in),java.nio.charset.StandardCharsets.UTF_8);}TextView text=text(notices+"\n\n"+license,12,INK);text.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.setPadding(dp(16),0,dp(16),0);scroll.addView(text);new AlertDialog.Builder(this).setTitle("Открытые компоненты").setView(scroll).setPositiveButton("Закрыть",null).show();}catch(IOException e){fail("Лицензии",e);}}).show();}
    private static byte[] assetBytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();Io.copy(in,out);return out.toByteArray();}
    private void openWifi(){startActivity(new Intent(Settings.Panel.ACTION_WIFI));}
    private void stateChanged(){
        if(isFinishing())return;
        if(!s.connected()&&wantsLive){stopLive();if(liveStatus!=null)liveStatus.setText(s.status);}
        if(deviceLabel!=null)deviceLabel.setText(s.connected()?s.deviceName():tab==3?"Устройство":"Прямое подключение без интернета");
        if(connection!=null){connection.setText(s.connected()?"● В сети":s.connecting?"Поиск…":"○ Не в сети");connection.setTextColor(s.connected()?ACCENT:MUTED);connection.setBackground(Ui.panel(this,s.connected()?0xff13342f:0xff162630,16));}
        if(connectButton!=null){Ui.leading(connectButton,!s.connected()?"wifi":tab==3?"wifi":wantsLive?"pause":"play");connectButton.setText(s.connecting?"Подключение…":!s.connected()?"Подключить":tab==3?"Отключиться":wantsLive?"Остановить просмотр":"Начать просмотр");connectButton.setEnabled(!s.connecting);}
        if(idleTitle!=null)idleTitle.setText(s.connected()?"Камера готова":"Дорога в кадре");
        if(idleHint!=null)idleHint.setText(s.connected()?"Нажмите «Начать просмотр»":"Подключите Wi-Fi регистратора");
        if(previewPlaceholder!=null)previewPlaceholder.setVisibility(wantsLive?View.GONE:View.VISIBLE);
        if(sourceBadge!=null)sourceBadge.setText((wantsLive?"● В ЭФИРЕ":"○ КАМЕРА")+(s.rearAvailable()?(camera==0?"  /  ПЕРЕДНЯЯ":"  /  ЗАДНЯЯ"):""));
        if(card!=null)card.setText(s.connected()?(s.cardKnown()?(s.cardPresent()?"Карта памяти установлена":"Без карты · просмотр доступен"):"Получаем состояние карты…"):"Подключите телефон к Wi-Fi регистратора");
        if(transfer!=null){transfer.setText(TransferService.progress);transfer.setVisibility(TransferService.progress.isEmpty()?View.GONE:View.VISIBLE);}
        if(remoteDownload!=null)remoteDownload.setEnabled(s.connected()&&s.cardPresent()&&!remote.isEmpty());
        if(recordButton!=null){JSONObject st=s.state.get("VIDEO_CTRL");recordButton.setText(st!=null&&"1".equals(st.optString("status"))?"Остановить запись":"Записывать на карту");recordButton.setEnabled(s.connected()&&s.cardPresent());}
        if(phoneRecordButton!=null){phoneRecordButton.setText(phoneRecording==null?"Записать":"Сохранить");selectButton(phoneRecordButton,phoneRecording!=null);Ui.leading(phoneRecordButton,"record");}
        for(View action:cardActions)action.setEnabled(s.connected()&&s.cardPresent());
        if(rearSwitch!=null){rearSwitch.setEnabled(s.connected());if(rearSwitch.isChecked()!=s.rearEnabled())rearSwitch.setChecked(s.rearEnabled());}
        if(cameraBar!=null)cameraBar.setVisibility(s.rearAvailable()?View.VISIBLE:View.GONE);
        if(camera==1&&s.connected()&&!s.rearAvailable()){camera=0;quality=getPreferences(0).getInt("quality.0",0);if(wantsLive){stopLive();startLive();}if(tab==1){remote.clear();refreshFiles();}}
        for(SettingDef def:SettingDef.ALL){TextView t=settingViews.get(def.topic());if(t!=null){JSONObject st=s.state.get(def.topic());t.setVisibility(st!=null&&st.has(def.key())&&s.supports(def.topic())&&(!def.topic().equals("DOUBLE_VIDEO")||s.rearAvailable())?View.VISIBLE:View.GONE);t.setText(Ui.settingValue(def.title(),st==null?"Не получено":def.display(st.optString(def.key()))));}}
        if(tab==2&&body!=null&&Library.all(this).length()!=localCount){body.removeAllViews();localPage();}
        if(tab==1&&s.cardKnown()&&!s.cardPresent()&&!remote.isEmpty()){remote.clear();renderRemote();}
    }
    private void connect(){
        String address=getPreferences(0).getString("host","");
        async("Подключение",()->s.connect(address),()->{stateChanged();if(tab==0)startLive();else if(tab==1)refreshFiles();});
    }
    private void livePage(){
        FrameLayout stage=new FrameLayout(this);stage.setBackground(Ui.panel(this,Color.BLACK,22));stage.setClipToOutline(true);
        preview=new AspectPreview(this);surface=preview.surface;stage.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout idle=new LinearLayout(this);idle.setOrientation(LinearLayout.VERTICAL);idle.setGravity(Gravity.CENTER);idle.setPadding(dp(14),dp(10),dp(14),dp(10));idle.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff102632,0xff060b10}));ImageView lens=new ImageView(this);lens.setImageResource(io.zem.ibox.R.drawable.ic_launcher_art);lens.setBackground(Ui.panel(this,0xff06141d,20));lens.setClipToOutline(true);idle.addView(lens,new LinearLayout.LayoutParams(dp(78),dp(78)));TextView welcome=text("Дорога в кадре",17,INK);idleTitle=welcome;welcome.setGravity(Gravity.CENTER);welcome.setSingleLine();welcome.setEllipsize(TextUtils.TruncateAt.END);welcome.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));idle.addView(welcome);idleHint=text("Подключите Wi-Fi регистратора",11,MUTED);idleHint.setGravity(Gravity.CENTER);idleHint.setSingleLine();idleHint.setEllipsize(TextUtils.TruncateAt.END);idle.addView(idleHint);stage.addView(idle,new FrameLayout.LayoutParams(-1,-1));previewPlaceholder=idle;
        sourceBadge=text("○ КАМЕРА",9,INK);sourceBadge.setLetterSpacing(.08f);sourceBadge.setPadding(dp(9),dp(5),dp(9),dp(5));sourceBadge.setBackground(Ui.panel(this,0xb308141c,10));FrameLayout.LayoutParams badgeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);badgeParams.setMargins(dp(12),dp(10),dp(12),0);stage.addView(sourceBadge,badgeParams);
        int videoHeight=Math.max(dp(145),Math.min((getResources().getDisplayMetrics().widthPixels-dp(32))*9/16,(int)(getResources().getDisplayMetrics().heightPixels*.34)));
        body.addView(stage,new LinearLayout.LayoutParams(-1,videoHeight));attachLiveSurface();
        liveStatus=text(s.connected()?"Камера готова к просмотру":"Прямое подключение · интернет не нужен",11,MUTED);liveStatus.setMaxLines(2);body.addView(liveStatus);
        cameraBar=row();cameraButtons(cameraBar,()->{if(s.connected()){stopLive();startLive();}});body.addView(cameraBar);
        LinearLayout primary=row();connectButton=button("Подключить",true);connectButton.setOnClickListener(v->{if(!s.connected())connect();else if(wantsLive){resumeLive=false;stopLive();liveStatus.setText("Просмотр остановлен");}else startLive();stateChanged();});primary.addView(connectButton,new LinearLayout.LayoutParams(0,dp(50),1));
        Button expand=Ui.iconButton(this,"expand","Видео на весь экран");expand.setOnClickListener(v->toggleFullScreen());primary.addView(expand,new LinearLayout.LayoutParams(dp(50),dp(50)));body.addView(primary);
        LinearLayout modes=row();modes.setPadding(dp(4),dp(4),dp(4),dp(4));modes.setBackground(Ui.panel(this,0xff0d1a23,16));String[] names={"Просмотр","На карту","На телефон"};for(int i=0;i<names.length;i++){final int index=i;Button b=button(names[i],false);b.setTag("segment");selectButton(b,controlTab==i);b.setTextSize(11);b.setPadding(dp(2),0,dp(2),0);b.setContentDescription("Управление: "+names[i]);b.setOnClickListener(v->{controlTab=index;for(int j=0;j<modes.getChildCount();j++)selectButton((Button)modes.getChildAt(j),j==index);renderControls();});modes.addView(b,new LinearLayout.LayoutParams(0,dp(42),1));}body.addView(modes);
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(false);scroller.setClipToPadding(false);controlBody=column();controlBody.setPadding(0,dp(4),0,dp(8));scroller.addView(controlBody);body.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));renderControls();
    }
    private void attachLiveSurface(){final SurfaceView attached=surface;surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){if(resumeLive&&visible){resumeLive=false;attached.post(MainActivity.this::startLive);}}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){if(surface==attached){resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();}}});}
    private void renderControls(){
        if(controlBody==null)return;controlBody.removeAllViews();deviceLabel=null;recordButton=null;phoneRecordButton=null;cardActions.clear();
        if(controlTab==0){
            LinearLayout identity=Ui.card(this);TextView eyebrow=Ui.section(this,"Регистратор");eyebrow.setPadding(0,0,0,dp(3));identity.addView(eyebrow);deviceLabel=text(s.connected()?s.deviceName():"Прямое подключение без интернета",14,INK);deviceLabel.setMaxLines(2);identity.addView(deviceLabel);controlBody.addView(identity);
            LinearLayout options=row();audioButton=button(sound?"Звук включён":"Звук выключен",false);Ui.leading(audioButton,"volume");audioButton.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);audioButton.setText(sound?"Звук включён":"Звук выключен");});options.addView(audioButton,new LinearLayout.LayoutParams(0,dp(54),1));Button level=button("Качество",false);Ui.leading(level,"quality");level.setOnClickListener(v->showLiveQuality());options.addView(level,new LinearLayout.LayoutParams(0,dp(54),1));controlBody.addView(options);
            addAction(controlBody,"Выбрать сеть Wi-Fi",this::openWifi);
        }else if(controlTab==1){
            controlBody.addView(Ui.section(this,"Память регистратора"));controlBody.addView(text(s.cardPresent()?"Оригиналы сохраняются на карту памяти":"Установите microSD для записи в регистратор",12,MUTED));recordButton=button("Записывать на карту",true);Ui.leading(recordButton,"record");recordButton.setOnClickListener(v->{if(!requireCard())return;JSONObject st=s.state.get("VIDEO_CTRL");command("VIDEO_CTRL",p("status",st!=null&&"1".equals(st.optString("status"))?"0":"1"),null);});controlBody.addView(recordButton,new LinearLayout.LayoutParams(-1,dp(52)));
            LinearLayout actions=row();Button photo=button("Фото",false);Ui.leading(photo,"photo");photo.setEnabled(s.connected()&&s.cardPresent());photo.setOnClickListener(v->{if(requireCard())command("PHOTO_CTRL",null,()->toast("Фото сохранено на карту"));});cardActions.add(photo);actions.addView(photo,new LinearLayout.LayoutParams(0,dp(52),1));Button protect=button("Защитить",false);Ui.leading(protect,"shield");protect.setEnabled(s.connected()&&s.cardPresent());protect.setOnClickListener(v->{if(requireCard())command("VIDEO_CYC_SAVEFILE",null,()->toast("Запись защищена от перезаписи"));});cardActions.add(protect);actions.addView(protect,new LinearLayout.LayoutParams(0,dp(52),1));controlBody.addView(actions);addAction(controlBody,"Открыть архив",()->navigate(1));
        }else{
            controlBody.addView(Ui.section(this,"Сохранить момент"));controlBody.addView(text("Кадры и видео текущего просмотра — на телефоне",12,MUTED));LinearLayout phone=row();Button snap=button("Кадр",false);Ui.leading(snap,"photo");snap.setOnClickListener(v->snapshot());phone.addView(snap,new LinearLayout.LayoutParams(0,dp(54),1));phoneRecordButton=button(phoneRecording==null?"Записать":"Сохранить",phoneRecording!=null);Ui.leading(phoneRecordButton,"record");phoneRecordButton.setOnClickListener(v->togglePhoneRecording(phoneRecordButton));phone.addView(phoneRecordButton,new LinearLayout.LayoutParams(0,dp(54),1));controlBody.addView(phone);addAction(controlBody,"Файлы на телефоне",()->navigate(2));controlBody.addView(text("Сначала включите просмотр камеры. Файлы появятся в Загрузках.",11,MUTED));
        }
        stateChanged();
    }
    private void selectButton(Button b,boolean selected){Ui.select(b,selected);}
    private void cameraButtons(LinearLayout parent,Runnable changed){
        for(int i=0;i<2;i++){final int cam=i;Button b=button(i==0?"Передняя":"Задняя",camera==i);b.setOnClickListener(v->{if(camera!=cam){camera=cam;quality=getPreferences(0).getInt("quality."+camera,0);for(int k=0;k<Math.min(2,parent.getChildCount());k++){selectButton((Button)parent.getChildAt(k),k==cam);}changed.run();}});parent.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}
    }
    private void showLiveQuality(){
        String[] all={"640 × 480","1280 × 720","1920 × 1080"};List<Integer> levels=new ArrayList<>();List<String> labels=new ArrayList<>();JSONArray supported=s.supportedLevels(camera==0?"forward_support":"behind_support");
        for(int level=0;level<all.length;level++){boolean found=supported==null;for(int j=0;supported!=null&&j<supported.length();j++)found|=supported.optInt(j,-1)==level;if(found){levels.add(level);labels.add(all[level]);}}
        if(levels.isEmpty()){toast("Регистратор не сообщил доступное качество");return;}
        String actual=live==null?"Лайв остановлен":"Сейчас с регистратора: "+live.width()+" × "+live.height();
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);content.addView(text(actual+"\nФактическое разрешение подтверждается потоком. Во время записи выбор может ограничиваться прошивкой регистратора.",13,MUTED));
        RadioGroup choices=new RadioGroup(this);for(int i=0;i<labels.size();i++){RadioButton choice=new RadioButton(this);choice.setText(labels.get(i));choice.setId(i+1);choice.setTextColor(INK);choice.setPadding(0,dp(10),0,dp(10));choices.addView(choice);if(levels.get(i)==quality)choices.check(i+1);}content.addView(choices);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Запрос качества лайва").setView(content).setNegativeButton("Закрыть",null).create();
        choices.setOnCheckedChangeListener((group,id)->{if(id>0){dialog.dismiss();selectLiveQuality(levels.get(id-1));}});dialog.show();
    }
    private void selectLiveQuality(int level){quality=level;getPreferences(0).edit().putInt("quality."+camera,level).apply();stopLive();startLive();}
    private void toggleFullScreen(){
        stopLive();fullScreen=!fullScreen;resumeLive=s.connected();
        if(getWindow().getInsetsController()!=null){if(fullScreen)getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());else getWindow().getInsetsController().show(WindowInsets.Type.systemBars());}
        setRequestedOrientation(fullScreen?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);build();
    }
    private void fullScreenPage(){
        connection=null;card=null;transfer=null;
        preview=new AspectPreview(this);surface=preview.surface;root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));attachLiveSurface();
        liveStatus=text("Просмотр на весь экран",12,MUTED);root.addView(liveStatus);LinearLayout bar=row();
        Button back=button("↙ Назад",false);back.setOnClickListener(v->toggleFullScreen());bar.addView(back,new LinearLayout.LayoutParams(0,dp(48),1));
        Button audio=button(sound?"Звук: вкл":"Звук: выкл",false);audio.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);audio.setText(sound?"Звук: вкл":"Звук: выкл");});bar.addView(audio,new LinearLayout.LayoutParams(0,dp(48),1));
        Button snap=button("Снимок",false);snap.setOnClickListener(v->snapshot());bar.addView(snap,new LinearLayout.LayoutParams(0,dp(48),1));
        if(s.rearAvailable()){Button cam=button("Камера",false);cam.setOnClickListener(v->{camera=1-camera;quality=getPreferences(0).getInt("quality."+camera,0);stopLive();startLive();});bar.addView(cam,new LinearLayout.LayoutParams(0,dp(48),1));}root.addView(bar);
    }
    private void startLive(){
        if(!s.connected()){toast("Сначала подключись к регистратору");return;}
        if(surface==null||!surface.getHolder().getSurface().isValid()){toast("Видеоповерхность ещё не готова");return;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);wantsLive=true;stateChanged();final long generation=++liveGeneration;final Surface target=surface.getHolder().getSurface();final int cam=camera,res=quality;
        s.worker.execute(()->{
            LiveStream old=live;live=null;if(old!=null){old.close();old.awaitStopped();}
            if(generation!=liveGeneration||!wantsLive||tab!=0||!target.isValid())return;
            LiveStream next=new LiveStream(s,target,cam,res,new LiveStream.Listener(){
                public void status(String text){s.main.post(()->{if(generation==liveGeneration&&liveStatus!=null&&tab==0){liveStatus.setText(text);if(live!=null&&preview!=null)preview.videoSize(live.width(),live.height());}});}
                public void frame(FrameAssembler.Frame f){PhoneRecording rec=phoneRecording;if(generation==liveGeneration&&rec!=null)rec.accept(f);}
            });live=next;next.mute(!sound);next.start();
        });
    }
    private void stopLive(){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);liveGeneration++;wantsLive=false;if(previewPlaceholder!=null)previewPlaceholder.setVisibility(View.VISIBLE);LiveStream old=live;live=null;if(old!=null){old.close();s.worker.execute(old::awaitStopped);}stopPhoneRecording();}
    private PhoneRecording phoneRecording;
    private void togglePhoneRecording(Button button){
        if(phoneRecording!=null){stopPhoneRecording();button.setText("Записать");return;}
        if(live==null){toast("Сначала запусти лайв");return;}
        try{phoneRecording=new PhoneRecording(this,live.width(),live.height(),live.sampleRate(),live.sps(),live.pps());button.setText("Сохранить");toast("Запись лайва на телефон начата");}catch(Exception e){fail("Запись лайва",e);}
    }
    private void stopPhoneRecording(){PhoneRecording rec=phoneRecording;phoneRecording=null;if(phoneRecordButton!=null)phoneRecordButton.setText("Записать");if(rec!=null)s.worker.execute(()->{try{rec.finish();s.main.post(()->toast("Лайв сохранён в Download/Zem iBox Drive/Live"));}catch(Exception e){s.main.post(()->fail("Сохранение лайва",e));}});}
    private void snapshot(){
        if(live==null||surface==null){toast("Сначала запусти лайв");return;}
        Bitmap b=Bitmap.createBitmap(Math.max(1,surface.getWidth()),Math.max(1,surface.getHeight()),Bitmap.Config.ARGB_8888);
        PixelCopy.request(surface,b,result->{if(result!=PixelCopy.SUCCESS){b.recycle();fail("Снимок",new IOException("PixelCopy="+result));return;}
            async("Сохранение кадра",()->{File f=new File(getCacheDir(),"snapshot.jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}finally{b.recycle();}String name="Live-"+stamp()+".jpg";Uri uri=Library.publish(this,f,name,"image/jpeg","Live");Library.add(this,uri,name,"image/jpeg",f.length(),"live snapshot");f.delete();},()->toast("Кадр сохранён на телефон"));},s.main);
    }
    private void remotePage(){
        LinearLayout toolbar=row();toolbar.addView(Ui.heading(this,"Архив","Оригиналы на карте регистратора"),new LinearLayout.LayoutParams(0,-2,1));Button refresh=Ui.iconButton(this,"refresh","Обновить записи");refresh.setOnClickListener(v->refreshFiles());toolbar.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(48)));body.addView(toolbar);
        if(s.rearAvailable()){LinearLayout cameras=row();cameraButtons(cameras,()->{remote.clear();refreshFiles();});body.addView(cameras);}
        EditText find=input("Поиск по дате или имени",search);find.setSingleLine();find.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this,"search",18,MUTED),null,null,null);find.setCompoundDrawablePadding(dp(10));find.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);find.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence a,int b,int c,int d){}public void onTextChanged(CharSequence a,int b,int c,int d){search=a.toString();renderRemote();}public void afterTextChanged(Editable e){}});body.addView(find);
        Spinner types=new Spinner(this);android.graphics.drawable.LayerDrawable filterBackground=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{Ui.panel(this,CARD,14),Ui.icon(this,"down",16,MUTED)});filterBackground.setLayerGravity(1,Gravity.END|Gravity.CENTER_VERTICAL);filterBackground.setLayerSize(1,dp(16),dp(16));filterBackground.setLayerInset(1,0,0,dp(14),0);types.setBackground(filterBackground);types.setPadding(dp(12),0,dp(36),0);types.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Все видео","Обычные видео","Закреплённые видео","Фото"}));types.setSelection(filter);types.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){filter=pos;renderRemote();}public void onNothingSelected(AdapterView<?> p){}});body.addView(types,new LinearLayout.LayoutParams(-1,dp(48)));
        body.addView(text("Нажмите для просмотра · удерживайте для действий",11,MUTED));
        FrameLayout files=new FrameLayout(this);fileList=new ListView(this);fileList.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));fileList.setDividerHeight(dp(8));fileList.setClipToPadding(false);fileList.setPadding(0,dp(4),0,dp(4));files.addView(fileList,new FrameLayout.LayoutParams(-1,-1));remoteEmpty=emptyLabel("archive");files.addView(remoteEmpty,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));fileList.setEmptyView(remoteEmpty);body.addView(files,new LinearLayout.LayoutParams(-1,0,1));
        remoteDownload=button("Скачать папку",true);Ui.leading(remoteDownload,"download");remoteDownload.setOnClickListener(v->downloadFolder());body.addView(remoteDownload,new LinearLayout.LayoutParams(-1,dp(50)));renderRemote();
        if(remote.isEmpty()&&s.connected())refreshFiles();
    }
    private TextView emptyLabel(String icon){TextView label=text("",14,MUTED);label.setGravity(Gravity.CENTER);label.setLineSpacing(dp(5),1);label.setPadding(dp(20),dp(12),dp(20),dp(12));label.setCompoundDrawables(null,Ui.icon(this,icon,38,0xff527482),null,null);label.setCompoundDrawablePadding(dp(16));return label;}
    private static boolean inFolder(MediaItem item,int folder){return folder==3?item.photo():!item.photo()&&(folder!=1||!item.locked())&&(folder!=2||item.locked());}
    private List<MediaItem> filtered(){List<MediaItem> a=new ArrayList<>();String q=search.toLowerCase(Locale.ROOT);for(MediaItem i:remote)if((q.isEmpty()||(i.name+" "+i.date()+" "+i.time).toLowerCase(Locale.ROOT).contains(q))&&inFolder(i,filter))a.add(i);a.sort(MediaItem.newestFirst());return a;}
    private void downloadFolder(){
        if(!requireCard())return;final int cam=camera,folder=filter;
        async("Список всей папки",()->{
            List<MediaItem> full=s.files(cam),selected=new ArrayList<>();long total=0;for(MediaItem item:full)if(inFolder(item,folder)){selected.add(item);total+=item.size;}selected.sort(MediaItem.newestFirst());long bytes=total;
            s.main.post(()->{if(selected.isEmpty()){toast("В выбранной папке нет файлов");return;}String title=(cam==0?"Передняя · ":"Задняя · ")+new String[]{"Все видео","Обычные","Закреплённые","Фото"}[folder];new AlertDialog.Builder(this).setTitle(title).setMessage("Файлов: "+selected.size()+" · "+TransferService.bytes(bytes)+"\n\nСкачиваем от новых к старым. Поиск не ограничивает скачивание всей папки. Уже сохранённые оригиналы будут пропущены; частичные загрузки продолжатся. Обычные и закреплённые файлы сохраняются отдельно.").setNegativeButton("Отмена",null).setPositiveButton("Скачать папку",(d,w)->async("Очередь скачивания",()->TransferService.enqueueBatch(this,selected),()->toast("Папка добавлена в очередь"))).show();});
        },null);
    }
    private void renderRemote(){if(tab!=1||fileList==null)return;if(remoteDownload!=null)remoteDownload.setEnabled(s.connected()&&s.cardPresent()&&!remote.isEmpty());List<MediaItem> a=filtered();fileList.setAdapter(new Rows(a.size(),i->{MediaItem f=a.get(i);return (f.locked()?"◆ ":"")+f.date()+"\n"+f.name+" · "+TransferService.bytes(f.size)+(!f.photo()?" · "+f.duration+" с":"");}));fileList.setOnItemClickListener((p,v,pos,id)->openRemote(a.get(pos)));fileList.setOnItemLongClickListener((p,v,pos,id)->{fileActions(a.get(pos));return true;});if(remoteEmpty!=null)remoteEmpty.setText(!s.connected()?"Подключите регистратор, чтобы открыть архив":s.cardKnown()&&!s.cardPresent()?"Карта памяти не установлена\nПросмотр камеры доступен без неё":search.isBlank()&&filter==0?"В архиве пока нет записей\nНажмите ↻, чтобы обновить":"Ничего не найдено\nИзмените поиск или фильтр");}
    private void refreshFiles(){if(!s.connected()){toast("Сначала подключись к регистратору");return;}if(s.cardKnown()&&!s.cardPresent()){remote.clear();renderRemote();toast("Карты памяти нет. Лайв работает без неё");return;}int cam=camera;async("Список записей",()->{List<MediaItem> items=s.files(cam);s.main.post(()->{if(camera==cam){remote=items;renderRemote();if(items.isEmpty())toast("Файлов на карте нет");}});},null);}
    private void fileActions(MediaItem f){String[] choices=BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS?new String[]{"Смотреть","Скачать оригинал",f.locked()?"Снять закрепление":"Закрепить от перезаписи","Удалить с регистратора"}:new String[]{"Смотреть","Скачать оригинал",f.locked()?"Снять закрепление":"Закрепить от перезаписи"};new AlertDialog.Builder(this).setTitle(f.name).setItems(choices,(d,w)->{
        if(w==0)openRemote(f);
        if(w==1)download(f);
        if(w==2)command("FILE_LOCK",p("path",f.path,"status",f.locked()?"0":"1"),this::refreshFiles);
        if(w==3&&BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)confirm("Удалить файл?",f.name+" будет удалён с карты регистратора.",()->command("FILES_DELETE",p("path_0",f.path),this::refreshFiles));
    }).show();}
    private void download(MediaItem f){try{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)){getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);}TransferService.enqueue(this,f);toast("Добавлено в очередь: "+f.name);}catch(Exception e){fail("Скачивание",e);}}
    private void openRemote(MediaItem f){try{startActivity(new Intent(this,PlayerActivity.class).putExtra("uri",s.url(f.path).toString()).putExtra("mime",f.mime()).putExtra("name",f.name));}catch(Exception e){fail("Просмотр",e);}}
    private void localPage(){
        JSONArray items=Library.all(this);localCount=items.length();body.addView(Ui.heading(this,"На телефоне",items.length()==0?"Моменты, которые вы сохранили":"Сохранено файлов: "+items.length()));body.addView(text("Открывайте в любимом плеере. Удерживайте, чтобы поделиться.",12,MUTED));
        FrameLayout area=new FrameLayout(this);ListView list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));list.setDividerHeight(dp(8));list.setClipToPadding(false);list.setPadding(0,dp(4),0,dp(4));area.addView(list,new FrameLayout.LayoutParams(-1,-1));TextView empty=emptyLabel("folder");empty.setText("Здесь будут ваши кадры и видео\nСохраните момент с камеры или скачайте запись из архива");area.addView(empty,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));list.setEmptyView(empty);body.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        list.setAdapter(new Rows(items.length(),i->{JSONObject item=items.optJSONObject(i);return item.optString("name")+"\n"+TransferService.bytes(item.optLong("size"));}));
        list.setOnItemClickListener((p,v,pos,id)->{JSONObject item=items.optJSONObject(pos);openLocal(Uri.parse(item.optString("uri")),item.optString("mime"));});
        list.setOnItemLongClickListener((p,v,pos,id)->{JSONObject item=items.optJSONObject(pos);new AlertDialog.Builder(this).setTitle(item.optString("name")).setItems(new String[]{"Открыть в…","Поделиться"},(d,n)->{Uri uri=Uri.parse(item.optString("uri"));if(n==0)openLocal(uri,item.optString("mime"));else share(uri,item.optString("mime"));}).show();return true;});
        Button folder=button("Открыть Загрузки",false);Ui.leading(folder,"folder");folder.setOnClickListener(v->{try{startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));}catch(Exception e){fail("Загрузки",e);}});body.addView(folder,new LinearLayout.LayoutParams(-1,dp(50)));
    }
    private void settingsPage(){
        settingViews.clear();connectButton=null;rearSwitch=null;deviceLabel=null;card=null;
        body.addView(Ui.heading(this,"Настройки","Всё для спокойной дороги"));
        LinearLayout groups=row();groups.setPadding(dp(4),dp(4),dp(4),dp(4));groups.setBackground(Ui.panel(this,0xff0d1a23,16));String[] labels={"Регистратор","Приложение"};for(int i=0;i<2;i++){final int page=i;Button b=button(labels[i],false);b.setTag("segment");selectButton(b,settingsTab==i);b.setTextSize(12);b.setOnClickListener(v->{settingsTab=page;body.removeAllViews();settingsPage();stateChanged();});groups.addView(b,new LinearLayout.LayoutParams(0,dp(42),1));}body.addView(groups);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);LinearLayout content=column();content.setPadding(0,dp(2),0,dp(12));scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(settingsTab==1){
            LinearLayout app=Ui.card(this);ImageView logo=new ImageView(this);logo.setImageResource(io.zem.ibox.R.drawable.ic_launcher_art);logo.setBackground(Ui.panel(this,0xff06141d,16));logo.setClipToOutline(true);app.addView(logo,new LinearLayout.LayoutParams(dp(64),dp(64)));TextView name=text("Zem iBox",23,INK);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));app.addView(name);app.addView(text("Версия "+BuildConfig.VERSION_NAME,12,MUTED));content.addView(app);
            content.addView(Ui.section(this,"Диагностика"));content.addView(loggingSwitch());content.addView(text("Логи помогают разобраться с подключением. Запись можно выключить в любой момент.",12,MUTED));addAction(content,"Логи и диагностика",this::showLogs);
            content.addView(Ui.section(this,"Хранение"));addAction(content,"Файлы на телефоне",()->navigate(2));content.addView(text("Ваши фото и видео доступны в Загрузках и через приложения телефона.",12,MUTED));
            content.addView(Ui.section(this,"Zem iBox"));addAction(content,"О приложении и лицензии",this::showAbout);return;
        }
        LinearLayout identity=Ui.card(this);TextView eyebrow=Ui.section(this,"Ваше устройство");eyebrow.setPadding(0,0,0,dp(4));identity.addView(eyebrow);deviceLabel=text(s.connected()?s.deviceName():"Регистратор не подключён",17,INK);deviceLabel.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));identity.addView(deviceLabel);card=text("",12,MUTED);identity.addView(card);content.addView(identity);
        connectButton=button(s.connected()?"Отключиться":"Подключить",!s.connected());connectButton.setOnClickListener(v->{if(s.connected()){stopLive();s.disconnect();}else connect();});content.addView(connectButton,new LinearLayout.LayoutParams(-1,dp(48)));addAction(content,"Сеть Wi-Fi",this::openWifi);
        content.addView(Ui.section(this,"Камеры и запись"));Switch rear=new Switch(this);rearSwitch=rear;rear.setText("Задняя камера");Ui.switchStyle(rear);rear.setChecked(s.rearEnabled());rear.setOnCheckedChangeListener((v,on)->{s.setRearEnabled(on);if(!on)camera=0;stateChanged();});content.addView(rear);content.addView(text("Включите, если к регистратору подключена вторая камера.",12,MUTED));
        addAction(content,"Разрешение записи · передняя",()->videoResolution(0));if(s.rearAvailable())addAction(content,"Разрешение записи · задняя",()->videoResolution(1));
        for(SettingDef def:SettingDef.ALL){TextView t=text(def.title(),14,INK);t.setPadding(dp(16),dp(13),dp(16),dp(13));t.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,Ui.icon(this,"chevron",16,MUTED),null);t.setCompoundDrawablePadding(dp(8));t.setBackground(Ui.panel(this,CARD,16));t.setMinHeight(dp(58));content.addView(t,new LinearLayout.LayoutParams(-1,-2));settingViews.put(def.topic(),t);t.setOnClickListener(v->editSetting(def));}
        addAction(content,"Обновить настройки",()->async("Чтение настроек",()->{for(SettingDef def:SettingDef.ALL)try{if(tab!=3||settingsTab!=0)break;CtpClient c=s.ctp;if(c==null)break;c.request(def.topic(),"GET",null,1);}catch(CtpClient.DeviceException e){if(e.code!=6)Diag.error("Read "+def.topic(),e);}catch(Exception e){Diag.error("Read "+def.topic(),e);if(!s.connected())break;}},null));
        content.addView(Ui.section(this,"Подключение и обслуживание"));addAction(content,"Дата и время с телефона",()->command("DATE_TIME",p("date",new SimpleDateFormat("yyyyMMddHHmmss",Locale.ROOT).format(new Date())),()->toast("Дата и время отправлены")));
        addAction(content,"Память и прошивка",()->async("Сведения",()->{try{s.get("TF_CAP");}catch(Exception e){Diag.error("Capacity",e);}s.main.post(()->new AlertDialog.Builder(this).setTitle("Регистратор").setMessage(s.report()).setPositiveButton("Закрыть",null).show());},null));
        addAction(content,"Имя и пароль Wi-Fi",this::changeWifi);addAction(content,"Адрес подключения",this::addressDialog);addAction(content,"Перезагрузить регистратор",()->confirm("Перезагрузить регистратор?","Wi-Fi отключится до завершения перезагрузки.",()->command("RESET",null,()->{stopLive();s.disconnect();})));
        if(BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)addAction(content,"Сбросить настройки регистратора",()->confirm("Сброс настроек","Будут восстановлены заводские настройки регистратора. Имя и пароль Wi-Fi могут измениться.",()->command("SYSTEM_DEFAULT",p("def","1"),null)));
        if(BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)addAction(content,"Форматировать microSD",()->{if(requireCard())confirm("Форматировать карту?","Все записи на microSD, включая закреплённые, будут удалены.",()->command("FORMAT",null,()->{remote.clear();toast("Карта отформатирована");}));});
    }
    private void editSetting(SettingDef def){
        if(!s.connected()){toast("Сначала подключись к регистратору");return;}
        JSONObject state=s.state.get(def.topic());String current=state==null?"":state.optString(def.key());int selection=-1;for(int i=0;i<def.values().length;i++)if(current.equals(def.values()[i]))selection=i;
        new AlertDialog.Builder(this).setTitle(def.title()).setSingleChoiceItems(def.labels(),selection,(d,pos)->{d.dismiss();command(def.topic(),p(def.key(),def.values()[pos]),()->toast("Настройка подтверждена регистратором"));}).setNegativeButton("Отмена",null).show();
    }
    private void videoResolution(int cam){
        String topic=cam==0?"VIDEO_PARAM":"PULL_VIDEO_PARAM";
        new AlertDialog.Builder(this).setTitle("Разрешение записи").setItems(new String[]{"VGA · 640 × 480","HD · 1280 × 720","Full HD · 1920 × 1080"},(d,pos)->{
            int[] w={640,1280,1920},h={480,720,1080};JSONObject current=s.state.get(topic);JSONObject p=current==null?new JSONObject():cloneJson(current);try{p.put("w",""+w[pos]).put("h",""+h[pos]).put("fps",current==null?"30":current.optString("fps","30")).put("rate",current==null?"8000":current.optString("rate","8000")).put("format",current==null?"1":current.optString("format","1"));command(topic,p,null);}catch(Exception e){fail("Разрешение",e);}
        }).show();
    }
    private void addressDialog(){EditText field=input("Пусто = определить автоматически",getPreferences(0).getString("host",""));new AlertDialog.Builder(this).setTitle("IP регистратора").setView(field).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(d,w)->{getPreferences(0).edit().putString("host",field.getText().toString().trim()).apply();toast("Адрес сохранён. Подключись заново");}).show();}
    private void changeWifi(){
        LinearLayout fields=column();EditText ssid=input("Имя Wi-Fi",s.state.get("AP_SSID_INFO")==null?"":s.state.get("AP_SSID_INFO").optString("ssid","")),password=input("Новый пароль, не менее 8 символов","");password.setInputType(129);fields.addView(ssid);fields.addView(password);
        new AlertDialog.Builder(this).setTitle("Wi-Fi регистратора").setMessage("После применения нужно подключиться к новой сети.").setView(fields).setNegativeButton("Отмена",null).setPositiveButton("Применить",(d,w)->{if(password.length()<8||password.length()>63||ssid.length()<1||ssid.getText().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32){toast("Проверь имя сети и длину пароля");return;}command("AP_SSID_INFO",p("ssid",ssid.getText().toString(),"pwd",password.getText().toString(),"status","1"),()->{stopLive();s.disconnect();});}).show();
    }
    private void showLogs(){
        LinearLayout content=column();content.setPadding(dp(12),0,dp(12),0);EditText notes=input("Что произошло (необязательно)",note);content.addView(notes);
        content.addView(loggingSwitch());
        TextView log=text(Diag.tail(),10,MUTED);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);
        copyButton=button("Скопировать полные логи",true);copyButton.setOnClickListener(v->{note=notes.getText().toString();copyLogs();});content.addView(copyButton);
        Button reset=button("Новый снимок логов",false);reset.setOnClickListener(v->{clipboardParts=null;clipboardIndex=0;copyButton.setText("Скопировать полные логи");toast("Следующее копирование соберёт свежий журнал");});content.addView(reset);
        Button clear=button("Очистить логи",false);clear.setOnClickListener(v->clearLogs(log));content.addView(clear);
        TextView info=text("Команды, ответы, Wi-Fi, HTTP, ошибки и видеопоток. Пароли скрыты. Храним около 30 МБ; полный журнал копируется по частям без обрезки.",12,MUTED);content.addView(info);
        ScrollView logScroll=new ScrollView(this);logScroll.addView(log);content.addView(logScroll,new LinearLayout.LayoutParams(-1,dp(220)));
        ScrollView panel=new ScrollView(this);panel.addView(content);new AlertDialog.Builder(this).setTitle("Диагностика Zem iBox").setView(panel).setNegativeButton("Закрыть",null).show();
    }
    private Switch loggingSwitch(){Switch toggle=new Switch(this);toggle.setText("Запись логов");Ui.switchStyle(toggle);toggle.setChecked(Diag.enabled());toggle.setOnCheckedChangeListener((button,checked)->{toggle.setEnabled(false);s.worker.execute(()->{try{Diag.setEnabled(checked);s.main.post(()->{toggle.setEnabled(true);clipboardParts=null;clipboardIndex=0;toast(checked?"Запись логов включена":"Запись логов выключена, сохранённые логи остаются");});}catch(Exception e){s.main.post(()->{toggle.setEnabled(true);fail("Запись логов",e);});}});});return toggle;}
    private void clearLogs(TextView preview){
        async("Очистка логов",Diag::clear,()->{clipboardParts=null;clipboardIndex=0;copyButton.setText("Скопировать полные логи");preview.setText(Diag.tail());android.content.ClipboardManager cb=getSystemService(android.content.ClipboardManager.class);if(cb.hasPrimaryClip()&&cb.getPrimaryClipDescription()!=null&&TextUtils.equals("Zem iBox logs",cb.getPrimaryClipDescription().getLabel()))cb.clearPrimaryClip();toast(Diag.enabled()?"Логи очищены. Новые события продолжают записываться":"Логи очищены. Запись выключена");});
    }
    private void openLocal(Uri uri,String mime){try{startActivity(MediaOpen.chooser(uri,mime));}catch(ActivityNotFoundException e){fail("Открыть файл",new IOException("На телефоне нет приложения для этого формата",e));}}
    private void copyLogs(){
        try {
            if(clipboardParts==null||clipboardIndex>=clipboardParts.size()){clipboardParts=Diag.copyParts(s.report()+"\nUSER NOTE: "+note);clipboardIndex=0;}
            String value="Zem iBox · часть "+(clipboardIndex+1)+"/"+clipboardParts.size()+"\n"+clipboardParts.get(clipboardIndex);
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Zem iBox logs",value));clipboardIndex++;
            copyButton.setText(clipboardIndex<clipboardParts.size()?"Скопировать часть "+(clipboardIndex+1)+"/"+clipboardParts.size():"Скопировано всё · собрать свежие");toast("Скопирована часть "+clipboardIndex+"/"+clipboardParts.size());
        }catch(Exception e){fail("Копирование логов",e);}
    }
    private void command(String topic,JSONObject p,Runnable after){async(topic,()->s.put(topic,p),()->{stateChanged();if(after!=null)after.run();});}
    private boolean requireCard(){if(!s.connected()){toast("Сначала подключись к регистратору");return false;}if(s.cardKnown()&&!s.cardPresent()){toast("Для этой функции нужна microSD. Лайв и кадр на телефон работают без неё");return false;}return true;}
    private void async(String name,Work work,Runnable after){s.worker.execute(()->{try{work.run();if(after!=null)s.main.post(after);}catch(Exception e){s.main.post(()->fail(name,e));}});}
    private void fail(String context,Throwable e){Diag.error(context,e);new AlertDialog.Builder(this).setTitle(context).setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("Понятно",null).setNeutralButton("Логи",(d,w)->showLogs()).show();}
    private void confirm(String title,String message,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Подтвердить",(d,w)->action.run()).show();}
    private void share(Uri uri,String mime){try{Intent send=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Zem iBox",uri));startActivity(Intent.createChooser(send,"Поделиться записью"));}catch(Exception e){fail("Отправка",e);}}
    private static JSONObject p(String...pairs){try{return JieliSession.params(pairs);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    private static JSONObject cloneJson(JSONObject v){try{return new JSONObject(v.toString());}catch(Exception e){return new JSONObject();}}
    private String stamp(){return new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date());}
    private void addAction(LinearLayout layout,String title,Runnable action){Button b=button(title.replace("  ›","").replace("  ›",""),false);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setTextSize(13);b.setPadding(dp(14),dp(8),dp(14),dp(8));String icon=title.contains("Wi-Fi")||title.contains("Адрес")?"wifi":title.contains("архив")?"archive":title.contains("телефоне")||title.contains("Загрузки")?"folder":title.contains("Память")?"sd":title.contains("Дата")?"clock":title.contains("Обновить")||title.contains("Перезагрузить")?"refresh":title.contains("Логи")||title.contains("приложении")?"info":"settings";b.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this,icon,19,ACCENT),null,Ui.icon(this,"chevron",15,MUTED),null);b.setCompoundDrawablePadding(dp(12));b.setOnClickListener(v->action.run());layout.addView(b,new LinearLayout.LayoutParams(-1,dp(54)));}
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private LinearLayout row(){LinearLayout l=spaced(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout column(){return spaced(LinearLayout.VERTICAL);}
    private LinearLayout spaced(int orientation){LinearLayout l=new LinearLayout(this);l.setOrientation(orientation);GradientDrawable gap=new GradientDrawable();gap.setColor(Color.TRANSPARENT);gap.setSize(dp(8),dp(8));l.setDividerDrawable(gap);l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);return l;}
    private TextView text(String value,int size,int color){return Ui.text(this,value,size,color);}
    private EditText input(String hint,String value){return Ui.input(this,hint,value);}
    private Button button(String label,boolean selected){return Ui.button(this,label,selected);}
    private GradientDrawable shape(int color,int radius){return Ui.panel(this,color,radius);}
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();}
    private interface RowText {String get(int i);}
    private final class Rows extends BaseAdapter {
        private final int count;private final RowText text;Rows(int n,RowText t){count=n;text=t;}public int getCount(){return count;}public Object getItem(int p){return p;}public long getItemId(int p){return p;}
        public View getView(int p,View recycled,ViewGroup parent){MediaRow row=recycled instanceof MediaRow?(MediaRow)recycled:new MediaRow();String value=text.get(p);int split=value.indexOf('\n');row.title.setText(split<0?value:value.substring(0,split));row.detail.setText(split<0?"":value.substring(split+1));boolean photo=value.toLowerCase(Locale.ROOT).contains(".jpg")||value.toLowerCase(Locale.ROOT).contains(".jpeg");row.glyph.setImageDrawable(Ui.icon(MainActivity.this,photo?"photo":value.startsWith("◆")?"shield":"play",22,ACCENT));return row;}
    }
    private final class MediaRow extends LinearLayout {
        final TextView title,detail;final ImageView glyph;
        MediaRow(){super(MainActivity.this);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(12),dp(13),dp(12),dp(13));setBackground(Ui.panel(MainActivity.this,CARD,17));glyph=new ImageView(MainActivity.this);glyph.setPadding(dp(10),dp(10),dp(10),dp(10));glyph.setBackground(Ui.panel(MainActivity.this,0xff1c3540,13));addView(glyph,new LinearLayout.LayoutParams(dp(44),dp(48)));LinearLayout labels=new LinearLayout(MainActivity.this);labels.setOrientation(VERTICAL);title=text("",14,INK);title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));detail=text("",11,MUTED);detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);labels.addView(detail);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(12),0,dp(8),0);addView(labels,lp);ImageView more=new ImageView(MainActivity.this);more.setImageDrawable(Ui.icon(MainActivity.this,"chevron",16,MUTED));addView(more,new LinearLayout.LayoutParams(dp(16),dp(24)));}
    }
    private void goBack(){if(fullScreen)toggleFullScreen();else if(tab!=0)navigate(0);else finish();}
    // API 33+ uses the OnBackInvokedCallback registered in onCreate; this override serves API 30–32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    protected void onResume(){super.onResume();visible=true;if(resumeLive&&tab==0&&s.connected()&&surface!=null&&surface.getHolder().getSurface().isValid()){resumeLive=false;surface.post(this::startLive);}}
    protected void onPause(){visible=false;resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();super.onPause();}
    protected void onDestroy(){s.unlisten(observer);stopLive();super.onDestroy();}
    protected void onSaveInstanceState(Bundle b){b.putInt("tab",tab);b.putInt("camera",camera);b.putInt("quality",quality);super.onSaveInstanceState(b);}
}
