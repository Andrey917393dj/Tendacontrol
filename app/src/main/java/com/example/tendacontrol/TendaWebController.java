package com.example.tendacontrol;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TendaWebController {
    public interface Callback<T> { void done(T value, String error); }

    private final Context context;
    private final WebView web;
    private String base;

    @SuppressLint("SetJavaScriptEnabled")
    public TendaWebController(Context context, WebView web) {
        this.context = context;
        this.web = web;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setUserAgentString(s.getUserAgentString() + " TendaPhoneControl/1.0");
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebChromeClient(new WebChromeClient());
    }

    public void setBase(String router) {
        String r = router.trim();
        if (!r.startsWith("http://") && !r.startsWith("https://")) r = "http://" + r;
        if (r.endsWith("/")) r = r.substring(0, r.length()-1);
        base = r;
    }

    public String base() { return base; }

    public WebView webView() { return web; }

    public void loginPage(WebViewClient extra) {
        web.setWebViewClient(extra);
        web.loadUrl(base + "/index.htr");
    }

    public void openBandwidthAndRead(final Callback<List<Models.Device>> cb) {
        if (base == null) { cb.done(null, "Не задан адрес роутера"); return; }
        web.loadUrl(base + "/index.htr");
        web.postDelayed(new Runnable() {
            @Override public void run() {
                clickBandwidth(new Runnable() {
                    @Override public void run() { readDevices(cb); }
                });
            }
        }, 500);
    }

    private void clickBandwidth(final Runnable after) {
        String js = "javascript:(function(){" +
                "var els=[...document.querySelectorAll('a,button,div,span,li')];" +
                "var keys=['Контроль полосы пропускания','Bandwidth Control','控制带宽','bandwidth'];" +
                "for(var e of els){var t=(e.innerText||e.textContent||'').trim(); if(t && keys.some(k=>t.toLowerCase()===k.toLowerCase() || t.toLowerCase().includes(k.toLowerCase()))){try{e.click();return 'clicked';}catch(x){}}}" +
                "return 'notfound';})()";
        web.evaluateJavascript(js, value -> web.postDelayed(after, 700));
    }

    private void readDevices(final Callback<List<Models.Device>> cb) {
        String js = "javascript:(function(){" +
                "function esc(s){return (s||'').replace(/\\\\/g,'\\\\\\\\').replace(/\"/g,'\\\\\"').replace(/\\n/g,' ');}" +
                "var out=[], seen={};" +
                "document.querySelectorAll('select').forEach(function(sel){" +
                " var row=sel,txt='',ip=null,mac=null; for(var i=0;i<10&&row;i++,row=row.parentElement){txt=(row.innerText||row.textContent||'').trim(); if(!ip){var m=txt.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/); if(m)ip=m[0];} if(!mac){var mm=txt.match(/(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}/); if(mm)mac=mm[0];}}" +
                " if(!ip || seen[ip]) return; seen[ip]=1;" +
                " var options=[...sel.options].map(o=>({t:(o.textContent||'').trim(),v:o.value||''}));" +
                " var lines=txt.split(/\\n+/).map(x=>x.trim()).filter(Boolean); var name=lines.find(x=>x && !x.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/)) || ip;" +
                " var control=row||sel; var toggle=control.querySelector('input[type=checkbox],input[type=radio],[role=switch]');" +
                " out.push({id:sel.value||sel.getAttribute('data-id')||ip,ip:ip,mac:mac||'',name:name,current:(sel.options[sel.selectedIndex]||{}).textContent||'',enabled:toggle?!!toggle.checked:true,options:options});" +
                "});" +
                "return JSON.stringify(out);})()";
        web.evaluateJavascript(js, value -> {
            try {
                if (value == null || value.equals("null")) { cb.done(new ArrayList<>(), null); return; }
                String json = value;
                try {
                    Object decoded = new org.json.JSONTokener(value).nextValue();
                    if (decoded != null) json = decoded.toString();
                } catch (Exception ignored) {}
                JSONArray arr = new JSONArray(json);
                List<Models.Device> list = new ArrayList<>();
                for (int i=0;i<arr.length();i++) {
                    JSONObject o = arr.getJSONObject(i); Models.Device d = new Models.Device();
                    d.id=o.optString("id", d.ip); d.ip=o.optString("ip", ""); d.mac=o.optString("mac", ""); d.name=o.optString("name", d.ip);
                    d.currentText=o.optString("current", ""); d.enabled=o.optBoolean("enabled", true);
                    JSONArray ops=o.optJSONArray("options"); if(ops!=null) for(int j=0;j<ops.length();j++) d.options.add(ops.getJSONObject(j).optString("t",""));
                    d.name=Prefs.label(context,d.id,d.name); d.frozen=Prefs.frozen(context,d.id); list.add(d);
                }
                cb.done(list,null);
            } catch(Exception e) { cb.done(null, "Не удалось разобрать список устройств: " + e.getMessage()); }
        });
    }

    public void setSpeed(final Models.Device device, final String target, final Callback<Boolean> cb) {
        String payloadTarget = target == null ? "" : target;
        String ip = device.ip;
        String js = "javascript:(function(){" +
                "function rate(t){t=(t||'').toLowerCase().replace(',','.'); if(/нет.*огран|no.*limit|unlimit|без огранич/.test(t))return 1e99; var n=parseFloat((t.match(/[0-9]+(?:\\.[0-9]+)?/)||['0'])[0]); if(/mbit|мбит/.test(t))return n; if(/kbit|кбит/.test(t))return n/1024; if(/kb|кб/.test(t))return n*8/1024; return n;}" +
                "var sels=[...document.querySelectorAll('select')],sel=null,row=null; for(var s of sels){var r=s,tx=''; for(var i=0;i<9&&r;i++,r=r.parentElement){tx=(r.innerText||r.textContent||'').trim(); if(tx.includes('" + escJs(ip) + "')){sel=s;row=r;break;}} if(sel)break;}" +
                "if(!sel)return 'no_select'; var opts=[...sel.options].map(o=>({o:o,t:(o.textContent||'').trim(),r:rate(o.textContent||'')})); var want='" + escJs(payloadTarget) + "'; var num=parseFloat(want)||0; var cand=opts.find(x=>x.t.toLowerCase()===want.toLowerCase()); if(!cand && num===0){cand=opts.find(x=>x.r>1e90 || /нет.*огран|no.*limit|unlimit|без огранич/i.test(x.t));} if(!cand){opts=opts.filter(x=>x.r<1e90); if(!opts.length)return 'no_option'; opts.sort((a,b)=>Math.abs(a.r-num)-Math.abs(b.r-num)); cand=opts[0];} sel.value=cand.o.value; ['input','change'].forEach(e=>sel.dispatchEvent(new Event(e,{bubbles:true})));" +
                "var btn=[...document.querySelectorAll('button,input[type=button],input[type=submit],a')].find(e=>/^ok$|^сохранить$|^应用$|^save$/i.test((e.innerText||e.value||'').trim())); if(btn)btn.click(); return cand.t;})()";
        web.evaluateJavascript(js, value -> cb.done(value != null && !value.contains("no_"), value));
    }

    public void setAccess(final Models.Device device, final boolean allow, final Callback<Boolean> cb) {
        String ip = device.ip;
        String js = "javascript:(function(){var els=[...document.querySelectorAll('input[type=checkbox],input[type=radio],[role=switch],button')]; for(var e of els){var r=e,tx=''; for(var i=0;i<9&&r;i++,r=r.parentElement){tx=(r.innerText||r.textContent||'').trim(); if(tx.includes('"+escJs(ip)+"')){var cur=('checked' in e)?!!e.checked:(e.getAttribute('aria-checked')==='true'); if(cur!=="+(allow?"true":"false")+") e.click(); return 'ok';}}} return 'no_toggle';})()";
        web.evaluateJavascript(js, value -> cb.done(value != null && value.contains("ok"), value));
    }

    private static String escJs(String s){return (s==null?"":s).replace("\\","\\\\").replace("'","\\'").replace("\"","\\\"");}
}
