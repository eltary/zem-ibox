package io.zem.iboxdrive;

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
    private LinearLayout root,body;
    private TextView connection,card,transfer,liveStatus;
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
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());root.setPadding(dp(16)+bars.left,dp(10)+bars.top,dp(16)+bars.right,bars.bottom);return insets;});
        setContentView(root);build();s.listen(observer);
        if(BuildConfig.DEBUG&&getIntent().hasExtra("test_host"))s.worker.execute(()->{try{s.connect(getIntent().getStringExtra("test_host"));}catch(Exception e){Diag.error("Test connect",e);}});
    }
    private void build(){
        root.removeAllViews();
        if(fullScreen){fullScreenPage();return;}
        LinearLayout title=row();TextView brand=text("Zem iBox Drive",23,INK);brand.setTypeface(null,Typeface.BOLD);title.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1));
        Button logs=button("Логи",false);logs.setOnClickListener(v->showLogs());title.addView(logs);root.addView(title);
        LinearLayout box=column();box.setPadding(dp(14),dp(12),dp(14),dp(12));box.setBackground(shape(CARD,16));
        connection=text("",16,INK);connection.setTypeface(null,Typeface.BOLD);box.addView(connection);
        card=text("",13,MUTED);box.addView(card);
        LinearLayout actions=row();connectButton=button("Подключиться",true);connectButton.setOnClickListener(v->{if(s.connected()){stopLive();s.disconnect();stateChanged();}else connect();});actions.addView(connectButton,new LinearLayout.LayoutParams(0,dp(48),1));
        Button wifi=button("Wi-Fi",false);wifi.setOnClickListener(v->startActivity(new Intent(Settings.Panel.ACTION_WIFI)));actions.addView(wifi);box.addView(actions);root.addView(box);
        transfer=text("",12,ACCENT);transfer.setPadding(0,dp(5),0,dp(5));transfer.setOnClickListener(v->{if(TransferService.active)new AlertDialog.Builder(this).setMessage("Остановить скачивание? Частичная копия останется для продолжения.").setNegativeButton("Нет",null).setPositiveButton("Остановить",(d,w)->TransferService.cancel=true).show();});root.addView(transfer);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout navigation=row();String[] labels={"Лайв","Записи","Скачано","Настройки"};
        for(int i=0;i<labels.length;i++){final int page=i;Button b=button(labels[i],tab==i);b.setTextSize(12);b.setMinWidth(0);b.setPadding(dp(3),0,dp(3),0);b.setOnClickListener(v->{if(tab!=page){stopLive();tab=page;build();}});navigation.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));}root.addView(navigation);
        if(tab==0)livePage();else if(tab==1)remotePage();else if(tab==2)localPage();else settingsPage();stateChanged();
    }
    private void stateChanged(){
        if(isFinishing()||connection==null)return;
        connection.setText(s.status);connection.setTextColor(s.connected()?ACCENT:INK);connectButton.setText(s.connected()?"Отключиться":s.connecting?"Подключение…":"Подключиться");connectButton.setEnabled(!s.connecting&&checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_GRANTED);
        card.setText(s.connected()?(s.cardKnown()?(s.cardPresent()?"microSD установлена":"Без microSD · лайв доступен"):"Получение состояния microSD…"):"iBOX Flash WiFi Dual · по Wi-Fi");
        transfer.setText(TransferService.progress);transfer.setVisibility(TransferService.progress.isEmpty()?View.GONE:View.VISIBLE);
        if(recordButton!=null){JSONObject st=s.state.get("VIDEO_CTRL");recordButton.setText(st!=null&&"1".equals(st.optString("status"))?"■ Стоп записи":"● Запись на карту");}
        for(SettingDef def:SettingDef.ALL){TextView t=settingViews.get(def.topic());if(t!=null){JSONObject st=s.state.get(def.topic());t.setText(def.title()+"\n"+(st==null?"Не получено":def.display(st.optString(def.key()))));}}
        if(tab==2&&body!=null&&Library.all(this).length()!=localCount){body.removeAllViews();localPage();}
        if(tab==1&&s.cardKnown()&&!s.cardPresent()&&!remote.isEmpty()){remote.clear();renderRemote();}
    }
    private void connect(){
        String address=getPreferences(0).getString("host","");
        async("Подключение",()->s.connect(address),()->{stateChanged();if(tab==0)startLive();else if(tab==1)refreshFiles();});
    }
    private void livePage(){
        LinearLayout cameras=row();cameraButtons(cameras,()->{stopLive();startLive();});Button expand=button("⛶",false);expand.setContentDescription("Лайв на весь экран");expand.setOnClickListener(v->toggleFullScreen());cameras.addView(expand);body.addView(cameras);
        preview=new AspectPreview(this);surface=preview.surface;body.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){if(resumeLive){resumeLive=false;surface.post(MainActivity.this::startLive);}}public void surfaceChanged(SurfaceHolder h,int format,int w,int hh){}public void surfaceDestroyed(SurfaceHolder h){resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();}});
        liveStatus=text("Подключись к Wi-Fi регистратора и нажми «Лайв»",13,MUTED);body.addView(liveStatus);
        LinearLayout buttons=row();Button start=button("▶ Лайв",true);start.setOnClickListener(v->startLive());buttons.addView(start,new LinearLayout.LayoutParams(0,dp(48),1));
        Button stop=button("Стоп",false);stop.setOnClickListener(v->{stopLive();liveStatus.setText("Лайв остановлен");});buttons.addView(stop,new LinearLayout.LayoutParams(0,dp(48),1));
        Button audio=button(sound?"Звук: вкл":"Звук: выкл",false);audio.setTextSize(12);audio.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);audio.setText(sound?"Звук: вкл":"Звук: выкл");});buttons.addView(audio,new LinearLayout.LayoutParams(0,dp(48),1));
        Button level=button("Качество",false);level.setOnClickListener(v->showLiveQuality());buttons.addView(level,new LinearLayout.LayoutParams(0,dp(48),1));body.addView(buttons);
        LinearLayout controls=row();recordButton=button("● Запись на карту",false);recordButton.setTextSize(12);recordButton.setOnClickListener(v->{if(!requireCard())return;JSONObject st=s.state.get("VIDEO_CTRL");boolean recording=st!=null&&"1".equals(st.optString("status"));command("VIDEO_CTRL",p("status",recording?"0":"1"),null);});controls.addView(recordButton,new LinearLayout.LayoutParams(0,dp(48),1));
        Button photo=button("Фото",false);photo.setOnClickListener(v->{if(requireCard())command("PHOTO_CTRL",null,()->toast("Фото сохранено на карту"));});controls.addView(photo);
        Button protect=button("Защитить",false);protect.setOnClickListener(v->{if(requireCard())command("VIDEO_CYC_SAVEFILE",null,()->toast("Команда защиты отправлена"));});controls.addView(protect);body.addView(controls);
        LinearLayout phone=row();Button screenshot=button("Кадр на телефон",false);screenshot.setOnClickListener(v->snapshot());phone.addView(screenshot,new LinearLayout.LayoutParams(0,dp(48),1));Button capture=button("Запись лайва",false);capture.setOnClickListener(v->togglePhoneRecording(capture));phone.addView(capture,new LinearLayout.LayoutParams(0,dp(48),1));body.addView(phone);
    }
    private void cameraButtons(LinearLayout parent,Runnable changed){
        for(int i=0;i<2;i++){final int cam=i;Button b=button(i==0?"Передняя":"Задняя",camera==i);b.setOnClickListener(v->{if(camera!=cam){camera=cam;quality=getPreferences(0).getInt("quality."+camera,0);for(int k=0;k<Math.min(2,parent.getChildCount());k++){parent.getChildAt(k).setBackground(shape(k==cam?ACCENT:CARD,12));((Button)parent.getChildAt(k)).setTextColor(k==cam?BG:INK);}changed.run();}});parent.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}
    }
    private void showLiveQuality(){
        String[] all={"640 × 480","1280 × 720","1920 × 1080"};List<Integer> levels=new ArrayList<>();List<String> labels=new ArrayList<>();JSONArray supported=s.description.optJSONArray(camera==0?"forward_support":"behind_support");
        for(int level=0;level<all.length;level++){boolean found=supported==null;for(int j=0;supported!=null&&j<supported.length();j++)found|=supported.optInt(j,-1)==level;if(found){levels.add(level);labels.add(all[level]);}}
        if(levels.isEmpty()){toast("Регистратор не сообщил доступное качество");return;}
        String actual=live==null?"Лайв остановлен":"Сейчас с iBOX: "+live.width()+" × "+live.height();
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);content.addView(text(actual+"\nФактическое разрешение подтверждается потоком. Во время записи выбор может ограничиваться прошивкой iBOX.",13,MUTED));
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
        preview=new AspectPreview(this);surface=preview.surface;root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){if(resumeLive){resumeLive=false;surface.post(MainActivity.this::startLive);}}public void surfaceChanged(SurfaceHolder h,int f,int w,int hh){}public void surfaceDestroyed(SurfaceHolder h){resumeLive|=wantsLive&&tab==0&&s.connected();stopLive();}});
        liveStatus=text("Запуск лайва…",12,MUTED);root.addView(liveStatus);LinearLayout bar=row();
        Button back=button("Назад",false);back.setOnClickListener(v->toggleFullScreen());bar.addView(back);
        Button start=button("▶",true);start.setOnClickListener(v->startLive());bar.addView(start);
        Button cam=button("Камера",false);cam.setOnClickListener(v->{camera=1-camera;quality=getPreferences(0).getInt("quality."+camera,0);stopLive();startLive();});bar.addView(cam);
        Button audio=button("Звук",false);audio.setOnClickListener(v->{sound=!sound;if(live!=null)live.mute(!sound);audio.setText(sound?"Звук: вкл":"Звук: выкл");});bar.addView(audio);
        Button rec=button("Запись лайва",false);rec.setOnClickListener(v->togglePhoneRecording(rec));bar.addView(rec);
        Button snap=button("Кадр",false);snap.setOnClickListener(v->snapshot());bar.addView(snap);
        Button logs=button("Логи",false);logs.setOnClickListener(v->showLogs());bar.addView(logs);root.addView(bar);
    }
    private void startLive(){
        if(!s.connected()){toast("Сначала подключись к регистратору");return;}
        if(surface==null||!surface.getHolder().getSurface().isValid()){toast("Видеоповерхность ещё не готова");return;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);wantsLive=true;final long generation=++liveGeneration;final Surface target=surface.getHolder().getSurface();final int cam=camera,res=quality;
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
        if(phoneRecording!=null){stopPhoneRecording();button.setText("Запись лайва");return;}
        if(live==null){toast("Сначала запусти лайв");return;}
        try{phoneRecording=new PhoneRecording(this,live.width(),live.height(),live.sampleRate(),live.sps(),live.pps());button.setText("■ Сохранить лайв");toast("Запись лайва на телефон начата");}catch(Exception e){fail("Запись лайва",e);}
    }
    private void stopPhoneRecording(){PhoneRecording rec=phoneRecording;phoneRecording=null;if(rec!=null)s.worker.execute(()->{try{rec.finish();s.main.post(()->toast("Лайв сохранён в Download/Zem iBox Drive/Live"));}catch(Exception e){s.main.post(()->fail("Сохранение лайва",e));}});}
    private void snapshot(){
        if(live==null||surface==null){toast("Сначала запусти лайв");return;}
        Bitmap b=Bitmap.createBitmap(Math.max(1,surface.getWidth()),Math.max(1,surface.getHeight()),Bitmap.Config.ARGB_8888);
        PixelCopy.request(surface,b,result->{if(result!=PixelCopy.SUCCESS){b.recycle();fail("Снимок",new IOException("PixelCopy="+result));return;}
            async("Сохранение кадра",()->{File f=new File(getCacheDir(),"snapshot.jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}finally{b.recycle();}String name="Live-"+stamp()+".jpg";Uri uri=Library.publish(this,f,name,"image/jpeg","Live");Library.add(this,uri,name,"image/jpeg",f.length(),"live snapshot");f.delete();},()->toast("Кадр сохранён на телефон"));},s.main);
    }
    private void remotePage(){
        LinearLayout toolbar=row();cameraButtons(toolbar,()->{remote.clear();refreshFiles();});Button refresh=button("↻",false);refresh.setContentDescription("Обновить записи");refresh.setOnClickListener(v->refreshFiles());toolbar.addView(refresh);body.addView(toolbar);
        EditText find=input("Поиск по дате или имени",search);find.setSingleLine();find.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence a,int b,int c,int d){}public void onTextChanged(CharSequence a,int b,int c,int d){search=a.toString();renderRemote();}public void afterTextChanged(Editable e){}});body.addView(find);
        Spinner types=new Spinner(this);types.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Все видео","Обычные видео","Закреплённые видео","Фото"}));types.setSelection(filter);types.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){filter=pos;renderRemote();}public void onNothingSelected(AdapterView<?> p){}});body.addView(types);
        body.addView(text("Сначала новые записи · закреплённые отдельно",12,MUTED));
        fileList=new ListView(this);fileList.setDividerHeight(dp(6));body.addView(fileList,new LinearLayout.LayoutParams(-1,0,1));renderRemote();
        Button all=button("Скачать всю папку · новые → старые",true);all.setOnClickListener(v->downloadFolder());body.addView(all);
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
    private void renderRemote(){if(tab!=1||fileList==null)return;List<MediaItem> a=filtered();fileList.setAdapter(new Rows(a.size(),i->{MediaItem f=a.get(i);return (f.locked()?"◆ ":"")+f.date()+"\n"+f.name+" · "+TransferService.bytes(f.size)+(!f.photo()?" · "+f.duration+" с":"");}));fileList.setOnItemClickListener((p,v,pos,id)->fileActions(a.get(pos)));fileList.setEmptyView(null);}
    private void refreshFiles(){if(!s.connected()){toast("Сначала подключись к регистратору");return;}if(s.cardKnown()&&!s.cardPresent()){remote.clear();renderRemote();toast("Карты памяти нет. Лайв работает без неё");return;}int cam=camera;async("Список записей",()->{List<MediaItem> items=s.files(cam);s.main.post(()->{if(camera==cam){remote=items;renderRemote();if(items.isEmpty())toast("Файлов на карте нет");}});},null);}
    private void fileActions(MediaItem f){String[] choices=BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS?new String[]{"Смотреть","Скачать оригинал",f.locked()?"Снять закрепление":"Закрепить от перезаписи","Удалить с регистратора"}:new String[]{"Смотреть","Скачать оригинал",f.locked()?"Снять закрепление":"Закрепить от перезаписи"};new AlertDialog.Builder(this).setTitle(f.name).setItems(choices,(d,w)->{
        if(w==0){try{startActivity(new Intent(this,PlayerActivity.class).putExtra("uri",s.url(f.path).toString()).putExtra("mime",f.mime()).putExtra("name",f.name));}catch(Exception e){fail("Просмотр",e);}}
        if(w==1)download(f);
        if(w==2)command("FILE_LOCK",p("path",f.path,"status",f.locked()?"0":"1"),this::refreshFiles);
        if(w==3&&BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS)confirm("Удалить файл?",f.name+" будет удалён с карты регистратора.",()->command("FILES_DELETE",p("path_0",f.path),this::refreshFiles));
    }).show();}
    private void download(MediaItem f){try{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)){getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);}TransferService.enqueue(this,f);toast("Добавлено в очередь: "+f.name);}catch(Exception e){fail("Скачивание",e);}}
    private void localPage(){
        TextView path=text("Download / Zem iBox Drive\nFront, Rear → Обычные / Закреплённые",14,MUTED);body.addView(path);JSONArray a=Library.all(this);localCount=a.length();ListView list=new ListView(this);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));list.setAdapter(new Rows(a.length(),i->{JSONObject f=a.optJSONObject(i);return f.optString("name")+"\n"+TransferService.bytes(f.optLong("size"));}));
        list.setOnItemClickListener((p,v,pos,id)->{JSONObject f=a.optJSONObject(pos);new AlertDialog.Builder(this).setTitle(f.optString("name")).setItems(new String[]{"Открыть в плеере / галерее…","Поделиться"},(d,w)->{Uri uri=Uri.parse(f.optString("uri"));if(w==0)openLocal(uri,f.optString("mime"));else share(uri,f.optString("mime"));}).show();});
        if(a.length()==0)body.addView(text("Скачанные файлы появятся здесь.\nИми можно поделиться через меню Android.",15,MUTED));
        Button folder=button("Открыть Загрузки",false);folder.setOnClickListener(v->{try{startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));}catch(Exception e){fail("Загрузки",e);}});body.addView(folder);
    }
    private void settingsPage(){
        settingViews.clear();ScrollView scroll=new ScrollView(this);LinearLayout content=column();scroll.addView(content);body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        content.addView(text("Настройки регистратора",20,INK));content.addView(text(BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS?"Значения настроек подтверждаются ответом устройства.":"На время проверки удаление файлов и форматирование карты заблокированы.",13,MUTED));
        content.addView(loggingSwitch());
        addAction(content,"Обновить значения",()->async("Чтение настроек",()->{for(SettingDef def:SettingDef.ALL)try{if(tab!=3)break;CtpClient c=s.ctp;if(c==null)break;c.request(def.topic(),"GET",null,3);}catch(CtpClient.DeviceException e){if(e.code!=6)Diag.error("Read "+def.topic(),e);}catch(Exception e){Diag.error("Read "+def.topic(),e);if(!s.connected())break;}},null));
        addAction(content,"Разрешение передней камеры",()->videoResolution(0));addAction(content,"Разрешение задней камеры",()->videoResolution(1));
        for(SettingDef def:SettingDef.ALL){TextView t=text(def.title(),15,INK);t.setPadding(dp(12),dp(13),dp(12),dp(13));t.setBackground(shape(CARD,12));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,0);content.addView(t,lp);settingViews.put(def.topic(),t);t.setOnClickListener(v->editSetting(def));}
        addAction(content,"Синхронизировать дату и время с телефоном",()->command("DATE_TIME",p("date",new SimpleDateFormat("yyyyMMddHHmmss",Locale.ROOT).format(new Date())),()->toast("Дата и время отправлены")));
        addAction(content,"Память и прошивка",()->async("Сведения",()->{try{s.get("TF_CAP");}catch(Exception e){Diag.error("Capacity",e);}s.main.post(()->new AlertDialog.Builder(this).setTitle("Регистратор").setMessage(s.report()).setPositiveButton("Закрыть",null).show());},null));
        addAction(content,"Имя и пароль Wi-Fi регистратора",this::changeWifi);
        addAction(content,"Адрес подключения",this::addressDialog);
        addAction(content,"Перезагрузить регистратор",()->confirm("Перезагрузить iBOX?","Wi-Fi отключится до завершения перезагрузки.",()->command("RESET",null,()->{stopLive();s.disconnect();})));
        addAction(content,"Сбросить настройки регистратора",()->confirm("Сброс настроек","Будут восстановлены заводские настройки регистратора. Имя и пароль Wi-Fi могут измениться.",()->command("SYSTEM_DEFAULT",p("def","1"),null)));
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
        LinearLayout fields=column();EditText ssid=input("Имя Wi-Fi","iBOX_Flash_WiFi_Dual"),password=input("Новый пароль, не менее 8 символов","");password.setInputType(129);fields.addView(ssid);fields.addView(password);
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
        ScrollView panel=new ScrollView(this);panel.addView(content);new AlertDialog.Builder(this).setTitle("Диагностика Zem iBox Drive").setView(panel).setNegativeButton("Закрыть",null).show();
    }
    private Switch loggingSwitch(){Switch toggle=new Switch(this);toggle.setText("Запись логов");toggle.setTextColor(INK);toggle.setTextSize(15);toggle.setMinHeight(dp(48));toggle.setChecked(Diag.enabled());toggle.setOnCheckedChangeListener((button,checked)->{toggle.setEnabled(false);s.worker.execute(()->{try{Diag.setEnabled(checked);s.main.post(()->{toggle.setEnabled(true);clipboardParts=null;clipboardIndex=0;toast(checked?"Запись логов включена":"Запись логов выключена, сохранённые логи остаются");});}catch(Exception e){s.main.post(()->{toggle.setEnabled(true);fail("Запись логов",e);});}});});return toggle;}
    private void clearLogs(TextView preview){
        async("Очистка логов",Diag::clear,()->{clipboardParts=null;clipboardIndex=0;copyButton.setText("Скопировать полные логи");preview.setText(Diag.tail());android.content.ClipboardManager cb=getSystemService(android.content.ClipboardManager.class);if(cb.hasPrimaryClip()&&cb.getPrimaryClipDescription()!=null&&TextUtils.equals("Zem iBox Drive logs",cb.getPrimaryClipDescription().getLabel()))cb.clearPrimaryClip();toast(Diag.enabled()?"Логи очищены. Новые события продолжают записываться":"Логи очищены. Запись выключена");});
    }
    private void openLocal(Uri uri,String mime){try{startActivity(MediaOpen.chooser(uri,mime));}catch(ActivityNotFoundException e){fail("Открыть файл",new IOException("На телефоне нет приложения для этого формата",e));}}
    private void copyLogs(){
        try {
            if(clipboardParts==null||clipboardIndex>=clipboardParts.size()){clipboardParts=Diag.copyParts(s.report()+"\nUSER NOTE: "+note);clipboardIndex=0;}
            String value="Zem iBox Drive · часть "+(clipboardIndex+1)+"/"+clipboardParts.size()+"\n"+clipboardParts.get(clipboardIndex);
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Zem iBox Drive logs",value));clipboardIndex++;
            copyButton.setText(clipboardIndex<clipboardParts.size()?"Скопировать часть "+(clipboardIndex+1)+"/"+clipboardParts.size():"Скопировано всё · собрать свежие");toast("Скопирована часть "+clipboardIndex+"/"+clipboardParts.size());
        }catch(Exception e){fail("Копирование логов",e);}
    }
    private void command(String topic,JSONObject p,Runnable after){async(topic,()->s.put(topic,p),()->{stateChanged();if(after!=null)after.run();});}
    private boolean requireCard(){if(!s.connected()){toast("Сначала подключись к регистратору");return false;}if(s.cardKnown()&&!s.cardPresent()){toast("Для этой функции нужна microSD. Лайв и кадр на телефон работают без неё");return false;}return true;}
    private void async(String name,Work work,Runnable after){s.worker.execute(()->{try{work.run();if(after!=null)s.main.post(after);}catch(Exception e){s.main.post(()->fail(name,e));}});}
    private void fail(String context,Throwable e){Diag.error(context,e);new AlertDialog.Builder(this).setTitle(context).setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("Понятно",null).setNeutralButton("Логи",(d,w)->showLogs()).show();}
    private void confirm(String title,String message,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Подтвердить",(d,w)->action.run()).show();}
    private void share(Uri uri,String mime){try{Intent send=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Zem iBox Drive",uri));startActivity(Intent.createChooser(send,"Поделиться записью"));}catch(Exception e){fail("Отправка",e);}}
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
    private Button button(String label,boolean selected){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);b.setMaxLines(2);b.setTextColor(selected?BG:INK);b.setBackground(shape(selected?ACCENT:CARD,12));b.setPadding(dp(10),dp(3),dp(10),dp(3));b.setStateListAnimator(null);return b;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();}
    private interface RowText {String get(int i);}
    private final class Rows extends BaseAdapter {private final int count;private final RowText text;Rows(int n,RowText t){count=n;text=t;}public int getCount(){return count;}public Object getItem(int p){return p;}public long getItemId(int p){return p;}public View getView(int p,View recycled,android.view.ViewGroup parent){TextView t=recycled instanceof TextView?(TextView)recycled:MainActivity.this.text("",14,INK);t.setPadding(dp(12),dp(12),dp(12),dp(12));t.setBackground(shape(CARD,12));t.setText(text.get(p));return t;}}
    public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    protected void onPause(){super.onPause();stopLive();}
    protected void onDestroy(){s.unlisten(observer);stopLive();super.onDestroy();}
    protected void onSaveInstanceState(Bundle b){b.putInt("tab",tab);b.putInt("camera",camera);b.putInt("quality",quality);super.onSaveInstanceState(b);}
}
