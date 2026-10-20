package io.zem.iboxdrive;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;

public final class PlayerActivity extends Activity {
    private VideoView video;private MediaPlayer player;private Uri uri;private String mime;
    private Network previous;private ConnectivityManager cm;
    public void onCreate(Bundle saved){
        super.onCreate(saved);if(checkSelfPermission("android.permission.INTERNET")!=android.content.pm.PackageManager.PERMISSION_GRANTED){TextView blocked=new TextView(this);blocked.setText("Тест C без разрешений: просмотр с регистратора недоступен");setContentView(blocked);return;}uri=Uri.parse(getIntent().getStringExtra("uri"));mime=getIntent().getStringExtra("mime");Diag.log("PLAY","Open "+uri+" MIME="+mime);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);return i;});setContentView(root);
        LinearLayout buttons=new LinearLayout(this);Button back=new Button(this);back.setText("Назад");back.setOnClickListener(v->finish());buttons.addView(back);TextView title=new TextView(this);title.setText(getIntent().getStringExtra("name"));title.setTextColor(Color.WHITE);buttons.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(buttons);
        if("http".equals(uri.getScheme())){cm=getSystemService(ConnectivityManager.class);previous=cm.getBoundNetworkForProcess();Network selected=RoadApp.instance.session.network;if(selected!=null){cm.bindProcessToNetwork(selected);Diag.log("PLAY","Bound playback process to Wi-Fi "+selected);}}
        if(mime!=null&&mime.startsWith("image/")){
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
            new Thread(()->{try{InputStream in="content".equals(uri.getScheme())?getContentResolver().openInputStream(uri):RoadApp.instance.session.http(uri.toString()).getInputStream();Bitmap b;try(in){b=BitmapFactory.decodeStream(in);}runOnUiThread(()->image.setImageBitmap(b));}catch(Exception e){Diag.error("Image playback",e);runOnUiThread(()->error(e.getMessage()));}},"Photo viewer").start();return;
        }
        video=new VideoView(this);root.addView(video,new LinearLayout.LayoutParams(-1,0,1));MediaController controls=new MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);
        video.setOnPreparedListener(p->{player=p;Diag.log("PLAY","Prepared duration="+p.getDuration()+" size="+p.getVideoWidth()+"x"+p.getVideoHeight());video.start();});video.setOnErrorListener((p,what,extra)->{Diag.log("PLAY ERROR","what="+what+" extra="+extra);error("Встроенный проигрыватель не открыл этот формат. Скачанный оригинал можно открыть внешним проигрывателем.");return true;});video.setOnCompletionListener(p->Diag.log("PLAY","Completed"));video.setVideoURI(uri);
        LinearLayout more=new LinearLayout(this);Button rotate=new Button(this);rotate.setText("Повернуть");rotate.setOnClickListener(v->setRequestedOrientation(getResources().getConfiguration().orientation==2?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));more.addView(rotate);
        Button speed=new Button(this);speed.setText("Скорость");speed.setOnClickListener(v->new AlertDialog.Builder(this).setItems(new String[]{"0.5×","1×","2×"},(d,n)->{try{if(player!=null)player.setPlaybackParams(new PlaybackParams().setSpeed(new float[]{.5f,1f,2f}[n]));}catch(Exception e){Diag.error("Playback speed",e);}}).show());more.addView(speed);Button external=new Button(this);external.setText("Открыть в…");external.setOnClickListener(v->external());more.addView(external);root.addView(more);
    }
    private void external(){try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){error("Не найден проигрыватель для "+mime);}}
    private void error(String text){if(!isFinishing())new AlertDialog.Builder(this).setTitle("Просмотр записи").setMessage(text).setPositiveButton("Закрыть",null).show();}
    protected void onPause(){if(video!=null)video.pause();super.onPause();}
    protected void onDestroy(){if(video!=null)video.stopPlayback();if(cm!=null)cm.bindProcessToNetwork(previous);super.onDestroy();}
}
