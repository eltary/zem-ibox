package io.zem.iboxdrive;
import android.app.Application;
public final class RoadApp extends Application {
    public static RoadApp instance;
    public JieliSession session;
    public void onCreate(){super.onCreate();instance=this;Diag.init(this);session=new JieliSession(this);}
}
