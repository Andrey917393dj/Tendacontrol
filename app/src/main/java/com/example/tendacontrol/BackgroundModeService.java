package com.example.tendacontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class BackgroundModeService extends Service {
    public static final String ACTION_START = "com.example.tendacontrol.START";
    public static final String ACTION_STOP = "com.example.tendacontrol.STOP";
    public static final String ACTION_RESUME = "com.example.tendacontrol.RESUME";
    public static final String EXTRA_CONFIG = "config";
    private static final String CHANNEL = "tenda_background";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private int emptyReads;
    private final Random random = new Random();
    private WebView web;
    private TendaWebController controller;
    private JSONObject cfg;
    private final Map<String, String> originalSpeed = new LinkedHashMap<>();
    private final Map<String, Boolean> originalAccess = new LinkedHashMap<>();
    private final Map<String, Models.Device> known = new LinkedHashMap<>();
    private Runnable tickRunnable, stopRunnable;
    private boolean initialized, busy, authComplete;

    public static void start(Context c, JSONObject config) {
        Intent i = new Intent(c, BackgroundModeService.class);
        i.setAction(ACTION_START); i.putExtra(EXTRA_CONFIG, config.toString());
        ContextCompat.startForegroundService(c, i);
    }
    public static void resume(Context c) {
        try { ContextCompat.startForegroundService(c, new Intent(c, BackgroundModeService.class).setAction(ACTION_RESUME)); }
        catch (Exception ignored) {}
    }
    public static void stop(Context c) { try { c.startService(new Intent(c, BackgroundModeService.class).setAction(ACTION_STOP)); } catch (Exception ignored) {} }

    @Override public void onCreate() {
        super.onCreate(); createChannel();
        if (Build.VERSION.SDK_INT >= 29) startForeground(101, notification("Подготовка…"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(101, notification("Подготовка…"));
        web = new WebView(getApplicationContext());
        controller = new TendaWebController(this, web);
        controller.setBase(Prefs.router(this));
        web.onResume(); web.resumeTimers();
        acquireLocks();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) { wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tenda:bg"); wakeLock.setReferenceCounted(false); wakeLock.acquire(); }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tenda:wifi");
                wifiLock.setReferenceCounted(false); wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESUME : intent.getAction();
        try {
            if (ACTION_STOP.equals(action)) { restoreAndStop("⛔ Остановлено"); return START_NOT_STICKY; }
            if (ACTION_START.equals(action)) {
                String raw = intent.getStringExtra(EXTRA_CONFIG); cfg = raw == null ? null : new JSONObject(raw);
                if (cfg == null) { Prefs.bgRunning(this,false); stopSelf(); return START_NOT_STICKY; }
                boolean wasRunning = Prefs.bgRunning(this);
                Prefs.bgConfig(this, cfg.toString()); Prefs.bgRunning(this,true); Prefs.bgMode(this,cfg.optString("mode","ФОН"));
                if (!wasRunning) clearOriginals(); else loadOriginals();
                if (initialized && authComplete) { busy = false; beginModeIfNeeded(); return START_STICKY; }
            } else {
                String raw = Prefs.bgConfig(this); cfg = raw == null ? null : new JSONObject(raw); loadOriginals();
                if (cfg == null) { Prefs.bgRunning(this,false); stopSelf(); return START_NOT_STICKY; }
            }
            if (cfg != null) initController();
        } catch (Exception e) { notifyNow("Ошибка фонового режима: " + e.getMessage()); }
        return START_STICKY;
    }

    private void initController() {
        if (initialized) return; initialized = true;
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (!authComplete) ensureLoggedIn(0);
            }
        });
        web.loadUrl(controller.base() + "/index.htr");
    }

    private void ensureLoggedIn(final int n) {
        if (n > 180) { notifyNow("Не удалось войти в Tenda — открой приложение и проверь пароль"); return; }
        web.evaluateJavascript("(function(){var t=(document.body&&document.body.innerText)||'';var p=document.querySelector('input[type=password]');return JSON.stringify({login:!!p,ready:/Bandwidth Control|Контроль полосы пропускания|Подключенные устройства/i.test(t)});})()", value -> {
            boolean login = value != null && value.contains("login\\\":true");
            boolean ready = value != null && value.contains("ready\\\":true");
            if (ready && !login) {
                authComplete = true;
                controller.openBandwidthAndRead((list, error) -> {
                    if (error != null) { notifyNow(error); return; }
                    known.clear(); if (list != null) for (Models.Device d:list) known.put(d.key(),d);
                    loadOriginals(); beginModeIfNeeded();
                }); return;
            }
            if (login) {
                String pass = SecureStore.loadPassword(this);
                if (pass == null || pass.isEmpty()) { notifyNow("Открой приложение и войди в Tenda один раз"); return; }
                web.evaluateJavascript("(function(){var p=document.querySelector('input[type=password]');if(!p)return 'no';p.value='"+MainActivity.jsEscapePublic(pass)+"';p.dispatchEvent(new Event('input',{bubbles:true}));var bs=[...document.querySelectorAll('button,input[type=submit],input[type=button]')];var b=bs.find(e=>/войти|вход|login|sign in/i.test((e.innerText||e.value||'').trim()))||document.querySelector('input[type=submit]');if(b)b.click();return 'ok';})()", x -> handler.postDelayed(() -> ensureLoggedIn(n+1), 1000));
            } else { if (n % 10 == 9) web.loadUrl(controller.base() + "/index.htr"); handler.postDelayed(() -> ensureLoggedIn(n+1), 1000); }
        });
    }

    private void beginModeIfNeeded() {
        if (cfg == null) return;
        long endAt=cfg.optLong("endAt",0); int duration=cfg.optInt("duration",0);
        if(duration>0 && endAt==0){endAt=System.currentTimeMillis()+duration*1000L;try{cfg.put("endAt",endAt);Prefs.bgConfig(this,cfg.toString());}catch(Exception ignored){}}
        if(endAt>0 && System.currentTimeMillis()>=endAt){restoreAndStop("⏱ Режим завершён");return;}
        saveOriginalsIfNeeded();
        int interval=Math.max(2,cfg.optInt("interval",10));
        notifyNow("🟢 "+cfg.optString("mode","ФОН")+" • работает в фоне");
        if(stopRunnable!=null)handler.removeCallbacks(stopRunnable);
        if(endAt>0){long left=Math.max(1000,endAt-System.currentTimeMillis());stopRunnable=()->restoreAndStop("⏱ Режим завершён");handler.postDelayed(stopRunnable,left);}
        if(tickRunnable!=null)handler.removeCallbacks(tickRunnable);
        String mode=cfg.optString("mode","");
        if ("BOOST".equals(mode) || "SLOW".equals(mode) || "ROULETTE".equals(mode)) {
            tickRunnable = () -> { if (cfg != null && !busy) tick(); };
            handler.post(tickRunnable);
        } else {
            tickRunnable=new Runnable(){@Override public void run(){if(cfg==null)return;if(!busy)tick();handler.postDelayed(this,interval*1000L);}}; handler.post(tickRunnable);
        }
    }

    private void tick(){busy=true;refreshDevicesThen(()->{String m=cfg.optString("mode","");if("CHAOS".equals(m))tickChaos();else if("WAVE".equals(m))tickWave();else if("JITTER".equals(m))tickJitter();else if("FLICKER".equals(m))tickFlicker();else if("BOOST".equals(m)||"SLOW".equals(m))tickFixed();else if("ROULETTE".equals(m))tickRoulette();else busy=false;});}
    private void refreshDevicesThen(final Runnable after){controller.openBandwidthAndRead((list,err)->{if(err!=null){notifyNow("Фон: "+err);busy=false;return;}if(list==null||list.isEmpty()){if(++emptyReads>=2){emptyReads=0;authComplete=false;busy=false;web.loadUrl(controller.base()+"/index.htr");return;}busy=false;return;}emptyReads=0;known.clear();for(Models.Device d:list)known.put(d.key(),d);if(originalSpeed.isEmpty()&&originalAccess.isEmpty())saveOriginalsIfNeeded();after.run();});}

    private List<Models.Device> targets(){ArrayList<Models.Device> out=new ArrayList<>();JSONArray arr=cfg.optJSONArray("targets");boolean all=arr==null||arr.length()==0||"*".equals(cfg.optString("targetsMode","list"));for(Models.Device d:known.values()){if(d.frozen)continue;if(all||containsTarget(arr,d))out.add(d);}return out;}
    private boolean containsTarget(JSONArray a, Models.Device d){String k=d.key();for(int i=0;a!=null&&i<a.length();i++){String x=a.optString(i,"");if(x.equals(k)||x.equals(d.ip)||x.equals(d.id))return true;}return false;}

    private void tickChaos(){List<Models.Device> ts=targets();double min=cfg.optDouble("min",1),max=cfg.optDouble("max",20),step=cfg.optDouble("step",1);boolean un=cfg.optBoolean("unlimited",false);LinkedHashMap<Models.Device,String> m=new LinkedHashMap<>();for(Models.Device d:ts)m.put(d,un&&random.nextInt(20)==0?"0":nearestOption(d,randomStepped(min,max,step)));applySpeedSeq(m,()->busy=false);}
    private void tickWave(){List<Models.Device> ts=targets();double min=cfg.optDouble("min",1),max=cfg.optDouble("max",20),step=cfg.optDouble("step",2);int idx=cfg.optInt("waveIndex",0);boolean f=cfg.optBoolean("waveForward",true);int count=Math.max(1,(int)Math.floor((max-min)/step));double v=min+idx*step;if(f){idx++;if(idx>=count){idx=count;f=false;}}else{idx--;if(idx<=0){idx=0;f=true;}}try{cfg.put("waveIndex",idx);cfg.put("waveForward",f);Prefs.bgConfig(this,cfg.toString());}catch(Exception ignored){}LinkedHashMap<Models.Device,String>m=new LinkedHashMap<>();for(Models.Device d:ts)m.put(d,nearestOption(d,v));applySpeedSeq(m,()->busy=false);}
    private void tickJitter(){List<Models.Device>ts=targets();boolean h=!cfg.optBoolean("jitterHigh",false);try{cfg.put("jitterHigh",h);Prefs.bgConfig(this,cfg.toString());}catch(Exception ignored){}double lo=cfg.optDouble("low",1),hi=cfg.optDouble("high",20),v=h?hi:lo;LinkedHashMap<Models.Device,String>m=new LinkedHashMap<>();for(Models.Device d:ts)m.put(d,nearestOption(d,v));applySpeedSeq(m,()->busy=false);}
    private void tickFlicker(){List<Models.Device>ts=targets();boolean on=!cfg.optBoolean("flicker",false);try{cfg.put("flicker",on);Prefs.bgConfig(this,cfg.toString());}catch(Exception ignored){}applyAccessSeq(ts,on,()->busy=false);}
    private void tickFixed(){List<Models.Device>ts=targets();String s=cfg.optString("speed","1");LinkedHashMap<Models.Device,String>m=new LinkedHashMap<>();for(Models.Device d:ts)m.put(d,s);applySpeedSeq(m,()->busy=false);}
    private void tickRoulette(){List<Models.Device>ts=targets();if(ts.isEmpty()){busy=false;return;}Models.Device d=ts.get(random.nextInt(ts.size()));double lo=cfg.optDouble("min",1),hi=cfg.optDouble("max",10);String s=nearestOption(d,lo+random.nextDouble()*Math.max(0,hi-lo));LinkedHashMap<Models.Device,String>m=new LinkedHashMap<>();m.put(d,s);applySpeedSeq(m,()->busy=false);}

    private void applySpeedSeq(Map<Models.Device,String>m,Runnable done){if(m.isEmpty()){done.run();return;}List<Map.Entry<Models.Device,String>>e=new ArrayList<>(m.entrySet());applySpeedAt(e,0,done);}
    private void applySpeedAt(List<Map.Entry<Models.Device,String>>e,int i,Runnable done){if(i>=e.size()){done.run();return;}Map.Entry<Models.Device,String>x=e.get(i);controller.setSpeed(x.getKey(),x.getValue(),(ok,err)->handler.postDelayed(()->applySpeedAt(e,i+1,done),250));}
    private void applyAccessSeq(List<Models.Device>ds,boolean allow,Runnable done){applyAccessAt(ds,0,allow,done);}
    private void applyAccessAt(List<Models.Device>ds,int i,boolean allow,Runnable done){if(i>=ds.size()){done.run();return;}controller.setAccess(ds.get(i),allow,(ok,err)->handler.postDelayed(()->applyAccessAt(ds,i+1,allow,done),250));}

    private void saveOriginalsIfNeeded(){for(Models.Device d:targets()){String k=d.key();if(!originalSpeed.containsKey(k))originalSpeed.put(k,d.currentText);if(d.ip!=null&&!d.ip.isEmpty()&&!originalSpeed.containsKey(d.ip))originalSpeed.put(d.ip,d.currentText);if(!originalAccess.containsKey(k))originalAccess.put(k,d.enabled);if(d.ip!=null&&!d.ip.isEmpty()&&!originalAccess.containsKey(d.ip))originalAccess.put(d.ip,d.enabled);}saveOriginals();}
    private void saveOriginals(){try{JSONObject o=new JSONObject();JSONArray s=new JSONArray(),a=new JSONArray();for(Map.Entry<String,String>e:originalSpeed.entrySet()){JSONObject x=new JSONObject();x.put("k",e.getKey());x.put("v",e.getValue());s.put(x);}for(Map.Entry<String,Boolean>e:originalAccess.entrySet()){JSONObject x=new JSONObject();x.put("k",e.getKey());x.put("v",e.getValue());a.put(x);}o.put("speed",s);o.put("access",a);getSharedPreferences("bg_originals",MODE_PRIVATE).edit().putString("data",o.toString()).apply();}catch(Exception ignored){}}
    private void loadOriginals(){try{String r=getSharedPreferences("bg_originals",MODE_PRIVATE).getString("data",null);if(r==null)return;JSONObject o=new JSONObject(r);JSONArray s=o.optJSONArray("speed"),a=o.optJSONArray("access");originalSpeed.clear();originalAccess.clear();for(int i=0;s!=null&&i<s.length();i++){JSONObject x=s.getJSONObject(i);originalSpeed.put(x.optString("k"),x.optString("v"));}for(int i=0;a!=null&&i<a.length();i++){JSONObject x=a.getJSONObject(i);originalAccess.put(x.optString("k"),x.optBoolean("v",true));}}catch(Exception ignored){}}
    private void clearOriginals(){originalSpeed.clear();originalAccess.clear();getSharedPreferences("bg_originals",MODE_PRIVATE).edit().clear().apply();}

    private void restoreAndStop(String msg){if(tickRunnable!=null)handler.removeCallbacks(tickRunnable);if(stopRunnable!=null)handler.removeCallbacks(stopRunnable);if(cfg==null){Prefs.bgRunning(this,false);stopSelf();return;}if(originalSpeed.isEmpty()&&originalAccess.isEmpty()){Prefs.bgRunning(this,false);Prefs.bgMode(this,"");Prefs.bgConfig(this,null);clearOriginals();stopSelf();return;}controller.openBandwidthAndRead((list,err)->{if(list!=null){known.clear();for(Models.Device d:list)known.put(d.key(),d);}LinkedHashMap<Models.Device,String> sm=new LinkedHashMap<>();List<Models.Device> ds=new ArrayList<>(known.values());for(Models.Device d:ds){String s=originalSpeed.get(d.key());if((s==null||s.isEmpty())&&d.ip!=null)s=originalSpeed.get(d.ip);if(s!=null&&!s.isEmpty())sm.put(d,s);}applySpeedSeq(sm,()->restoreAccess(ds,0,()->{Prefs.bgRunning(this,false);Prefs.bgMode(this,"");Prefs.bgConfig(this,null);clearOriginals();notifyNow(msg);stopSelf();}));});}
    private void restoreAccess(List<Models.Device>ds,int i,Runnable done){if(i>=ds.size()){done.run();return;}Models.Device d=ds.get(i);Boolean a=originalAccess.get(d.key());if(a==null){restoreAccess(ds,i+1,done);return;}controller.setAccess(d,a,(ok,e)->handler.postDelayed(()->restoreAccess(ds,i+1,done),250));}

    private double randomStepped(double min,double max,double step){if(max<=min)return min;int count=(int)Math.floor((max-min)/step)+1;return Math.min(max,min+random.nextInt(Math.max(1,count))*step);}
    private String nearestOption(Models.Device d,double desired){if(d.options==null||d.options.isEmpty())return String.valueOf(round2(desired));String best=null;double bd=Double.POSITIVE_INFINITY;for(String o:d.options){double r=parseMbps(o);if(Double.isNaN(r)||Double.isInfinite(r))continue;double dif=Math.abs(r-desired);if(dif<bd){bd=dif;best=o;}}return best==null?String.valueOf(round2(desired)):best;}
    private double parseMbps(String s){if(s==null)return Double.NaN;String t=s.toLowerCase(Locale.ROOT).replace(',','.');if(t.matches(".*нет.*огран.*|.*без огранич.*|.*no.*limit.*|.*unlimit.*"))return Double.POSITIVE_INFINITY;java.util.regex.Matcher m=java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(t);if(!m.find())return Double.NaN;double n=Double.parseDouble(m.group(1));if(t.contains("mbit")||t.contains("мбит"))return n;if(t.contains("mb/s")||t.contains("мб/с"))return n*8;if(t.contains("kb")||t.contains("кб"))return n*8/1024.0;if(t.contains("kbit")||t.contains("кбит"))return n/1024.0;return n;}
    private double round2(double x){return Math.round(x*100.0)/100.0;}

    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"Tenda фон",NotificationManager.IMPORTANCE_LOW);c.setDescription("Фоновые режимы управления Tenda F3");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification notification(String text){Intent i=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Tenda Phone Control").setContentText(text).setOngoing(true).setContentIntent(pi).addAction(new Notification.Action.Builder(0, "Стоп", PendingIntent.getService(this, 1, new Intent(this, BackgroundModeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build()).build();}
    private void notifyNow(String text){((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(101,notification(text));}
    @Override public void onDestroy(){releaseLocks();if(tickRunnable!=null)handler.removeCallbacks(tickRunnable);if(stopRunnable!=null)handler.removeCallbacks(stopRunnable);if(web!=null)web.destroy();super.onDestroy();}
    @Nullable @Override public IBinder onBind(Intent intent){return null;}
}
