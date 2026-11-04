package io.zem.ibox;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import org.videolan.libvlc.interfaces.IMedia;

public final class PlayerActivity extends Activity {
    private LibVLC engine;private MediaPlayer player;private WifiMediaProxy proxy;
    private android.content.res.AssetFileDescriptor mediaFile;
    private JieliSession session;private CtpClient sourceSession;
    private boolean deviceSource,sourceAvailable=true;
    private final List<View> sourceActions=new ArrayList<>();
    private final Runnable sourceObserver=this::updateSourceAvailability;
    private Uri uri;private String mime;private TextView title,status,time;
    private Button play,previousVideo,nextVideo,queueButton,speedButton;
    private static final float[] SPEEDS={.25f,.5f,.75f,1f,1.25f,1.5f,2f,3f,4f};
    private float playbackRate=1f;
    private boolean softwareDecoder,automaticFallback,haveVideoFrame,frameFailureShown,pauseAfterOpen;
    private long startupWaitMs;private boolean launchVlc,waitingStartup;
    private long textureTimestamp,lastFrameAt,frameWaitMs,lastTickAt,lastStatsAt,playbackStartedAt;
    private float buffering=100f;
    private double videoAspect=16.0/9;
    private LinearLayout playerArea;private ScrollView controlScroll;private VLCVideoLayout video;
    private SeekBar seek;private boolean dragging,resume,visible,destroyed,ended;private Bitmap photo;
    private volatile HttpURLConnection photoConnection;
    private List<PlaybackQueue.Entry> videos=Collections.emptyList();
    private int videoIndex;private long mediaGeneration,restorePosition;
    private AlertDialog sourceDialog;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){if(player==null||destroyed)return;
        long length=player.getLength(),now=Math.max(0,player.getTime());
        if(!dragging)seek.setProgress(length>0?(int)Math.min(1000,now*1000/length):0);
        seek.setEnabled(sourceAvailable&&player.isSeekable());time.setText(clock(now)+" / "+clock(length));
        play.setEnabled(sourceAvailable);play.setContentDescription(player.isPlaying()?"Пауза":ended?"Повторить":"Смотреть");Ui.setIcon(play,player.isPlaying()?"pause":"play");watchVideo();handler.postDelayed(this,400);
    }};
    public void onCreate(Bundle saved){
        super.onCreate(saved);launchVlc=saved==null&&getIntent().getBooleanExtra("openVlc",false);session=RoadApp.instance.session;softwareDecoder=getSharedPreferences("playback",0).getBoolean("software",false);
        try{
            String raw=getIntent().getStringExtra("uri");if(raw==null)throw new IOException("Не выбран файл");
            PlaybackQueue queue=PlaybackQueue.read(this,getIntent(),session);
            if(queue!=null){videos=queue.items;videoIndex=saved==null?queue.selected:Math.max(0,Math.min(videos.size()-1,saved.getInt("videoIndex",queue.selected)));raw=videos.get(videoIndex).uri;}
            uri=Uri.parse(raw);if(!Set.of("http","content").contains(uri.getScheme()))throw new IOException("Этот источник не поддерживается");
            deviceSource="http".equals(uri.getScheme());
            if(deviceSource){session.url(raw);sourceSession=session.ctp;if(!session.isCurrent(sourceSession))throw new IOException("Подключите видеорегистратор для просмотра записи");if(!session.cardPresent())throw new IOException("Для просмотра записи нужна microSD в видеорегистраторе");}
            mime=queue==null?getIntent().getStringExtra("mime"):videos.get(videoIndex).mime;
            if(videos.isEmpty()&&(mime==null||!mime.startsWith("image/")))videos=Collections.singletonList(new PlaybackQueue.Entry(raw,getIntent().getStringExtra("name"),mime));
            restorePosition=saved==null?0:saved.getLong("position",0);if(saved!=null){float savedRate=saved.getFloat("speed",1f);for(float rate:SPEEDS)if(rate==savedRate)playbackRate=rate;}
        }catch(Exception e){error(e.getMessage());return;}
        LinearLayout root=column();root.setBackground(Ui.background());
        root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(dp(16)+b.left,b.top+dp(6),dp(16)+b.right,b.bottom+dp(8));return i;});setContentView(root);
        LinearLayout header=row();Button back=Ui.iconButton(this,"back","Назад к записям");back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        title=text(videos.isEmpty()?getIntent().getStringExtra("name"):videos.get(videoIndex).name,16);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
        status=text(deviceSource?"Просмотр по Wi-Fi · без скачивания":"Файл на телефоне",12);root.addView(status);if(deviceSource)session.listen(sourceObserver);
        if(mime!=null&&mime.startsWith("image/")){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));loadPhoto(image);return;}
        playerArea=new LinearLayout(this);root.addView(playerArea,new LinearLayout.LayoutParams(-1,0,1));video=new VLCVideoLayout(this){protected void onAttachedToWindow(){super.onAttachedToWindow();post(PlayerActivity.this::arrangePlayer);}};playerArea.addView(video);
        LinearLayout controls=column();controlScroll=new ScrollView(this){protected void onMeasure(int w,int h){if(playerArea.getOrientation()==LinearLayout.HORIZONTAL)super.onMeasure(w,h);else super.onMeasure(w,MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(h),Math.max(dp(270),(int)(getResources().getDisplayMetrics().heightPixels*.55f))),MeasureSpec.AT_MOST));}};
        controlScroll.setFillViewport(false);controlScroll.addView(controls);playerArea.addView(controlScroll);playerArea.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)arrangePlayer();});arrangePlayer();
        LinearLayout navigation=row();previousVideo=Ui.iconButton(this,"previous","Предыдущее видео");previousVideo.setOnClickListener(v->switchVideo(videoIndex-1));navigation.addView(previousVideo,new LinearLayout.LayoutParams(dp(48),dp(48)));
        queueButton=button("");queueButton.setContentDescription("Выбрать видео из списка");queueButton.setOnClickListener(v->showQueue());navigation.addView(queueButton,new LinearLayout.LayoutParams(0,-2,1));
        nextVideo=Ui.iconButton(this,"next","Следующее видео");nextVideo.setOnClickListener(v->switchVideo(videoIndex+1));navigation.addView(nextVideo,new LinearLayout.LayoutParams(dp(48),dp(48)));controls.addView(navigation);
        time=text("Подготовка видео…",12);controls.addView(time);seek=new SeekBar(this);seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));seek.setMax(1000);controls.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){dragging=true;}public void onProgressChanged(SeekBar b,int p,boolean user){}public void onStopTrackingTouch(SeekBar b){if(player!=null&&canReadSource()&&player.isSeekable()){player.setPosition(b.getProgress()/1000f);resetFrameWait();}dragging=false;}});
        LinearLayout actions=row();Button previous=button("−10 с");sourceActions.add(previous);previous.setOnClickListener(v->skip(-10000));actions.addView(previous,new LinearLayout.LayoutParams(0,-1,1));
        play=Ui.iconButton(this,"play","Смотреть / пауза");Ui.select(play,true);Ui.setIcon(play,"play");play.setOnClickListener(v->{if(player!=null&&canReadSource()){if(player.isPlaying()){waitingStartup=false;player.pause();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}else{if(ended){restorePosition=0;loadVideo();}else{waitingStartup=true;startupWaitMs=0;resetFrameWait();player.play();}}}});actions.addView(play,new LinearLayout.LayoutParams(0,-1,1));
        Button next=button("+10 с");sourceActions.add(next);next.setOnClickListener(v->skip(10000));actions.addView(next,new LinearLayout.LayoutParams(0,-1,1));controls.addView(actions);
        LinearLayout more=row();Button rotate=Ui.iconButton(this,"rotate","Повернуть экран");rotate.setOnClickListener(v->setRequestedOrientation(getResources().getConfiguration().orientation==2?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));more.addView(rotate,new LinearLayout.LayoutParams(dp(48),dp(48)));
        speedButton=button("");updateSpeedLabel();sourceActions.add(speedButton);speedButton.setOnClickListener(v->showSpeed());more.addView(speedButton,new LinearLayout.LayoutParams(0,-2,1));
        Button external=Ui.iconButton(this,"settings","Параметры просмотра и совместимый режим");sourceActions.add(external);external.setOnClickListener(this::showPlaybackOptions);more.addView(external,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout.LayoutParams moreParams=new LinearLayout.LayoutParams(-1,-2);moreParams.topMargin=dp(8);controls.addView(more,moreParams);
        try{engine=new LibVLC(this,new ArrayList<>(Arrays.asList("--no-video-title-show")));player=new MediaPlayer(engine);player.setUseOrientationFromBounds(true);player.attachViews(video,null,false,true);player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);loadVideo();handler.post(tick);}catch(Exception|LinkageError e){Diag.error("Player initialization",e);error("Проигрыватель не запустился: "+e.getMessage());}
    }
    private void arrangePlayer(){if(playerArea==null)return;boolean landscape=getResources().getConfiguration().orientation==2;playerArea.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        int width=playerArea.getWidth()>0?playerArea.getWidth():getResources().getDisplayMetrics().widthPixels-dp(32);
        int available=playerArea.getHeight()>0?playerArea.getHeight():getResources().getDisplayMetrics().heightPixels-dp(160);
        int controlHeight=controlScroll.getMeasuredHeight()>0?controlScroll.getMeasuredHeight():dp(270);
        int height=Math.min((int)Math.round(width/videoAspect),Math.max(dp(96),available-controlHeight-dp(8)));
        video.setLayoutParams(landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,height));
        LinearLayout.LayoutParams controls=landscape?new LinearLayout.LayoutParams(Math.min(dp(320),Math.max(dp(260),(int)(getResources().getDisplayMetrics().widthPixels*.4f))),-1):new LinearLayout.LayoutParams(-1,-2);if(landscape)controls.leftMargin=dp(12);controlScroll.setLayoutParams(controls);
    }
    public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);arrangePlayer();if(player!=null)player.updateVideoSurfaces();}
    private void switchVideo(int index){if(index<0||index>=videos.size()||index==videoIndex||!canReadSource())return;dismissSourceDialog();videoIndex=index;restorePosition=0;automaticFallback=false;pauseAfterOpen=false;loadVideo();}
    private void loadVideo(){
        if(player==null||!canReadSource())return;long generation=++mediaGeneration;player.setEventListener(null);if(proxy!=null){proxy.close();proxy=null;}player.stop();closeMediaFile();ended=false;resume=false;dragging=false;haveVideoFrame=false;frameFailureShown=false;startupWaitMs=0;waitingStartup=true;videoAspect=16.0/9;arrangePlayer();TextureView oldOutput=texture(video);textureTimestamp=oldOutput!=null&&oldOutput.isAvailable()&&oldOutput.getSurfaceTexture()!=null?oldOutput.getSurfaceTexture().getTimestamp():0;playbackStartedAt=0;buffering=0;resetFrameWait();lastStatsAt=0;
        PlaybackQueue.Entry entry=videos.get(videoIndex);uri=Uri.parse(entry.uri);mime=entry.mime;title.setText(entry.label());getIntent().putExtra("uri",entry.uri).putExtra("name",entry.name).putExtra("mime",mime);time.setText("Подготовка видео…");seek.setProgress(0);seek.setEnabled(false);updateNavigation();status.setText(deviceSource?"Открываем запись по Wi-Fi…":"Открываем видео на телефоне…");
        try{
            Uri input=uri;if(deviceSource){android.net.Network wifi=session.network;java.net.URL path=session.url(entry.uri);if(!canReadSource())throw new IOException("Подключение изменилось. Откройте запись заново");proxy=new WifiMediaProxy(wifi,path);input=Uri.parse(proxy.address());}
            Media media;if(deviceSource)media=new Media(engine,input);else{mediaFile=getContentResolver().openAssetFileDescriptor(uri,"r");if(mediaFile==null)throw new IOException("Нет доступа к видео на телефоне");media=mediaFile.getStartOffset()==0?new Media(engine,mediaFile.getFileDescriptor()):new Media(engine,mediaFile);}media.setHWDecoderEnabled(!softwareDecoder,false);
            // Keep hardware decoding, but compose frames through TextureView rather than opaque direct rendering.
            if(!softwareDecoder)media.addOption(":no-mediacodec-dr");
            media.addOption(":network-caching=1200");media.addOption(":http-reconnect");player.setMedia(media);media.release();
            player.setEventListener(event->{if(destroyed||!sourceAvailable||generation!=mediaGeneration)return;
                if(event.type==MediaPlayer.Event.EncounteredError){Diag.log("PLAY","Native error file="+entry.name+" software="+softwareDecoder);if(!trySoftware("native error")){frameFailureShown=true;waitingStartup=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText("Ошибка просмотра · повторите открытие через параметры");error("Не удалось воспроизвести запись. В параметрах просмотра можно повторить открытие или выбрать другой режим. Также можно скачать оригинал из архива.");}}
                else if(event.type==MediaPlayer.Event.Buffering){buffering=event.getBuffering();if(!frameFailureShown)status.setText(buffering<100?"Загрузка видео · "+Math.round(buffering)+"%":haveVideoFrame?playingLabel():"Ожидаем изображение…");}
                else if(event.type==MediaPlayer.Event.SeekableChanged){resetFrameWait();}
                else if(event.type==MediaPlayer.Event.Playing){applySpeed(playbackRate,false);ended=false;if(playbackStartedAt==0)playbackStartedAt=SystemClock.elapsedRealtime();resetFrameWait();if(visible)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(restorePosition>0){player.setTime(restorePosition);restorePosition=0;}if(pauseAfterOpen||!visible){pauseAfterOpen=false;player.pause();resume=!visible;}status.setText(haveVideoFrame?playingLabel():"Ожидаем изображение…");Diag.log("PLAY","Playing file="+entry.name+" length="+player.getLength()+" tracks="+player.getVideoTracksCount()+" software="+softwareDecoder);}
                else if(event.type==MediaPlayer.Event.EndReached){ended=true;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText("Просмотр завершён");play.setContentDescription("Повторить");if(visible&&videoIndex+1<videos.size())handler.post(()->{if(generation==mediaGeneration&&canReadSource())switchVideo(videoIndex+1);});}
            });Diag.log("PLAY","Open file="+entry.name+" source="+(deviceSource?"Wi-Fi":"phone")+" software="+softwareDecoder+" speed="+playbackRate+" restore="+restorePosition);if(launchVlc){launchVlc=false;waitingStartup=false;externalVlc();}else player.play();updateSourceAvailability();
        }catch(Exception|LinkageError e){Diag.error("Open video",e);error("Не удалось открыть запись: "+e.getMessage());}
    }
    private void showSpeed(){
        if(player==null||!canReadSource())return;long generation=mediaGeneration;int selected=3;String[] labels=new String[SPEEDS.length];
        for(int i=0;i<SPEEDS.length;i++){labels[i]=speedLabel(SPEEDS[i])+(SPEEDS[i]==1f?" · обычная":"");if(SPEEDS[i]==playbackRate)selected=i;}
        sourceDialog=new AlertDialog.Builder(this).setTitle("Скорость видео").setSingleChoiceItems(labels,selected,(d,index)->{d.dismiss();if(generation!=mediaGeneration||!canReadSource())return;applySpeed(SPEEDS[index],true);}).setNegativeButton("Закрыть",null).show();
    }
    private void applySpeed(float requested,boolean notify){resetFrameWait();player.setRate(requested);float actual=player.getRate();playbackRate=actual>0?actual:1f;updateSpeedLabel();if(notify&&Math.abs(playbackRate-requested)>.01f)Toast.makeText(this,"Для этой записи выбранная скорость недоступна",Toast.LENGTH_SHORT).show();}
    private static String speedLabel(float rate){return java.math.BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString().replace('.',',')+"×";}
    private void updateSpeedLabel(){if(speedButton!=null){speedButton.setText("Скорость\n"+speedLabel(playbackRate));speedButton.setContentDescription("Скорость видео: "+speedLabel(playbackRate));}}
    private String playingLabel(){return "Внутренний плеер · "+(deviceSource?"По Wi-Fi · без скачивания":"Запись на телефоне")+(softwareDecoder?" · совместимый режим":"");}
    private TextureView texture(View view){if(view instanceof TextureView t)return t;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){TextureView found=texture(group.getChildAt(i));if(found!=null)return found;}return null;}
    private void resetFrameWait(){lastTickAt=SystemClock.elapsedRealtime();frameWaitMs=0;lastFrameAt=lastTickAt;}
    private void watchVideo(){
        long now=SystemClock.elapsedRealtime(),delta=Math.min(800,Math.max(0,now-lastTickAt));lastTickAt=now;
        TextureView output=texture(video);long timestamp=output!=null&&output.isAvailable()&&output.getSurfaceTexture()!=null?output.getSurfaceTexture().getTimestamp():0;
        if(timestamp!=0&&timestamp!=textureTimestamp){textureTimestamp=timestamp;lastFrameAt=now;frameWaitMs=0;if(!haveVideoFrame){haveVideoFrame=true;IMedia.VideoTrack track=player.getCurrentVideoTrack();if(track!=null&&track.width>0&&track.height>0){videoAspect=track.width/(double)track.height*(track.sarNum>0&&track.sarDen>0?track.sarNum/(double)track.sarDen:1);arrangePlayer();}status.setText(playingLabel());Diag.log("PLAY","First video frame after="+(now-playbackStartedAt)+"ms");}}
        if(waitingStartup&&visible&&!haveVideoFrame&&!frameFailureShown&&!ended&&!pauseAfterOpen){startupWaitMs+=delta;
            if(startupWaitMs>=20000){frameFailureShown=true;mediaGeneration++;if(proxy!=null){proxy.close();proxy=null;}player.setEventListener(null);player.stop();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText("Не удалось загрузить видео · откройте параметры просмотра");sourceDialog=new AlertDialog.Builder(this).setTitle("Видео не загрузилось").setMessage("Не удалось получить изображение за 20 секунд. Повторите открытие или скачайте оригинал из архива для просмотра на телефоне.").setPositiveButton("Повторить",(d,w)->loadVideo()).setNeutralButton("Открыть через VLC",(d,w)->externalVlc()).setNegativeButton("Закрыть",null).show();Diag.log("PLAY","Startup deadline exceeded");return;}
        }
        boolean advancing=visible&&player.isPlaying()&&buffering>=99&&!dragging&&player.getTime()>0&&player.getVideoTracksCount()>0;
        if(advancing&&now-lastFrameAt>1000)frameWaitMs+=delta;else if(!advancing)resetFrameWait();
        if(!frameFailureShown&&frameWaitMs>(haveVideoFrame?8000:5000)){
            if(trySoftware("video frames stalled"))return;
            frameFailureShown=true;status.setText("Изображение не поступает · откройте параметры просмотра");Diag.log("PLAY","Frame output stalled in compatible mode");
        }
        if(visible&&now-lastStatsAt>5000){lastStatsAt=now;IMedia media=player.getMedia();try{IMedia.Stats stats=media==null?null:media.getStats();Diag.log("PLAY STATS","position="+player.getTime()+" length="+player.getLength()+" buffering="+Math.round(buffering)+" software="+softwareDecoder+" frame="+textureTimestamp+(stats==null?"":" decoded="+stats.decodedVideo+" displayed="+stats.displayedPictures+" lost="+stats.lostPictures)+(proxy==null?"":" "+proxy.stats()));}finally{if(media!=null)media.release();}}
    }
    private boolean trySoftware(String reason){if(softwareDecoder||automaticFallback||!visible||!canReadSource())return false;automaticFallback=true;Diag.log("PLAY","Retry in compatible mode: "+reason);restartDecoder(true);pauseAfterOpen=false;Toast.makeText(this,"Пробуем совместимый режим просмотра",Toast.LENGTH_SHORT).show();return true;}
    private void restartDecoder(boolean software){if(player==null||!canReadSource())return;restorePosition=Math.max(0,player.getTime());pauseAfterOpen=!player.isPlaying()&&playbackStartedAt>0;softwareDecoder=software;loadVideo();}
    private void showPlaybackOptions(View anchor){if(!canReadSource())return;PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add(0,1,0,softwareDecoder?"Совместимый режим: включён":"Совместимый режим: выключен");menu.getMenu().add(0,2,1,"Открыть запись заново");menu.getMenu().add(0,3,2,"Открыть через VLC");menu.getMenu().add(0,4,3,"Открыть в другом плеере");menu.setOnMenuItemClickListener(item->{if(!canReadSource())return true;if(item.getItemId()==1){automaticFallback=false;getSharedPreferences("playback",0).edit().putBoolean("software",!softwareDecoder).apply();restartDecoder(!softwareDecoder);}else if(item.getItemId()==2){restorePosition=Math.max(0,player.getTime());pauseAfterOpen=false;loadVideo();}else if(item.getItemId()==3)externalVlc();else external();return true;});menu.show();}
    private void closeMediaFile(){if(mediaFile!=null){try{mediaFile.close();}catch(IOException ignored){}mediaFile=null;}}
    private void updateNavigation(){if(queueButton==null)return;queueButton.setText((videoIndex+1)+" из "+videos.size()+" · Список");queueButton.setEnabled(canReadSource()&&videos.size()>1);previousVideo.setEnabled(canReadSource()&&videoIndex>0);nextVideo.setEnabled(canReadSource()&&videoIndex+1<videos.size());previousVideo.setAlpha(previousVideo.isEnabled()?1f:.35f);nextVideo.setAlpha(nextVideo.isEnabled()?1f:.35f);}
    private void showQueue(){if(!canReadSource()||videos.size()<2)return;String[] names=new String[videos.size()];for(int i=0;i<names.length;i++)names[i]=(i+1)+". "+videos.get(i).label();sourceDialog=new AlertDialog.Builder(this).setTitle("Видео в этом списке").setSingleChoiceItems(names,videoIndex,(d,index)->{d.dismiss();switchVideo(index);}).setNegativeButton("Закрыть",null).show();}
    private void dismissSourceDialog(){if(sourceDialog!=null){sourceDialog.dismiss();sourceDialog=null;}}
    private void loadPhoto(ImageView image){new Thread(()->{HttpURLConnection conn=null;try{InputStream stream;if("content".equals(uri.getScheme()))stream=getContentResolver().openInputStream(uri);else{conn=session.http(sourceSession,uri.toString());photoConnection=conn;if(conn.getResponseCode()!=200)throw new IOException("Фото недоступно");stream=conn.getInputStream();}byte[] data;try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("Нет доступа к фото");byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>24*1024*1024)throw new IOException("Фото слишком большое");out.write(b,0,n);}data=out.toByteArray();}BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);options.inSampleSize=1;while(options.outWidth/options.inSampleSize>2560||options.outHeight/options.inSampleSize>2560)options.inSampleSize*=2;options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);if(bitmap==null)throw new IOException("Неизвестный формат фото");handler.post(()->{if(destroyed||!canReadSource())bitmap.recycle();else{photo=bitmap;image.setImageBitmap(bitmap);}});}catch(Exception e){Diag.error("Photo playback",e);handler.post(()->{if(canReadSource())error(e.getMessage());});}finally{if(conn!=null)conn.disconnect();}},"Photo viewer").start();}
    private boolean canReadSource(){return !deviceSource||sourceAvailable&&session.isCurrent(sourceSession)&&session.cardPresent();}
    private void updateSourceAvailability(){if(!deviceSource||destroyed||!sourceAvailable||canReadSource())return;sourceAvailable=false;resume=false;mediaGeneration++;dismissSourceDialog();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(proxy!=null){proxy.close();proxy=null;}if(player!=null)player.stop();if(photoConnection!=null)photoConnection.disconnect();if(play!=null)play.setEnabled(false);if(seek!=null)seek.setEnabled(false);for(View action:sourceActions)action.setEnabled(false);updateNavigation();if(status!=null)status.setText(session.isCurrent(sourceSession)?"microSD извлечена. Вернитесь в архив после установки карты":"Связь с видеорегистратором потеряна. Подключитесь и откройте запись заново");}
    private void skip(long amount){if(player!=null&&canReadSource()&&player.isSeekable()){player.setTime(Math.max(0,Math.min(player.getLength(),player.getTime()+amount)));resetFrameWait();}}
    private void externalVlc(){if(!canReadSource())return;try{Uri target=uri;if(deviceSource){if(proxy==null)proxy=new WifiMediaProxy(session.network,session.url(uri.toString()));target=Uri.parse(proxy.address());}if(player!=null)player.pause();waitingStartup=false;startActivity(MediaOpen.vlc(target,mime));status.setText("Открыто во внешнем VLC · Смотреть вернёт внутренний плеер");}catch(ActivityNotFoundException e){error("VLC не установлен. Установите VLC или выберите внутренний плеер");}catch(Exception e){error("Не удалось открыть VLC: "+e.getMessage());}}
    private void external(){if(!canReadSource()){updateSourceAvailability();return;}try{startActivity(MediaOpen.chooser(uri,mime));}catch(Exception e){error("Не найден проигрыватель для этого файла");}}
    private void error(String message){if(!isFinishing()&&!destroyed)new AlertDialog.Builder(this).setTitle("Просмотр записи").setMessage(message).setPositiveButton("Закрыть",(d,w)->{if(player==null)finish();}).show();}
    private static String clock(long ms){long s=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){return Ui.text(this,s,size,size<=12?Ui.MUTED:Ui.INK);}
    private Button button(String label){return Ui.button(this,label,false);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);android.graphics.drawable.GradientDrawable gap=new android.graphics.drawable.GradientDrawable();gap.setSize(dp(8),dp(8));gap.setColor(Color.TRANSPARENT);l.setDividerDrawable(gap);l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    protected void onResume(){super.onResume();visible=true;updateSourceAvailability();if(resume&&player!=null&&canReadSource()){player.play();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);resume=false;}}
    protected void onPause(){visible=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(player!=null){resume=player.isPlaying();player.pause();}if(isFinishing())releasePlayer();super.onPause();}
    protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("videoIndex",videoIndex);out.putFloat("speed",playbackRate);if(player!=null)out.putLong("position",Math.max(0,player.getTime()));}
    private void releasePlayer(){mediaGeneration++;if(proxy!=null){proxy.close();proxy=null;}if(player!=null){player.setEventListener(null);player.stop();player.detachViews();player.release();player=null;}closeMediaFile();if(engine!=null){engine.release();engine=null;}}
    protected void onDestroy(){destroyed=true;mediaGeneration++;dismissSourceDialog();if(session!=null)session.unlisten(sourceObserver);handler.removeCallbacksAndMessages(null);if(proxy!=null)proxy.close();if(photoConnection!=null)photoConnection.disconnect();releasePlayer();if(photo!=null)photo.recycle();super.onDestroy();}
}
