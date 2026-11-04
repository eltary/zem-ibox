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
    private LinearLayout root,body,controlBody,cameraBar,cameraContent,remoteEmptyPanel,savedBar,transferBar;
    private int controlTab,settingsTab;
    private Button formatButton,formatConfirm,formatCancel;private TextView formatStatus;
    private Button qualityButton,audioButton,liveAudioButton,phoneRecordButton,snapshotButton,remoteRetry,remoteRefresh;
    private TextView recordingStatus,savedHint,remoteNotice;
    private Button transferAction;
    private EditText remoteSearch;private Spinner remoteTypes;
    private boolean connectionPending,remoteLoading,phoneSaving;
    private String savedMessage="",remoteError="";
    private long remoteRequest,phoneStarted;
    private CtpClient archiveSession;
    private static final int WIFI_PICK=61;
    private Switch rearSwitch;
    private final List<View> cardActions=new ArrayList<>(),previewActions=new ArrayList<>();
    private final Map<View,Boolean> deviceActions=new LinkedHashMap<>();
    private AlertDialog deviceDialog;private CtpClient deviceDialogOwner;private boolean deviceDialogCard,lastCardReady;
    private TextView dvrStorageHint;
    private boolean visible;
    private TextView connection,card,transfer,liveStatus,deviceLabel,sourceBadge,idleTitle,idleHint;
    private View previewPlaceholder;
    private Button connectButton,copyButton,recordButton,remoteDownload;
    private SurfaceView surface;
    private AspectPreview preview;
    private LiveSource live;
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
    private interface DeviceWork {void run(CtpClient owner)throws Exception;}
    public void onCreate(Bundle saved){
        super.onCreate(saved);s=((RoadApp)getApplication()).session;thumbnails=new MediaThumbnails(this);
        if(saved!=null){tab=saved.getInt("tab");camera=saved.getInt("camera");quality=saved.getInt("quality");}
        else quality=getPreferences(0).getInt("quality."+camera,0);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        root=column();root.setBackground(Ui.background());root.setPadding(dp(16),dp(12),dp(16),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());root.setPadding(dp(16)+bars.left,dp(6)+bars.top,dp(16)+bars.right,bars.bottom+dp(4));return insets;});
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);root.setFocusableInTouchMode(true);setContentView(root);build();s.listen(observer);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
        if(BuildConfig.DEBUG&&getIntent().hasExtra("test_host"))s.worker.execute(()->{try{s.connect(getIntent().getStringExtra("test_host"));}catch(Exception e){Diag.error("Test connect",e);}});
    }
    private MediaThumbnails thumbnails;
    private ListView localList;private boolean checkingLibrary,openingLocal;private long localOpenRequest;
    private long checkedClockConnection=-1,pendingClockConnection;private DeviceClock.Reading pendingClock;
    private void build(){
        formatButton=null;formatStatus=null;updateThumbnailWork();root.removeAllViews();localList=null;deviceActions.clear();previewActions.clear();dvrStorageHint=null;recordingStatus=null;snapshotButton=null;cameraContent=null;remoteEmptyPanel=null;remoteRetry=null;remoteRefresh=null;remoteSearch=null;remoteTypes=null;remoteNotice=null;previewPlaceholder=null;sourceBadge=null;idleTitle=null;idleHint=null;deviceLabel=null;settingViews.clear();recordButton=null;connectButton=null;audioButton=null;liveAudioButton=null;phoneRecordButton=null;cameraBar=null;controlBody=null;fileList=null;remoteEmpty=null;rearSwitch=null;remoteDownload=null;cardActions.clear();
        savedBar=null;transferBar=null;transferAction=null;if(fullScreen){fullScreenPage();return;}
        LinearLayout title=row();ImageView mark=new ImageView(this);mark.setImageResource(io.zem.ibox.R.drawable.ic_launcher_art);mark.setBackground(Ui.panel(this,0xff06141d,12));mark.setClipToOutline(true);mark.setContentDescription(null);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);title.addView(mark,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout branding=new LinearLayout(this);branding.setOrientation(LinearLayout.VERTICAL);branding.setGravity(Gravity.CENTER_VERTICAL);TextView brand=text("Zem iBox",20,INK);brand.setMaxLines(1);brand.setHorizontallyScrolling(false);brand.setEllipsize(TextUtils.TruncateAt.END);brand.setAutoSizeTextTypeUniformWithConfiguration(14,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));branding.addView(brand);TextView tagline=text("ТВОЯ ДОРОГА В КАДРЕ",8,MUTED);tagline.setLetterSpacing(.12f);tagline.setSingleLine();tagline.setEllipsize(TextUtils.TruncateAt.END);if(getResources().getConfiguration().screenWidthDp>=360&&getResources().getConfiguration().fontScale<=1.15f)branding.addView(tagline);title.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        connection=text("",12,MUTED);connection.setSingleLine();connection.setEllipsize(TextUtils.TruncateAt.END);connection.setGravity(Gravity.CENTER);connection.setPadding(dp(9),dp(6),dp(9),dp(6));title.addView(connection,new LinearLayout.LayoutParams(dp(compactHeader()?48:92),dp(36)));
        Button menu=Ui.iconButton(this,"menu","Меню приложения");menu.setOnClickListener(v->appMenu(menu));title.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(title);
        card=text("",12,MUTED);card.setVisibility(View.GONE);
        transferBar=row();transferBar.setPadding(dp(12),dp(2),dp(4),dp(2));transferBar.setBackground(Ui.panel(this,0xff123039,12));transfer=text("",12,ACCENT);transferBar.addView(transfer,new LinearLayout.LayoutParams(0,-2,1));transferAction=Ui.iconButton(this,"close","Скрыть статус скачивания");transferAction.setOnClickListener(v->{if(TransferService.active)new AlertDialog.Builder(this).setMessage("Остановить скачивание? Частичная копия сохранится для продолжения.").setNegativeButton("Продолжить",null).setPositiveButton("Остановить",(d,w)->TransferService.requestCancel()).show();else{TransferService.progress="";stateChanged();}});transferBar.addView(transferAction,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(transferBar);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        savedBar=row();savedBar.setPadding(dp(12),dp(4),dp(4),dp(4));savedBar.setBackground(Ui.panel(this,0xff15343a,16));savedHint=text("",12,INK);savedBar.addView(savedHint,new LinearLayout.LayoutParams(0,-2,1));Button show=button("Показать",false);show.setPadding(dp(8),dp(5),dp(8),dp(5));show.setOnClickListener(v->{savedMessage="";navigate(2);stateChanged();});savedBar.addView(show,new LinearLayout.LayoutParams(-2,-2));Button dismiss=Ui.iconButton(this,"close","Скрыть сообщение о сохранении");dismiss.setOnClickListener(v->{savedMessage="";stateChanged();});savedBar.addView(dismiss,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(savedBar);
        boolean gridNav=accessibleNavigation();LinearLayout navigation=column();navigation.setPadding(dp(4),dp(5),dp(4),dp(4));navigation.setBackground(Ui.panel(this,0xff101d26,22));String[] labels={"Камера","Архив","Телефон","Настройки"},icons={"camera","archive","folder","settings"};LinearLayout navRow=row();navigation.addView(navRow);
        for(int i=0;i<labels.length;i++){if(gridNav&&i==2){navRow=row();navigation.addView(navRow);}final int page=i;Button b=Ui.nav(this,labels[i],icons[i],tab==i);b.setContentDescription("Вкладка: "+labels[i]);b.setOnClickListener(v->navigate(page));navRow.addView(b,new LinearLayout.LayoutParams(0,gridNav?-2:dp(72),1));}root.addView(navigation);
        if(tab==0)livePage();else if(tab==1)remotePage();else if(tab==2)localPage();else settingsPage();stateChanged();
    }
    private void navigate(int page){if(tab==page)return;leaveRecording(()->{resumeLive=false;stopLive();tab=page;if(!s.rearAvailable())camera=0;build();});}
    private void leaveRecording(Runnable action){
        if(phoneRecording==null){action.run();return;}
        new AlertDialog.Builder(this).setTitle("Сохранить запись и продолжить?").setMessage("Запись на телефон завершится. Видео останется во вкладке «Телефон».").setNegativeButton("Остаться",null).setPositiveButton("Сохранить и перейти",(d,w)->{stopPhoneRecording();action.run();}).show();
    }
    private void disconnect(){leaveRecording(()->{resumeLive=false;stopLive();s.disconnect();});}
    private void appMenu(View anchor){PopupMenu popup=new PopupMenu(this,anchor);popup.getMenu().add("Wi-Fi регистратора");if(s.connected())popup.getMenu().add("Отключиться");popup.getMenu().add("Логи и диагностика");popup.getMenu().add("О приложении");popup.setOnMenuItemClickListener(item->{String label=item.getTitle().toString();if(label.startsWith("Wi-Fi"))openWifi();else if(label.equals("Отключиться"))disconnect();else if(label.startsWith("Логи"))showLogs();else showAbout();return true;});popup.show();}
    private void showAbout(){new AlertDialog.Builder(this).setTitle("Zem iBox "+BuildConfig.VERSION_NAME).setMessage("Просмотр и управление совместимыми регистраторами Jieli / DVRunning2.\n\nВозможности зависят от устройства и прошивки. Встроенный плеер использует libVLC 3.7.7 (VideoLAN).").setPositiveButton("Закрыть",null).setNeutralButton("Лицензии",(d,n)->showLicenses(false)).show();}
    private void showLicenses(boolean english){try{String notices,license;try(InputStream in=getAssets().open(english?"third-party-notices.txt":"third-party-notices-ru.txt")){notices=new String(assetBytes(in),java.nio.charset.StandardCharsets.UTF_8);}try(InputStream in=getAssets().open(english?"libvlc-LICENSE.txt":"libvlc-LICENSE-ru.txt")){license=new String(assetBytes(in),java.nio.charset.StandardCharsets.UTF_8);}TextView content=text(notices+"\n\n"+license,12,INK);content.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.setPadding(dp(16),0,dp(16),0);scroll.addView(content);new AlertDialog.Builder(this).setTitle(english?"Licenses · original":"Лицензии · русский перевод").setView(scroll).setPositiveButton("Закрыть",null).setNeutralButton(english?"На русском":"English original",(d,w)->showLicenses(!english)).show();}catch(IOException e){fail("Лицензии",e);}}
    private static byte[] assetBytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();Io.copy(in,out);return out.toByteArray();}
    private void openWifi(){leaveRecording(()->chooseWifi(false));}
    private void chooseWifi(boolean connectAfter){try{startActivityForResult(new Intent(Settings.Panel.ACTION_WIFI),connectAfter?WIFI_PICK:WIFI_PICK+1);}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==WIFI_PICK&&!s.connected()){if(s.hasWifi())attemptConnect();else connectionHelp(false);}}
    private void connectionHelp(boolean failed){
        String message=failed?"Регистратор не ответил. Проверьте, что он включён, а телефон подключён именно к его Wi-Fi.\n\nСеть может быть без интернета — это нормально. Выберите «Оставаться в сети», если Android предложит отключиться.":"1. Включите регистратор и его Wi-Fi.\n2. Выберите сеть регистратора на телефоне.\n\nИнтернет для просмотра не нужен. После выбора сети попробуем подключиться.";
        new AlertDialog.Builder(this).setTitle(failed?"Не удалось подключиться":"Подключение к регистратору").setMessage(message).setNegativeButton("Закрыть",null).setNeutralButton("Повторить",(d,w)->attemptConnect()).setPositiveButton("Выбрать Wi-Fi",(d,w)->chooseWifi(true)).show();
    }
    private void stateChanged(){
        if(isFinishing()||isDestroyed())return;
        checkDeviceClock();
        boolean cardReady=s.connected()&&s.cardPresent(),cardArrived=cardReady&&!lastCardReady;lastCardReady=cardReady;
        if(deviceDialog!=null&&(!s.connected()||s.ctp!=deviceDialogOwner||deviceDialogCard&&!s.cardPresent()))dismissDeviceDialog();
        for(Map.Entry<View,Boolean> action:deviceActions.entrySet())action.getKey().setEnabled(s.connected()&&!s.cardFormatting()&&(!action.getValue()||s.cardPresent()));
        for(View action:previewActions)action.setEnabled(s.connected());
        if(dvrStorageHint!=null)dvrStorageHint.setText(!s.connected()?"Подключите видеорегистратор для записи на него":!s.cardKnown()?"Получаем состояние microSD…":s.cardPresent()?"Фото и видео сохраняются на microSD видеорегистратора":"Для записи на видеорегистратор установите в него microSD");
        if(archiveSession!=s.ctp){archiveSession=s.ctp;remoteRequest++;remoteLoading=false;remoteError="";remote.clear();if(tab==1)renderRemote();}
        if(!s.connected()&&wantsLive){stopLive();if(liveStatus!=null)liveStatus.setText(s.status);}
        if(deviceLabel!=null)deviceLabel.setText(s.connected()?s.deviceName():tab==3?"Видеорегистратор не подключён":"Прямое подключение без интернета");
        if(connection!=null){String status=s.connected()?"В сети":s.connecting||connectionPending?"Поиск…":"Не в сети";connection.setText(compactHeader()?(s.connected()?"●":s.connecting||connectionPending?"…":"○"):status);connection.setContentDescription("Регистратор: "+status);connection.setTextColor(s.connected()?ACCENT:MUTED);connection.setBackground(Ui.panel(this,s.connected()?0xff13342f:0xff162630,16));}
        if(connectButton!=null){Ui.leading(connectButton,!s.connected()?"wifi":tab==3?"wifi":wantsLive?"pause":"play");connectButton.setText(s.connecting||connectionPending?"Подключение…":!s.connected()?"Подключить":tab==3?"Отключиться":wantsLive?"Остановить просмотр":"Начать просмотр");connectButton.setEnabled(!s.connecting&&!connectionPending&&!s.cardFormatting());}
        boolean hasVideo=s.connected()&&live!=null&&live.hasVideo();
        if(idleTitle!=null)idleTitle.setText(wantsLive?"Получаем видео…":s.connected()?"Камера готова":"Дорога в кадре");
        if(idleHint!=null)idleHint.setText(wantsLive?"Ожидаем изображение от регистратора":s.connected()?"Нажмите «Начать просмотр»":"Подключите Wi-Fi регистратора");
        if(previewPlaceholder!=null)previewPlaceholder.setVisibility(wantsLive&&hasVideo?View.GONE:View.VISIBLE);
        if(sourceBadge!=null)sourceBadge.setText((hasVideo?"● В ЭФИРЕ":wantsLive?"ПОДКЛЮЧЕНИЕ":"○ КАМЕРА")+(s.rearAvailable()?(camera==0?"  /  ПЕРЕДНЯЯ":"  /  ЗАДНЯЯ"):""));
        if(card!=null)card.setText(s.connected()?(s.cardKnown()?(s.cardPresent()?"Карта памяти установлена":"Без карты · просмотр доступен"):"Получаем состояние карты…"):"Подключите телефон к Wi-Fi регистратора");
        if(transferBar!=null){transfer.setText(TransferService.progress);transferBar.setVisibility(TransferService.progress.isEmpty()?View.GONE:View.VISIBLE);transferAction.setContentDescription(TransferService.active?"Остановить скачивание":"Скрыть статус скачивания");}
        if(remoteDownload!=null)remoteDownload.setEnabled(s.connected()&&s.cardPresent()&&!remoteLoading&&!remote.isEmpty());
        if(recordButton!=null){JSONObject st=s.state.get("VIDEO_CTRL");boolean recording=s.connected()&&st!=null&&"1".equals(st.optString("status"));recordButton.setText(recording?"Остановить запись":"Начать запись");recordButton.setEnabled(s.connected()&&(recording||s.cardPresent()));}
        if(phoneRecordButton!=null){phoneRecordButton.setText(phoneRecording!=null?"Стоп и сохранить":phoneSaving?"Сохраняем…":"Записать видео");selectButton(phoneRecordButton,phoneRecording!=null);Ui.leading(phoneRecordButton,"record");phoneRecordButton.setEnabled(phoneRecording!=null||(!phoneSaving&&hasVideo));}
        if(snapshotButton!=null)snapshotButton.setEnabled(hasVideo);
        if(formatButton!=null)formatButton.setEnabled(s.connected()&&s.cardKnown()&&s.cardPresent()&&!s.cardFormatting()&&!TransferService.active);if(formatStatus!=null)formatStatus.setText(s.formatStatus.isEmpty()?"Удаляет все файлы с microSD регистратора, включая защищённые записи":s.formatStatus);
        updateRecordingStatus();
        if(savedBar!=null){savedBar.setVisibility(savedMessage.isEmpty()?View.GONE:View.VISIBLE);savedHint.setText(savedMessage);}
        for(View action:cardActions)action.setEnabled(s.connected()&&s.cardPresent());
        if(rearSwitch!=null){rearSwitch.setEnabled(s.connected());if(rearSwitch.isChecked()!=s.rearEnabled())rearSwitch.setChecked(s.rearEnabled());}
        if(cameraBar!=null)cameraBar.setVisibility(s.rearAvailable()?View.VISIBLE:View.GONE);
        if(liveAudioButton!=null){Ui.select(liveAudioButton,sound);Ui.setIcon(liveAudioButton,sound?"volume":"mute");liveAudioButton.setContentDescription(sound?"Выключить звук лайва":"Включить звук лайва");}
        if(camera==1&&s.connected()&&!s.rearAvailable()){camera=0;quality=getPreferences(0).getInt("quality.0",0);if(wantsLive){stopLive();startLive();}if(tab==1){remote.clear();refreshFiles();}}
        for(SettingDef def:SettingDef.ALL){TextView t=settingViews.get(def.topic());if(t!=null){JSONObject st=s.state.get(def.topic());t.setEnabled(s.connected());t.setVisibility(s.connected()&&st!=null&&st.has(def.key())&&s.supports(def.topic())&&(!def.topic().equals("DOUBLE_VIDEO")||s.rearAvailable())?View.VISIBLE:View.GONE);t.setText(Ui.settingValue(def.title(),st==null?"Не получено":def.display(st.optString(def.key()))));}}
        if(tab==2&&body!=null&&Library.all(this).length()!=localCount){body.removeAllViews();localPage();}
        if(tab==1){if(!s.cardPresent()&&!remote.isEmpty()){remote.clear();renderRemote();}else renderRemoteState();if(cardArrived&&!remoteLoading&&remote.isEmpty())refreshFiles();}
    }
    private void checkDeviceClock(){
        if(!s.connected()||s.connecting){pendingClock=null;return;}long connectionId=s.connectionId();
        if(checkedClockConnection!=connectionId){checkedClockConnection=connectionId;final CtpClient owner=s.ctp;
            s.worker.execute(()->{try{org.json.JSONObject data=owner.request("DATE_TIME","GET",null,3);DeviceClock.Reading reading=DeviceClock.read(data.optString("date"),System.currentTimeMillis());s.main.post(()->{if(!s.isCurrent(owner)||isFinishing()||isDestroyed())return;if(reading!=null&&reading.needsSync()){pendingClock=reading;pendingClockConnection=connectionId;showClockPrompt();}Diag.log("DEVICE TIME",reading==null?"Device time unavailable":"differenceSeconds="+reading.differenceSeconds());});}catch(Exception e){Diag.log("DEVICE TIME","Check unavailable: "+e.getClass().getSimpleName());}});
        }
        showClockPrompt();
    }
    private void showClockPrompt(){if(!visible||pendingClock==null||deviceDialog!=null||!s.connected()||pendingClockConnection!=s.connectionId())return;DeviceClock.Reading reading=pendingClock;pendingClock=null;CtpClient owner=s.ctp;
        showDeviceDialog(new AlertDialog.Builder(this).setTitle("Время регистратора отличается").setMessage(reading.message(System.currentTimeMillis())).setNegativeButton("Пока нет",null).setPositiveButton("Синхронизировать",(d,w)->{if(deviceReady(owner,false))command(owner,"DATE_TIME",p("date",DeviceClock.wire(System.currentTimeMillis())),()->toast("Время отправлено регистратору"));}),false);
    }
    private void connect(){if(s.cardFormatting()){toast("Дождитесь завершения форматирования microSD");return;}if(connectionPending||s.connecting)return;if(!s.hasWifi()){connectionHelp(false);return;}attemptConnect();}
    private void attemptConnect(){
        if(connectionPending||s.connecting||s.connected())return;connectionPending=true;stateChanged();String address=getPreferences(0).getString("host","");
        s.worker.execute(()->{try{s.connect(address);s.main.post(()->{connectionPending=false;if(isFinishing()||isDestroyed())return;stateChanged();if(tab==0)startLive();else if(tab==1)refreshFiles();});}catch(Exception e){Diag.error("Подключение",e);s.main.post(()->{connectionPending=false;if(isFinishing()||isDestroyed())return;stateChanged();if(visible)connectionHelp(true);});}});
    }
    private void livePage(){
        ScrollView cameraScroll=new ScrollView(this);cameraScroll.setClipToPadding(false);cameraContent=column();cameraContent.setPadding(0,0,0,dp(8));cameraScroll.addView(cameraContent);body.addView(cameraScroll,new LinearLayout.LayoutParams(-1,-1));
        FrameLayout stage=new FrameLayout(this);stage.setBackground(Ui.panel(this,Color.BLACK,22));stage.setClipToOutline(true);
        preview=new AspectPreview(this);surface=preview.surface;stage.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout idle=new LinearLayout(this);idle.setOrientation(LinearLayout.VERTICAL);idle.setGravity(Gravity.CENTER);idle.setPadding(dp(14),dp(10),dp(14),dp(10));idle.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff102632,0xff060b10}));ImageView lens=new ImageView(this);lens.setImageResource(io.zem.ibox.R.drawable.ic_launcher_art);lens.setBackground(Ui.panel(this,0xff06141d,20));lens.setClipToOutline(true);idle.addView(lens,new LinearLayout.LayoutParams(dp(78),dp(78)));TextView welcome=text("Дорога в кадре",17,INK);idleTitle=welcome;welcome.setGravity(Gravity.CENTER);welcome.setMaxLines(2);welcome.setEllipsize(TextUtils.TruncateAt.END);welcome.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));idle.addView(welcome);idleHint=text("Подключите Wi-Fi регистратора",12,MUTED);idleHint.setGravity(Gravity.CENTER);idleHint.setMaxLines(2);idleHint.setEllipsize(TextUtils.TruncateAt.END);idle.addView(idleHint);if(getResources().getConfiguration().fontScale>1.5f){lens.setVisibility(View.GONE);idleHint.setVisibility(View.GONE);}stage.addView(idle,new FrameLayout.LayoutParams(-1,-1));previewPlaceholder=idle;
        sourceBadge=text("○ КАМЕРА",9,INK);sourceBadge.setLetterSpacing(.08f);sourceBadge.setPadding(dp(9),dp(5),dp(9),dp(5));sourceBadge.setBackground(Ui.panel(this,0xb308141c,10));FrameLayout.LayoutParams badgeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);badgeParams.setMargins(dp(12),dp(10),dp(12),0);stage.addView(sourceBadge,badgeParams);
        int videoHeight=Math.max(dp(145),Math.min((getResources().getDisplayMetrics().widthPixels-dp(32))*9/16,(int)(getResources().getDisplayMetrics().heightPixels*.34)));
        cameraContent.addView(stage,new LinearLayout.LayoutParams(-1,videoHeight));attachLiveSurface();
        liveStatus=text(s.connected()?"Камера готова к просмотру":"Подключение по Wi-Fi · интернет не нужен",12,MUTED);liveStatus.setMaxLines(2);cameraContent.addView(liveStatus);recordingStatus=text("",12,0xffffa6ab);recordingStatus.setPadding(dp(12),dp(8),dp(12),dp(8));recordingStatus.setBackground(Ui.panel(this,0xff40232d,12));cameraContent.addView(recordingStatus);
        cameraBar=row();cameraButtons(cameraBar,()->{if(s.connected()){stopLive();startLive();}});cameraContent.addView(cameraBar);
        boolean largeLiveControls=getResources().getConfiguration().screenWidthDp<360&&getResources().getConfiguration().fontScale>1.5f;LinearLayout primary=largeLiveControls?column():row();connectButton=button("Подключить",true);connectButton.setOnClickListener(v->{if(!s.connected())connect();else if(wantsLive)leaveRecording(()->{resumeLive=false;stopLive();liveStatus.setText("Просмотр остановлен");stateChanged();});else startLive();stateChanged();});primary.addView(connectButton,new LinearLayout.LayoutParams(largeLiveControls?-1:0,-2,largeLiveControls?0:1));LinearLayout liveTools=largeLiveControls?row():primary;if(largeLiveControls)primary.addView(liveTools,new LinearLayout.LayoutParams(-1,-2));liveAudioButton=Ui.iconButton(this,sound?"volume":"mute",sound?"Выключить звук лайва":"Включить звук лайва");liveAudioButton.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);if(audioButton!=null)audioButton.setText(sound?"Звук включён":"Звук выключен");stateChanged();});LinearLayout.LayoutParams soundParams=new LinearLayout.LayoutParams(dp(48),dp(48));soundParams.leftMargin=largeLiveControls?0:dp(8);liveTools.addView(liveAudioButton,soundParams);previewActions.add(liveAudioButton);
        Button expand=Ui.iconButton(this,"expand","Видео на весь экран");expand.setOnClickListener(v->toggleFullScreen());previewActions.add(expand);LinearLayout.LayoutParams expandParams=new LinearLayout.LayoutParams(dp(48),dp(48));expandParams.leftMargin=dp(8);liveTools.addView(expand,expandParams);cameraContent.addView(primary);
        LinearLayout modes=getResources().getConfiguration().screenWidthDp<360?column():responsiveRow();modes.setPadding(dp(4),dp(4),dp(4),dp(4));modes.setBackground(Ui.panel(this,0xff0d1a23,16));String[] names={"Просмотр","На видеорегистратор","На телефон"};for(int i=0;i<names.length;i++){final int index=i;Button b=button(names[i],false);b.setTag("segment");selectButton(b,controlTab==i);b.setTextSize(12);b.setPadding(dp(2),dp(6),dp(2),dp(6));b.setContentDescription("Управление: "+names[i]);b.setOnClickListener(v->{controlTab=index;for(int j=0;j<modes.getChildCount();j++)selectButton((Button)modes.getChildAt(j),j==index);renderControls();});LinearLayout.LayoutParams modeParams=pairParams(modes);if(modes.getOrientation()==LinearLayout.HORIZONTAL){modeParams.weight=i==1?1.6f:1f;modeParams.height=-1;}modes.addView(b,modeParams);}cameraContent.addView(modes);
        controlBody=column();controlBody.setPadding(0,dp(4),0,dp(8));cameraContent.addView(controlBody);renderControls();
    }
    private void attachLiveSurface(){final SurfaceView attached=surface;surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){if(resumeLive&&visible){resumeLive=false;attached.post(MainActivity.this::startLive);}}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){if(surface==attached){resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();}}});}
    private void renderControls(){
        if(controlBody==null)return;controlBody.removeAllViews();deviceLabel=null;recordButton=null;phoneRecordButton=null;snapshotButton=null;dvrStorageHint=null;cardActions.clear();previewActions.remove(audioButton);previewActions.remove(qualityButton);
        if(controlTab==0){
            LinearLayout identity=Ui.card(this);TextView eyebrow=Ui.section(this,"Регистратор");eyebrow.setPadding(0,0,0,dp(3));identity.addView(eyebrow);deviceLabel=text(s.connected()?s.deviceName():"Прямое подключение без интернета",14,INK);deviceLabel.setMaxLines(2);identity.addView(deviceLabel);controlBody.addView(identity);
            LinearLayout options=responsiveRow();audioButton=button(sound?"Звук включён":"Звук выключен",false);Ui.leading(audioButton,"volume");audioButton.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);audioButton.setText(sound?"Звук включён":"Звук выключен");stateChanged();});previewActions.add(audioButton);options.addView(audioButton,pairParams(options));Button level=button("Качество",false);qualityButton=level;Ui.leading(level,"quality");level.setOnClickListener(v->showLiveQuality());previewActions.add(level);options.addView(level,pairParams(options));controlBody.addView(options);
            addAction(controlBody,"Выбрать сеть Wi-Fi",this::openWifi);
        }else if(controlTab==1){
            controlBody.addView(Ui.section(this,"Запись на видеорегистратор"));dvrStorageHint=text("",12,MUTED);controlBody.addView(dvrStorageHint);recordButton=button("Начать запись",true);Ui.leading(recordButton,"record");recordButton.setOnClickListener(v->{JSONObject st=s.state.get("VIDEO_CTRL");command("VIDEO_CTRL",p("status",st!=null&&"1".equals(st.optString("status"))?"0":"1"),null);});controlBody.addView(recordButton,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout actions=responsiveRow();Button photo=button("Фото",false);Ui.leading(photo,"photo");photo.setEnabled(s.connected()&&s.cardPresent());photo.setOnClickListener(v->{if(requireCard())command("PHOTO_CTRL",null,()->toast("Фото сохранено на видеорегистраторе"));});cardActions.add(photo);actions.addView(photo,pairParams(actions));Button protect=button("Защитить",false);Ui.leading(protect,"shield");protect.setEnabled(s.connected()&&s.cardPresent());protect.setOnClickListener(v->{if(requireCard())command("VIDEO_CYC_SAVEFILE",null,()->toast("Запись защищена от перезаписи"));});cardActions.add(protect);actions.addView(protect,pairParams(actions));controlBody.addView(actions);addAction(controlBody,"Открыть архив",()->navigate(1));
        }else{
            controlBody.addView(Ui.section(this,"Сохранить момент"));controlBody.addView(text("Кадры и видео текущего просмотра — на телефоне",12,MUTED));LinearLayout phone=responsiveRow();Button snap=button("Сохранить кадр",false);snapshotButton=snap;Ui.leading(snap,"photo");snap.setOnClickListener(v->snapshot());phone.addView(snap,pairParams(phone));phoneRecordButton=button(phoneRecording==null?"Записать видео":"Стоп и сохранить",phoneRecording!=null);Ui.leading(phoneRecordButton,"record");phoneRecordButton.setOnClickListener(v->togglePhoneRecording(phoneRecordButton));phone.addView(phoneRecordButton,pairParams(phone));controlBody.addView(phone);addAction(controlBody,"Файлы на телефоне",()->navigate(2));controlBody.addView(text("Для сохранения включите просмотр. Видео запишется на телефон, даже если в регистраторе нет microSD.",12,MUTED));
        }
        stateChanged();
    }
    private void selectButton(Button b,boolean selected){Ui.select(b,selected);}
    private void cameraButtons(LinearLayout parent,Runnable changed){
        for(int i=0;i<2;i++){final int cam=i;Button b=button(i==0?"Передняя":"Задняя",camera==i);b.setOnClickListener(v->{if(!requireConnection())return;if(camera!=cam)leaveRecording(()->{camera=cam;quality=getPreferences(0).getInt("quality."+camera,0);for(int k=0;k<Math.min(2,parent.getChildCount());k++){selectButton((Button)parent.getChildAt(k),k==cam);}changed.run();});});parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    }
    private void showLiveQuality(){
        if(!requireConnection())return;final CtpClient owner=s.ctp;
        String[] all={"640 × 480","1280 × 720","1920 × 1080"};List<Integer> levels=new ArrayList<>();List<String> labels=new ArrayList<>();JSONArray supported=s.supportedLevels((s.liveTransport().equals("rtsp")?"rtsp_":"")+(camera==0?"forward_support":"behind_support"));
        for(int level=0;level<all.length;level++){boolean found=supported==null;for(int j=0;supported!=null&&j<supported.length();j++)found|=supported.optInt(j,-1)==level;if(found){levels.add(level);labels.add(all[level]);}}
        if(levels.isEmpty()){toast("Регистратор не сообщил доступное качество");return;}
        String actual=live==null?"Лайв остановлен":"Сейчас с регистратора: "+live.width()+" × "+live.height();
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);content.addView(text(actual+"\nФактическое разрешение подтверждается потоком. Во время записи выбор может ограничиваться прошивкой регистратора.",13,MUTED));
        RadioGroup choices=new RadioGroup(this);for(int i=0;i<labels.size();i++){RadioButton choice=new RadioButton(this);choice.setMinHeight(dp(48));choice.setText(labels.get(i));choice.setId(i+1);choice.setTextColor(INK);choice.setPadding(0,dp(10),0,dp(10));choices.addView(choice);if(levels.get(i)==quality)choices.check(i+1);}content.addView(choices);
        JSONObject record=s.state.get("VIDEO_CTRL");boolean ignored=live instanceof LiveStream&&!((LiveStream)live).qualityText().isEmpty();
        if(ignored&&record!=null&&"1".equals(record.optString("status"))){content.addView(text("Прошивка может связывать качество Jieli с записью на microSD. Можно остановить запись на регистраторе и повторить запрос.",13,MUTED));Button retry=button("Повторить без записи на регистраторе",false);content.addView(retry,new LinearLayout.LayoutParams(-1,-2));retry.setOnClickListener(v->{dismissDeviceDialog();if(!deviceReady(owner,false))return;showDeviceDialog(new AlertDialog.Builder(this).setTitle("Остановить запись на регистраторе?").setMessage("Текущий ролик на microSD завершится. Повторим запрос качества лайва; запись на регистраторе останется выключенной. Возобновить её можно кнопкой «Начать запись».").setNegativeButton("Отмена",null).setPositiveButton("Остановить и повторить",(d,w)->{if(deviceReady(owner,false))leaveRecording(()->command(owner,"VIDEO_CTRL",p("status","0"),()->{stopLive();startLive();}));}),false);});}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(content);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Запрос качества лайва").setView(scroll).setNegativeButton("Закрыть",null).create();
        choices.setOnCheckedChangeListener((group,id)->{if(id>0){dialog.dismiss();if(deviceReady(owner,false))selectLiveQuality(levels.get(id-1));}});trackDeviceDialog(dialog,false);
    }
    private void selectLiveQuality(int level){if(!requireConnection())return;leaveRecording(()->{quality=level;getPreferences(0).edit().putInt("quality."+camera,level).apply();stopLive();startLive();});}
    private void toggleFullScreen(){if(!fullScreen&&!requireConnection())return;leaveRecording(this::applyFullScreen);}
    private void applyFullScreen(){
        stopLive();fullScreen=!fullScreen;resumeLive=s.connected();
        if(getWindow().getInsetsController()!=null){if(fullScreen)getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());else getWindow().getInsetsController().show(WindowInsets.Type.systemBars());}
        setRequestedOrientation(fullScreen?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);build();
    }
    private void fullScreenPage(){
        connection=null;card=null;transfer=null;
        preview=new AspectPreview(this);surface=preview.surface;root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));attachLiveSurface();
        liveStatus=text("Просмотр на весь экран",12,MUTED);root.addView(liveStatus);LinearLayout bar=row();
        Button back=button("↙ Назад",false);back.setOnClickListener(v->toggleFullScreen());bar.addView(back,new LinearLayout.LayoutParams(0,-2,1));
        Button audio=button(sound?"Звук: вкл":"Звук: выкл",false);previewActions.add(audio);audio.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);audio.setText(sound?"Звук: вкл":"Звук: выкл");});bar.addView(audio,new LinearLayout.LayoutParams(0,-2,1));
        Button snap=button("Снимок",false);snapshotButton=snap;snap.setOnClickListener(v->snapshot());bar.addView(snap,new LinearLayout.LayoutParams(0,-2,1));
        if(s.rearAvailable()){Button cam=button("Камера",false);previewActions.add(cam);cam.setOnClickListener(v->{if(!requireConnection())return;leaveRecording(()->{camera=1-camera;quality=getPreferences(0).getInt("quality."+camera,0);stopLive();startLive();});});bar.addView(cam,new LinearLayout.LayoutParams(0,-2,1));}root.addView(bar);stateChanged();
    }
    private void startLive(){
        if(s.cardFormatting()){toast("Дождитесь завершения форматирования microSD");return;}
        if(!s.connected()){toast("Сначала подключись к регистратору");return;}
        if(surface==null||!surface.getHolder().getSurface().isValid()){toast("Видеоповерхность ещё не готова");return;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);thumbnails.pause();wantsLive=true;stateChanged();final long generation=++liveGeneration;final Surface target=surface.getHolder().getSurface();final SurfaceView targetView=surface;final int cam=camera,res=quality;
        s.worker.execute(()->{
            LiveSource old=live;live=null;if(old!=null){old.close();old.awaitStopped();}
            if(generation!=liveGeneration||!wantsLive||tab!=0||!target.isValid())return;
            LiveSource.Listener listener=new LiveSource.Listener(){
                public void status(String text){s.main.post(()->{if(generation==liveGeneration&&liveStatus!=null&&tab==0){liveStatus.setText(text);if(live!=null&&preview!=null)preview.videoSize(live.width(),live.height());stateChanged();}});}
                public void frame(FrameAssembler.Frame f){PhoneCapture rec=phoneRecording;if(generation==liveGeneration&&rec!=null)rec.accept(f);}
            };LiveSource next=s.liveTransport().equals("rtsp")?new RtspLive(s,targetView,cam,res,listener):new LiveStream(s,target,cam,res,listener);live=next;next.mute(!sound);next.start();
        });
    }
    private void stopLive(){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);liveGeneration++;wantsLive=false;if(previewPlaceholder!=null)previewPlaceholder.setVisibility(View.VISIBLE);LiveSource old=live;live=null;if(old!=null){old.close();s.worker.execute(old::awaitStopped);}stopPhoneRecording();}
    private volatile PhoneCapture phoneRecording;
    private void togglePhoneRecording(Button button){
        if(phoneRecording!=null){stopPhoneRecording();return;}if(phoneSaving)return;
        if(!s.connected()||live==null||!live.hasVideo()){toast("Дождитесь изображения с камеры");return;}
        try{phoneRecording=live.recording(this);phoneStarted=SystemClock.elapsedRealtime();s.main.removeCallbacks(recordTicker);s.main.post(recordTicker);stateChanged();toast("Запись на телефон начата");}catch(Exception e){fail("Запись на телефон",e);}
    }
    private final Runnable recordTicker=new Runnable(){public void run(){updateRecordingStatus();if(phoneRecording!=null)s.main.postDelayed(this,1000);}};
    private void updateRecordingStatus(){if(recordingStatus==null)return;recordingStatus.setVisibility(phoneRecording!=null||phoneSaving?View.VISIBLE:View.GONE);long seconds=(SystemClock.elapsedRealtime()-phoneStarted)/1000;recordingStatus.setText(phoneSaving?"Сохраняем видео на телефон…":String.format(Locale.ROOT,"● Запись на телефон · %02d:%02d",seconds/60,seconds%60));}
    private void showSaved(String message){savedMessage=message;stateChanged();}
    private void stopPhoneRecording(){PhoneCapture rec=phoneRecording;phoneRecording=null;s.main.removeCallbacks(recordTicker);if(rec==null)return;phoneSaving=true;stateChanged();s.worker.execute(()->{try{rec.finish();s.main.post(()->{phoneSaving=false;showSaved(rec.warning().isEmpty()?"Видео сохранено":rec.warning());});}catch(Exception e){s.main.post(()->{phoneSaving=false;stateChanged();fail("Сохранение видео",e);});}});}

    private void snapshot(){
        if(!s.connected()||live==null||!live.hasVideo()||surface==null){toast("Дождитесь изображения с камеры");return;}
        Bitmap b=Bitmap.createBitmap(Math.max(1,surface.getWidth()),Math.max(1,surface.getHeight()),Bitmap.Config.ARGB_8888);
        PixelCopy.request(surface,b,result->{if(result!=PixelCopy.SUCCESS){b.recycle();fail("Снимок",new IOException("PixelCopy="+result));return;}
            async("Сохранение кадра",()->{File f=new File(getCacheDir(),"snapshot.jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}finally{b.recycle();}String name="Live-"+stamp()+".jpg";Uri uri=Library.publish(this,f,name,"image/jpeg","Live");Library.add(this,uri,name,"image/jpeg",f.length(),"live snapshot");f.delete();},()->showSaved("Кадр сохранён"));},s.main);
    }
    private void remotePage(){
        LinearLayout content=column();content.setPadding(0,0,0,dp(8));
        LinearLayout toolbar=row();toolbar.addView(Ui.heading(this,"Архив","Оригиналы на карте регистратора"),new LinearLayout.LayoutParams(0,-2,1));Button refresh=Ui.iconButton(this,"refresh","Обновить записи");remoteRefresh=refresh;refresh.setOnClickListener(v->refreshFiles());toolbar.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(toolbar);
        if(s.rearAvailable()){LinearLayout cameras=row();cameraButtons(cameras,()->{remote.clear();refreshFiles();});content.addView(cameras);}
        EditText find=input("Поиск по дате или имени",search);remoteSearch=find;find.setSingleLine();find.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this,"search",18,MUTED),null,null,null);find.setCompoundDrawablePadding(dp(10));find.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);find.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence a,int b,int c,int d){}public void onTextChanged(CharSequence a,int b,int c,int d){search=a.toString();renderRemote();}public void afterTextChanged(Editable e){}});content.addView(find);
        Spinner types=new Spinner(this);remoteTypes=types;types.setMinimumHeight(dp(48));types.setContentDescription("Тип записей");android.graphics.drawable.LayerDrawable filterBackground=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{Ui.panel(this,CARD,14),Ui.icon(this,"down",16,MUTED)});filterBackground.setLayerGravity(1,Gravity.END|Gravity.CENTER_VERTICAL);filterBackground.setLayerSize(1,dp(16),dp(16));filterBackground.setLayerInset(1,0,0,dp(14),0);types.setBackground(filterBackground);types.setPadding(dp(12),0,dp(36),0);types.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Все видео","Обычные видео","Закреплённые видео","Фото"}));types.setSelection(filter);types.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){filter=pos;renderRemote();}public void onNothingSelected(AdapterView<?> p){}});content.addView(types,new LinearLayout.LayoutParams(-1,-2));
        content.addView(text("Нажмите запись для просмотра. Меню ⋮ — скачать и защитить.",12,MUTED));
        remoteNotice=text("",12,ACCENT);content.addView(remoteNotice);
        fileList=new ListView(this);fileList.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));fileList.setDividerHeight(dp(8));fileList.setClipToPadding(false);fileList.setPadding(0,dp(4),0,dp(4));fileList.addHeaderView(content,null,false);
        remoteEmptyPanel=column();remoteEmpty=emptyLabel("archive");remoteEmptyPanel.addView(remoteEmpty);remoteRetry=button("Подключить",true);remoteRetry.setOnClickListener(v->archiveRecovery());remoteEmptyPanel.addView(remoteRetry);content.addView(remoteEmptyPanel);
        LinearLayout footer=column();footer.setPadding(0,dp(8),0,dp(8));remoteDownload=button("Скачать папку",true);Ui.leading(remoteDownload,"download");remoteDownload.setOnClickListener(v->downloadFolder());footer.addView(remoteDownload);fileList.addFooterView(footer,null,false);body.addView(fileList,new LinearLayout.LayoutParams(-1,0,1));renderRemote();
        if(remote.isEmpty()&&s.connected())refreshFiles();
    }
    private TextView emptyLabel(String icon){TextView label=text("",14,MUTED);label.setGravity(Gravity.CENTER);label.setLineSpacing(dp(5),1);label.setPadding(dp(20),dp(12),dp(20),dp(12));label.setCompoundDrawables(null,Ui.icon(this,icon,38,0xff527482),null,null);label.setCompoundDrawablePadding(dp(16));return label;}
    private static boolean inFolder(MediaItem item,int folder){return folder==3?item.photo():!item.photo()&&(folder!=1||!item.locked())&&(folder!=2||item.locked());}
    private List<MediaItem> filtered(){List<MediaItem> a=new ArrayList<>();String q=search.toLowerCase(Locale.ROOT);for(MediaItem i:remote)if((q.isEmpty()||(i.name+" "+i.date()+" "+i.time).toLowerCase(Locale.ROOT).contains(q))&&inFolder(i,filter))a.add(i);a.sort(MediaItem.newestFirst());return a;}
    private void downloadFolder(){
        if(!requireCard())return;final int cam=camera,folder=filter;final CtpClient owner=s.ctp;
        deviceAsync("Список всей папки",true,c->{
            List<MediaItem> full=s.files(c,cam),selected=new ArrayList<>();long total=0;for(MediaItem item:full)if(inFolder(item,folder)){selected.add(item);total+=item.size;}selected.sort(MediaItem.newestFirst());long bytes=total;
            s.main.post(()->{if(!s.isCurrent(owner)||!s.cardPresent()||camera!=cam)return;if(selected.isEmpty()){toast("В выбранной папке нет файлов");return;}String title=(cam==0?"Передняя · ":"Задняя · ")+new String[]{"Все видео","Обычные","Закреплённые","Фото"}[folder];showDeviceDialog(new AlertDialog.Builder(this).setTitle(title).setMessage("Файлов: "+selected.size()+" · "+TransferService.bytes(bytes)+"\n\nСкачиваем от новых к старым. Поиск не ограничивает скачивание всей папки. Уже сохранённые оригиналы будут пропущены; частичные загрузки продолжатся.").setNegativeButton("Отмена",null).setPositiveButton("Скачать папку",(d,w)->{if(deviceReady(owner,true))deviceAsync("Очередь скачивания",owner,true,source->TransferService.enqueueBatch(this,selected),()->toast("Папка добавлена в очередь"));}),true);});
        },null);
    }
    private void renderRemote(){
        if(tab!=1||fileList==null)return;final CtpClient owner=s.ctp;List<MediaItem> a=filtered();fileList.setAdapter(new Rows(a.size(),i->{MediaItem f=a.get(i);return (f.locked()?"Защищено · ":"")+f.date()+"\n"+f.name+" · "+TransferService.bytes(f.size)+(!f.photo()?" · "+f.duration+" с":"");},i->openRemote(a.get(i)),i->fileActions(a.get(i)),i->MediaThumbnails.Source.remote(s,a.get(i),owner)));fileList.setOnItemClickListener((p,v,pos,id)->{if(pos>0&&pos<=a.size())openRemote(a.get(pos-1));});fileList.setOnItemLongClickListener((p,v,pos,id)->{if(pos<1||pos>a.size())return false;fileActions(a.get(pos-1));return true;});remoteEmptyPanel.setVisibility(a.isEmpty()?View.VISIBLE:View.GONE);renderRemoteState();
    }
    private void renderRemoteState(){
        if(tab!=1||remoteEmpty==null)return;
        remoteDownload.setVisibility(s.connected()&&s.cardPresent()?View.VISIBLE:View.GONE);
        if(remoteDownload!=null)remoteDownload.setEnabled(s.connected()&&s.cardPresent()&&!remoteLoading&&!remote.isEmpty());if(remoteRefresh!=null)remoteRefresh.setEnabled(s.connected()&&!remoteLoading&&!connectionPending);
        if(remoteNotice!=null){boolean show=!remote.isEmpty()&&(remoteLoading||!remoteError.isEmpty());remoteNotice.setVisibility(show?View.VISIBLE:View.GONE);remoteNotice.setText(remoteLoading?"Обновляем записи…":"Не удалось обновить. Нажмите кнопку обновления выше.");}
        String message,action;
        if(!s.connected()){message="Архив хранится в регистраторе\nПодключитесь к его Wi-Fi, чтобы увидеть записи";action="Подключить";}
        else if(remoteLoading){message=(s.cardPresent()?"Получаем записи…":"Проверяем microSD…")+"\nЭто может занять немного времени";action="";}
        else if(s.cardKnown()&&!s.cardPresent()){message="Карта памяти не установлена\nКамеру можно смотреть и без microSD";action="Открыть камеру";}
        else if(!remoteError.isEmpty()){message="Не удалось получить записи\nПроверьте подключение и попробуйте ещё раз";action="Повторить";}
        else if(!s.cardKnown()){message="Получаем состояние microSD…\nДождитесь ответа видеорегистратора";action="Обновить";}
        else if(!search.isBlank()||filter!=0){message="Ничего не найдено\nПопробуйте другую дату или покажите все записи";action="Сбросить фильтр";}
        else{message="В архиве пока нет записей\nСнимите видео или фото на карту регистратора";action="Обновить";}
        remoteEmpty.setText(message);remoteRetry.setText(action);remoteRetry.setVisibility(action.isEmpty()?View.GONE:View.VISIBLE);
        // A failed refresh remains visible even when a previous list is available.
        if(remoteRefresh!=null)remoteRefresh.setContentDescription(remoteLoading?"Получаем записи":remoteError.isEmpty()?"Обновить записи":"Не удалось обновить. Повторить");
    }
    private void archiveRecovery(){if(!s.connected())connect();else if(s.cardKnown()&&!s.cardPresent())navigate(0);else if(!remoteError.isEmpty())refreshFiles();else if(!search.isBlank()||filter!=0){search="";filter=0;if(remoteSearch!=null)remoteSearch.setText("");if(remoteTypes!=null)remoteTypes.setSelection(0);renderRemote();}else refreshFiles();}
    private void refreshFiles(){
        if(!s.connected()){renderRemote();return;}
        final int cam=camera;final CtpClient client=s.ctp;final long request=++remoteRequest;remoteLoading=true;remoteError="";renderRemote();
        s.worker.execute(()->{try{if(!s.cardKnown()||!s.cardPresent())s.command(client,"SD_STATUS","GET",null);List<MediaItem> items=s.cardPresent()?s.files(client,cam):new ArrayList<>();s.main.post(()->{if(request!=remoteRequest||camera!=cam||s.ctp!=client)return;remoteLoading=false;remote=items;renderRemote();});}catch(Exception e){Diag.error("Список записей",e);s.main.post(()->{if(request!=remoteRequest||camera!=cam||s.ctp!=client)return;remoteLoading=false;remoteError="failed";renderRemoteState();if(!remote.isEmpty()&&tab==1)toast("Не удалось обновить записи. Показан предыдущий список");});}});
    }
    private void fileActions(MediaItem f){if(!requireCard())return;final CtpClient owner=s.ctp;String[] choices=f.photo()?(BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS?new String[]{"Смотреть фото","Скачать оригинал",f.locked()?"Снять закрепление":"Закрепить от перезаписи","Удалить с регистратора"}:new String[]{"Смотреть фото","Скачать оригинал",f.locked()?"Снять закрепление":"Закрепить от перезаписи"}):BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS?new String[]{"Внутренний плеер","Скачать оригинал",f.locked()?"Снять закрепление":"Закрепить от перезаписи","Открыть через VLC","Удалить с регистратора"}:new String[]{"Внутренний плеер","Скачать оригинал",f.locked()?"Снять закрепление":"Закрепить от перезаписи","Открыть через VLC"};showDeviceDialog(new AlertDialog.Builder(this).setTitle(f.name).setItems(choices,(d,w)->{
        if(!deviceReady(owner,true))return;
        if(w==0)openRemote(f);
        if(w==1)download(f);
        if(w==2)command(owner,"FILE_LOCK",p("path",f.path,"status",f.locked()?"0":"1"),this::refreshFiles);
        if(w==3&&!f.photo())openRemoteVlc(f);
        if(w==(f.photo()?3:4)&&BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)confirm("Удалить файл?",f.name+" будет удалён с карты регистратора.",true,c->command(c,"FILES_DELETE",p("path_0",f.path),this::refreshFiles));
    }),true);}
    private void download(MediaItem f){if(!requireCard())return;try{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)){getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);}TransferService.enqueue(this,f);toast("Добавлено в очередь: "+f.name);}catch(Exception e){fail("Скачивание",e);}}
    private void openRemoteVlc(MediaItem f){if(!requireCard())return;try{startActivity(PlaybackQueue.remote(this,s,filtered(),f).putExtra("openVlc",true));}catch(Exception e){fail("VLC",e);}}
    private void openRemote(MediaItem f){if(!requireCard())return;try{startActivity(f.photo()?new Intent(this,PlayerActivity.class).putExtra("uri",s.url(f.path).toString()).putExtra("mime",f.mime()).putExtra("name",f.name):PlaybackQueue.remote(this,s,filtered(),f));}catch(Exception e){fail("Просмотр",e);}}
    private void chooseTransport(){if(!requireConnection())return;CtpClient owner=s.ctp;String[] modes={"auto","tcp","udp","rtsp"},labels={"Автоматически по устройству","TCP · Jieli","UDP · Jieli","RTSP · H.264 / MJPEG"};int selected=java.util.Arrays.asList(modes).indexOf(s.transportPreference());showDeviceDialog(new AlertDialog.Builder(this).setTitle("Передача видео").setSingleChoiceItems(labels,selected,(d,index)->{d.dismiss();if(deviceReady(owner,false))leaveRecording(()->{boolean resume=wantsLive;stopLive();s.setLiveTransport(modes[index]);if(resume&&tab==0)startLive();});}).setNegativeButton("Закрыть",null),false);}
    private void refreshLocalFiles(){if(checkingLibrary)return;checkingLibrary=true;Library.IO.execute(()->{try{Library.reconcile(this);}catch(Exception e){Diag.error("Check phone files",e);}finally{s.main.post(()->{checkingLibrary=false;if(!isFinishing()&&!isDestroyed())stateChanged();});}});}
    private void updateThumbnailWork(){if(visible&&(tab==1||tab==2)&&!wantsLive)thumbnails.resume();else thumbnails.pause();}
    private void localPage(){refreshLocalFiles();
        JSONArray items=Library.all(this);localCount=items.length();boolean compact=getResources().getConfiguration().fontScale>1.2f&&getResources().getConfiguration().screenHeightDp<700;
        LinearLayout header=column(),heading=row();heading.addView(Ui.heading(this,"На телефоне",items.length()==0?"Моменты, которые вы сохранили":"Сохранено файлов: "+items.length()),new LinearLayout.LayoutParams(0,-2,1));boolean hasVideos=false;for(int i=0;i<items.length();i++)hasVideos|=items.optJSONObject(i).optString("mime").startsWith("video/");if(hasVideos){Button playAll=Ui.iconButton(this,"play","Смотреть видео подряд");playAll.setOnClickListener(v->openLocalQueue(items,null));heading.addView(playAll,new LinearLayout.LayoutParams(dp(48),dp(48)));}header.addView(heading);header.addView(text("Папка: Download / Zem iBox Drive\nНажмите видео — откроется внутренний плеер. Меню ⋮ — другие плееры и действия.",12,MUTED));
        FrameLayout area=new FrameLayout(this);ListView list=new ListView(this);localList=list;list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));list.setDividerHeight(dp(8));list.setClipToPadding(false);list.setPadding(0,dp(4),0,dp(4));area.addView(list,new FrameLayout.LayoutParams(-1,-1));TextView empty=emptyLabel("folder");empty.setText("Здесь будут ваши кадры и видео\nСохраните момент с камеры или скачайте запись из архива");
        Button folder=button("Открыть папку записей",false);Ui.leading(folder,"folder");folder.setOnClickListener(v->openRecordingsFolder());LinearLayout.LayoutParams folderParams=new LinearLayout.LayoutParams(-1,-2);folderParams.topMargin=dp(10);folderParams.bottomMargin=dp(10);
        if(compact){if(items.length()==0)header.addView(empty);list.addHeaderView(header,null,false);LinearLayout footer=column();footer.addView(folder,folderParams);list.addFooterView(footer,null,false);body.addView(area,new LinearLayout.LayoutParams(-1,-1));}
        else{body.addView(header);area.addView(empty,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));list.setEmptyView(empty);body.addView(area,new LinearLayout.LayoutParams(-1,0,1));body.addView(folder,folderParams);}
        list.setAdapter(new Rows(items.length(),i->{JSONObject item=items.optJSONObject(i);return item.optString("name")+"\n"+TransferService.bytes(item.optLong("size"));},i->{JSONObject item=items.optJSONObject(i);openPhoneItem(item);},i->localActions(items.optJSONObject(i)),i->MediaThumbnails.Source.local(items.optJSONObject(i))));
        list.setOnItemClickListener((p,v,pos,id)->{JSONObject item=items.optJSONObject(pos-list.getHeaderViewsCount());if(item!=null)openPhoneItem(item);});
        list.setOnItemLongClickListener((p,v,pos,id)->{JSONObject item=items.optJSONObject(pos-list.getHeaderViewsCount());if(item==null)return false;localActions(item);return true;});
    }
    private void openPhoneItem(JSONObject item){if(item.optString("mime").startsWith("video/"))openLocalQueue(Library.all(this),item.optString("uri"));else openLocal(Uri.parse(item.optString("uri")),item.optString("mime"));}
    private void localActions(JSONObject item){boolean video=item.optString("mime").startsWith("video/");new AlertDialog.Builder(this).setTitle(item.optString("name")).setItems(video?new String[]{"Открыть в…","Внутренний плеер · смотреть подряд","Открыть через VLC","Поделиться"}:new String[]{"Открыть в…","Поделиться"},(d,n)->{Uri uri=Uri.parse(item.optString("uri"));if(n==0)openLocal(uri,item.optString("mime"));else if(video&&n==1)openLocalQueue(Library.all(this),uri.toString());else if(video&&n==2)openLocalIn(uri,item.optString("mime"),true);else share(uri,item.optString("mime"));}).show();}
    private void openLocalQueue(JSONArray items,String selected){try{startActivity(PlaybackQueue.local(this,items,selected));}catch(Exception e){fail("Просмотр видео",e);}}
    private void settingsPage(){
        settingViews.clear();deviceActions.clear();connectButton=null;rearSwitch=null;deviceLabel=null;card=null;
        body.addView(Ui.heading(this,"Настройки","Всё для спокойной дороги"));
        LinearLayout groups=row();groups.setPadding(dp(4),dp(4),dp(4),dp(4));groups.setBackground(Ui.panel(this,0xff0d1a23,16));String[] labels={"Регистратор","Приложение"};for(int i=0;i<2;i++){final int page=i;Button b=button(labels[i],false);b.setTag("segment");selectButton(b,settingsTab==i);b.setTextSize(12);b.setOnClickListener(v->{settingsTab=page;body.removeAllViews();settingsPage();stateChanged();});groups.addView(b,new LinearLayout.LayoutParams(0,-2,1));}body.addView(groups);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);LinearLayout content=column();content.setPadding(0,dp(2),0,dp(12));scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(settingsTab==1){
            LinearLayout app=Ui.card(this);ImageView logo=new ImageView(this);logo.setImageResource(io.zem.ibox.R.drawable.ic_launcher_art);logo.setBackground(Ui.panel(this,0xff06141d,16));logo.setClipToOutline(true);app.addView(logo,new LinearLayout.LayoutParams(dp(64),dp(64)));TextView name=text("Zem iBox",23,INK);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));app.addView(name);app.addView(text("Версия "+BuildConfig.VERSION_NAME,12,MUTED));content.addView(app);
            content.addView(Ui.section(this,"Диагностика"));content.addView(loggingSwitch());content.addView(text("Логи помогают разобраться с подключением. Запись можно выключить в любой момент.",12,MUTED));addAction(content,"Логи и диагностика",this::showLogs);
            content.addView(Ui.section(this,"Хранение"));addAction(content,"Файлы на телефоне",()->navigate(2));content.addView(text("Ваши фото и видео доступны в Загрузках и через приложения телефона.",12,MUTED));
            content.addView(Ui.section(this,"Zem iBox"));addAction(content,"О приложении и лицензии",this::showAbout);return;
        }
        LinearLayout identity=Ui.card(this);TextView eyebrow=Ui.section(this,"Ваше устройство");eyebrow.setPadding(0,0,0,dp(4));identity.addView(eyebrow);deviceLabel=text(s.connected()?s.deviceName():"Регистратор не подключён",17,INK);deviceLabel.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));identity.addView(deviceLabel);card=text("",12,MUTED);identity.addView(card);content.addView(identity);
        connectButton=button(s.connected()?"Отключиться":"Подключить",!s.connected());connectButton.setOnClickListener(v->{if(s.connected())disconnect();else connect();});content.addView(connectButton,new LinearLayout.LayoutParams(-1,-2));addAction(content,"Сеть Wi-Fi",this::openWifi);
        content.addView(Ui.section(this,"Камеры и запись"));addDeviceAction(content,"Передача видео",this::chooseTransport);Switch rear=new Switch(this);rearSwitch=rear;rear.setText("Задняя камера");Ui.switchStyle(rear);rear.setChecked(s.rearEnabled());rear.setOnCheckedChangeListener((v,on)->{s.setRearEnabled(on);if(!on)camera=0;stateChanged();});content.addView(rear);content.addView(text("Включите, если к регистратору подключена вторая камера.",12,MUTED));
        addDeviceAction(content,"Разрешение записи · передняя",()->videoResolution(0));if(s.rearAvailable())addDeviceAction(content,"Разрешение записи · задняя",()->videoResolution(1));
        for(SettingDef def:SettingDef.ALL){TextView t=text(def.title(),14,INK);t.setPadding(dp(16),dp(13),dp(16),dp(13));t.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,Ui.icon(this,"chevron",16,MUTED),null);t.setCompoundDrawablePadding(dp(8));t.setBackground(Ui.panel(this,CARD,16));t.setMinHeight(dp(58));content.addView(t,new LinearLayout.LayoutParams(-1,-2));settingViews.put(def.topic(),t);t.setOnClickListener(v->editSetting(def));}
        addDeviceAction(content,"Обновить настройки",()->deviceAsync("Чтение настроек",false,c->{for(SettingDef def:SettingDef.ALL)try{if(tab!=3||settingsTab!=0)break;if(!s.isCurrent(c))break;c.request(def.topic(),"GET",null,1);}catch(CtpClient.DeviceException e){if(e.code!=6)Diag.error("Read "+def.topic(),e);}catch(Exception e){Diag.error("Read "+def.topic(),e);if(!s.connected())break;}},null));
        content.addView(Ui.section(this,"Подключение и обслуживание"));addDeviceAction(content,"Дата и время с телефона",()->command("DATE_TIME",p("date",DeviceClock.wire(System.currentTimeMillis())),()->toast("Дата и время отправлены")));
        addDeviceAction(content,"Память и прошивка",()->deviceAsync("Сведения",false,c->{if(s.cardPresent())try{s.command(c,"TF_CAP","GET",null);}catch(Exception e){Diag.error("Capacity",e);}},()->showDeviceDialog(new AlertDialog.Builder(this).setTitle("Регистратор").setMessage(s.report()).setPositiveButton("Закрыть",null),false)));
        addDeviceAction(content,"Имя и пароль Wi-Fi",this::changeWifi);addAction(content,"Адрес подключения",this::addressDialog);addDeviceAction(content,"Перезагрузить регистратор",()->confirm("Перезагрузить регистратор?","Wi-Fi отключится до завершения перезагрузки.",false,c->command(c,"RESET",null,()->{stopLive();s.disconnect();})));
        if(BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)addDeviceAction(content,"Сбросить настройки регистратора",()->confirm("Сброс настроек","Будут восстановлены заводские настройки регистратора. Имя и пароль Wi-Fi могут измениться.",false,c->command(c,"SYSTEM_DEFAULT",p("def","1"),null)));
        content.addView(Ui.section(this,"Карта памяти"));formatButton=addAction(content,"Форматировать microSD",this::confirmCardFormat);deviceActions.put(formatButton,true);formatStatus=text(s.formatStatus,12,MUTED);content.addView(formatStatus);

    }
    private void confirmCardFormat(){if(!requireCard()||s.cardFormatting())return;if(TransferService.active){toast("Сначала остановите скачивание с регистратора");return;}final CtpClient owner=s.ctp;
        LinearLayout content=column();content.setPadding(dp(16),0,dp(16),dp(12));ScrollView scroll=new ScrollView(this);TextView warning=text("Будут безвозвратно удалены все файлы на microSD, включая защищённые записи. Файлы на телефоне останутся.\n\nЗапись на регистраторе будет остановлена. Не отключайте питание во время форматирования.",16,INK);scroll.addView(warning);content.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(260),getResources().getDisplayMetrics().heightPixels/3)));
        Button approve=button("Форматировать",true),cancel=button("Отмена",false);formatConfirm=approve;formatCancel=cancel;LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,-2);actionParams.topMargin=dp(8);content.addView(approve,actionParams);LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(-1,-2);cancelParams.topMargin=dp(8);content.addView(cancel,cancelParams);
        AlertDialog prompt=new AlertDialog.Builder(this).setTitle("Форматирование microSD").setView(content).create();approve.setOnClickListener(v->{prompt.dismiss();performCardFormat(owner);});cancel.setOnClickListener(v->prompt.dismiss());trackDeviceDialog(prompt,true);
    }
    private void performCardFormat(CtpClient owner){if(!deviceReady(owner,true))return;try{s.beginCardFormat(owner);}catch(Exception e){fail("Форматирование",e);return;}stopLive();remoteRequest++;remoteLoading=false;remote.clear();thumbnails.pause();stateChanged();
        s.worker.execute(()->{try{s.formatCard(owner);}catch(Exception ignored){}final String outcome=s.formatStatus;s.main.post(()->{if(!isFinishing()&&!isDestroyed()){stateChanged();if(visible&&(!s.connected()||s.ctp==owner))new AlertDialog.Builder(this).setTitle("Форматирование microSD").setMessage(outcome).setPositiveButton("Закрыть",null).show();}});});
    }
    private void editSetting(SettingDef def){
        if(!requireConnection())return;final CtpClient owner=s.ctp;
        JSONObject state=s.state.get(def.topic());String current=state==null?"":state.optString(def.key());int selection=-1;for(int i=0;i<def.values().length;i++)if(current.equals(def.values()[i]))selection=i;
        showDeviceDialog(new AlertDialog.Builder(this).setTitle(def.title()).setSingleChoiceItems(def.labels(),selection,(d,pos)->{d.dismiss();command(owner,def.topic(),p(def.key(),def.values()[pos]),()->toast("Настройка подтверждена регистратором"));}).setNegativeButton("Отмена",null),false);
    }
    private void videoResolution(int cam){
        if(!requireConnection())return;final CtpClient owner=s.ctp;
        String topic=cam==0?"VIDEO_PARAM":"PULL_VIDEO_PARAM";
        showDeviceDialog(new AlertDialog.Builder(this).setTitle("Разрешение записи").setItems(new String[]{"VGA · 640 × 480","HD · 1280 × 720","Full HD · 1920 × 1080"},(d,pos)->{
            int[] w={640,1280,1920},h={480,720,1080};JSONObject current=s.state.get(topic);JSONObject p=current==null?new JSONObject():cloneJson(current);try{p.put("w",""+w[pos]).put("h",""+h[pos]).put("fps",current==null?"30":current.optString("fps","30")).put("rate",current==null?"8000":current.optString("rate","8000")).put("format",current==null?"1":current.optString("format","1"));command(owner,topic,p,null);}catch(Exception e){fail("Разрешение",e);}
        }),false);
    }
    private void addressDialog(){EditText field=input("Пусто = определить автоматически",getPreferences(0).getString("host",""));new AlertDialog.Builder(this).setTitle("IP регистратора").setView(field).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(d,w)->{getPreferences(0).edit().putString("host",field.getText().toString().trim()).apply();toast("Адрес сохранён. Подключись заново");}).show();}
    private void changeWifi(){
        if(!requireConnection())return;final CtpClient owner=s.ctp;
        LinearLayout fields=column();EditText ssid=input("Имя Wi-Fi",s.state.get("AP_SSID_INFO")==null?"":s.state.get("AP_SSID_INFO").optString("ssid","")),password=input("Новый пароль, не менее 8 символов","");password.setInputType(129);fields.addView(ssid);fields.addView(password);
        showDeviceDialog(new AlertDialog.Builder(this).setTitle("Wi-Fi регистратора").setMessage("После применения нужно подключиться к новой сети.").setView(fields).setNegativeButton("Отмена",null).setPositiveButton("Применить",(d,w)->{if(password.length()<8||password.length()>63||ssid.length()<1||ssid.getText().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32){toast("Проверь имя сети и длину пароля");return;}command(owner,"AP_SSID_INFO",p("ssid",ssid.getText().toString(),"pwd",password.getText().toString(),"status","1"),()->{stopLive();s.disconnect();});}),false);
    }
    private void showLogs(){
        LinearLayout content=column();content.setPadding(dp(12),0,dp(12),0);EditText notes=input("Что произошло (необязательно)",note);content.addView(notes);
        content.addView(loggingSwitch());
        TextView log=text(Diag.tail(),10,MUTED);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);
        Button export=button("Полные логи файлом",false);export.setOnClickListener(v->{String report=s.report()+"\nUSER NOTE: "+notes.getText();export.setEnabled(false);s.worker.execute(()->{try{Uri file=Diag.export(this,report);s.main.post(()->{export.setEnabled(true);if(isFinishing()||isDestroyed())return;Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Zem iBox logs",file));startActivity(Intent.createChooser(send,"Поделиться файлом логов"));});}catch(Exception e){s.main.post(()->{export.setEnabled(true);fail("Файл логов",e);});}});});
        copyButton=button("Скопировать короткий отчёт",true);copyButton.setOnClickListener(v->{note=notes.getText().toString();copyLogs();});content.addView(copyButton);content.addView(export);

        Button clear=button("Очистить логи",false);clear.setOnClickListener(v->clearLogs(log));content.addView(clear);
        TextView info=text("Команды, ответы, Wi-Fi, HTTP, ошибки и видеопоток. Пароли скрыты. Храним около 30 МБ; Короткий отчёт копируется целиком одним сообщением (до 12 КБ). Полный журнал можно отправить отдельным файлом.",12,MUTED);content.addView(info);
        ScrollView logScroll=new ScrollView(this);logScroll.addView(log);content.addView(logScroll,new LinearLayout.LayoutParams(-1,dp(220)));
        ScrollView panel=new ScrollView(this);panel.addView(content);new AlertDialog.Builder(this).setTitle("Диагностика Zem iBox").setView(panel).setNegativeButton("Закрыть",null).show();
    }
    private Switch loggingSwitch(){Switch toggle=new Switch(this);toggle.setText("Запись логов");Ui.switchStyle(toggle);toggle.setChecked(Diag.enabled());toggle.setOnCheckedChangeListener((button,checked)->{toggle.setEnabled(false);s.worker.execute(()->{try{Diag.setEnabled(checked);s.main.post(()->{toggle.setEnabled(true);clipboardParts=null;clipboardIndex=0;toast(checked?"Запись логов включена":"Запись логов выключена, сохранённые логи остаются");});}catch(Exception e){s.main.post(()->{toggle.setEnabled(true);fail("Запись логов",e);});}});});return toggle;}
    private void clearLogs(TextView preview){
        async("Очистка логов",Diag::clear,()->{clipboardParts=null;clipboardIndex=0;copyButton.setText("Скопировать короткий отчёт");preview.setText(Diag.tail());android.content.ClipboardManager cb=getSystemService(android.content.ClipboardManager.class);if(cb.hasPrimaryClip()&&cb.getPrimaryClipDescription()!=null&&TextUtils.equals("Zem iBox logs",cb.getPrimaryClipDescription().getLabel()))cb.clearPrimaryClip();toast(Diag.enabled()?"Логи очищены. Новые события продолжают записываться":"Логи очищены. Запись выключена");});
    }
    private void openLocal(Uri uri,String mime){openLocalIn(uri,mime,false);}
    private void openLocalIn(Uri uri,String mime,boolean vlc){
        if(openingLocal)return;openingLocal=true;long request=++localOpenRequest;final int page=tab;
        Library.IO.execute(()->{Exception failure=null;try{Library.requireReadable(this,uri);}catch(Exception e){failure=e;if(e instanceof FileNotFoundException)try{Library.forget(this,Collections.singleton(uri.toString()));}catch(Exception ignored){}}
            Exception error=failure;s.main.post(()->{openingLocal=false;if(request!=localOpenRequest||!visible||page!=tab||isFinishing()||isDestroyed())return;
                if(error!=null){stateChanged();fail("Файл недоступен",new IOException("Файл удалён или к нему нет доступа. Список записей обновлён.",error));return;}
                try{startActivity(vlc?MediaOpen.vlc(uri,mime):MediaOpen.chooser(uri,mime));}
                catch(ActivityNotFoundException e){fail("Открыть файл",new IOException(vlc?"VLC не установлен":"На телефоне нет приложения для этого формата",e));}
                catch(RuntimeException e){fail("Открыть файл",new IOException("Android не смог открыть выбор приложения. Попробуйте внутренний плеер.",e));}
            });
        });
    }
    private void openRecordingsFolder(){try{startActivity(MediaOpen.folderChooser(this));}catch(Exception e){try{startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));}catch(Exception fallback){fail("Папка записей",fallback);}}}

    private void copyLogs(){
        try {
            String value=Diag.compact(s.report()+"\nUSER NOTE: "+note);
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Zem iBox logs",value));
            copyButton.setText("Отчёт скопирован · обновить");toast("Короткий отчёт скопирован целиком");
        }catch(Exception e){fail("Копирование логов",e);}
    }
    private static boolean commandNeedsCard(String topic,JSONObject p){return topic.equals("VIDEO_CTRL")?p!=null&&"1".equals(p.optString("status")):Set.of("PHOTO_CTRL","VIDEO_CYC_SAVEFILE","FILE_LOCK","FILES_DELETE","FORMAT","FORMAT_TF_CARD").contains(topic);}
    private void command(String topic,JSONObject p,Runnable after){command(s.ctp,topic,p,after);}
    private void command(CtpClient owner,String topic,JSONObject p,Runnable after){boolean storage=commandNeedsCard(topic,p);if(!deviceReady(owner,storage))return;deviceAsync(topic,owner,storage,c->s.command(c,topic,"PUT",p),()->{stateChanged();if(after!=null)after.run();});}
    private boolean requireConnection(){if(isFinishing()||isDestroyed())return false;if(s.connected())return true;toast("Сначала подключите видеорегистратор");return false;}
    private boolean requireCard(){if(!requireConnection())return false;if(!s.cardKnown()){toast("Получаем состояние microSD. Подождите");return false;}if(!s.cardPresent()){toast("Для этой функции нужна microSD в видеорегистраторе. Просмотр и сохранение на телефон доступны без неё");return false;}return true;}
    private boolean deviceReady(CtpClient owner,boolean storage){if(!requireConnection())return false;if(owner!=s.ctp){toast("Подключение изменилось. Повторите действие");return false;}return !storage||requireCard();}
    private void deviceAsync(String name,boolean storage,DeviceWork work,Runnable after){deviceAsync(name,s.ctp,storage,work,after);}
    private void deviceAsync(String name,CtpClient owner,boolean storage,DeviceWork work,Runnable after){
        if(!deviceReady(owner,storage))return;
        s.worker.execute(()->{if(!s.isCurrent(owner)||storage&&!s.cardPresent())return;try{work.run(owner);s.main.post(()->{if(!isFinishing()&&!isDestroyed()&&s.isCurrent(owner)&&(!storage||s.cardPresent())&&after!=null)after.run();});}catch(Exception e){Diag.error(name,e);s.main.post(()->{if(s.isCurrent(owner)&&(!storage||s.cardPresent()))fail(name,e);});}});
    }
    private AlertDialog showDeviceDialog(AlertDialog.Builder builder,boolean storage){if(storage?!requireCard():!requireConnection())return null;AlertDialog dialog=builder.create();trackDeviceDialog(dialog,storage);return dialog;}
    private void dismissDeviceDialog(){AlertDialog old=deviceDialog;deviceDialog=null;deviceDialogOwner=null;deviceDialogCard=false;if(old!=null)old.dismiss();}
    private void trackDeviceDialog(AlertDialog dialog,boolean storage){dismissDeviceDialog();deviceDialog=dialog;deviceDialogOwner=s.ctp;deviceDialogCard=storage;dialog.setOnDismissListener(d->{if(deviceDialog==dialog){deviceDialog=null;deviceDialogOwner=null;}});dialog.show();}

    private void async(String name,Work work,Runnable after){s.worker.execute(()->{try{work.run();if(after!=null)s.main.post(after);}catch(Exception e){s.main.post(()->fail(name,e));}});}
    private void fail(String context,Throwable e){Diag.error(context,e);new AlertDialog.Builder(this).setTitle(context).setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("Понятно",null).setNeutralButton("Логи",(d,w)->showLogs()).show();}
    private void confirm(String title,String message,boolean storage,DeviceWork action){if(storage?!requireCard():!requireConnection())return;final CtpClient owner=s.ctp;showDeviceDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Подтвердить",(d,w)->{if(!deviceReady(owner,storage))return;try{action.run(owner);}catch(Exception e){fail(title,e);}}),storage);}
    private void share(Uri uri,String mime){try{Intent send=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Zem iBox",uri));startActivity(Intent.createChooser(send,"Поделиться записью"));}catch(Exception e){fail("Отправка",e);}}
    private static JSONObject p(String...pairs){try{return JieliSession.params(pairs);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    private static JSONObject cloneJson(JSONObject v){try{return new JSONObject(v.toString());}catch(Exception e){return new JSONObject();}}
    private String stamp(){return new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date());}
    private void addDeviceAction(LinearLayout layout,String title,Runnable action){Button b=addAction(layout,title,()->{if(requireConnection())action.run();});deviceActions.put(b,title.equals("Форматировать microSD"));b.setEnabled(s.connected()&&(!deviceActions.get(b)||s.cardPresent()));}
    private Button addAction(LinearLayout layout,String title,Runnable action){Button b=button(title.replace("  ›","").replace("  ›",""),false);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setTextSize(13);b.setPadding(dp(14),dp(8),dp(14),dp(8));String icon=title.contains("Wi-Fi")||title.contains("Адрес")?"wifi":title.contains("архив")?"archive":title.contains("телефоне")||title.contains("Загрузки")?"folder":title.contains("Память")?"sd":title.contains("Дата")?"clock":title.contains("Обновить")||title.contains("Перезагрузить")?"refresh":title.contains("Логи")||title.contains("приложении")?"info":"settings";b.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this,icon,19,ACCENT),null,Ui.icon(this,"chevron",15,MUTED),null);b.setCompoundDrawablePadding(dp(12));b.setOnClickListener(v->action.run());layout.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    private boolean accessibleNavigation(){return getResources().getConfiguration().fontScale>1.5f||(getResources().getConfiguration().screenWidthDp<360&&getResources().getConfiguration().fontScale>1.15f);}
    private boolean compactHeader(){return getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.15f;}
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private LinearLayout responsiveRow(){return getResources().getConfiguration().fontScale>1.3f?column():row();}
    private LinearLayout.LayoutParams pairParams(LinearLayout row){return row.getOrientation()==LinearLayout.VERTICAL?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1);}
    private LinearLayout row(){LinearLayout l=spaced(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout column(){return spaced(LinearLayout.VERTICAL);}
    private LinearLayout spaced(int orientation){LinearLayout l=new LinearLayout(this);l.setOrientation(orientation);GradientDrawable gap=new GradientDrawable();gap.setColor(Color.TRANSPARENT);gap.setSize(dp(8),dp(8));l.setDividerDrawable(gap);l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);return l;}
    private TextView text(String value,int size,int color){return Ui.text(this,value,size,color);}
    private EditText input(String hint,String value){return Ui.input(this,hint,value);}
    private Button button(String label,boolean selected){return Ui.button(this,label,selected);}
    private GradientDrawable shape(int color,int radius){return Ui.panel(this,color,radius);}
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();}
    private interface RowText {String get(int i);}
    private interface RowAction {void run(int i);}
    private final class Rows extends BaseAdapter {
        private final int count;private final RowText text;private final RowAction open,actions;private final java.util.function.IntFunction<MediaThumbnails.Source> previews;
        Rows(int n,RowText t,RowAction o,RowAction a,java.util.function.IntFunction<MediaThumbnails.Source> previews){count=n;text=t;open=o;actions=a;this.previews=previews;}public int getCount(){return count;}public Object getItem(int p){return p;}public long getItemId(int p){return p;}
        public View getView(int p,View recycled,ViewGroup parent){MediaRow row=recycled instanceof MediaRow?(MediaRow)recycled:new MediaRow();String value=text.get(p);int split=value.indexOf('\n');row.title.setText(split<0?value:value.substring(0,split));row.detail.setText(split<0?"":value.substring(split+1));boolean photo=value.toLowerCase(Locale.ROOT).contains(".jpg")||value.toLowerCase(Locale.ROOT).contains(".jpeg");row.glyph.setScaleType(ImageView.ScaleType.CENTER_INSIDE);row.glyph.setPadding(dp(12),dp(12),dp(12),dp(12));row.glyph.setImageDrawable(Ui.icon(MainActivity.this,photo?"photo":"play",22,ACCENT));thumbnails.bind(row.glyph,previews.apply(p));row.open.setContentDescription("Открыть: "+value.replace('\n',' '));row.open.setOnClickListener(v->open.run(p));row.open.setOnLongClickListener(v->{actions.run(p);return true;});row.more.setContentDescription("Действия с файлом: "+value.replace('\n',' '));row.more.setOnClickListener(v->actions.run(p));return row;}
    }
    private final class MediaRow extends LinearLayout {
        final TextView title,detail;final ImageView glyph;final LinearLayout open;final Button more;
        MediaRow(){super(MainActivity.this);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(8),dp(8),dp(8),dp(8));setBackground(Ui.panel(MainActivity.this,CARD,17));open=new LinearLayout(MainActivity.this);open.setOrientation(HORIZONTAL);open.setGravity(Gravity.CENTER_VERTICAL);open.setPadding(0,dp(5),0,dp(5));open.setFocusable(true);open.setClickable(true);open.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2452d9ca),null,Ui.panel(MainActivity.this,Color.WHITE,10)));addView(open,new LinearLayout.LayoutParams(0,-2,1));glyph=new ImageView(MainActivity.this);glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);glyph.setPadding(dp(10),dp(10),dp(10),dp(10));glyph.setBackground(Ui.panel(MainActivity.this,0xff1c3540,10));glyph.setClipToOutline(true);open.addView(glyph,new LinearLayout.LayoutParams(dp(84),dp(56)));LinearLayout labels=new LinearLayout(MainActivity.this);labels.setOrientation(VERTICAL);labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);title=text("",14,INK);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));detail=text("",12,MUTED);detail.setMaxLines(3);detail.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);labels.addView(detail);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(12),0,dp(8),0);open.addView(labels,lp);more=Ui.iconButton(MainActivity.this,"menu","Действия с файлом");LinearLayout.LayoutParams moreParams=new LinearLayout.LayoutParams(dp(48),dp(48));moreParams.leftMargin=dp(8);addView(more,moreParams);}
    }
    private void goBack(){if(fullScreen)toggleFullScreen();else if(tab!=0)navigate(0);else leaveRecording(this::finish);}
    // API 33+ uses the OnBackInvokedCallback registered in onCreate; this override serves API 30–32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    private void rebindThumbnails(ListView list){if(list==null)return;ListAdapter adapter=list.getAdapter();if(adapter instanceof HeaderViewListAdapter header)adapter=header.getWrappedAdapter();if(adapter instanceof BaseAdapter rows)rows.notifyDataSetChanged();}
    protected void onResume(){super.onResume();visible=true;checkDeviceClock();updateThumbnailWork();if(tab==1)rebindThumbnails(fileList);if(tab==2){rebindThumbnails(localList);refreshLocalFiles();}if(resumeLive&&tab==0&&s.connected()&&surface!=null&&surface.getHolder().getSurface().isValid()){resumeLive=false;surface.post(this::startLive);}}
    protected void onPause(){visible=false;thumbnails.pause();resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();super.onPause();}
    protected void onDestroy(){s.unlisten(observer);thumbnails.close();dismissDeviceDialog();stopLive();super.onDestroy();}
    protected void onSaveInstanceState(Bundle b){b.putInt("tab",tab);b.putInt("camera",camera);b.putInt("quality",quality);super.onSaveInstanceState(b);}
}
