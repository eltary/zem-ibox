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
    final ArchiveScreen archiveScreen=new ArchiveScreen(this);
    JieliSession s;AppUpdates appUpdates;TextView appUpdateStatus;
    void refreshAppUpdateStatus(){if(appUpdateStatus!=null)appUpdateStatus.setText(appUpdates.summary());}
    void showAppUpdates(){leaveRecording(()->appUpdates.show());}
    boolean updateScreenVisible(){return visible;}
    boolean pendingUpdatePermission;
    boolean canInstallAppUpdate(){return phoneRecording==null&&!phoneSaving&&!TransferService.active&&MediaExports.active.get()==0;}
    LinearLayout root,body,controlBody,cameraBar,cameraContent,remoteEmptyPanel,savedBar,transferBar;
    int controlTab,settingsTab;
    Button formatButton,formatConfirm,formatCancel;TextView formatStatus;
    Button qualityButton,audioButton,liveAudioButton,phoneRecordButton,snapshotButton,remoteRetry,remoteRefresh;
    TextView recordingStatus,savedHint,remoteNotice,liveAudioStatus;
    Button transferAction;
    EditText remoteSearch;Spinner remoteTypes;
    boolean connectionPending,remoteLoading,phoneSaving;
    String savedMessage="",remoteError="";
    long remoteRequest,phoneStarted;
    CtpClient archiveSession;
    static final int WIFI_PICK=61,STORAGE_PERMISSION=62;
    Runnable storageAction;CtpClient storageOwner;
    boolean requireStorage(Runnable action){
        if(Library.canPublish(this))return true;
        if(storageAction==null){storageAction=action;storageOwner=s.ctp;requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_PERMISSION);}
        return false;
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=STORAGE_PERMISSION)return;Runnable action=storageAction;CtpClient owner=storageOwner;storageAction=null;storageOwner=null;
        if(!Library.canPublish(this)){toast("Для сохранения в Загрузки разрешите доступ к файлам");return;}
        if(action!=null&&!isFinishing()&&!isDestroyed()&&s.isCurrent(owner))action.run();
    }
    Switch rearSwitch;
    final List<View> cardActions=new ArrayList<>(),previewActions=new ArrayList<>();
    final Map<View,Boolean> deviceActions=new LinkedHashMap<>();
    AlertDialog deviceDialog;CtpClient deviceDialogOwner;boolean deviceDialogCard,lastCardReady;
    TextView dvrStorageHint;
    boolean visible;
    TextView connection,card,transfer,liveStatus,deviceLabel,sourceBadge,idleTitle,idleHint;
    View previewPlaceholder;
    Button connectButton,copyButton,recordButton,remoteDownload;
    SurfaceView surface;
    AspectPreview preview;
    LiveSource live;
    volatile boolean wantsLive;
    boolean sound,fullScreen,resumeLive;
    int tab=0,camera=0,quality=0;
    volatile long liveGeneration;
    String search="";
    int filter=0;
    int localCount=-1;
    List<MediaItem> remote=new ArrayList<>();
    ListView fileList;
    TextView remoteEmpty;
    List<String> clipboardParts;
    int clipboardIndex;
    String note="";
    final Runnable observer=this::stateChanged;
    final Map<String,TextView> settingViews=new HashMap<>();
    interface Work {void run()throws Exception;}
    interface DeviceWork {void run(CtpClient owner)throws Exception;}
    public void onCreate(Bundle saved){
        super.onCreate(saved);s=((RoadApp)getApplication()).session;thumbnails=new MediaThumbnails(this);appUpdates=new AppUpdates(this,this::refreshAppUpdateStatus);
        if(saved!=null){tab=saved.getInt("tab");camera=saved.getInt("camera");quality=saved.getInt("quality");}
        else quality=getPreferences(0).getInt("quality."+camera,0);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        root=column();root.setBackground(Ui.background());root.setPadding(dp(16),dp(12),dp(16),0);
        Platform.insets(this,root,dp(16),dp(6),dp(16),dp(4));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);root.setFocusableInTouchMode(true);setContentView(root);build();s.listen(observer);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
        if(BuildConfig.DEBUG&&getIntent().hasExtra("test_host"))s.worker.execute(()->{try{s.connect(getIntent().getStringExtra("test_host"));}catch(Exception e){Diag.error("Test connect",e);}});
    }
    MediaThumbnails thumbnails;
    ListView localList;boolean checkingLibrary,openingLocal;long localOpenRequest;
    long checkedClockConnection=-1,pendingClockConnection;DeviceClock.Reading pendingClock;
    void build(){
        appUpdateStatus=null;formatButton=null;formatStatus=null;updateThumbnailWork();root.removeAllViews();localList=null;deviceActions.clear();previewActions.clear();dvrStorageHint=null;recordingStatus=null;snapshotButton=null;cameraContent=null;remoteEmptyPanel=null;remoteRetry=null;remoteRefresh=null;remoteSearch=null;remoteTypes=null;remoteNotice=null;previewPlaceholder=null;sourceBadge=null;idleTitle=null;idleHint=null;deviceLabel=null;settingViews.clear();recordButton=null;connectButton=null;audioButton=null;liveAudioButton=null;phoneRecordButton=null;cameraBar=null;controlBody=null;fileList=null;remoteEmpty=null;rearSwitch=null;remoteDownload=null;cardActions.clear();
        savedBar=null;transferBar=null;transferAction=null;if(fullScreen){fullScreenPage();return;}
        LinearLayout title=row();ImageView mark=new ImageView(this);mark.setImageResource(io.zem.ibox.R.drawable.ic_launcher_art);mark.setBackground(Ui.panel(this,0xff06141d,12));mark.setClipToOutline(true);mark.setContentDescription(null);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);title.addView(mark,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout branding=new LinearLayout(this);branding.setOrientation(LinearLayout.VERTICAL);branding.setGravity(Gravity.CENTER_VERTICAL);TextView brand=text("Zem iBox",20,INK);brand.setMaxLines(1);brand.setHorizontallyScrolling(false);brand.setEllipsize(TextUtils.TruncateAt.END);if(Build.VERSION.SDK_INT>=26)brand.setAutoSizeTextTypeUniformWithConfiguration(14,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);else brand.setTextSize(compactHeader()?16:20);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));branding.addView(brand);TextView tagline=text("ТВОЯ ДОРОГА В КАДРЕ",8,MUTED);tagline.setLetterSpacing(.12f);tagline.setSingleLine();tagline.setEllipsize(TextUtils.TruncateAt.END);if(getResources().getConfiguration().screenWidthDp>=360&&getResources().getConfiguration().fontScale<=1.15f)branding.addView(tagline);title.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        connection=text("",12,MUTED);connection.setSingleLine();connection.setEllipsize(TextUtils.TruncateAt.END);connection.setGravity(Gravity.CENTER);connection.setPadding(dp(9),dp(6),dp(9),dp(6));title.addView(connection,new LinearLayout.LayoutParams(dp(compactHeader()?48:92),dp(36)));
        Button menu=Ui.iconButton(this,"menu","Меню приложения");menu.setOnClickListener(v->appMenu(menu));title.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(title);
        card=text("",12,MUTED);card.setVisibility(View.GONE);
        transferBar=row();transferBar.setPadding(dp(12),dp(2),dp(4),dp(2));transferBar.setBackground(Ui.panel(this,0xff123039,12));transfer=text("",12,ACCENT);transferBar.addView(transfer,new LinearLayout.LayoutParams(0,-2,1));transferAction=Ui.iconButton(this,"close","Скрыть статус скачивания");transferAction.setOnClickListener(v->{if(TransferService.active)Dialogs.builder(this).setMessage("Остановить скачивание? Частичная копия сохранится для продолжения.").setNegativeButton("Продолжить",null).setPositiveButton("Остановить",(d,w)->TransferService.requestCancel()).show();else{TransferService.progress="";stateChanged();}});transferBar.addView(transferAction,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(transferBar);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        savedBar=row();savedBar.setPadding(dp(12),dp(4),dp(4),dp(4));savedBar.setBackground(Ui.panel(this,0xff15343a,16));savedHint=text("",12,INK);savedBar.addView(savedHint,new LinearLayout.LayoutParams(0,-2,1));Button show=button("Показать",false);show.setPadding(dp(8),dp(5),dp(8),dp(5));show.setOnClickListener(v->{savedMessage="";navigate(2);stateChanged();});savedBar.addView(show,new LinearLayout.LayoutParams(-2,-2));Button dismiss=Ui.iconButton(this,"close","Скрыть сообщение о сохранении");dismiss.setOnClickListener(v->{savedMessage="";stateChanged();});savedBar.addView(dismiss,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(savedBar);
        boolean gridNav=accessibleNavigation();LinearLayout navigation=column();navigation.setPadding(dp(4),dp(5),dp(4),dp(4));navigation.setBackground(Ui.panel(this,0xff101d26,22));String[] labels={"Камера","Архив","Телефон","Настройки"},icons={"camera","archive","folder","settings"};LinearLayout navRow=row();navigation.addView(navRow);
        for(int i=0;i<labels.length;i++){if(gridNav&&i==2){navRow=row();navigation.addView(navRow);}final int page=i;Button b=Ui.nav(this,labels[i],icons[i],tab==i);b.setContentDescription("Вкладка: "+labels[i]);b.setOnClickListener(v->navigate(page));navRow.addView(b,new LinearLayout.LayoutParams(0,gridNav?-2:dp(72),1));}root.addView(navigation);
        if(tab==0)livePage();else if(tab==1)remotePage();else if(tab==2)localPage();else settingsPage();stateChanged();
    }
    void navigate(int page){if(tab==page)return;leaveRecording(()->{resumeLive=false;stopLive();tab=page;if(!s.rearAvailable())camera=0;build();});}
    void leaveRecording(Runnable action){
        if(phoneRecording==null){action.run();return;}
        Dialogs.builder(this).setTitle("Сохранить запись и продолжить?").setMessage("Запись на телефон завершится. Видео останется во вкладке «Телефон».").setNegativeButton("Остаться",null).setPositiveButton("Сохранить и перейти",(d,w)->{stopPhoneRecording();action.run();}).show();
    }
    void disconnect(){leaveRecording(()->{resumeLive=false;stopLive();s.disconnect();});}
    void appMenu(View anchor){PopupMenu popup=new PopupMenu(this,anchor);popup.getMenu().add("Wi-Fi регистратора");if(s.connected())popup.getMenu().add("Отключиться");popup.getMenu().add("Загрузки");popup.getMenu().add("Обновление приложения");popup.getMenu().add("Логи и диагностика");popup.getMenu().add("О приложении");popup.setOnMenuItemClickListener(item->{String label=item.getTitle().toString();if(label.startsWith("Wi-Fi"))openWifi();else if(label.equals("Отключиться"))disconnect();else if(label.equals("Загрузки"))leaveRecording(()->startActivity(new Intent(this,DownloadsActivity.class)));else if(label.equals("Обновление приложения"))showAppUpdates();else if(label.startsWith("Логи"))showLogs();else showAbout();return true;});popup.show();}
    AlertDialog aboutDialog;
    void showAbout(){
        if(aboutDialog!=null)aboutDialog.dismiss();
        LinearLayout content=column();content.setPadding(dp(20),dp(20),dp(20),dp(12));
        LinearLayout brand=row();ImageView logo=new ImageView(this);logo.setImageResource(R.mipmap.ic_launcher);brand.addView(logo,new LinearLayout.LayoutParams(dp(56),dp(56)));brand.addView(Ui.heading(this,"Zem iBox","Версия "+BuildConfig.VERSION_NAME),new LinearLayout.LayoutParams(0,-2,1));content.addView(brand);
        content.addView(text("Камера, архив и записи с видеорегистратора на телефоне.",14,INK));
        Button update=button("Проверить обновления",true);Ui.leading(update,"download");content.addView(update,new LinearLayout.LayoutParams(-1,-2));
        content.addView(text("Неофициальное приложение для iBOX и совместимых регистраторов Jieli / DVRunning2. Возможности зависят от устройства и прошивки.",12,MUTED));
        LinearLayout links=column();Button repository=button("Репозиторий",false);Ui.leading(repository,"external");links.addView(repository,pairParams(links));Button licenses=button("Лицензии",false);links.addView(licenses,pairParams(links));content.addView(links);content.addView(text("Видеоплеер libVLC 3.7.7 · VideoLAN",12,MUTED));
        ScrollView scroll=Dialogs.body(this);scroll.addView(content);
        aboutDialog=Dialogs.builder(this).setView(scroll).setPositiveButton("Закрыть",null).create();
        update.setOnClickListener(v->{aboutDialog.dismiss();showAppUpdates();});repository.setOnClickListener(v->openRepository());licenses.setOnClickListener(v->{aboutDialog.dismiss();showLicenses(false);});aboutDialog.show();
    }

    void openRepository(){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/eltary/zem-ibox")));}catch(ActivityNotFoundException e){toast("Установите браузер для открытия репозитория");}}
    void showLicenses(boolean english){try{String notices,license;String own;try(InputStream in=getAssets().open(english?"zem-ibox-LICENSE.txt":"zem-ibox-LICENSE-ru.txt")){own=new String(assetBytes(in),java.nio.charset.StandardCharsets.UTF_8);}try(InputStream in=getAssets().open(english?"third-party-notices.txt":"third-party-notices-ru.txt")){notices=new String(assetBytes(in),java.nio.charset.StandardCharsets.UTF_8);}try(InputStream in=getAssets().open(english?"libvlc-LICENSE.txt":"libvlc-LICENSE-ru.txt")){license=new String(assetBytes(in),java.nio.charset.StandardCharsets.UTF_8);}TextView content=text(own+"\n\n"+notices+"\n\n"+license,12,INK);content.setTextIsSelectable(true);ScrollView scroll=Dialogs.body(this);scroll.addView(content);Dialogs.builder(this).setTitle(english?"Licenses · original":"Лицензии · русский перевод").setView(scroll).setPositiveButton("Закрыть",null).setNeutralButton(english?"На русском":"English original",(d,w)->showLicenses(!english)).show();}catch(IOException e){fail("Лицензии",e);}}
    static byte[] assetBytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();Io.copy(in,out);return out.toByteArray();}
    void openWifi(){leaveRecording(()->chooseWifi(false));}
    void chooseWifi(boolean connectAfter){try{startActivityForResult(new Intent(Build.VERSION.SDK_INT>=29?Settings.Panel.ACTION_WIFI:Settings.ACTION_WIFI_SETTINGS),connectAfter?WIFI_PICK:WIFI_PICK+1);}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result==RESULT_OK&&data!=null&&(request==StorageLocation.PICK_FOLDER||request==StorageLocation.IMPORT)){try{if(request==StorageLocation.PICK_FOLDER){StorageLocation.select(this,data.getData(),data.getFlags());toast("Папка сохранения выбрана");if(tab==3){body.removeAllViews();settingsPage();}}else{List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());Library.IO.execute(()->{try{for(Uri uri:uris)StorageLocation.importFile(getApplicationContext(),uri,data.getFlags());s.main.post(()->{if(isFinishing()||isDestroyed())return;if(tab==2){body.removeAllViews();localPage();}toast("Файлы добавлены");});}catch(Exception e){s.main.post(()->{if(!isFinishing()&&!isDestroyed())fail("Добавление файлов",e);});}});} }catch(Exception e){fail("Папка сохранения",e);}return;}if(request==AppUpdates.INSTALL_PERMISSION){pendingUpdatePermission=true;return;}if(request==WIFI_PICK&&!s.connected()){if(s.hasWifi())attemptConnect();else connectionHelp(false);}}
    void connectionHelp(boolean failed){
        String message=failed?"Регистратор не ответил. Проверьте, что он включён, а телефон подключён именно к его Wi-Fi.\n\nСеть может быть без интернета — это нормально. Выберите «Оставаться в сети», если Android предложит отключиться.":"1. Включите регистратор и его Wi-Fi.\n2. Выберите сеть регистратора на телефоне.\n\nИнтернет для просмотра не нужен. После выбора сети попробуем подключиться.";
        Dialogs.builder(this).setTitle(failed?"Не удалось подключиться":"Подключение к регистратору").setMessage(message).setNegativeButton("Закрыть",null).setNeutralButton("Повторить",(d,w)->attemptConnect()).setPositiveButton("Выбрать Wi-Fi",(d,w)->chooseWifi(true)).show();
    }
    void stateChanged(){
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
        if(liveAudioStatus!=null)liveAudioStatus.setText(live==null?"Звук появится после начала просмотра":live.hasAudio()?(sound?"Звук поступает · динамик включён":"Звук поступает · динамик выключен"):"Регистратор пока не передал звук");
        if(liveAudioButton!=null){Ui.select(liveAudioButton,sound);Ui.setIcon(liveAudioButton,sound?"volume":"mute");liveAudioButton.setContentDescription(sound?"Выключить звук лайва":"Включить звук лайва");}
        if(camera==1&&s.connected()&&!s.rearAvailable()){camera=0;quality=getPreferences(0).getInt("quality.0",0);if(wantsLive){stopLive();startLive();}if(tab==1){remote.clear();refreshFiles();}}
        for(SettingDef def:SettingDef.ALL){TextView t=settingViews.get(def.topic());if(t!=null){JSONObject st=s.state.get(def.topic());t.setEnabled(s.connected());t.setVisibility(s.connected()&&st!=null&&st.has(def.key())&&s.supports(def.topic())&&(!def.topic().equals("DOUBLE_VIDEO")||s.rearAvailable())?View.VISIBLE:View.GONE);t.setText(Ui.settingValue(def.title(),st==null?"Не получено":def.display(st.optString(def.key()))));}}
        if(tab==2&&body!=null&&Library.all(this).length()!=localCount){body.removeAllViews();localPage();}
        if(tab==1){if(!s.cardPresent()&&!remote.isEmpty()){remote.clear();renderRemote();}else renderRemoteState();if(cardArrived&&!remoteLoading&&remote.isEmpty())refreshFiles();}
    }
    void checkDeviceClock(){
        if(!s.connected()||s.connecting){pendingClock=null;return;}long connectionId=s.connectionId();
        if(checkedClockConnection!=connectionId){checkedClockConnection=connectionId;final CtpClient owner=s.ctp;
            s.worker.execute(()->{try{org.json.JSONObject data=owner.request("DATE_TIME","GET",null,3);DeviceClock.Reading reading=DeviceClock.read(data.optString("date"),System.currentTimeMillis());s.main.post(()->{if(!s.isCurrent(owner)||isFinishing()||isDestroyed())return;if(reading!=null&&reading.needsSync()){pendingClock=reading;pendingClockConnection=connectionId;showClockPrompt();}Diag.log("DEVICE TIME",reading==null?"Device time unavailable":"differenceSeconds="+reading.differenceSeconds());});}catch(Exception e){Diag.log("DEVICE TIME","Check unavailable: "+e.getClass().getSimpleName());}});
        }
        showClockPrompt();
    }
    void showClockPrompt(){if(!visible||pendingClock==null||deviceDialog!=null||!s.connected()||pendingClockConnection!=s.connectionId())return;DeviceClock.Reading reading=pendingClock;pendingClock=null;CtpClient owner=s.ctp;
        showDeviceDialog(Dialogs.builder(this).setTitle("Время регистратора отличается").setMessage(reading.message(System.currentTimeMillis())).setNegativeButton("Пока нет",null).setPositiveButton("Синхронизировать",(d,w)->{if(deviceReady(owner,false))command(owner,"DATE_TIME",p("date",DeviceClock.wire(System.currentTimeMillis())),()->toast("Время отправлено регистратору"));}),false);
    }
    void connect(){if(s.cardFormatting()){toast("Дождитесь завершения форматирования microSD");return;}if(connectionPending||s.connecting)return;if(!s.hasWifi()){connectionHelp(false);return;}attemptConnect();}
    void attemptConnect(){
        if(connectionPending||s.connecting||s.connected())return;connectionPending=true;stateChanged();String address=getPreferences(0).getString("host","");
        s.worker.execute(()->{try{s.connect(address);s.main.post(()->{connectionPending=false;if(isFinishing()||isDestroyed())return;stateChanged();if(tab==0)startLive();else if(tab==1)refreshFiles();});}catch(Exception e){Diag.error("Подключение",e);s.main.post(()->{connectionPending=false;if(isFinishing()||isDestroyed())return;stateChanged();if(visible)connectionHelp(true);});}});
    }
    void livePage(){
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
    void attachLiveSurface(){final SurfaceView attached=surface;surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){if(resumeLive&&visible){resumeLive=false;attached.post(MainActivity.this::startLive);}}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){if(surface==attached){resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();}}});}
    void renderControls(){
        if(controlBody==null)return;controlBody.removeAllViews();deviceLabel=null;recordButton=null;phoneRecordButton=null;snapshotButton=null;dvrStorageHint=null;cardActions.clear();previewActions.remove(audioButton);previewActions.remove(qualityButton);
        if(controlTab==0){
            LinearLayout identity=Ui.card(this);deviceLabel=text(s.connected()?s.deviceName():"Прямое подключение без интернета",14,INK);deviceLabel.setMaxLines(2);identity.addView(deviceLabel);controlBody.addView(identity);
            liveAudioStatus=text("",12,MUTED);controlBody.addView(liveAudioStatus);LinearLayout options=responsiveRow();Button smooth=button("Плавность",false);smooth.setOnClickListener(v->choosePreviewMode());options.addView(smooth,pairParams(options));Button level=button("Качество",false);qualityButton=level;Ui.leading(level,"quality");level.setOnClickListener(v->showLiveQuality());previewActions.add(level);options.addView(level,pairParams(options));controlBody.addView(options);
            addAction(controlBody,"Выбрать сеть Wi-Fi",this::openWifi);
        }else if(controlTab==1){
            controlBody.addView(Ui.section(this,"Запись на видеорегистратор"));dvrStorageHint=text("",12,MUTED);controlBody.addView(dvrStorageHint);recordButton=button("Начать запись",true);Ui.leading(recordButton,"record");recordButton.setOnClickListener(v->{JSONObject st=s.state.get("VIDEO_CTRL");command("VIDEO_CTRL",p("status",st!=null&&"1".equals(st.optString("status"))?"0":"1"),null);});controlBody.addView(recordButton,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout actions=responsiveRow();Button photo=button("Фото",false);Ui.leading(photo,"photo");photo.setEnabled(s.connected()&&s.cardPresent());photo.setOnClickListener(v->{if(requireCard())command("PHOTO_CTRL",null,()->toast("Фото сохранено на видеорегистраторе"));});cardActions.add(photo);actions.addView(photo,pairParams(actions));Button protect=button("Защитить",false);Ui.leading(protect,"shield");protect.setEnabled(s.connected()&&s.cardPresent());protect.setOnClickListener(v->{if(requireCard())command("VIDEO_CYC_SAVEFILE",null,()->toast("Запись защищена от перезаписи"));});cardActions.add(protect);actions.addView(protect,pairParams(actions));controlBody.addView(actions);addAction(controlBody,"Открыть архив",()->navigate(1));
        }else{
            controlBody.addView(Ui.section(this,"Сохранить момент"));controlBody.addView(text("Кадры и видео текущего просмотра — на телефоне",12,MUTED));LinearLayout phone=responsiveRow();Button snap=button("Сохранить кадр",false);snapshotButton=snap;Ui.leading(snap,"photo");snap.setOnClickListener(v->snapshot());phone.addView(snap,pairParams(phone));phoneRecordButton=button(phoneRecording==null?"Записать видео":"Стоп и сохранить",phoneRecording!=null);Ui.leading(phoneRecordButton,"record");phoneRecordButton.setOnClickListener(v->togglePhoneRecording(phoneRecordButton));phone.addView(phoneRecordButton,pairParams(phone));controlBody.addView(phone);addAction(controlBody,"Файлы на телефоне",()->navigate(2));controlBody.addView(text("Для сохранения включите просмотр. Видео запишется на телефон, даже если в регистраторе нет microSD.",12,MUTED));
        }
        stateChanged();
    }
    void selectButton(Button b,boolean selected){Ui.select(b,selected);}
    void cameraButtons(LinearLayout parent,Runnable changed){
        for(int i=0;i<2;i++){final int cam=i;Button b=button(i==0?"Передняя":"Задняя",camera==i);b.setOnClickListener(v->{if(!requireConnection())return;if(camera!=cam)leaveRecording(()->{camera=cam;quality=getPreferences(0).getInt("quality."+camera,0);for(int k=0;k<Math.min(2,parent.getChildCount());k++){selectButton((Button)parent.getChildAt(k),k==cam);}changed.run();});});parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    }
    void showLiveQuality(){
        if(!requireConnection())return;final CtpClient owner=s.ctp;
        String[] all={"640 × 480","1280 × 720","1920 × 1080"};List<Integer> levels=new ArrayList<>();List<String> labels=new ArrayList<>();JSONArray supported=s.supportedLevels((s.liveTransport().equals("rtsp")?"rtsp_":"")+(camera==0?"forward_support":"behind_support"));
        for(int level=0;level<all.length;level++){boolean found=supported==null;for(int j=0;supported!=null&&j<supported.length();j++)found|=supported.optInt(j,-1)==level;if(found){levels.add(level);labels.add(all[level]);}}
        if(levels.isEmpty()){toast("Регистратор не сообщил доступное качество");return;}
        String actual=live==null?"Лайв остановлен":"Сейчас с регистратора: "+live.width()+" × "+live.height();
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);content.addView(text(actual+"\nФактическое разрешение подтверждается потоком. Во время записи выбор может ограничиваться прошивкой регистратора.",13,MUTED));
        RadioGroup choices=new RadioGroup(this);for(int i=0;i<labels.size();i++){RadioButton choice=new RadioButton(this);choice.setMinHeight(dp(48));choice.setText(labels.get(i));choice.setId(i+1);choice.setTextColor(INK);choice.setPadding(0,dp(10),0,dp(10));choices.addView(choice);if(levels.get(i)==quality)choices.check(i+1);}content.addView(choices);
        JSONObject record=s.state.get("VIDEO_CTRL");boolean ignored=live instanceof LiveStream&&!((LiveStream)live).qualityText().isEmpty();
        if(ignored&&record!=null&&"1".equals(record.optString("status"))){content.addView(text("Прошивка может связывать качество Jieli с записью на microSD. Можно остановить запись на регистраторе и повторить запрос.",13,MUTED));Button retry=button("Повторить без записи на регистраторе",false);content.addView(retry,new LinearLayout.LayoutParams(-1,-2));retry.setOnClickListener(v->{dismissDeviceDialog();if(!deviceReady(owner,false))return;showDeviceDialog(Dialogs.builder(this).setTitle("Остановить запись на регистраторе?").setMessage("Текущий ролик на microSD завершится. Повторим запрос качества лайва; запись на регистраторе останется выключенной. Возобновить её можно кнопкой «Начать запись».").setNegativeButton("Отмена",null).setPositiveButton("Остановить и повторить",(d,w)->{if(deviceReady(owner,false))leaveRecording(()->command(owner,"VIDEO_CTRL",p("status","0"),()->{stopLive();startLive();}));}),false);});}
        ScrollView scroll=Dialogs.body(this);scroll.addView(content);AlertDialog dialog=Dialogs.builder(this).setTitle("Запрос качества лайва").setView(scroll).setNegativeButton("Закрыть",null).create();
        choices.setOnCheckedChangeListener((group,id)->{if(id>0){dialog.dismiss();if(deviceReady(owner,false))selectLiveQuality(levels.get(id-1));}});trackDeviceDialog(dialog,false);
    }
    void selectLiveQuality(int level){if(!requireConnection())return;leaveRecording(()->{quality=level;getPreferences(0).edit().putInt("quality."+camera,level).apply();stopLive();startLive();});}
    void toggleFullScreen(){if(!fullScreen&&!requireConnection())return;leaveRecording(this::applyFullScreen);}
    void applyFullScreen(){
        stopLive();fullScreen=!fullScreen;resumeLive=s.connected();
        Platform.fullscreen(this,fullScreen);
        setRequestedOrientation(fullScreen?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);build();
    }
    void fullScreenPage(){
        connection=null;card=null;transfer=null;
        preview=new AspectPreview(this);surface=preview.surface;root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));attachLiveSurface();
        liveStatus=text("Просмотр на весь экран",12,MUTED);root.addView(liveStatus);LinearLayout bar=row();
        Button back=button("↙ Назад",false);back.setOnClickListener(v->toggleFullScreen());bar.addView(back,new LinearLayout.LayoutParams(0,-2,1));
        Button audio=button(sound?"Звук: вкл":"Звук: выкл",false);previewActions.add(audio);audio.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);audio.setText(sound?"Звук: вкл":"Звук: выкл");});bar.addView(audio,new LinearLayout.LayoutParams(0,-2,1));
        Button snap=button("Снимок",false);snapshotButton=snap;snap.setOnClickListener(v->snapshot());bar.addView(snap,new LinearLayout.LayoutParams(0,-2,1));
        if(s.rearAvailable()){Button cam=button("Камера",false);previewActions.add(cam);cam.setOnClickListener(v->{if(!requireConnection())return;leaveRecording(()->{camera=1-camera;quality=getPreferences(0).getInt("quality."+camera,0);stopLive();startLive();});});bar.addView(cam,new LinearLayout.LayoutParams(0,-2,1));}root.addView(bar);stateChanged();
    }
    void startLive(){
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
            };LiveSource next=s.liveTransport().equals("rtsp")?new RtspLive(s,targetView,cam,res,listener):new LiveStream(s,target,cam,res,listener);live=next;next.previewMode(getSharedPreferences("preview",0).getInt("mode",0));next.mute(!sound);next.start();
        });
    }
    void stopLive(){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);liveGeneration++;wantsLive=false;if(previewPlaceholder!=null)previewPlaceholder.setVisibility(View.VISIBLE);LiveSource old=live;live=null;if(old!=null){old.close();s.worker.execute(old::awaitStopped);}stopPhoneRecording();}
    volatile PhoneCapture phoneRecording;
    void choosePreviewMode(){String[] names={"Автоматически по Wi-Fi","Меньше задержка","Плавнее"};Dialogs.builder(this).setTitle("Плавность просмотра").setSingleChoiceItems(names,getSharedPreferences("preview",0).getInt("mode",0),(d,index)->{d.dismiss();leaveRecording(()->{getSharedPreferences("preview",0).edit().putInt("mode",index).apply();if(wantsLive){stopLive();startLive();}});}).setNegativeButton("Закрыть",null).show();}
    void togglePhoneRecording(Button button){if(phoneRecording!=null){stopPhoneRecording();return;}if(phoneSaving)return;if(!s.connected()||live==null||!live.hasVideo()){toast("Дождитесь изображения с камеры");return;}if(!requireStorage(()->togglePhoneRecording(phoneRecordButton)))return;LiveSource source=live;if(!source.hasAudio()){Dialogs.builder(this).setTitle("Звук ещё не получен").setMessage("Регистратор пока не передал аудио. Можно подождать или начать запись; если звук появится в потоке, он будет сохранён.").setNegativeButton("Подождать",null).setPositiveButton("Начать запись",(d,w)->{if(source==live&&s.connected()&&live.hasVideo())beginPhoneRecording();}).show();}else beginPhoneRecording();}
    void beginPhoneRecording(){if(phoneRecording!=null||phoneSaving||live==null||!live.hasVideo())return;try{phoneRecording=live.recording(this);phoneStarted=SystemClock.elapsedRealtime();s.main.removeCallbacks(recordTicker);s.main.post(recordTicker);stateChanged();toast("Запись на телефон начата");}catch(Exception e){fail("Запись на телефон",e);}}
    final Runnable recordTicker=new Runnable(){public void run(){updateRecordingStatus();if(phoneRecording!=null)s.main.postDelayed(this,1000);}};
    void updateRecordingStatus(){if(recordingStatus==null)return;recordingStatus.setVisibility(phoneRecording!=null||phoneSaving?View.VISIBLE:View.GONE);long seconds=(SystemClock.elapsedRealtime()-phoneStarted)/1000;recordingStatus.setText(phoneSaving?"Сохраняем видео на телефон…":String.format(Locale.ROOT,"● Запись на телефон · %02d:%02d",seconds/60,seconds%60));}
    void showSaved(String message){savedMessage=message;stateChanged();}
    void stopPhoneRecording(){PhoneCapture rec=phoneRecording;phoneRecording=null;s.main.removeCallbacks(recordTicker);if(rec==null)return;phoneSaving=true;stateChanged();s.worker.execute(()->{try{rec.finish();s.main.post(()->{phoneSaving=false;showSaved(rec.warning().isEmpty()?"Видео сохранено":rec.warning());});}catch(Exception e){s.main.post(()->{phoneSaving=false;stateChanged();fail("Сохранение видео",e);});}});}

    void snapshot(){
        if(!s.connected()||live==null||!live.hasVideo()||surface==null){toast("Дождитесь изображения с камеры");return;}
        if(!requireStorage(this::snapshot))return;
        Bitmap b=Bitmap.createBitmap(Math.max(1,surface.getWidth()),Math.max(1,surface.getHeight()),Bitmap.Config.ARGB_8888);
        PixelCopy.request(surface,b,result->{if(result!=PixelCopy.SUCCESS){b.recycle();fail("Снимок",new IOException("PixelCopy="+result));return;}
            async("Сохранение кадра",()->{File f=new File(getCacheDir(),"snapshot.jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}finally{b.recycle();}String name="Live-"+stamp()+".jpg";Uri uri=Library.publish(this,f,name,"image/jpeg","Live");Library.add(this,uri,name,"image/jpeg",f.length(),"live snapshot");f.delete();},()->showSaved("Кадр сохранён"));},s.main);
    }
    void remotePage(){archiveScreen.remotePage();}
    TextView emptyLabel(String icon){TextView label=text("",14,MUTED);label.setGravity(Gravity.CENTER);label.setLineSpacing(dp(5),1);label.setPadding(dp(20),dp(12),dp(20),dp(12));label.setCompoundDrawables(null,Ui.icon(this,icon,38,0xff527482),null,null);label.setCompoundDrawablePadding(dp(16));return label;}
    static boolean inFolder(MediaItem item,int folder){return folder==3?item.photo():!item.photo()&&(folder!=1||!item.locked())&&(folder!=2||item.locked());}
    List<MediaItem> filtered(){return archiveScreen.filtered();}
    void downloadFolder(){archiveScreen.downloadFolder();}
    void renderRemote(){archiveScreen.renderRemote();}
    void renderRemoteState(){archiveScreen.renderRemoteState();}
    void archiveRecovery(){archiveScreen.archiveRecovery();}
    void refreshFiles(){archiveScreen.refreshFiles();}
    void fileActions(MediaItem f){archiveScreen.fileActions(f);}
    void download(MediaItem f){if(!requireCard()||!requireStorage(()->download(f)))return;try{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)){getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);}TransferService.enqueue(this,f);toast("Добавлено в очередь: "+f.name);}catch(Exception e){fail("Скачивание",e);}}
    void openRemoteVlc(MediaItem f){if(!requireCard())return;try{startActivity(PlaybackQueue.remote(this,s,filtered(),f).putExtra("openVlc",true));}catch(Exception e){fail("VLC",e);}}
    void openRemote(MediaItem f){if(!requireCard())return;try{startActivity(f.photo()?new Intent(this,PlayerActivity.class).putExtra("uri",s.url(f.path).toString()).putExtra("mime",f.mime()).putExtra("name",f.name):PlaybackQueue.remote(this,s,filtered(),f));}catch(Exception e){fail("Просмотр",e);}}
    void chooseTransport(){if(!requireConnection())return;CtpClient owner=s.ctp;String[] modes={"auto","tcp","udp","rtsp"},labels={"Автоматически по устройству","TCP · Jieli","UDP · Jieli","RTSP · H.264 / MJPEG"};int selected=java.util.Arrays.asList(modes).indexOf(s.transportPreference());showDeviceDialog(Dialogs.builder(this).setTitle("Передача видео").setSingleChoiceItems(labels,selected,(d,index)->{d.dismiss();if(deviceReady(owner,false))leaveRecording(()->{boolean resume=wantsLive;stopLive();s.setLiveTransport(modes[index]);if(resume&&tab==0)startLive();});}).setNegativeButton("Закрыть",null),false);}
    void refreshLocalFiles(){if(checkingLibrary)return;checkingLibrary=true;Library.IO.execute(()->{try{Library.reconcile(this);}catch(Exception e){Diag.error("Check phone files",e);}finally{s.main.post(()->{checkingLibrary=false;if(!isFinishing()&&!isDestroyed())stateChanged();});}});}
    void updateThumbnailWork(){if(visible&&(tab==1||tab==2)&&!wantsLive)thumbnails.resume();else thumbnails.pause();}
    final PhoneLibraryScreen libraryScreen=new PhoneLibraryScreen(this);
    void localPage(){libraryScreen.page();}
    void openPhoneItem(JSONObject item){if(item.optString("mime").startsWith("video/"))openLocalQueue(Library.all(this),item.optString("uri"));else openLocal(Uri.parse(item.optString("uri")),item.optString("mime"));}
    void localActions(JSONObject item){libraryScreen.actions(item);}
    void openLocalQueue(JSONArray items,String selected){try{startActivity(PlaybackQueue.local(this,items,selected));}catch(Exception e){fail("Просмотр видео",e);}}
    void importFiles(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","image/*"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),StorageLocation.IMPORT);}catch(ActivityNotFoundException e){toast("Нет приложения для выбора файлов");}}
    void chooseStorage(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),StorageLocation.PICK_FOLDER);}catch(ActivityNotFoundException e){toast("Нет приложения для выбора папки");}}
    void settingsPage(){
        settingViews.clear();deviceActions.clear();connectButton=null;rearSwitch=null;deviceLabel=null;card=null;
        body.addView(Ui.heading(this,"Настройки","Всё для спокойной дороги"));
        LinearLayout groups=row();groups.setPadding(dp(4),dp(4),dp(4),dp(4));groups.setBackground(Ui.panel(this,0xff0d1a23,16));String[] labels={"Регистратор","Приложение"};for(int i=0;i<2;i++){final int page=i;Button b=button(labels[i],false);b.setTag("segment");selectButton(b,settingsTab==i);b.setTextSize(12);b.setOnClickListener(v->{settingsTab=page;body.removeAllViews();settingsPage();stateChanged();});groups.addView(b,new LinearLayout.LayoutParams(0,-2,1));}body.addView(groups);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);LinearLayout content=column();content.setPadding(0,dp(2),0,dp(12));scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(settingsTab==1){
            LinearLayout app=Ui.card(this);ImageView logo=new ImageView(this);logo.setImageResource(io.zem.ibox.R.drawable.ic_launcher_art);logo.setBackground(Ui.panel(this,0xff06141d,16));logo.setClipToOutline(true);app.addView(logo,new LinearLayout.LayoutParams(dp(64),dp(64)));TextView name=text("Zem iBox",23,INK);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));app.addView(name);app.addView(text("Версия "+BuildConfig.VERSION_NAME,12,MUTED));content.addView(app);
            content.addView(Ui.section(this,"Обновления"));appUpdateStatus=text(appUpdates.summary(),12,MUTED);content.addView(appUpdateStatus);addAction(content,"Проверить обновления",this::showAppUpdates);
            content.addView(Ui.section(this,"Диагностика"));content.addView(loggingSwitch());content.addView(text("Логи по умолчанию выключены. Включите их перед повторением проблемы и выключите после отправки отчёта.",12,MUTED));addAction(content,"Логи и диагностика",this::showLogs);
            content.addView(Ui.section(this,"Хранение"));addAction(content,"Папка сохранения: "+StorageLocation.label(this),this::chooseStorage);addAction(content,"Сохранять в Download/Zem iBox Drive",()->{StorageLocation.reset(this);body.removeAllViews();settingsPage();});addAction(content,"Загрузки",()->leaveRecording(()->startActivity(new Intent(this,DownloadsActivity.class))));addAction(content,"Файлы на телефоне",()->navigate(2));content.addView(text("Ваши фото и видео доступны в Загрузках и через приложения телефона.",12,MUTED));
            content.addView(Ui.section(this,"Zem iBox"));addAction(content,"Репозиторий на GitHub",this::openRepository);addAction(content,"О приложении и лицензии",this::showAbout);return;
        }
        LinearLayout identity=Ui.card(this);TextView eyebrow=Ui.section(this,"Ваше устройство");eyebrow.setPadding(0,0,0,dp(4));identity.addView(eyebrow);deviceLabel=text(s.connected()?s.deviceName():"Регистратор не подключён",17,INK);deviceLabel.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));identity.addView(deviceLabel);card=text("",12,MUTED);identity.addView(card);content.addView(identity);
        connectButton=button(s.connected()?"Отключиться":"Подключить",!s.connected());connectButton.setOnClickListener(v->{if(s.connected())disconnect();else connect();});content.addView(connectButton,new LinearLayout.LayoutParams(-1,-2));addAction(content,"Сеть Wi-Fi",this::openWifi);
        content.addView(Ui.section(this,"Камеры и запись"));addDeviceAction(content,"Передача видео",this::chooseTransport);Switch rear=new Switch(this);rearSwitch=rear;rear.setText("Задняя камера");Ui.switchStyle(rear);rear.setChecked(s.rearEnabled());rear.setOnCheckedChangeListener((v,on)->{s.setRearEnabled(on);if(!on)camera=0;stateChanged();});content.addView(rear);content.addView(text("Включите, если к регистратору подключена вторая камера.",12,MUTED));
        addDeviceAction(content,"Разрешение записи · передняя",()->videoResolution(0));if(s.rearAvailable())addDeviceAction(content,"Разрешение записи · задняя",()->videoResolution(1));
        for(SettingDef def:SettingDef.ALL){TextView t=text(def.title(),14,INK);t.setPadding(dp(16),dp(13),dp(16),dp(13));t.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,Ui.icon(this,"chevron",16,MUTED),null);t.setCompoundDrawablePadding(dp(8));t.setBackground(Ui.panel(this,CARD,16));t.setMinHeight(dp(58));content.addView(t,new LinearLayout.LayoutParams(-1,-2));settingViews.put(def.topic(),t);t.setOnClickListener(v->editSetting(def));}
        addDeviceAction(content,"Обновить настройки",()->deviceAsync("Чтение настроек",false,c->{for(SettingDef def:SettingDef.ALL)try{if(tab!=3||settingsTab!=0)break;if(!s.isCurrent(c))break;c.request(def.topic(),"GET",null,1);}catch(CtpClient.DeviceException e){if(e.code!=6)Diag.error("Read "+def.topic(),e);}catch(Exception e){Diag.error("Read "+def.topic(),e);if(!s.connected())break;}},null));
        content.addView(Ui.section(this,"Подключение и обслуживание"));addDeviceAction(content,"Дата и время с телефона",()->command("DATE_TIME",p("date",DeviceClock.wire(System.currentTimeMillis())),()->toast("Дата и время отправлены")));
        addDeviceAction(content,"Память и прошивка",()->deviceAsync("Сведения",false,c->{if(s.cardPresent())try{s.command(c,"TF_CAP","GET",null);}catch(Exception e){Diag.error("Capacity",e);}},()->showDeviceDialog(Dialogs.builder(this).setTitle("Регистратор").setMessage(s.report()).setPositiveButton("Закрыть",null),false)));
        addDeviceAction(content,"Имя и пароль Wi-Fi",this::changeWifi);addAction(content,"Адрес подключения",this::addressDialog);addDeviceAction(content,"Перезагрузить регистратор",()->confirm("Перезагрузить регистратор?","Wi-Fi отключится до завершения перезагрузки.",false,c->command(c,"RESET",null,()->{stopLive();s.disconnect();})));
        if(BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)addDeviceAction(content,"Сбросить настройки регистратора",()->confirm("Сброс настроек","Будут восстановлены заводские настройки регистратора. Имя и пароль Wi-Fi могут измениться.",false,c->command(c,"SYSTEM_DEFAULT",p("def","1"),null)));
        content.addView(Ui.section(this,"Карта памяти"));formatButton=addAction(content,"Форматировать microSD",this::confirmCardFormat);deviceActions.put(formatButton,true);formatStatus=text(s.formatStatus,12,MUTED);content.addView(formatStatus);

    }
    void confirmCardFormat(){if(!requireCard()||s.cardFormatting())return;if(TransferService.active){toast("Сначала остановите скачивание с регистратора");return;}final CtpClient owner=s.ctp;
        LinearLayout content=column();content.setPadding(dp(16),0,dp(16),dp(12));ScrollView scroll=new ScrollView(this);TextView warning=text("Будут безвозвратно удалены все файлы на microSD, включая защищённые записи. Файлы на телефоне останутся.\n\nЗапись на регистраторе будет остановлена. Не отключайте питание во время форматирования.",16,INK);scroll.addView(warning);content.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(260),getResources().getDisplayMetrics().heightPixels/3)));
        Button approve=button("Форматировать",true),cancel=button("Отмена",false);formatConfirm=approve;formatCancel=cancel;LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,-2);actionParams.topMargin=dp(8);content.addView(approve,actionParams);LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(-1,-2);cancelParams.topMargin=dp(8);content.addView(cancel,cancelParams);
        AlertDialog prompt=Dialogs.builder(this).setTitle("Форматирование microSD").setView(content).create();approve.setOnClickListener(v->{prompt.dismiss();performCardFormat(owner);});cancel.setOnClickListener(v->prompt.dismiss());trackDeviceDialog(prompt,true);
    }
    void performCardFormat(CtpClient owner){if(!deviceReady(owner,true))return;try{s.beginCardFormat(owner);}catch(Exception e){fail("Форматирование",e);return;}stopLive();remoteRequest++;remoteLoading=false;remote.clear();thumbnails.pause();stateChanged();
        s.worker.execute(()->{try{s.formatCard(owner);}catch(Exception ignored){}final String outcome=s.formatStatus;s.main.post(()->{if(!isFinishing()&&!isDestroyed()){stateChanged();if(visible&&(!s.connected()||s.ctp==owner))Dialogs.builder(this).setTitle("Форматирование microSD").setMessage(outcome).setPositiveButton("Закрыть",null).show();}});});
    }
    void editSetting(SettingDef def){
        if(!requireConnection())return;final CtpClient owner=s.ctp;
        JSONObject state=s.state.get(def.topic());String current=state==null?"":state.optString(def.key());int selection=-1;for(int i=0;i<def.values().length;i++)if(current.equals(def.values()[i]))selection=i;
        showDeviceDialog(Dialogs.builder(this).setTitle(def.title()).setSingleChoiceItems(def.labels(),selection,(d,pos)->{d.dismiss();command(owner,def.topic(),p(def.key(),def.values()[pos]),()->toast("Настройка подтверждена регистратором"));}).setNegativeButton("Отмена",null),false);
    }
    void videoResolution(int cam){
        if(!requireConnection())return;final CtpClient owner=s.ctp;
        String topic=cam==0?"VIDEO_PARAM":"PULL_VIDEO_PARAM";
        showDeviceDialog(Dialogs.builder(this).setTitle("Разрешение записи").setItems(new String[]{"VGA · 640 × 480","HD · 1280 × 720","Full HD · 1920 × 1080"},(d,pos)->{
            int[] w={640,1280,1920},h={480,720,1080};JSONObject current=s.state.get(topic);JSONObject p=current==null?new JSONObject():cloneJson(current);try{p.put("w",""+w[pos]).put("h",""+h[pos]).put("fps",current==null?"30":current.optString("fps","30")).put("rate",current==null?"8000":current.optString("rate","8000")).put("format",current==null?"1":current.optString("format","1"));command(owner,topic,p,null);}catch(Exception e){fail("Разрешение",e);}
        }),false);
    }
    void addressDialog(){
        LinearLayout content=column();EditText field=input("192.168.1.1",getPreferences(0).getString("host",""));field.setSingleLine();field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);field.setContentDescription("Адрес видеорегистратора");field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);content.addView(field);content.addView(text("Оставьте поле пустым для автоматического определения адреса.",13,MUTED));
        AlertDialog dialog=Dialogs.builder(this).setTitle("IP регистратора").setView(content).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(d,w)->{getPreferences(0).edit().putString("host",field.getText().toString().trim()).apply();toast("Адрес сохранён. Подключитесь заново");}).create();field.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();return true;}return false;});dialog.show();
    }
    void changeWifi(){
        if(!requireConnection())return;final CtpClient owner=s.ctp;
        LinearLayout fields=column();EditText ssid=input("Имя сети",s.state.get("AP_SSID_INFO")==null?"":s.state.get("AP_SSID_INFO").optString("ssid","")),password=input("Пароль","");ssid.setSingleLine();password.setSingleLine();password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);ssid.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);fields.addView(text("Имя сети Wi-Fi",13,MUTED));fields.addView(ssid);fields.addView(text("Новый пароль",13,MUTED));fields.addView(password);fields.addView(text("Пароль должен содержать от 8 до 63 символов.",12,MUTED));
        showDeviceDialog(Dialogs.builder(this).setTitle("Wi-Fi регистратора").setMessage("После применения нужно подключиться к новой сети.").setView(fields).setNegativeButton("Отмена",null).setPositiveButton("Применить",null),false);AlertDialog dialog=deviceDialog;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(password.length()<8||password.length()>63){password.setError("Нужно от 8 до 63 символов");return;}if(ssid.length()<1||ssid.getText().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32){ssid.setError("Укажите имя сети: не более 32 байт");return;}dialog.dismiss();command(owner,"AP_SSID_INFO",p("ssid",ssid.getText().toString(),"pwd",password.getText().toString(),"status","1"),()->{stopLive();s.disconnect();});});
    }
    void showLogs(){
        LinearLayout content=column();content.setPadding(dp(12),0,dp(12),0);EditText notes=input("Что произошло",note);notes.setMinLines(2);notes.setMaxLines(4);notes.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);notes.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);content.addView(notes);
        content.addView(loggingSwitch());
        TextView log=text(Diag.tail(),10,MUTED);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);
        Button export=button("Полные логи файлом",false);export.setOnClickListener(v->{String report=s.report()+"\nUSER NOTE: "+notes.getText();export.setEnabled(false);s.worker.execute(()->{try{Uri file=Diag.export(this,report);s.main.post(()->{export.setEnabled(true);if(isFinishing()||isDestroyed())return;Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Zem iBox logs",file));startActivity(Intent.createChooser(send,"Поделиться файлом логов"));});}catch(Exception e){s.main.post(()->{export.setEnabled(true);fail("Файл логов",e);});}});});
        copyButton=button("Скопировать короткий отчёт",true);copyButton.setOnClickListener(v->{note=notes.getText().toString();copyLogs();});content.addView(copyButton);content.addView(export);

        Button clear=button("Очистить логи",false);clear.setOnClickListener(v->clearLogs(log));content.addView(clear);
        TextView info=text("Команды, ответы, Wi-Fi, HTTP, ошибки и видеопоток. Пароли скрыты. Храним около 30 МБ; Короткий отчёт копируется целиком одним сообщением (до 12 КБ). Полный журнал можно отправить отдельным файлом.",12,MUTED);content.addView(info);
        ScrollView logScroll=new ScrollView(this);logScroll.addView(log);logScroll.setVisibility(View.GONE);Button reveal=button("Показать последние события",false);reveal.setOnClickListener(v->{boolean show=logScroll.getVisibility()!=View.VISIBLE;logScroll.setVisibility(show?View.VISIBLE:View.GONE);reveal.setText(show?"Скрыть последние события":"Показать последние события");});content.addView(reveal);content.addView(logScroll,new LinearLayout.LayoutParams(-1,dp(160)));
        ScrollView panel=Dialogs.body(this);panel.addView(content);Dialogs.builder(this).setTitle("Диагностика Zem iBox").setView(panel).setNegativeButton("Закрыть",null).show();
    }
    Switch loggingSwitch(){Switch toggle=new Switch(this);toggle.setText("Запись логов");Ui.switchStyle(toggle);toggle.setChecked(Diag.enabled());toggle.setOnCheckedChangeListener((button,checked)->{toggle.setEnabled(false);s.worker.execute(()->{try{Diag.setEnabled(checked);s.main.post(()->{toggle.setEnabled(true);clipboardParts=null;clipboardIndex=0;toast(checked?"Запись логов включена":"Запись логов выключена, сохранённые логи остаются");});}catch(Exception e){s.main.post(()->{toggle.setEnabled(true);fail("Запись логов",e);});}});});return toggle;}
    void clearLogs(TextView preview){
        async("Очистка логов",Diag::clear,()->{clipboardParts=null;clipboardIndex=0;copyButton.setText("Скопировать короткий отчёт");preview.setText(Diag.tail());android.content.ClipboardManager cb=getSystemService(android.content.ClipboardManager.class);if(cb.hasPrimaryClip()&&cb.getPrimaryClipDescription()!=null&&TextUtils.equals("Zem iBox logs",cb.getPrimaryClipDescription().getLabel())){if(Build.VERSION.SDK_INT>=28)cb.clearPrimaryClip();else cb.setPrimaryClip(ClipData.newPlainText("",""));}toast(Diag.enabled()?"Логи очищены. Новые события продолжают записываться":"Логи очищены. Запись выключена");});
    }
    void openLocal(Uri uri,String mime){openLocalIn(uri,mime,false);}
    void openLocalIn(Uri uri,String mime,boolean vlc){
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
    void openRecordingsFolder(){try{startActivity(MediaOpen.folderChooser(this));}catch(Exception e){try{startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));}catch(Exception fallback){fail("Папка записей",fallback);}}}

    void copyLogs(){
        try {
            String value=Diag.compact(s.report()+"\nUSER NOTE: "+note);
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Zem iBox logs",value));
            copyButton.setText("Отчёт скопирован · обновить");toast("Короткий отчёт скопирован целиком");
        }catch(Exception e){fail("Копирование логов",e);}
    }
    static boolean commandNeedsCard(String topic,JSONObject p){return topic.equals("VIDEO_CTRL")?p!=null&&"1".equals(p.optString("status")):Set.of("PHOTO_CTRL","VIDEO_CYC_SAVEFILE","FILE_LOCK","FILES_DELETE","FORMAT","FORMAT_TF_CARD").contains(topic);}
    void command(String topic,JSONObject p,Runnable after){command(s.ctp,topic,p,after);}
    void command(CtpClient owner,String topic,JSONObject p,Runnable after){boolean storage=commandNeedsCard(topic,p);if(!deviceReady(owner,storage))return;deviceAsync(topic,owner,storage,c->s.command(c,topic,"PUT",p),()->{stateChanged();if(after!=null)after.run();});}
    boolean requireConnection(){if(isFinishing()||isDestroyed())return false;if(s.connected())return true;toast("Сначала подключите видеорегистратор");return false;}
    boolean requireCard(){if(!requireConnection())return false;if(!s.cardKnown()){toast("Получаем состояние microSD. Подождите");return false;}if(!s.cardPresent()){toast("Для этой функции нужна microSD в видеорегистраторе. Просмотр и сохранение на телефон доступны без неё");return false;}return true;}
    boolean deviceReady(CtpClient owner,boolean storage){if(!requireConnection())return false;if(owner!=s.ctp){toast("Подключение изменилось. Повторите действие");return false;}return !storage||requireCard();}
    void deviceAsync(String name,boolean storage,DeviceWork work,Runnable after){deviceAsync(name,s.ctp,storage,work,after);}
    void deviceAsync(String name,CtpClient owner,boolean storage,DeviceWork work,Runnable after){
        if(!deviceReady(owner,storage))return;
        s.worker.execute(()->{if(!s.isCurrent(owner)||storage&&!s.cardPresent())return;try{work.run(owner);s.main.post(()->{if(!isFinishing()&&!isDestroyed()&&s.isCurrent(owner)&&(!storage||s.cardPresent())&&after!=null)after.run();});}catch(Exception e){Diag.error(name,e);s.main.post(()->{if(s.isCurrent(owner)&&(!storage||s.cardPresent()))fail(name,e);});}});
    }
    AlertDialog showDeviceDialog(AlertDialog.Builder builder,boolean storage){if(storage?!requireCard():!requireConnection())return null;AlertDialog dialog=builder.create();trackDeviceDialog(dialog,storage);return dialog;}
    void dismissDeviceDialog(){AlertDialog old=deviceDialog;deviceDialog=null;deviceDialogOwner=null;deviceDialogCard=false;if(old!=null)old.dismiss();}
    void trackDeviceDialog(AlertDialog dialog,boolean storage){dismissDeviceDialog();deviceDialog=dialog;deviceDialogOwner=s.ctp;deviceDialogCard=storage;dialog.setOnDismissListener(d->{if(deviceDialog==dialog){deviceDialog=null;deviceDialogOwner=null;}});dialog.show();}

    void async(String name,Work work,Runnable after){s.worker.execute(()->{try{work.run();if(after!=null)s.main.post(after);}catch(Exception e){s.main.post(()->fail(name,e));}});}
    void fail(String context,Throwable e){Diag.error(context,e);Dialogs.builder(this).setTitle(context).setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("Понятно",null).setNeutralButton("Логи",(d,w)->showLogs()).show();}
    void confirm(String title,String message,boolean storage,DeviceWork action){if(storage?!requireCard():!requireConnection())return;final CtpClient owner=s.ctp;showDeviceDialog(Dialogs.builder(this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Подтвердить",(d,w)->{if(!deviceReady(owner,storage))return;try{action.run(owner);}catch(Exception e){fail(title,e);}}),storage);}
    void share(Uri uri,String mime){try{Intent send=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Zem iBox",uri));startActivity(Intent.createChooser(send,"Поделиться записью"));}catch(Exception e){fail("Отправка",e);}}
    static JSONObject p(String...pairs){try{return JieliSession.params(pairs);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    static JSONObject cloneJson(JSONObject v){try{return new JSONObject(v.toString());}catch(Exception e){return new JSONObject();}}
    String stamp(){return new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date());}
    void addDeviceAction(LinearLayout layout,String title,Runnable action){Button b=addAction(layout,title,()->{if(requireConnection())action.run();});deviceActions.put(b,title.equals("Форматировать microSD"));b.setEnabled(s.connected()&&(!deviceActions.get(b)||s.cardPresent()));}
    Button addAction(LinearLayout layout,String title,Runnable action){Button b=button(title.replace("  ›","").replace("  ›",""),false);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setTextSize(13);b.setPadding(dp(14),dp(8),dp(14),dp(8));String icon=title.contains("Wi-Fi")||title.contains("Адрес")?"wifi":title.contains("архив")?"archive":title.contains("телефоне")||title.contains("Загрузки")?"folder":title.contains("Память")?"sd":title.contains("Дата")?"clock":title.contains("Обновить")||title.contains("Перезагрузить")?"refresh":title.contains("Логи")||title.contains("приложении")?"info":"settings";Ui.sides(b,Ui.icon(this,icon,19,ACCENT),Ui.icon(this,"chevron",15,MUTED));b.setOnClickListener(v->action.run());layout.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    boolean accessibleNavigation(){return getResources().getConfiguration().fontScale>1.5f||(getResources().getConfiguration().screenWidthDp<360&&getResources().getConfiguration().fontScale>1.15f);}
    boolean compactHeader(){return getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.15f;}
    int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    LinearLayout responsiveRow(){return getResources().getConfiguration().fontScale>1.3f?column():row();}
    LinearLayout.LayoutParams pairParams(LinearLayout row){return row.getOrientation()==LinearLayout.VERTICAL?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1);}
    LinearLayout row(){LinearLayout l=spaced(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    LinearLayout column(){return spaced(LinearLayout.VERTICAL);}
    LinearLayout spaced(int orientation){LinearLayout l=new LinearLayout(this);l.setOrientation(orientation);GradientDrawable gap=new GradientDrawable();gap.setColor(Color.TRANSPARENT);gap.setSize(dp(8),dp(8));l.setDividerDrawable(gap);l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);return l;}
    TextView text(String value,int size,int color){return Ui.text(this,value,size,color);}
    EditText input(String hint,String value){return Ui.input(this,hint,value);}
    Button button(String label,boolean selected){return Ui.button(this,label,selected);}
    GradientDrawable shape(int color,int radius){return Ui.panel(this,color,radius);}
    void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();}
    interface RowText {String get(int i);}interface RowAction {void run(int i);}
    final class Rows extends MediaRows {Rows(int n,RowText t,RowAction o,RowAction a,java.util.function.IntFunction<MediaThumbnails.Source> previews){super(MainActivity.this,thumbnails,n,t::get,o::run,a::run,previews);}}
    void goBack(){if(fullScreen)toggleFullScreen();else if(tab!=0)navigate(0);else leaveRecording(this::finish);}
    // API 33+ uses the OnBackInvokedCallback registered in onCreate; this override serves API 30–32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    void rebindThumbnails(ListView list){if(list==null)return;ListAdapter adapter=list.getAdapter();if(adapter instanceof HeaderViewListAdapter header)adapter=header.getWrappedAdapter();if(adapter instanceof BaseAdapter rows)rows.notifyDataSetChanged();}
    protected void onResume(){super.onResume();visible=true;if(pendingUpdatePermission){pendingUpdatePermission=false;appUpdates.permissionResult();}appUpdates.autoCheck();checkDeviceClock();updateThumbnailWork();if(tab==1)rebindThumbnails(fileList);if(tab==2){rebindThumbnails(localList);refreshLocalFiles();}if(resumeLive&&tab==0&&s.connected()&&surface!=null&&surface.getHolder().getSurface().isValid()){resumeLive=false;surface.post(this::startLive);}}
    protected void onPause(){visible=false;thumbnails.pause();resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();super.onPause();}
    protected void onDestroy(){Dialogs.dismissAll(this);if(aboutDialog!=null)aboutDialog.dismiss();if(appUpdates!=null)appUpdates.close();s.unlisten(observer);thumbnails.close();dismissDeviceDialog();stopLive();super.onDestroy();}
    protected void onSaveInstanceState(Bundle b){b.putInt("tab",tab);b.putInt("camera",camera);b.putInt("quality",quality);super.onSaveInstanceState(b);}
}
