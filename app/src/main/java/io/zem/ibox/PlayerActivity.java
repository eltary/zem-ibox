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

public final class PlayerActivity extends Activity {
    private LibVLC engine;private MediaPlayer player;private WifiMediaProxy proxy;
    private Uri uri;private String mime;private TextView status,time;private Button play;
    private SeekBar seek;private boolean dragging,resume,visible,destroyed,ended;private Bitmap photo;
    private volatile HttpURLConnection photoConnection;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){if(player!=null&&!destroyed){long length=player.getLength(),now=Math.max(0,player.getTime());if(!dragging)seek.setProgress(length>0?(int)Math.min(1000,now*1000/length):0);seek.setEnabled(player.isSeekable());time.setText(clock(now)+" / "+clock(length));play.setText(player.isPlaying()?"Ⅱ Пауза":ended?"▶ Повторить":"▶ Смотреть");handler.postDelayed(this,400);}}};
    public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{String raw=getIntent().getStringExtra("uri");if(raw==null)throw new IOException("Не выбран файл");uri=Uri.parse(raw);if(!Set.of("http","content").contains(uri.getScheme()))throw new IOException("Этот источник не поддерживается");if("http".equals(uri.getScheme()))RoadApp.instance.session.url(raw);}catch(Exception e){error(e.getMessage());return;}
        mime=getIntent().getStringExtra("mime");
        LinearLayout root=column();root.setBackgroundColor(MainActivity.BG);root.setPadding(dp(12),0,dp(12),0);
        root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(dp(12)+b.left,b.top,dp(12)+b.right,b.bottom);return i;});setContentView(root);
        LinearLayout header=row();Button back=button("‹");back.setContentDescription("Назад к записям");back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView title=text(getIntent().getStringExtra("name"),16);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
        status=text("http".equals(uri.getScheme())?"Просмотр по Wi-Fi · без скачивания":"Файл на телефоне",12);root.addView(status);
        if(mime!=null&&mime.startsWith("image/")){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));loadPhoto(image);return;}
        VLCVideoLayout video=new VLCVideoLayout(this);root.addView(video,new LinearLayout.LayoutParams(-1,0,1));
        time=text("Подготовка видео…",12);root.addView(time);seek=new SeekBar(this);seek.setMax(1000);root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){dragging=true;}public void onProgressChanged(SeekBar b,int p,boolean user){}public void onStopTrackingTouch(SeekBar b){if(player!=null&&player.isSeekable())player.setPosition(b.getProgress()/1000f);dragging=false;}});
        LinearLayout actions=row();Button previous=button("−10 с");previous.setOnClickListener(v->skip(-10000));actions.addView(previous,new LinearLayout.LayoutParams(0,dp(48),1));play=button("▶ Смотреть");play.setOnClickListener(v->{if(player!=null){if(player.isPlaying()){player.pause();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}else{if(player.getLength()>0&&player.getTime()>=player.getLength()-200)player.setTime(0);player.play();}}});actions.addView(play,new LinearLayout.LayoutParams(0,dp(48),1));Button next=button("+10 с");next.setOnClickListener(v->skip(10000));actions.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        LinearLayout more=row();Button rotate=button("Повернуть");rotate.setOnClickListener(v->setRequestedOrientation(getResources().getConfiguration().orientation==2?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));more.addView(rotate,new LinearLayout.LayoutParams(0,dp(48),1));Button speed=button("Скорость");speed.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Скорость видео").setItems(new String[]{"0.5×","1×","1.5×","2×"},(d,n)->{if(player!=null)player.setRate(new float[]{.5f,1f,1.5f,2f}[n]);}).show());more.addView(speed,new LinearLayout.LayoutParams(0,dp(48),1));Button external=button("Открыть в…");external.setOnClickListener(v->external());more.addView(external,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams moreParams=new LinearLayout.LayoutParams(-1,-2);moreParams.topMargin=dp(8);root.addView(more,moreParams);
        try{
            engine=new LibVLC(this,new ArrayList<>(Arrays.asList("--no-video-title-show","--no-stats")));player=new MediaPlayer(engine);player.attachViews(video,null,false,false);
            Uri input=uri;if("http".equals(uri.getScheme())){proxy=new WifiMediaProxy(RoadApp.instance.session.network,RoadApp.instance.session.url(uri.toString()));input=Uri.parse(proxy.address());}
            Media media=new Media(engine,input);media.setHWDecoderEnabled(true,false);media.addOption(":network-caching=700");media.addOption(":http-reconnect");player.setMedia(media);media.release();
            player.setEventListener(event->{if(destroyed)return;if(event.type==MediaPlayer.Event.EncounteredError){status.setText("Не удалось открыть видео");error("Не удалось воспроизвести запись. Проверьте Wi-Fi и попробуйте скачать оригинал из архива.");}else if(event.type==MediaPlayer.Event.Playing){ended=false;if(visible)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText("http".equals(uri.getScheme())?"По Wi-Fi · встроенный VLC":"Встроенный VLC");}else if(event.type==MediaPlayer.Event.EndReached){ended=true;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText("Просмотр завершён");play.setText("▶ Повторить");}});
            player.play();handler.post(tick);
        }catch(Exception|LinkageError e){Diag.error("Player initialization",e);error("Проигрыватель не запустился: "+e.getMessage());}
    }
    private void loadPhoto(ImageView image){new Thread(()->{HttpURLConnection conn=null;try{InputStream stream;if("content".equals(uri.getScheme()))stream=getContentResolver().openInputStream(uri);else{conn=RoadApp.instance.session.http(uri.toString());photoConnection=conn;if(conn.getResponseCode()!=200)throw new IOException("Фото недоступно");stream=conn.getInputStream();}byte[] data;try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("Нет доступа к фото");byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>24*1024*1024)throw new IOException("Фото слишком большое");out.write(b,0,n);}data=out.toByteArray();}BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);options.inSampleSize=1;while(options.outWidth/options.inSampleSize>2560||options.outHeight/options.inSampleSize>2560)options.inSampleSize*=2;options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);if(bitmap==null)throw new IOException("Неизвестный формат фото");handler.post(()->{if(destroyed)bitmap.recycle();else{photo=bitmap;image.setImageBitmap(bitmap);}});}catch(Exception e){Diag.error("Photo playback",e);handler.post(()->error(e.getMessage()));}finally{if(conn!=null)conn.disconnect();}},"Photo viewer").start();}
    private void skip(long amount){if(player!=null&&player.isSeekable())player.setTime(Math.max(0,Math.min(player.getLength(),player.getTime()+amount)));}
    private void external(){try{startActivity(MediaOpen.chooser(uri,mime));}catch(Exception e){error("Не найден проигрыватель для этого файла");}}
    private void error(String message){if(!isFinishing()&&!destroyed)new AlertDialog.Builder(this).setTitle("Просмотр записи").setMessage(message).setPositiveButton("Закрыть",null).show();}
    private static String clock(long ms){long s=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(MainActivity.INK);t.setTextSize(size);t.setPadding(0,dp(8),0,dp(8));return t;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextColor(MainActivity.INK);b.setAllCaps(false);b.setTextSize(13);b.setMinimumWidth(0);b.setMinWidth(0);b.setPadding(dp(6),0,dp(6),0);b.setMaxLines(2);android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(MainActivity.CARD);shape.setCornerRadius(dp(12));b.setBackground(shape);return b;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);android.graphics.drawable.GradientDrawable gap=new android.graphics.drawable.GradientDrawable();gap.setSize(dp(8),dp(8));gap.setColor(Color.TRANSPARENT);l.setDividerDrawable(gap);l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    protected void onResume(){super.onResume();visible=true;if(resume&&player!=null){player.play();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);resume=false;}}
    protected void onPause(){visible=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(player!=null){resume=player.isPlaying();player.pause();}super.onPause();}
    protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(proxy!=null)proxy.close();if(photoConnection!=null)photoConnection.disconnect();if(player!=null){player.setEventListener(null);player.stop();player.detachViews();player.release();}if(engine!=null)engine.release();if(photo!=null)photo.recycle();super.onDestroy();}
}
