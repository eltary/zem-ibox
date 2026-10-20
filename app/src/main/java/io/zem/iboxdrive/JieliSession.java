package io.zem.iboxdrive;

import android.content.*;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@android.annotation.SuppressLint("MissingPermission")
public final class JieliSession {
    public final ExecutorService worker=Executors.newSingleThreadExecutor();
    public final Handler main=new Handler(Looper.getMainLooper());
    private final ConnectivityManager cm;
    private final boolean networkAllowed;
    private final Context permissionContext;
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    public final Map<String,JSONObject> state=new ConcurrentHashMap<>();
    public volatile JSONObject description=new JSONObject();
    public volatile Network network;
    public volatile String host="192.168.1.1",status="Не подключён";
    public volatile CtpClient ctp;
    public volatile boolean connecting;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private volatile int heartbeatMs=2000;
    private volatile long lastHeartbeat;
    public JieliSession(Context c){
        cm=c.getSystemService(ConnectivityManager.class);
        permissionContext=c.getApplicationContext();
        networkAllowed=c.checkSelfPermission("android.permission.INTERNET")==android.content.pm.PackageManager.PERMISSION_GRANTED&&c.checkSelfPermission("android.permission.ACCESS_NETWORK_STATE")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(c.checkSelfPermission("android.permission.ACCESS_NETWORK_STATE")!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        if(!networkAllowed){status="Тест C · разрешения не объявлены";return;}
        NetworkRequest req=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        cm.registerNetworkCallback(req,new ConnectivityManager.NetworkCallback(){
            public void onAvailable(Network n){Diag.log("NETWORK","Wi-Fi available "+n);changed();}
            public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){Diag.log("NETWORK",n+" "+caps);}
            public void onLinkPropertiesChanged(Network n,LinkProperties lp){Diag.log("NETWORK",n+" "+lp);}
            public void onLost(Network n){Diag.log("NETWORK","Wi-Fi lost "+n);if(n.equals(network)){disconnect();status="Соединение Wi-Fi потеряно";changed();}}
        });
        timer.scheduleWithFixedDelay(()->{
            CtpClient current=ctp;
            if(current!=null&&current.isOpen()&&!connecting){
                try{
                    long now=System.currentTimeMillis();
                    if(now-current.lastRx()>30000){Diag.log("CTP","No replies for 30s");disconnect();status="Регистратор перестал отвечать";changed();return;}
                    if(now-lastHeartbeat>=heartbeatMs){lastHeartbeat=now;current.send("CTP_KEEP_ALIVE","PUT",null);}
                }catch(Exception e){Diag.error("Heartbeat",e);disconnect();}
            }
        },500,500,TimeUnit.MILLISECONDS);
    }
    public void listen(Runnable r){listeners.add(r);}
    public void unlisten(Runnable r){listeners.remove(r);}
    public void changed(){for(Runnable r:listeners)main.post(r);}
    public boolean connected(){return ctp!=null&&ctp.isOpen()&&!connecting;}
    public boolean cardPresent(){JSONObject s=state.get("SD_STATUS");return s!=null&&"1".equals(s.optString("online"));}
    public boolean cardKnown(){return state.containsKey("SD_STATUS");}
    public void connect(String override)throws Exception {
        if(permissionContext.checkSelfPermission("android.permission.ACCESS_NETWORK_STATE")!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IOException("В тесте C нет разрешений; подключение недоступно");
        if(!networkAllowed)throw new IOException("В тесте C нет разрешений; подключение недоступно");
        disconnect();connecting=true;status="Подключение…";changed();
        try {
            Network wifi=null;
            for(Network n:cm.getAllNetworks()){NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){wifi=n;break;}}
            boolean test=BuildConfig.DEBUG&&"10.0.2.2".equals(override);
            if(wifi==null&&!test)throw new IOException("Подключи телефон к Wi-Fi iBOX_Flash_WiFi_Dual");
            network=wifi;String gateway="192.168.1.1";
            if(wifi!=null){LinkProperties lp=cm.getLinkProperties(wifi);Diag.log("CONNECT","network="+wifi+" links="+lp);if(lp!=null)for(RouteInfo r:lp.getRoutes())if(r.isDefaultRoute()&&r.getGateway() instanceof Inet4Address){gateway=r.getGateway().getHostAddress();break;}}
            host=override==null||override.isBlank()?gateway:override.trim();validateHost(host);
            state.clear();description=new JSONObject();
            Socket socket=wifi==null?new Socket():wifi.getSocketFactory().createSocket();socket.setTcpNoDelay(true);socket.connect(new InetSocketAddress(host,3333),6000);
            ctp=new CtpClient(socket,this::notify);
            ctp.request("APP_ACCESS","PUT",params("type","0","ver","20938"),10);
            try{description=new JSONObject(readText("/mnt/spiflash/res/dev_desc.txt",1024*1024));Diag.log("DEVICE DESC",description.toString());}catch(Exception e){Diag.error("Device description",e);}
            connecting=false;status="Подключён к iBOX";lastHeartbeat=0;changed();
            try{get("SD_STATUS");}catch(Exception e){Diag.error("Read SD state",e);}
        }catch(Exception e){disconnect();status=e.getMessage();changed();throw e;}finally{connecting=false;changed();}
    }
    private void notify(String topic,JSONObject value){
        if(topic.equals("__DISCONNECTED")){status="Регистратор отключился";changed();return;}
        JSONObject p=value.optJSONObject("param");if(p!=null&&value.optInt("errno",0)==0)state.put(topic,p);
        if(topic.equals("KEEP_ALIVE_INTERVAL")&&p!=null)heartbeatMs=Math.max(250,Math.min(3000,p.optInt("timeout",6000)/3));
        changed();
    }
    public void disconnect(){CtpClient old=ctp;ctp=null;if(old!=null)old.close();network=null;status="Не подключён";Diag.log("SESSION","Disconnected");changed();}
    public JSONObject get(String topic)throws Exception{return command(topic,"GET",null);}
    public JSONObject put(String topic,JSONObject params)throws Exception{return command(topic,"PUT",params);}
    public JSONObject command(String topic,String op,JSONObject params)throws Exception {CtpClient c=ctp;if(c==null)throw new IOException("Сначала подключись к регистратору");return c.request(topic,op,params,15);}
    public static JSONObject params(String...pairs)throws JSONException {JSONObject p=new JSONObject();for(int i=0;i<pairs.length;i+=2)p.put(pairs[i],pairs[i+1]);return p;}
    static void validateHost(String host)throws IOException{
        if(!host.matches("[0-9.]+"))throw new IOException("Нужен IPv4-адрес регистратора");
        InetAddress a=InetAddress.getByName(host);
        if(!(a instanceof Inet4Address)||(!a.isSiteLocalAddress()&&!a.isLinkLocalAddress()&&!a.isLoopbackAddress()))throw new IOException("Нужен адрес в локальной сети");
    }
    public URL url(String path)throws Exception {
        if(path==null||path.isBlank())throw new IOException("Пустой путь к файлу");
        if(path.startsWith("http://")){URI u=new URI(path);if(!host.equals(u.getHost())||u.getPort()!=8080)throw new IOException("Регистратор прислал посторонний адрес");return u.toURL();}
        if(path.contains("://"))throw new IOException("Неверный путь регистратора");
        return new URI("http",null,host,8080,path.startsWith("/")?path:"/"+path,null,null).toURL();
    }
    public HttpURLConnection http(String path)throws Exception {
        URL url=url(path);Network n=network;
        if(n==null)throw new IOException("Соединение Wi-Fi потеряно. Подключись к регистратору заново");
        HttpURLConnection c=(HttpURLConnection)n.openConnection(url);
        c.setConnectTimeout(6000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept-Encoding","identity");
        Diag.log("HTTP TX","GET "+url+" via Wi-Fi="+n);return c;
    }
    public String readText(String path,int limit)throws Exception {
        HttpURLConnection c=http(path);
        try {int code=c.getResponseCode();Diag.log("HTTP RX",code+" "+c.getHeaderFields());if(code!=200)throw new IOException("HTTP "+code+" для "+path);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Слишком большой ответ регистратора");out.write(b,0,n);}String text=new String(out.toByteArray(),StandardCharsets.UTF_8);Diag.log("HTTP BODY",text);return text;}
        }finally{c.disconnect();}
    }
    public List<MediaItem> files(int camera)throws Exception {
        if(cardKnown()&&!cardPresent())return new ArrayList<>();
        JSONObject reply=put(camera==0?"FORWARD_MEDIA_FILES_LIST":"BEHIND_MEDIA_FILES_LIST",params("type","1"));
        if(reply.optInt("type",1)==0)return new ArrayList<>();
        String text=readText(reply.getString("path"),16*1024*1024);
        JSONArray a=new JSONObject(text).getJSONArray("file_list");List<MediaItem> list=new ArrayList<>();
        for(int i=0;i<a.length();i++)list.add(new MediaItem(a.getJSONObject(i),camera));
        list.sort(MediaItem.newestFirst());return list;
    }
    public String report(){return "Zem iBox Drive "+BuildConfig.VERSION_NAME+"\n"+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+"\nloggingEnabled="+Diag.enabled()+"\ndestructiveActions="+BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS+"\nstatus="+status+"\nhost="+host+"\nnetwork="+network+"\ndescription="+description+"\nstate="+state+"\n";}
}
