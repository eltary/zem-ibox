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
    static final int BG=0xff10161c,CARD=0xff1b252d,INK=0xffedf5f5,MUTED=0xff9baeb8,ACCENT=0xff55e2bb;
    private JieliSession s;
    private LinearLayout root,body,controlBody,cameraBar;
    private int controlTab;
    private Button audioButton,phoneRecordButton;
    private Switch rearSwitch;
    private final List<View> cardActions=new ArrayList<>();
    private boolean visible;
    private TextView connection,card,transfer,liveStatus,deviceLabel;
    private Button connectButton,copyButton,recordButton;
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
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(12),dp(16),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());root.setPadding(dp(12)+bars.left,dp(6)+bars.top,dp(12)+bars.right,bars.bottom);return insets;});
        setContentView(root);build();s.listen(observer);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
        if(BuildConfig.DEBUG&&getIntent().hasExtra("test_host"))s.worker.execute(()->{try{s.connect(getIntent().getStringExtra("test_host"));}catch(Exception e){Diag.error("Test connect",e);}});
    }
    private void build(){
        root.removeAllViews();deviceLabel=null;settingViews.clear();recordButton=null;connectButton=null;audioButton=null;phoneRecordButton=null;cameraBar=null;controlBody=null;fileList=null;remoteEmpty=null;rearSwitch=null;cardActions.clear();
        if(fullScreen){fullScreenPage();return;}
        LinearLayout title=row();TextView brand=text("Zem iBox",21,INK);brand.setTypeface(null,Typeface.BOLD);title.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));
        connection=text("",12,MUTED);title.addView(connection);Button menu=button("⋮",false);menu.setContentDescription("Меню приложения");menu.setTextSize(22);menu.setOnClickListener(v->appMenu(menu));title.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(44)));root.addView(title);
        card=text("",12,MUTED);card.setVisibility(View.GONE);
        transfer=text("",12,ACCENT);transfer.setOnClickListener(v->{if(TransferService.active)new AlertDialog.Builder(this).setMessage("Остановить скачивание? Частичная копия сохранится для продолжения.").setNegativeButton("Продолжить",null).setPositiveButton("Остановить",(d,w)->TransferService.cancel=true).show();});root.addView(transfer);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout navigation=row();String[] labels={"Камера","Архив","Телефон","Настройки"};
        for(int i=0;i<labels.length;i++){final int page=i;Button b=button(labels[i],tab==i);b.setContentDescription("Вкладка: "+labels[i]);b.setTextSize(12);b.setMaxLines(1);b.setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);b.setPadding(dp(2),0,dp(2),0);b.setSelected(tab==i);b.setOnClickListener(v->navigate(page));navigation.addView(b,new LinearLayout.LayoutParams(0,dp(54),1));}root.addView(navigation);
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
        if(connection!=null){connection.setText(s.connected()?"● Подключён":s.connecting?"Подключение…":"Не подключён");connection.setTextColor(s.connected()?ACCENT:MUTED);}
        if(connectButton!=null){connectButton.setText(s.connecting?"Подключение…":!s.connected()?"Подключить регистратор":tab==3?"Отключиться":wantsLive?"Ⅱ Остановить просмотр":"▶ Смотреть");connectButton.setEnabled(!s.connecting);}
        if(card!=null)card.setText(s.connected()?(s.cardKnown()?(s.cardPresent()?"Карта памяти установлена":"Без карты · просмотр доступен"):"Получаем состояние карты…"):"Подключите телефон к Wi-Fi регистратора");
        if(transfer!=null){transfer.setText(TransferService.progress);transfer.setVisibility(TransferService.progress.isEmpty()?View.GONE:View.VISIBLE);}
        if(recordButton!=null){JSONObject st=s.state.get("VIDEO_CTRL");recordButton.setText(st!=null&&"1".equals(st.optString("status"))?"■ Остановить запись на карту":"● Записывать на карту");recordButton.setEnabled(s.connected()&&s.cardPresent());}
        if(phoneRecordButton!=null){phoneRecordButton.setText(phoneRecording==null?"● Записать видео":"■ Сохранить видео");selectButton(phoneRecordButton,phoneRecording!=null);}
        for(View action:cardActions)action.setEnabled(s.connected()&&s.cardPresent());
        if(rearSwitch!=null){rearSwitch.setEnabled(s.connected());if(rearSwitch.isChecked()!=s.rearEnabled())rearSwitch.setChecked(s.rearEnabled());}
        if(cameraBar!=null)cameraBar.setVisibility(s.rearAvailable()?View.VISIBLE:View.GONE);
        if(camera==1&&s.connected()&&!s.rearAvailable()){camera=0;quality=getPreferences(0).getInt("quality.0",0);if(wantsLive){stopLive();startLive();}if(tab==1){remote.clear();refreshFiles();}}
        for(SettingDef def:SettingDef.ALL){TextView t=settingViews.get(def.topic());if(t!=null){JSONObject st=s.state.get(def.topic());t.setVisibility(st!=null&&st.has(def.key())&&s.supports(def.topic())&&(!def.topic().equals("DOUBLE_VIDEO")||s.rearAvailable())?View.VISIBLE:View.GONE);t.setText(def.title()+"\n"+(st==null?"Не получено":def.display(st.optString(def.key()))));}}
        if(tab==2&&body!=null&&Library.all(this).length()!=localCount){body.removeAllViews();localPage();}
        if(tab==1&&s.cardKnown()&&!s.cardPresent()&&!remote.isEmpty()){remote.clear();renderRemote();}
    }
    private void connect(){
        String address=getPreferences(0).getString("host","");
        async("Подключение",()->s.connect(address),()->{stateChanged();if(tab==0)startLive();else if(tab==1)refreshFiles();});
    }
    private void livePage(){
        FrameLayout stage=new FrameLayout(this);stage.setBackground(shape(Color.BLACK,18));stage.setClipToOutline(true);
        preview=new AspectPreview(this);surface=preview.surface;stage.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        int videoHeight=Math.max(dp(145),Math.min((getResources().getDisplayMetrics().widthPixels-dp(24))*9/16,(int)(getResources().getDisplayMetrics().heightPixels*.34)));
        body.addView(stage,new LinearLayout.LayoutParams(-1,videoHeight));attachLiveSurface();
        liveStatus=text(s.connected()?"Нажмите «Смотреть»":"Подключите телефон к Wi-Fi регистратора",12,MUTED);liveStatus.setMaxLines(2);body.addView(liveStatus);
        cameraBar=row();cameraButtons(cameraBar,()->{if(s.connected()){stopLive();startLive();}});body.addView(cameraBar);
        LinearLayout primary=row();connectButton=button("Подключить регистратор",true);connectButton.setOnClickListener(v->{if(!s.connected())connect();else if(wantsLive){resumeLive=false;stopLive();liveStatus.setText("Просмотр остановлен");}else startLive();stateChanged();});primary.addView(connectButton,new LinearLayout.LayoutParams(0,dp(50),1));
        Button expand=button("⛶",false);expand.setContentDescription("Видео на весь экран");expand.setTextSize(21);expand.setOnClickListener(v->toggleFullScreen());primary.addView(expand,new LinearLayout.LayoutParams(dp(52),dp(50)));body.addView(primary);
        LinearLayout modes=row();String[] names={"Просмотр","На карту","На телефон"};for(int i=0;i<names.length;i++){final int index=i;Button b=button(names[i],controlTab==i);b.setTextSize(12);b.setPadding(0,0,0,0);b.setContentDescription("Управление: "+names[i]);b.setOnClickListener(v->{controlTab=index;for(int j=0;j<modes.getChildCount();j++)selectButton((Button)modes.getChildAt(j),j==index);renderControls();});modes.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));}body.addView(modes);
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(false);controlBody=column();scroller.addView(controlBody);body.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));renderControls();
    }
    private void attachLiveSurface(){final SurfaceView attached=surface;surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){if(resumeLive&&visible){resumeLive=false;attached.post(MainActivity.this::startLive);}}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){if(surface==attached){resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();}}});}
    private void renderControls(){
        if(controlBody==null)return;controlBody.removeAllViews();deviceLabel=null;recordButton=null;phoneRecordButton=null;cardActions.clear();
        if(controlTab==0){
            deviceLabel=text(s.connected()?s.deviceName():"Прямое подключение без интернета",15,INK);controlBody.addView(deviceLabel);
            LinearLayout options=row();audioButton=button(sound?"Звук включён":"Звук выключен",false);audioButton.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);audioButton.setText(sound?"Звук включён":"Звук выключен");});options.addView(audioButton,new LinearLayout.LayoutParams(0,dp(48),1));Button level=button("Качество видео",false);level.setOnClickListener(v->showLiveQuality());options.addView(level,new LinearLayout.LayoutParams(0,dp(48),1));controlBody.addView(options);
            addAction(controlBody,"Выбрать Wi-Fi регистратора  ›",this::openWifi);
            controlBody.addView(text("Интернет не нужен. Запись на карту и сохранение на телефон находятся в соседних вкладках.",12,MUTED));
        }else if(controlTab==1){
            controlBody.addView(text(s.cardPresent()?"Запись сохраняется в регистраторе":"Для этих действий нужна карта памяти",14,MUTED));recordButton=button("● Записывать на карту",true);recordButton.setOnClickListener(v->{if(!requireCard())return;JSONObject st=s.state.get("VIDEO_CTRL");command("VIDEO_CTRL",p("status",st!=null&&"1".equals(st.optString("status"))?"0":"1"),null);});controlBody.addView(recordButton,new LinearLayout.LayoutParams(-1,dp(50)));
            LinearLayout actions=row();Button photo=button("Сделать фото",false);photo.setEnabled(s.connected()&&s.cardPresent());photo.setOnClickListener(v->{if(requireCard())command("PHOTO_CTRL",null,()->toast("Фото сохранено на карту"));});cardActions.add(photo);actions.addView(photo,new LinearLayout.LayoutParams(0,dp(48),1));Button protect=button("Защитить запись",false);protect.setEnabled(s.connected()&&s.cardPresent());protect.setOnClickListener(v->{if(requireCard())command("VIDEO_CYC_SAVEFILE",null,()->toast("Запись защищена от перезаписи"));});cardActions.add(protect);actions.addView(protect,new LinearLayout.LayoutParams(0,dp(48),1));controlBody.addView(actions);addAction(controlBody,"Открыть архив  ›",()->navigate(1));
        }else{
            controlBody.addView(text("Сохранение текущего просмотра на телефон",14,MUTED));LinearLayout phone=row();Button snap=button("Сохранить кадр",false);snap.setOnClickListener(v->snapshot());phone.addView(snap,new LinearLayout.LayoutParams(0,dp(50),1));phoneRecordButton=button(phoneRecording==null?"● Записать видео":"■ Сохранить видео",phoneRecording!=null);phoneRecordButton.setOnClickListener(v->togglePhoneRecording(phoneRecordButton));phone.addView(phoneRecordButton,new LinearLayout.LayoutParams(0,dp(50),1));controlBody.addView(phone);addAction(controlBody,"Мои файлы на телефоне  ›",()->navigate(2));controlBody.addView(text("Файлы сохраняются в Загрузках. Для снимка и видео сначала включите просмотр.",12,MUTED));
        }
        stateChanged();
    }
    private void selectButton(Button b,boolean selected){b.setTextColor(selected?BG:INK);b.setBackground(shape(selected?ACCENT:CARD,12));b.setSelected(selected);}
    private void cameraButtons(LinearLayout parent,Runnable changed){
        for(int i=0;i<2;i++){final int cam=i;Button b=button(i==0?"Передняя":"Задняя",camera==i);b.setOnClickListener(v->{if(camera!=cam){camera=cam;quality=getPreferences(0).getInt("quality."+camera,0);for(int k=0;k<Math.min(2,parent.getChildCount());k++){parent.getChildAt(k).setBackground(shape(k==cam?ACCENT:CARD,12));((Button)parent.getChildAt(k)).setTextColor(k==cam?BG:INK);}changed.run();}});parent.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}
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
    private void stopLive(){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);liveGeneration++;wantsLive=false;LiveStream old=live;live=null;if(old!=null){old.close();s.worker.execute(old::awaitStopped);}stopPhoneRecording();}
    private PhoneRecording phoneRecording;
    private void togglePhoneRecording(Button button){
        if(phoneRecording!=null){stopPhoneRecording();button.setText("● Записать видео");return;}
        if(live==null){toast("Сначала запусти лайв");return;}
        try{phoneRecording=new PhoneRecording(this,live.width(),live.height(),live.sampleRate(),live.sps(),live.pps());button.setText("■ Сохранить видео");toast("Запись лайва на телефон начата");}catch(Exception e){fail("Запись лайва",e);}
    }
    private void stopPhoneRecording(){PhoneRecording rec=phoneRecording;phoneRecording=null;if(phoneRecordButton!=null)phoneRecordButton.setText("● Записать видео");if(rec!=null)s.worker.execute(()->{try{rec.finish();s.main.post(()->toast("Лайв сохранён в Download/Zem iBox Drive/Live"));}catch(Exception e){s.main.post(()->fail("Сохранение лайва",e));}});}
    private void snapshot(){
        if(live==null||surface==null){toast("Сначала запусти лайв");return;}
        Bitmap b=Bitmap.createBitmap(Math.max(1,surface.getWidth()),Math.max(1,surface.getHeight()),Bitmap.Config.ARGB_8888);
        PixelCopy.request(surface,b,result->{if(result!=PixelCopy.SUCCESS){b.recycle();fail("Снимок",new IOException("PixelCopy="+result));return;}
            async("Сохранение кадра",()->{File f=new File(getCacheDir(),"snapshot.jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}finally{b.recycle();}String name="Live-"+stamp()+".jpg";Uri uri=Library.publish(this,f,name,"image/jpeg","Live");Library.add(this,uri,name,"image/jpeg",f.length(),"live snapshot");f.delete();},()->toast("Кадр сохранён на телефон"));},s.main);
    }
    private void remotePage(){
        LinearLayout toolbar=row();if(s.rearAvailable())cameraButtons(toolbar,()->{remote.clear();refreshFiles();});else toolbar.addView(text("Архив регистратора",19,INK),new LinearLayout.LayoutParams(0,dp(48),1));Button refresh=button("↻",false);refresh.setContentDescription("Обновить записи");refresh.setOnClickListener(v->refreshFiles());toolbar.addView(refresh);body.addView(toolbar);
        EditText find=input("Поиск по дате или имени",search);find.setSingleLine();find.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence a,int b,int c,int d){}public void onTextChanged(CharSequence a,int b,int c,int d){search=a.toString();renderRemote();}public void afterTextChanged(Editable e){}});body.addView(find);
        Spinner types=new Spinner(this);types.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Все видео","Обычные видео","Закреплённые видео","Фото"}));types.setSelection(filter);types.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){filter=pos;renderRemote();}public void onNothingSelected(AdapterView<?> p){}});body.addView(types);
        body.addView(text("Нажмите — смотреть. Удерживайте — скачать или защитить.",12,MUTED));
        FrameLayout files=new FrameLayout(this);fileList=new ListView(this);fileList.setDividerHeight(dp(8));files.addView(fileList,new FrameLayout.LayoutParams(-1,-1));remoteEmpty=text("",15,MUTED);remoteEmpty.setGravity(Gravity.CENTER);files.addView(remoteEmpty,new FrameLayout.LayoutParams(-1,-1));fileList.setEmptyView(remoteEmpty);body.addView(files,new LinearLayout.LayoutParams(-1,0,1));renderRemote();
        Button all=button("Скачать выбранную папку",true);all.setOnClickListener(v->downloadFolder());body.addView(all);
        if(remote.isEmpty()&&s.connected())refreshFiles();
    }
    private static boolean inFolder(MediaItem item,int folder){return folder==3?item.photo():!item.photo()&&(folder!=1||!item.locked())&&(folder!=2||item.locked());}
    private List<MediaItem> filtered(){List<MediaItem> a=new ArrayList<>();String q=search.toLowerCase(Locale.ROOT);for(MediaItem i:remote)if((q.isEmpty()||(i.name+" "+i.date()+" "+i.time).toLowerCase(Locale.ROOT).contains(q))&&inFolder(i,filter))a.add(i);a.sort(MediaItem.newestFirst());return a;}
    private void downloadFolder(){
        if(!requireCard())return;final int cam=camera,folder=filter;
        async("Список всей папки",()->{
            List<MediaItem> full=s.files(cam),selected=new ArrayList<>();long total=0;for(MediaItem item:full)if(inFolder(item,folder)){selected.add(item);total+=item.size;}selected.sort(MediaItem.newestFirst());long bytes=total;
            s.main.post(()->{if(selected.isEmpty()){toast("В выбранной папке нет файлов");return;}String title=(cam==0?"Передняя · ":"Задняя · ")+new String[]{"Все видео","Обычные","Закреплённые","Фото"}[folder];new AlertDialog.Builder(this).setTitle(title).setMessage("Файлов: "+selected.size()+" · "+TransferService.bytes(bytes)+"\n\nСкачиваем от новых к старым. Поиск не ограничивает скачивание всей папки. Уже сохранённые оригиналы будут пропущены; частичные загрузки продолжатся. Обычные и закреплённые файлы сохраняются отдельно.").setNegativeButton("Отмена",null).setPositiveButton("Скачать папку",(d,w)->async("Очередь скачивания",()->TransferService.enqueueBatch(this,selected),()->toast("Папка добавлена в очередь"))).show();});
        },null);
    }
    private void renderRemote(){if(tab!=1||fileList==null)return;List<MediaItem> a=filtered();fileList.setAdapter(new Rows(a.size(),i->{MediaItem f=a.get(i);return (f.locked()?"◆ ":"")+f.date()+"\n"+f.name+" · "+TransferService.bytes(f.size)+(!f.photo()?" · "+f.duration+" с":"");}));fileList.setOnItemClickListener((p,v,pos,id)->openRemote(a.get(pos)));fileList.setOnItemLongClickListener((p,v,pos,id)->{fileActions(a.get(pos));return true;});if(remoteEmpty!=null)remoteEmpty.setText(!s.connected()?"Подключите регистратор, чтобы открыть архив":s.cardKnown()&&!s.cardPresent()?"Карта памяти не установлена\nПросмотр камеры доступен без неё":search.isBlank()&&filter==0?"В архиве пока нет записей\nНажмите ↻, чтобы обновить":"Ничего не найдено\nИзмените поиск или фильтр");}
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
        JSONArray items=Library.all(this);localCount=items.length();body.addView(text("На телефоне",21,INK));body.addView(text("Нажмите файл — выбрать плеер. Удерживайте — поделиться.",12,MUTED));
        FrameLayout area=new FrameLayout(this);ListView list=new ListView(this);list.setDividerHeight(dp(8));area.addView(list,new FrameLayout.LayoutParams(-1,-1));TextView empty=text("Сохранённых файлов пока нет\nСкачайте запись из архива или сохраните видео с камеры",15,MUTED);empty.setGravity(Gravity.CENTER);area.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);body.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        list.setAdapter(new Rows(items.length(),i->{JSONObject item=items.optJSONObject(i);return item.optString("name")+"\n"+TransferService.bytes(item.optLong("size"));}));
        list.setOnItemClickListener((p,v,pos,id)->{JSONObject item=items.optJSONObject(pos);openLocal(Uri.parse(item.optString("uri")),item.optString("mime"));});
        list.setOnItemLongClickListener((p,v,pos,id)->{JSONObject item=items.optJSONObject(pos);new AlertDialog.Builder(this).setTitle(item.optString("name")).setItems(new String[]{"Открыть в…","Поделиться"},(d,n)->{Uri uri=Uri.parse(item.optString("uri"));if(n==0)openLocal(uri,item.optString("mime"));else share(uri,item.optString("mime"));}).show();return true;});
        Button folder=button("Открыть Загрузки",false);folder.setOnClickListener(v->{try{startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));}catch(Exception e){fail("Загрузки",e);}});body.addView(folder);
    }
    private void settingsPage(){
        settingViews.clear();ScrollView scroll=new ScrollView(this);LinearLayout content=column();scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        deviceLabel=text(s.connected()?s.deviceName():"Устройство",20,INK);content.addView(deviceLabel);card.setVisibility(View.VISIBLE);content.addView(card);
        connectButton=button(s.connected()?"Отключиться":"Подключить регистратор",true);connectButton.setOnClickListener(v->{if(s.connected()){stopLive();s.disconnect();}else connect();});content.addView(connectButton,new LinearLayout.LayoutParams(-1,dp(48)));
        addAction(content,"Wi-Fi и подключение  ›",this::openWifi);
        Switch rear=new Switch(this);rearSwitch=rear;rear.setText("Показывать заднюю камеру");rear.setTextColor(INK);rear.setMinHeight(dp(52));rear.setChecked(s.rearEnabled());rear.setOnCheckedChangeListener((v,on)->{s.setRearEnabled(on);if(!on)camera=0;stateChanged();});content.addView(rear);
        content.addView(text("Включайте, только если вторая камера действительно подключена. Режимы качества в описании прошивки не означают её наличие.",12,MUTED));
        content.addView(text("Настройки, полученные от регистратора",17,INK));
        content.addView(loggingSwitch());
        addAction(content,"Обновить значения",()->async("Чтение настроек",()->{for(SettingDef def:SettingDef.ALL)try{if(tab!=3)break;CtpClient c=s.ctp;if(c==null)break;c.request(def.topic(),"GET",null,1);}catch(CtpClient.DeviceException e){if(e.code!=6)Diag.error("Read "+def.topic(),e);}catch(Exception e){Diag.error("Read "+def.topic(),e);if(!s.connected())break;}},null));
        addAction(content,"Разрешение передней камеры",()->videoResolution(0));if(s.rearAvailable())addAction(content,"Разрешение задней камеры",()->videoResolution(1));
        for(SettingDef def:SettingDef.ALL){TextView t=text(def.title(),15,INK);t.setPadding(dp(12),dp(13),dp(12),dp(13));t.setBackground(shape(CARD,12));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,0);content.addView(t,lp);settingViews.put(def.topic(),t);t.setOnClickListener(v->editSetting(def));}
        addAction(content,"Синхронизировать дату и время с телефоном",()->command("DATE_TIME",p("date",new SimpleDateFormat("yyyyMMddHHmmss",Locale.ROOT).format(new Date())),()->toast("Дата и время отправлены")));
        addAction(content,"Память и прошивка",()->async("Сведения",()->{try{s.get("TF_CAP");}catch(Exception e){Diag.error("Capacity",e);}s.main.post(()->new AlertDialog.Builder(this).setTitle("Регистратор").setMessage(s.report()).setPositiveButton("Закрыть",null).show());},null));
        addAction(content,"Имя и пароль Wi-Fi регистратора",this::changeWifi);
        addAction(content,"Адрес подключения",this::addressDialog);
        addAction(content,"Перезагрузить регистратор",()->confirm("Перезагрузить регистратор?","Wi-Fi отключится до завершения перезагрузки.",()->command("RESET",null,()->{stopLive();s.disconnect();})));
        if(BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)addAction(content,"Сбросить настройки регистратора",()->confirm("Сброс настроек","Будут восстановлены заводские настройки регистратора. Имя и пароль Wi-Fi могут измениться.",()->command("SYSTEM_DEFAULT",p("def","1"),null)));
        if(BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)addAction(content,"Форматировать microSD",()->{if(requireCard())confirm("Форматировать карту?","Все записи на microSD, включая закреплённые, будут удалены.",()->command("FORMAT",null,()->{remote.clear();toast("Карта отформатирована");}));});
        addAction(content,"Логи и диагностика",this::showLogs);
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
    private Switch loggingSwitch(){Switch toggle=new Switch(this);toggle.setText("Запись логов");toggle.setTextColor(INK);toggle.setTextSize(15);toggle.setMinHeight(dp(48));toggle.setChecked(Diag.enabled());toggle.setOnCheckedChangeListener((button,checked)->{toggle.setEnabled(false);s.worker.execute(()->{try{Diag.setEnabled(checked);s.main.post(()->{toggle.setEnabled(true);clipboardParts=null;clipboardIndex=0;toast(checked?"Запись логов включена":"Запись логов выключена, сохранённые логи остаются");});}catch(Exception e){s.main.post(()->{toggle.setEnabled(true);fail("Запись логов",e);});}});});return toggle;}
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
    private void addAction(LinearLayout layout,String title,Runnable action){Button b=button(title,false);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setPadding(dp(12),dp(5),dp(12),dp(5));b.setOnClickListener(v->action.run());layout.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));}
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private LinearLayout row(){LinearLayout l=spaced(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout column(){return spaced(LinearLayout.VERTICAL);}
    private LinearLayout spaced(int orientation){LinearLayout l=new LinearLayout(this);l.setOrientation(orientation);GradientDrawable gap=new GradientDrawable();gap.setColor(Color.TRANSPARENT);gap.setSize(dp(8),dp(8));l.setDividerDrawable(gap);l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);return l;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(0,dp(4),0,dp(4));return t;}
    private EditText input(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setTextSize(14);e.setTextColor(INK);e.setHintTextColor(MUTED);return e;}
    private Button button(String label,boolean selected){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);b.setMaxLines(2);b.setEllipsize(TextUtils.TruncateAt.END);b.setTextColor(selected?BG:INK);b.setBackground(shape(selected?ACCENT:CARD,12));b.setPadding(dp(10),dp(3),dp(10),dp(3));android.animation.StateListAnimator press=new android.animation.StateListAnimator();press.addState(new int[]{android.R.attr.state_pressed},android.animation.ObjectAnimator.ofFloat(b,"alpha",.72f).setDuration(80));press.addState(new int[]{},android.animation.ObjectAnimator.ofFloat(b,"alpha",1f).setDuration(80));b.setStateListAnimator(press);return b;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();}
    private interface RowText {String get(int i);}
    private final class Rows extends BaseAdapter {private final int count;private final RowText text;Rows(int n,RowText t){count=n;text=t;}public int getCount(){return count;}public Object getItem(int p){return p;}public long getItemId(int p){return p;}public View getView(int p,View recycled,android.view.ViewGroup parent){TextView t=recycled instanceof TextView?(TextView)recycled:MainActivity.this.text("",14,INK);t.setPadding(dp(12),dp(12),dp(12),dp(12));t.setBackground(shape(CARD,12));t.setText(text.get(p));return t;}}
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
