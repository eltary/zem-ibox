package io.zem.ibox;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class DownloadsActivity extends Activity {
    private LinearLayout root;private ListView list;private BaseAdapter adapter;private final Runnable changed=this::refresh;private List<DownloadQueue.Job> jobs=Collections.emptyList();
    public void onCreate(Bundle state){super.onCreate(state);root=Widgets.column(this);root.setBackground(Ui.background());Platform.insets(this,root,Ui.dp(this,16),Ui.dp(this,6),Ui.dp(this,16),Ui.dp(this,8));setContentView(root);
        LinearLayout top=Widgets.row(this);Button back=Ui.iconButton(this,"back","Назад");back.setOnClickListener(v->finish());top.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));top.addView(Ui.heading(this,"Загрузки","Оригиналы с видеорегистратора"),new LinearLayout.LayoutParams(0,-2,1));root.addView(top);
        Button clean=Ui.button(this,"Убрать завершённые из списка",false);clean.setOnClickListener(v->{DownloadQueue.get(this).clearFinished();refresh();});root.addView(clean);list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));list.setDividerHeight(Ui.dp(this,10));root.addView(list,new LinearLayout.LayoutParams(-1,0,1));refresh();}
    private void refresh(){if(isFinishing()||isDestroyed())return;jobs=DownloadQueue.get(this).all();if(adapter!=null){adapter.notifyDataSetChanged();return;}adapter=new BaseAdapter(){public int getCount(){return Math.max(1,jobs.size());}public Object getItem(int p){return p;}public long getItemId(int p){return p;}public View getView(int p,View old,ViewGroup parent){if(jobs.isEmpty())return Ui.text(DownloadsActivity.this,"Пока нет загрузок. Выберите записи в архиве.",15,Ui.MUTED);DownloadQueue.Job job=jobs.get(p);LinearLayout card=Ui.card(DownloadsActivity.this);card.addView(Ui.text(DownloadsActivity.this,job.item().name,16,Ui.INK));card.addView(Ui.text(DownloadsActivity.this,DownloadQueue.label(job.state())+" · "+TransferService.bytes(job.done())+" / "+TransferService.bytes(job.total())+(job.error().isEmpty()?"":"\n"+job.error()),13,Ui.MUTED));
            if(!job.state().equals("done")&&!job.state().equals("cancelled")){LinearLayout actions=Widgets.responsive(DownloadsActivity.this);Button primary=Ui.button(DownloadsActivity.this,job.state().equals("running")||job.state().equals("queued")?"Пауза":"Продолжить",false);primary.setOnClickListener(v->{TransferService.control(DownloadsActivity.this,job.id(),job.state().equals("running")||job.state().equals("queued")?"pause":"resume");});actions.addView(primary,Widgets.pair(actions));Button stop=Ui.button(DownloadsActivity.this,"Отменить",false);stop.setOnClickListener(v->TransferService.control(DownloadsActivity.this,job.id(),"cancelItem"));actions.addView(stop,Widgets.pair(actions));card.addView(actions);}return card;}};list.setAdapter(adapter);}
    protected void onResume(){super.onResume();RoadApp.instance.session.listen(changed);refresh();}protected void onPause(){RoadApp.instance.session.unlisten(changed);super.onPause();}
}
