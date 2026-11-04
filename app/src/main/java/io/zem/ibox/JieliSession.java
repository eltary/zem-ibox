package io.zem.ibox;

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
    private final Set<String> unsupported=ConcurrentHashMap.newKeySet();
    private final Object sessionLock=new Object();
    private long generation;private volatile CtpClient formattingOwner;public volatile String formatStatus="";
    public JieliSession(Context c){
        cm=c.getSystemService(ConnectivityManager.class);
        permissionContext=c.getApplicationContext();
        networkAllowed=c.checkSelfPermission("android.permission.INTERNET")==android.content.pm.PackageManager.PERMISSION_GRANTED&&c.checkSelfPermission("android.permission.ACCESS_NETWORK_STATE")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(c.checkSelfPermission("android.permission.ACCESS_NETWORK_STATE")!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        if(!networkAllowed){status="Недоступны разрешения для подключения";return;}
        NetworkRequest req=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        cm.registerNetworkCallback(req,new ConnectivityManager.NetworkCallback(){
            public void onAvailable(Network n){Diag.log("NETWORK","Wi-Fi available "+n);changed();}
            public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){Diag.log("NETWORK",n+" "+caps);}
            public void onLinkPropertiesChanged(Network n,LinkProperties lp){Diag.log("NETWORK",n+" "+lp);}
            public void onLost(Network n){Diag.log("NETWORK","Wi-Fi lost "+n);synchronized(sessionLock){if(n.equals(network)){disconnect();status="Соединение Wi-Fi потеряно";changed();}}}
        });
        timer.scheduleWithFixedDelay(()->{
            CtpClient current=ctp;
            if(current!=null&&current.isOpen()&&!connecting){
                try{
                    long now=System.currentTimeMillis();
                    if(now-current.lastRx()>30000){Diag.log("CTP","No replies for 30s");disconnectCurrent(current,"Регистратор перестал отвечать");return;}
                    if(now-lastHeartbeat>=heartbeatMs){lastHeartbeat=now;current.send("CTP_KEEP_ALIVE","PUT",null);}
                }catch(Exception e){Diag.error("Heartbeat",e);disconnectCurrent(current,"Регистратор отключился");}
            }
        },500,500,TimeUnit.MILLISECONDS);
    }
    public void listen(Runnable r){listeners.add(r);}
    public void unlisten(Runnable r){listeners.remove(r);}
    public void changed(){for(Runnable r:listeners)main.post(r);}
    public boolean hasWifi(){if(!networkAllowed)return false;for(Network n:cm.getAllNetworks()){NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return true;}return false;}
    public boolean connected(){return ctp!=null&&ctp.isOpen()&&!connecting;}
    public String deviceIdentity(){JSONObject id=state.get("UUID");String uuid=id==null?description.optString("uuid",""):id.optString("uuid","");JSONObject ap=state.get("AP_SSID_INFO");String ssid=ap==null?"":ap.optString("ssid","");if(uuid.matches("(?i)x+|0+"))uuid="";String model=description.optString("product_type","");return !uuid.isBlank()?"uuid:"+uuid+"|"+model:!ssid.isBlank()?"ssid:"+ssid+"|"+model:"host:"+host+"|"+model;}
    public String deviceName(){JSONObject ap=state.get("AP_SSID_INFO");String name=ap==null?"":ap.optString("ssid","");return name.isBlank()?description.optString("product_type","Регистратор").replace('_',' '):name;}
    public boolean rearEnabled(){return permissionContext.getSharedPreferences("devices",0).getBoolean("rear."+deviceIdentity(),false);}
    public boolean rearAvailable(){JSONObject st=state.get("PULL_VIDEO_STATUS");return connected()&&rearEnabled()&&(st==null||!"0".equals(st.optString("status")))&&supports("PULL_VIDEO_STATUS");}
    public void setRearEnabled(boolean enabled){permissionContext.getSharedPreferences("devices",0).edit().putBoolean("rear."+deviceIdentity(),enabled).apply();changed();if(enabled&&connected())worker.execute(()->{try{command("PULL_VIDEO_STATUS","GET",null);}catch(Exception e){Diag.error("Rear camera status",e);}});}
    public JSONArray supportedLevels(String key){Object value=description.opt(key);if(value instanceof JSONArray)return (JSONArray)value;if(value instanceof String)try{return new JSONArray((String)value);}catch(JSONException ignored){}return null;}
    public String liveTransport(){String saved=permissionContext.getSharedPreferences("devices",0).getString("transport."+deviceIdentity(),"auto");return Set.of("tcp","udp","rtsp").contains(saved)?saved:StreamCapabilities.automatic(description);}
    public String transportPreference(){return permissionContext.getSharedPreferences("devices",0).getString("transport."+deviceIdentity(),"auto");}
    public void setLiveTransport(String mode){if(!Set.of("auto","tcp","udp","rtsp").contains(mode))throw new IllegalArgumentException();permissionContext.getSharedPreferences("devices",0).edit().putString("transport."+deviceIdentity(),mode).apply();changed();}
    public boolean supports(String topic){return !unsupported.contains(topic);}
    public boolean cardPresent(){JSONObject s=state.get("SD_STATUS");return s!=null&&"1".equals(s.optString("online"));}
    public boolean cardKnown(){return state.containsKey("SD_STATUS");}
    public void connect(String override)throws Exception {
        if(cardFormatting())throw new IOException("Дождитесь завершения форматирования microSD");
        if(!networkAllowed)throw new IOException("Недоступны разрешения для подключения к регистратору");
        disconnect();final long attempt;synchronized(sessionLock){attempt=generation;connecting=true;status="Подключение…";}changed();
        try {
            Network wifi=null;
            for(Network n:cm.getAllNetworks()){NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){wifi=n;break;}}
            boolean test=BuildConfig.DEBUG&&"10.0.2.2".equals(override);
            if(wifi==null&&!test)throw new IOException("Подключите телефон к Wi-Fi регистратора");
            String gateway="192.168.1.1";
            if(wifi!=null){LinkProperties lp=cm.getLinkProperties(wifi);Diag.log("CONNECT","network="+wifi+" links="+lp);if(lp!=null)for(RouteInfo r:lp.getRoutes())if(r.isDefaultRoute()&&r.getGateway() instanceof Inet4Address){gateway=r.getGateway().getHostAddress();break;}}
            String address=override==null||override.isBlank()?gateway:override.trim();validateHost(address);
            synchronized(sessionLock){checkAttempt(attempt);network=wifi;host=address;state.clear();unsupported.clear();description=new JSONObject();}
            Socket socket=wifi==null?new Socket():wifi.getSocketFactory().createSocket();CtpClient client;
            try{socket.setTcpNoDelay(true);socket.connect(new InetSocketAddress(address,3333),6000);synchronized(sessionLock){checkAttempt(attempt);client=new CtpClient(socket,(topic,value)->notify(attempt,topic,value));ctp=client;}}catch(Exception e){socket.close();throw e;}
            client.request("APP_ACCESS","PUT",params("type","0","ver","20938"),10);
            try{JSONObject desc=new JSONObject(readText("/mnt/spiflash/res/dev_desc.txt",1024*1024));synchronized(sessionLock){checkAttempt(attempt);description=desc;}Diag.log("DEVICE DESC",desc.toString());}catch(Exception e){Diag.error("Device description",e);}
            synchronized(sessionLock){checkAttempt(attempt);if(!client.isOpen())throw new IOException("Регистратор отключился при подключении");connecting=false;status="Регистратор подключён";lastHeartbeat=0;}changed();
            try{client.request("SD_STATUS","GET",null,15);}catch(Exception e){Diag.error("Read SD state",e);}
        }catch(Exception e){synchronized(sessionLock){if(attempt==generation){disconnect();status=e.getMessage();changed();}}throw e;}
        finally{synchronized(sessionLock){if(attempt==generation){connecting=false;changed();}}}
    }
    private void checkAttempt(long attempt)throws IOException{if(attempt!=generation)throw new IOException("Подключение отменено");}
    private void notify(long attempt,String topic,JSONObject value){
        synchronized(sessionLock){if(attempt!=generation)return;
        if(topic.equals("__DISCONNECTED")){disconnect();status="Регистратор отключился";changed();return;}
        if(value.optInt("errno",0)==6)unsupported.add(topic);
        JSONObject p=value.optJSONObject("param");if(p!=null&&value.optInt("errno",0)==0)state.put(topic,p);
        if(topic.equals("KEEP_ALIVE_INTERVAL")&&p!=null)heartbeatMs=Math.max(250,Math.min(3000,p.optInt("timeout",6000)/3));}
        changed();
    }
    public void disconnect(){CtpClient old;synchronized(sessionLock){generation++;old=ctp;ctp=null;connecting=false;network=null;status="Не подключён";}if(old!=null)old.close();Diag.log("SESSION","Disconnected");changed();}
    private void disconnectCurrent(CtpClient current,String reason){synchronized(sessionLock){if(ctp!=current)return;disconnect();status=reason;changed();}}
    public JSONObject get(String topic)throws Exception{return command(topic,"GET",null);}
    public JSONObject put(String topic,JSONObject params)throws Exception{return command(topic,"PUT",params);}
    public long connectionId(){synchronized(sessionLock){return generation;}}
    public boolean isCurrent(CtpClient owner){return owner!=null&&ctp==owner&&connected();}
    public JSONObject command(String topic,String op,JSONObject params)throws Exception {return command(ctp,topic,op,params);}
    public JSONObject command(CtpClient c,String topic,String op,JSONObject params)throws Exception {if(cardFormatting()&&!Set.of("CLOSE_RT_STREAM","CLOSE_PULL_RT_STREAM").contains(topic))throw new IOException("Дождитесь завершения форматирования microSD");if(!isCurrent(c))throw new IOException("Подключение изменилось. Подключите видеорегистратор заново");try{return c.request(topic,op,params,15);}catch(CtpClient.DeviceException e){if(isCurrent(c)){if(e.code==6){unsupported.add(topic);changed();}if(e.code==15||e.code==16){state.put("PULL_VIDEO_STATUS",params("status","0"));changed();}}throw e;}}
    public boolean cardFormatting(){return formattingOwner!=null;}
    public long cardContentVersion(){return permissionContext.getSharedPreferences("devices",0).getLong("cardContent."+deviceIdentity(),0);}
    void beginCardFormat(CtpClient owner)throws IOException {synchronized(sessionLock){if(!isCurrent(owner))throw new IOException("Подключение изменилось");if(cardFormatting())throw new IOException("Форматирование уже выполняется");if(!cardKnown()||!cardPresent())throw new IOException("microSD не установлена или её состояние ещё не получено");if(TransferService.active)throw new IOException("Сначала остановите скачивание с регистратора");formattingOwner=owner;formatStatus="Подготовка к форматированию microSD…";}changed();}
    void formatCard(CtpClient owner)throws Exception {boolean issued=false;String identity=deviceIdentity();
        try{if(formattingOwner!=owner||!isCurrent(owner)||!cardPresent())throw new IOException("Подключение или состояние microSD изменилось");if(TransferService.active)throw new IOException("Сначала остановите скачивание с регистратора");
            JSONObject record=state.get("VIDEO_CTRL");if(record==null||!record.has("status"))record=owner.request("VIDEO_CTRL","GET",null,5);
            if("1".equals(record.optString("status"))){JSONObject stopped=owner.request("VIDEO_CTRL","PUT",params("status","0"),10);if(!"0".equals(stopped.optString("status")))throw new IOException("Регистратор не подтвердил остановку записи");}
            else if(!"0".equals(record.optString("status")))throw new IOException("Не удалось проверить состояние записи регистратора");
            if(!isCurrent(owner)||!cardPresent())throw new IOException("Подключение или состояние microSD изменилось");
            formatStatus="Форматируем microSD… Не отключайте питание регистратора";changed();issued=true;
            permissionContext.getSharedPreferences("devices",0).edit().putLong("cardContent."+identity,cardContentVersion()+1).apply();
            JSONObject result=owner.formatCard();if("0".equals(result.optString("frm")))throw new CtpClient.DeviceException(1,"FORMAT","Не удалось отформатировать microSD");if(!"1".equals(result.optString("frm")))throw new IOException("Нет подтверждения завершения форматирования");
            formatStatus="microSD отформатирована. Запись на регистраторе остановлена";Diag.log("CARD FORMAT","Confirmed success");state.remove("TF_CAP");
            for(String topic:new String[]{"SD_STATUS","TF_CAP"})if(isCurrent(owner))try{owner.request(topic,"GET",null,3);}catch(Exception e){Diag.error("Refresh after format: "+topic,e);}
        }catch(Exception e){formatStatus=issued&&!(e instanceof CtpClient.DeviceException)?"Форматирование не подтверждено. Результат неизвестен: проверьте карту после подключения. Повтор автоматически не отправлялся":"Форматирование не выполнено: "+e.getMessage();Diag.error("Card format",e);throw e;}
        finally{synchronized(sessionLock){if(formattingOwner==owner)formattingOwner=null;}changed();}
    }
    public static JSONObject params(String...pairs)throws JSONException {JSONObject p=new JSONObject();for(int i=0;i<pairs.length;i+=2)p.put(pairs[i],pairs[i+1]);return p;}
    static void validateHost(String host)throws IOException{
        if(!host.matches("[0-9.]+"))throw new IOException("Нужен IPv4-адрес регистратора");
        InetAddress a=InetAddress.getByName(host);
        if(!(a instanceof Inet4Address)||(!a.isSiteLocalAddress()&&!a.isLinkLocalAddress()&&!a.isLoopbackAddress()))throw new IOException("Нужен адрес в локальной сети");
    }
    public URL url(String path)throws Exception {
        if(cardFormatting())throw new IOException("Дождитесь завершения форматирования microSD");
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
    public HttpURLConnection http(CtpClient owner,String path)throws Exception {synchronized(sessionLock){if(!isCurrent(owner))throw new IOException("Подключение изменилось. Повторите действие");return http(path);}}
    public String readText(String path,int limit)throws Exception {return readText(http(path),path,limit);}
    private String readText(HttpURLConnection c,String path,int limit)throws Exception {
        try {int code=c.getResponseCode();Diag.log("HTTP RX",code+" "+c.getHeaderFields());if(code!=200)throw new IOException("HTTP "+code+" для "+path);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Слишком большой ответ регистратора");out.write(b,0,n);}String text=new String(out.toByteArray(),StandardCharsets.UTF_8);Diag.log("HTTP BODY",text);return text;}
        }finally{c.disconnect();}
    }
    public List<MediaItem> files(int camera)throws Exception {return files(ctp,camera);}
    public List<MediaItem> files(CtpClient owner,int camera)throws Exception {
        if(!isCurrent(owner))throw new IOException("Сначала подключите видеорегистратор");
        if(!cardPresent())throw new IOException(cardKnown()?"В видеорегистраторе нет microSD":"Состояние microSD ещё не получено");
        JSONObject reply=command(owner,camera==0?"FORWARD_MEDIA_FILES_LIST":"BEHIND_MEDIA_FILES_LIST","PUT",params("type","1"));
        if(reply.optInt("type",1)==0)return new ArrayList<>();
        String path=reply.getString("path");String text=readText(http(owner,path),path,16*1024*1024);
        JSONArray a=new JSONObject(text).getJSONArray("file_list");List<MediaItem> list=new ArrayList<>();
        for(int i=0;i<a.length();i++)list.add(new MediaItem(a.getJSONObject(i),camera));
        list.sort(MediaItem.newestFirst());return list;
    }
    public String report(){return "Zem iBox "+BuildConfig.VERSION_NAME+"\n"+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+"\nloggingEnabled="+Diag.enabled()+"\ndestructiveActions="+BuildConfig.ALLOW_DESTRUCTIVE_ACTIONS+"\nconfirmedCardFormat=true\ncardFormatting="+cardFormatting()+"\ncardFormatStatus="+formatStatus+"\nstatus="+status+"\nhost="+host+"\nnetwork="+network+"\ndescription="+description+"\nstate="+state+"\n";}
}
