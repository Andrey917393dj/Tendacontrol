package com.example.tendacontrol;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.os.PowerManager;
import android.provider.Settings;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends android.app.Activity {
    private LinearLayout deviceList, actions;
    private TextView status;
    private EditText search;
    private WebView web;
    private TendaWebController controller;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<Models.Device> devices = new ArrayList<>();
    private final Map<String, String> originals = new LinkedHashMap<>();
    private final Map<String, Boolean> originalAccess = new LinkedHashMap<>();
    private final List<String> history = new ArrayList<>();
    private boolean modeRunning = false;
    private String activeMode = "";
    private Runnable modeTask;
    private Runnable modeStopTask;
    private int waveIndex = 0;
    private boolean waveForward = true;
    private boolean flickerState = false;
    private boolean autoSubmittedLogin = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        final View rootFrame = findViewById(R.id.rootFrame);
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        deviceList = findViewById(R.id.deviceList);
        actions = findViewById(R.id.actions);
        status = findViewById(R.id.status);
        search = findViewById(R.id.search);
        web = findViewById(R.id.web);
        controller = new TendaWebController(this, web);
        controller.setBase(Prefs.router(this));
        if (Prefs.bgRunning(this)) { modeRunning = true; activeMode = "🟢 " + Prefs.bgMode(this) + " • фон"; }

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { renderDevices(); }
            public void afterTextChanged(Editable e) {}
        });
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());

        makeAction("🎲 ХАОС", v -> showChaosDialog(targets()));
        makeAction("🌊 ВОЛНА", v -> showWaveDialog(targets()));
        makeAction("💥 ДЁРГАНИЕ", v -> showJitterDialog(targets()));
        makeAction("🚫 МЕРЦАНИЕ", v -> showFlickerDialog(targets()));
        makeAction("🎯 РУЛЕТКА", v -> showRoulette(targets()));
        makeAction("🔀 ПЕРЕМЕШКА", v -> shuffleLimits());
        makeAction("⚡ БУСТ", v -> showBoostDialog(targets()));
        makeAction("😈 ТРОЛЛ", v -> showTrollMenu(targets()));
        makeAction("🟢 ФОН: " + (Prefs.backgroundEnabled(this) ? "ВКЛ" : "ВЫКЛ"), v -> toggleBackground());
        makeAction("⚖ 5M ВСЕМ", v -> applyGlobal("5"));
        makeAction("∞ ВСЕМ", v -> applyGlobal("0"));
        makeAction("🔓 ВСЕМ", v -> accessGlobal(true));
        makeAction("🚫 ВСЕМ", v -> accessGlobal(false));
        makeAction("⛔ СТОП", v -> stopModeAndRestore());
        makeAction("↩ ВЕРНУТЬ", v -> restoreOriginals());
        makeAction("🧾 ИСТОРИЯ", v -> showHistory());
        makeAction("⚙", v -> showSettings());

        if (!Prefs.configured(this)) showRouterSetup(); else refresh();
    }

    private void makeAction(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setOnClickListener(l);
        actions.addView(b, new LinearLayout.LayoutParams(-2, -2));
    }

    private void showRouterSetup() {
        final EditText ip = new EditText(this);
        ip.setSingleLine(true);
        ip.setText(Prefs.router(this));
        ip.setHint("192.168.0.1");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(40, 8, 40, 8);
        box.addView(ip, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Подключение к Tenda F3")
                .setMessage("Адрес роутера. Обычно 192.168.0.1.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Открыть вход", (d, w) -> {
                    String r = ip.getText().toString().trim();
                    if (r.isEmpty()) r = "192.168.0.1";
                    Prefs.router(this, r);
                    controller.setBase(r);
                    showLogin();
                }).show();
    }

    private void showLogin() {
        web.setVisibility(View.VISIBLE);
        web.bringToFront();
        autoSubmittedLogin = false;
        status.setText("Открыт вход в Tenda");
        controller.loginPage(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                pollLoggedIn(0);
            }
        });
        Toast.makeText(this, "Введи пароль администратора Tenda один раз", Toast.LENGTH_LONG).show();
    }

    private void pollLoggedIn(final int n) {
        if (n > 45) return;
        final String saved = SecureStore.loadPassword(this);
        web.evaluateJavascript("(function(){var t=(document.body&&document.body.innerText)||''; var p=document.querySelector('input[type=password]'); var v=p?p.value:''; return JSON.stringify({hasPass:!!p,hasValue:!!v,ready:/Подключенные устройства|Bandwidth Control|Контроль полосы пропускания/i.test(t)});})()", value -> {
            boolean ready = value != null && value.contains("ready\\\":true") && !value.contains("hasPass\\\":true");
            if (ready) { Prefs.configured(this, true); hideWeb(); refresh(); return; }
            if (saved != null && !saved.isEmpty() && !autoSubmittedLogin) {
                autoSubmittedLogin = true;
                web.evaluateJavascript("(function(){var p=document.querySelector('input[type=password]'); if(!p)return 'no'; p.value='" + jsEscape(saved) + "'; p.dispatchEvent(new Event('input',{bubbles:true})); var bs=[...document.querySelectorAll('button,input[type=submit],input[type=button]')]; var b=bs.find(e=>/войти|вход|login|sign in/i.test((e.innerText||e.value||'').trim()))||document.querySelector('input[type=submit]'); if(b)b.click(); return 'filled';})()", x -> {});
            } else if (value != null && value.contains("hasValue\\\":true")) {
                web.evaluateJavascript("(function(){var p=document.querySelector('input[type=password]'); return p?p.value:'';})()", pw -> {
                    try { String v = unquoteJs(pw); if (!v.isEmpty()) SecureStore.savePassword(this, v); } catch (Exception ignored) {}
                });
            }
            handler.postDelayed(() -> pollLoggedIn(n + 1), 1000);
        });
    }

    private static String unquoteJs(String s) {
        if (s == null) return "";
        try { return new org.json.JSONTokener(s).nextValue().toString(); }
        catch (Exception e) { return s.replaceAll("^\"|\"$", "").replace("\\\"", "\""); }
    }
    public static String jsEscapePublic(String s) { return jsEscape(s); }
    private static String jsEscape(String s) {
        return (s == null ? "" : s).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }
    private void hideWeb() { web.stopLoading(); web.setVisibility(View.GONE); status.setText("Подключение…"); }

    private void refresh() {
        if (web.getVisibility() != View.GONE) return;
        status.setText("Обновление устройств…");
        controller.openBandwidthAndRead((list, error) -> runOnUiThread(() -> {
            if (error != null) { status.setText("Ошибка: " + error); return; }
            devices.clear();
            if (list != null) devices.addAll(list);
            renderDevices();
            if (Prefs.bgRunning(this)) { modeRunning = true; activeMode = "🟢 " + Prefs.bgMode(this) + " • фон"; }
            status.setText("🟢 Tenda F3 • устройств: " + devices.size() + (modeRunning ? " • " + activeMode : ""));
        }));
    }

    private void renderDevices() {
        deviceList.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Models.Device> list = new ArrayList<>();
        for (Models.Device d : devices) {
            if (q.isEmpty() || d.name.toLowerCase(Locale.ROOT).contains(q) || d.ip.contains(q)) list.add(d);
        }
        Collections.sort(list, Comparator.comparing(d -> d.name.toLowerCase(Locale.ROOT)));
        if (list.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Устройства не найдены. Нажми ↻ после подключения к Wi‑Fi.");
            t.setTextColor(getColor(R.color.muted));
            t.setPadding(24, 30, 24, 30);
            deviceList.addView(t);
            return;
        }
        for (Models.Device d : list) addDeviceCard(d);
    }

    private void addDeviceCard(final Models.Device d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(18, 14, 18, 14);
        card.setBackgroundResource(R.drawable.device_card);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, 12);
        deviceList.addView(card, cp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox check = new CheckBox(this);
        check.setChecked(d.selected);
        check.setOnCheckedChangeListener((b, v) -> d.selected = v);
        top.addView(check);
        TextView name = new TextView(this);
        name.setText((d.enabled ? "🟢 " : "🔴 ") + d.name + (d.frozen ? " ❄" : ""));
        name.setTextColor(getColor(R.color.text));
        name.setTextSize(18);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(name);
        Button edit = new Button(this); edit.setText("✎"); edit.setOnClickListener(v -> editDevice(d)); top.addView(edit);
        Button troll = new Button(this); troll.setText("😈"); troll.setOnClickListener(v -> showTrollMenu(Collections.singletonList(d))); top.addView(troll);
        Button freeze = new Button(this); freeze.setText(d.frozen ? "❄" : "○"); freeze.setOnClickListener(v -> { d.frozen = !d.frozen; Prefs.frozen(this, d.id, d.frozen); renderDevices(); }); top.addView(freeze);
        card.addView(top);

        TextView info = new TextView(this);
        info.setText(d.ip + "  •  " + (d.currentText.isEmpty() ? "без данных" : d.currentText));
        info.setTextColor(getColor(R.color.muted));
        info.setTextSize(13);
        card.addView(info);

        LinearLayout speed = new LinearLayout(this);
        speed.setOrientation(LinearLayout.HORIZONTAL);
        String[] qs = {"1", "5", "10", "20", "50", "0"};
        for (String qv : qs) {
            Button b = smallButton(qv.equals("0") ? "∞" : qv + "M");
            b.setOnClickListener(v -> applyOne(d, qv));
            speed.addView(b);
        }
        card.addView(speed);

        LinearLayout row = new LinearLayout(this);
        Button block = new Button(this);
        block.setText(d.enabled ? "🚫 Заблокировать" : "✅ Разрешить");
        block.setOnClickListener(v -> setAccess(d, !d.enabled));
        row.addView(block, new LinearLayout.LayoutParams(0, -2, 1));
        Button custom = new Button(this);
        custom.setText("⚙ Свой лимит");
        custom.setOnClickListener(v -> showCustomSpeed(d));
        row.addView(custom, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
    }

    private Button smallButton(String s) { Button b = new Button(this); b.setText(s); b.setTextSize(11); return b; }

    private void applyOne(Models.Device d, String speed) {
        saveOriginal(d);
        status.setText("Меняю " + d.name + "…");
        controller.setSpeed(d, speed, (ok, e) -> runOnUiThread(() -> {
            addHistory((ok ? "✓ " : "✗ ") + d.name + " → " + (speed.equals("0") ? "без лимита" : speed + " Мбит/с"));
            refresh();
        }));
    }

    private List<Models.Device> targets() {
        List<Models.Device> selected = new ArrayList<>();
        List<Models.Device> allFree = new ArrayList<>();
        for (Models.Device d : devices) {
            if (d.selected) selected.add(d);
            if (!d.frozen) allFree.add(d);
        }
        return selected.isEmpty() ? allFree : selected;
    }

    private void applyGlobal(String speed) {
        List<Models.Device> ts = targets();
        if (ts.isEmpty()) return;
        AtomicInteger left = new AtomicInteger(ts.size());
        for (Models.Device d : ts) {
            saveOriginal(d);
            controller.setSpeed(d, speed, (ok, e) -> { if (left.decrementAndGet() == 0) runOnUiThread(() -> { addHistory("Массовый лимит → " + (speed.equals("0") ? "без лимита" : speed + " Мбит/с") + " (" + ts.size() + ")"); refresh(); }); });
        }
    }

    private void accessGlobal(boolean allow) {
        List<Models.Device> ts = targets();
        for (Models.Device d : ts) saveAccessOriginal(d);
        for (Models.Device d : ts) controller.setAccess(d, allow, (ok, e) -> {});
        handler.postDelayed(this::refresh, 1000);
        addHistory((allow ? "Разрешён" : "Заблокирован") + " интернет для " + ts.size() + " устройств");
    }

    private void setAccess(Models.Device d, boolean allow) {
        saveAccessOriginal(d);
        controller.setAccess(d, allow, (ok, e) -> runOnUiThread(() -> { addHistory(d.name + " → " + (allow ? "доступ разрешён" : "интернет заблокирован")); refresh(); }));
    }

    private void editDevice(Models.Device d) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setText(d.name); e.setSelection(e.length());
        new AlertDialog.Builder(this).setTitle("Название устройства").setMessage(d.ip + "\nID: " + d.id).setView(e)
                .setPositiveButton("Сохранить", (a, w) -> { String n = e.getText().toString().trim(); if (n.isEmpty()) n = d.ip; d.name = n; Prefs.setLabel(this, d.id, n); addHistory("Переименовано: " + d.ip + " → " + n); renderDevices(); })
                .setNegativeButton("Отмена", null).show();
    }

    private void showCustomSpeed(final Models.Device d) {
        final EditText e = new EditText(this); e.setSingleLine(true); e.setHint("например 7.5"); e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        new AlertDialog.Builder(this).setTitle("Лимит для " + d.name).setMessage("Введите Мбит/с. 0 = без ограничения.").setView(e)
                .setPositiveButton("Применить", (a, w) -> applyOne(d, e.getText().toString().trim().isEmpty() ? "0" : e.getText().toString().trim()))
                .setNegativeButton("Отмена", null).show();
    }

    private void showTrollMenu(List<Models.Device> ts) {
        if (ts.isEmpty()) { toast("Нет устройств для режима"); return; }
        String targetText = ts.size() == 1 ? ts.get(0).name : "выбранные (" + ts.size() + ")";
        String[] items = {"🎲 Хаос на время", "🌊 Волна", "💥 Дёргание", "🚫 Мерцание доступа", "🐢 Временно замедлить", "⚡ Временно ускорить", "🎯 Рулетка"};
        new AlertDialog.Builder(this).setTitle("😈 Троллинг • " + targetText).setItems(items, (d, which) -> {
            switch (which) {
                case 0: showChaosDialog(ts); break;
                case 1: showWaveDialog(ts); break;
                case 2: showJitterDialog(ts); break;
                case 3: showFlickerDialog(ts); break;
                case 4: showTemporarySpeed(ts, false); break;
                case 5: showTemporarySpeed(ts, true); break;
                case 6: showRoulette(ts); break;
            }
        }).show();
    }

    private LinearLayout fieldBox() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(36, 0, 36, 0); return box; }
    private EditText numberField(String value, String hint) { EditText e = new EditText(this); e.setSingleLine(true); e.setText(value); e.setHint(hint); e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); return e; }

    private void showChaosDialog(final List<Models.Device> ts) {
        EditText min = numberField("1", "мин, Мбит/с");
        EditText max = numberField("20", "макс, Мбит/с");
        EditText step = numberField("1", "шаг, Мбит/с");
        EditText interval = numberField("10", "интервал, сек");
        EditText duration = numberField("60", "длительность, сек (0 = бесконечно)");
        CheckBox noLimit = new CheckBox(this); noLimit.setText("Разрешить '∞' как случайный результат");
        LinearLayout box = fieldBox(); box.addView(label("Минимум")); box.addView(min); box.addView(label("Максимум")); box.addView(max); box.addView(label("Шаг")); box.addView(step); box.addView(label("Менять каждые")); box.addView(interval); box.addView(label("Работать")); box.addView(duration); box.addView(noLimit);
        new AlertDialog.Builder(this).setTitle("🎲 ХАОС — подробно").setMessage("Каждый тик выбирается случайная скорость из диапазона с указанным шагом. Доступное значение в Tenda берётся ближайшее.").setView(box)
                .setPositiveButton("Запустить", (a, w) -> startChaos(ts, safeDouble(min.getText().toString(), 1), safeDouble(max.getText().toString(), 20), safeDouble(step.getText().toString(), 1), Math.max(2, safeInt(interval.getText().toString(), 10)), Math.max(0, safeInt(duration.getText().toString(), 60)), noLimit.isChecked()))
                .setNegativeButton("Отмена", null).show();
    }

    private void startChaos(final List<Models.Device> ts, double min, double max, double step, int interval, int duration, boolean includeUnlimited) {
        if (!validateRange(ts, min, max, step)) return;
        if (Prefs.backgroundEnabled(this)) { JSONObject c=baseBgConfig("CHAOS",ts); try{c.put("min",min);c.put("max",max);c.put("step",step);c.put("interval",interval);c.put("duration",duration);c.put("unlimited",includeUnlimited);}catch(Exception ignored){} startBackground(c); return; }
        prepareNewMode("🎲 ХАОС", ts);
        final double fMin = min, fMax = max, fStep = step;
        startRecurringMode("🎲 ХАОС", ts, interval, duration, () -> {
            Map<Models.Device, String> m = new LinkedHashMap<>();
            for (Models.Device d : ts) if (!d.frozen) {
                if (includeUnlimited && random.nextInt(20) == 0) m.put(d, "0");
                else m.put(d, nearestOption(d, randomStepped(fMin, fMax, fStep)));
            }
            applyMap(m);
        });
    }

    private double randomStepped(double min, double max, double step) {
        if (max <= min) return min;
        int count = (int) Math.floor((max - min) / step) + 1;
        int index = random.nextInt(Math.max(1, count));
        return Math.min(max, min + index * step);
    }

    private void showWaveDialog(final List<Models.Device> ts) {
        EditText min = numberField("1", "мин"); EditText max = numberField("20", "макс"); EditText step = numberField("2", "шаг"); EditText interval = numberField("5", "интервал, сек"); EditText duration = numberField("60", "длительность, сек");
        LinearLayout box = fieldBox(); box.addView(label("Мин, Мбит/с")); box.addView(min); box.addView(label("Макс, Мбит/с")); box.addView(max); box.addView(label("Шаг, Мбит/с")); box.addView(step); box.addView(label("Интервал, сек")); box.addView(interval); box.addView(label("Длительность, сек (0 = бесконечно)")); box.addView(duration);
        new AlertDialog.Builder(this).setTitle("🌊 ВОЛНА").setMessage("Постепенно идёт вверх по диапазону, потом вниз. Получается предсказуемый 'качели' режим.").setView(box)
                .setPositiveButton("Запустить", (a, w) -> startWave(ts, safeDouble(min.getText().toString(), 1), safeDouble(max.getText().toString(), 20), safeDouble(step.getText().toString(), 2), Math.max(2, safeInt(interval.getText().toString(), 5)), Math.max(0, safeInt(duration.getText().toString(), 60))))
                .setNegativeButton("Отмена", null).show();
    }

    private void startWave(final List<Models.Device> ts, double min, double max, double step, int interval, int duration) {
        if (!validateRange(ts, min, max, step)) return;
        if (Prefs.backgroundEnabled(this)) { JSONObject c=baseBgConfig("WAVE",ts); try{c.put("min",min);c.put("max",max);c.put("step",step);c.put("interval",interval);c.put("duration",duration);c.put("waveIndex",0);c.put("waveForward",true);}catch(Exception ignored){} startBackground(c); return; }
        prepareNewMode("🌊 ВОЛНА", ts); waveIndex = 0; waveForward = true;
        final int count = Math.max(1, (int)Math.floor((max - min) / step));
        startRecurringMode("🌊 ВОЛНА", ts, interval, duration, () -> {
            double val = min + waveIndex * step;
            if (waveForward) { waveIndex++; if (waveIndex >= count) { waveIndex = count; waveForward = false; } }
            else { waveIndex--; if (waveIndex <= 0) { waveIndex = 0; waveForward = true; } }
            Map<Models.Device, String> m = new LinkedHashMap<>();
            for (Models.Device d : ts) if (!d.frozen) m.put(d, nearestOption(d, val));
            applyMap(m);
        });
    }

    private void showJitterDialog(final List<Models.Device> ts) {
        EditText low = numberField("1", "низкая скорость"); EditText high = numberField("20", "высокая скорость"); EditText interval = numberField("8", "интервал, сек"); EditText duration = numberField("60", "длительность, сек");
        LinearLayout box = fieldBox(); box.addView(label("Низкая, Мбит/с")); box.addView(low); box.addView(label("Высокая, Мбит/с")); box.addView(high); box.addView(label("Интервал, сек")); box.addView(interval); box.addView(label("Длительность, сек (0 = бесконечно)")); box.addView(duration);
        new AlertDialog.Builder(this).setTitle("💥 ДЁРГАНИЕ").setMessage("Чередует низкую и высокую скорость. Проще и жёстче хаоса.").setView(box)
                .setPositiveButton("Запустить", (a, w) -> startJitter(ts, safeDouble(low.getText().toString(), 1), safeDouble(high.getText().toString(), 20), Math.max(2, safeInt(interval.getText().toString(), 8)), Math.max(0, safeInt(duration.getText().toString(), 60))))
                .setNegativeButton("Отмена", null).show();
    }

    private void startJitter(final List<Models.Device> ts, final double low, final double high, int interval, int duration) {
        if (!validateRange(ts, low, high, 0.01)) return;
        if (Prefs.backgroundEnabled(this)) { JSONObject c=baseBgConfig("JITTER",ts); try{c.put("low",low);c.put("high",high);c.put("interval",interval);c.put("duration",duration);c.put("jitterHigh",false);}catch(Exception ignored){} startBackground(c); return; }
        prepareNewMode("💥 ДЁРГАНИЕ", ts); final boolean[] highState = {false};
        startRecurringMode("💥 ДЁРГАНИЕ", ts, interval, duration, () -> {
            highState[0] = !highState[0]; double val = highState[0] ? high : low;
            Map<Models.Device, String> m = new LinkedHashMap<>();
            for (Models.Device d : ts) if (!d.frozen) m.put(d, nearestOption(d, val));
            applyMap(m);
        });
    }

    private void showFlickerDialog(final List<Models.Device> ts) {
        EditText interval = numberField("5", "интервал, сек"); EditText duration = numberField("30", "длительность, сек");
        LinearLayout box = fieldBox(); box.addView(label("Переключать каждые, сек")); box.addView(interval); box.addView(label("Длительность, сек (0 = бесконечно)")); box.addView(duration);
        new AlertDialog.Builder(this).setTitle("🚫 МЕРЦАНИЕ").setMessage("Чередует доступ в интернет ВКЛ/ВЫКЛ. В конце возвращает исходное состояние доступа.").setView(box)
                .setPositiveButton("Запустить", (a, w) -> startFlicker(ts, Math.max(3, safeInt(interval.getText().toString(), 5)), Math.max(0, safeInt(duration.getText().toString(), 30))))
                .setNegativeButton("Отмена", null).show();
    }

    private void startFlicker(final List<Models.Device> ts, int interval, int duration) {
        if (Prefs.backgroundEnabled(this)) { JSONObject c=baseBgConfig("FLICKER",ts); try{c.put("interval",interval);c.put("duration",duration);c.put("flicker",false);}catch(Exception ignored){} startBackground(c); return; }
        prepareNewMode("🚫 МЕРЦАНИЕ", ts); flickerState = false;
        startRecurringMode("🚫 МЕРЦАНИЕ", ts, interval, duration, () -> {
            flickerState = !flickerState;
            for (Models.Device d : ts) if (!d.frozen) controller.setAccess(d, flickerState, (ok, e) -> {});
        });
    }

    private void showTemporarySpeed(final List<Models.Device> ts, final boolean boost) {
        EditText speed = numberField(boost ? "25" : "1", "Мбит/с"); EditText duration = numberField("30", "сек");
        LinearLayout box = fieldBox(); box.addView(label("Скорость, Мбит/с")); box.addView(speed); box.addView(label("Длительность, сек")); box.addView(duration);
        new AlertDialog.Builder(this).setTitle(boost ? "⚡ ВРЕМЕННЫЙ БУСТ" : "🐢 ВРЕМЕННО ЗАМЕДЛИТЬ").setMessage("После таймера возвращается исходный лимит.").setView(box)
                .setPositiveButton("Запустить", (a, w) -> {
                    int sec = Math.max(3, safeInt(duration.getText().toString(), 30));
                    if (Prefs.backgroundEnabled(MainActivity.this)) { JSONObject c=baseBgConfig(boost?"BOOST":"SLOW",ts); try{c.put("speed",speed.getText().toString().trim().isEmpty()?(boost?"25":"1"):speed.getText().toString().trim());c.put("interval",Math.max(3,sec));c.put("duration",sec);}catch(Exception ignored){} startBackground(c); return; }
                    prepareNewMode(boost ? "⚡ БУСТ" : "🐢 ЗАМЕДЛЕНИЕ", ts);
                    final String s = speed.getText().toString().trim().isEmpty() ? (boost ? "25" : "1") : speed.getText().toString().trim();
                    for (Models.Device d : ts) if (!d.frozen) controller.setSpeed(d, s, (ok, e) -> {});
                    scheduleModeStop(sec);
                    addHistory((boost ? "⚡ Буст " : "🐢 Замедление ") + ts.size() + " устройств на " + sec + " с");
                }).setNegativeButton("Отмена", null).show();
    }

    private void showRoulette(final List<Models.Device> ts) {
        if (ts.isEmpty()) return;
        EditText min = numberField("1", "мин"); EditText max = numberField("10", "макс"); EditText sec = numberField("30", "сек");
        LinearLayout box = fieldBox(); box.addView(label("Диапазон, Мбит/с")); box.addView(min); box.addView(max); box.addView(label("Время на выбранного, сек")); box.addView(sec);
        new AlertDialog.Builder(this).setTitle("🎯 РУЛЕТКА").setMessage("Выбирается один участник и на время получает случайный лимит. Потом исходное значение возвращается.").setView(box)
                .setPositiveButton("Крутить", (a, w) -> {
                    Models.Device d = ts.get(random.nextInt(ts.size()));
                    double lo = safeDouble(min.getText().toString(), 1), hi = safeDouble(max.getText().toString(), 10);
                    if (!validateRange(Collections.singletonList(d), lo, hi, 0.01)) return;
                    if (Prefs.backgroundEnabled(MainActivity.this)) { int sec2=Math.max(3,safeInt(sec.getText().toString(),30)); JSONObject c=baseBgConfig("ROULETTE",Collections.singletonList(d)); try{c.put("min",lo);c.put("max",hi);c.put("interval",sec2);c.put("duration",sec2);}catch(Exception ignored){} startBackground(c); return; }
                    prepareNewMode("🎯 РУЛЕТКА", Collections.singletonList(d));
                    String chosen = nearestOption(d, lo + random.nextDouble() * Math.max(0, hi - lo));
                    controller.setSpeed(d, chosen, (ok, e) -> {});
                    int n = Math.max(3, safeInt(sec.getText().toString(), 30));
                    scheduleModeStop(n);
                    addHistory("🎯 Рулетка: " + d.name + " → " + chosen + " на " + n + " с");
                    handler.postDelayed(this::refresh, 800);
                }).setNegativeButton("Отмена", null).show();
    }

    private void shuffleLimits() {
        List<Models.Device> ts = targets();
        if (ts.size() < 2) { toast("Нужно минимум 2 устройства"); return; }
        List<String> vals = new ArrayList<>();
        for (Models.Device d : ts) { saveOriginal(d); vals.add(d.currentText); }
        Collections.shuffle(vals, random);
        for (int i = 0; i < ts.size(); i++) {
            String v = vals.get(i);
            if (v != null && !v.isEmpty()) controller.setSpeed(ts.get(i), v, (ok, e) -> {});
        }
        addHistory("🔀 Перемешаны лимиты для " + ts.size() + " устройств");
        handler.postDelayed(this::refresh, 1000);
    }

    private void showBoostDialog(final List<Models.Device> ts) { showTemporarySpeed(ts, true); }

    private void startRecurringMode(final String name, final List<Models.Device> ts, final int intervalSec, final int durationSec, final Runnable tick) {
        modeRunning = true; activeMode = name; status.setText(name + " запущен");
        if (modeTask != null) handler.removeCallbacks(modeTask);
        modeTask = new Runnable() { @Override public void run() {
            if (!modeRunning) return;
            tick.run();
            handler.postDelayed(this, intervalSec * 1000L);
        }};
        handler.post(modeTask);
        if (durationSec > 0) scheduleModeStop(durationSec);
    }

    private void scheduleModeStop(int durationSec) {
        if (modeStopTask != null) handler.removeCallbacks(modeStopTask);
        modeStopTask = () -> stopModeAndRestore();
        handler.postDelayed(modeStopTask, durationSec * 1000L);
    }

    private void prepareNewMode(String name, List<Models.Device> ts) {
        if (modeRunning) stopModeAndRestore();
        else if (!originals.isEmpty() || !originalAccess.isEmpty()) restoreOriginalsInternal(false);
        for (Models.Device d : ts) { if (d.frozen) continue; saveOriginal(d); saveAccessOriginal(d); }
        addHistory(name + " → старт (" + ts.size() + ")");
    }

    private void applyMap(Map<Models.Device, String> map) {
        for (Map.Entry<Models.Device, String> e : map.entrySet()) controller.setSpeed(e.getKey(), e.getValue(), (ok, err) -> {});
        if (!map.isEmpty()) addHistory(activeMode + " → обновлено " + map.size() + " устройств");
    }

    private boolean validateRange(List<Models.Device> ts, double min, double max, double step) {
        if (ts.isEmpty()) { toast("Нет устройств для режима"); return false; }
        if (Double.isNaN(min) || Double.isNaN(max) || min < 0 || max < min || step <= 0) { toast("Проверь диапазон и шаг"); return false; }
        return true;
    }

    private TextView label(String s) { TextView t = new TextView(this); t.setText(s); t.setTextColor(getColor(R.color.muted)); t.setPadding(0, 8, 0, 2); return t; }

    private String nearestOption(Models.Device d, double desiredMbps) {
        if (d.options == null || d.options.isEmpty()) return String.valueOf(round2(desiredMbps));
        String best = null; double bestDiff = Double.POSITIVE_INFINITY;
        for (String o : d.options) {
            double r = parseMbps(o);
            if (Double.isNaN(r) || Double.isInfinite(r)) continue;
            double diff = Math.abs(r - desiredMbps);
            if (diff < bestDiff) { bestDiff = diff; best = o; }
        }
        return best == null ? String.valueOf(round2(desiredMbps)) : best;
    }

    private double parseMbps(String s) {
        if (s == null) return Double.NaN;
        String t = s.toLowerCase(Locale.ROOT).replace(',', '.');
        if (t.matches(".*нет.*огран.*|.*без огранич.*|.*no.*limit.*|.*unlimit.*")) return Double.POSITIVE_INFINITY;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(t);
        if (!m.find()) return Double.NaN;
        double n = Double.parseDouble(m.group(1));
        if (t.contains("mbit") || t.contains("мбит")) return n;
        if (t.contains("mb/s") || t.contains("мб/с")) return n * 8;
        if (t.contains("kb") || t.contains("кб")) return n * 8 / 1024.0;
        if (t.contains("kbit") || t.contains("кбит")) return n / 1024.0;
        return n;
    }

    private double round2(double x) { return Math.round(x * 100.0) / 100.0; }

    private void saveOriginal(Models.Device d) { if (!originals.containsKey(d.id)) originals.put(d.id, d.currentText); }
    private void saveAccessOriginal(Models.Device d) { if (!originalAccess.containsKey(d.id)) originalAccess.put(d.id, d.enabled); }

    private void stopModeAndRestore() {
        if (Prefs.bgRunning(this)) BackgroundModeService.stop(this);
        if (modeTask != null) handler.removeCallbacks(modeTask);
        if (modeStopTask != null) handler.removeCallbacks(modeStopTask);
        boolean was = modeRunning;
        modeRunning = false;
        activeMode = "";
        restoreOriginalsInternal(was);
    }

    private void restoreOriginals() { stopModeAndRestore(); }

    private void restoreOriginalsInternal(boolean log) {
        int count = originals.size();
        int accessCount = originalAccess.size();
        for (Models.Device d : devices) {
            String s = originals.get(d.id);
            if (s != null && !s.isEmpty()) controller.setSpeed(d, s, (ok, e) -> {});
            Boolean a = originalAccess.get(d.id);
            if (a != null) controller.setAccess(d, a, (ok, e) -> {});
        }
        originals.clear();
        originalAccess.clear();
        if (count > 0 || accessCount > 0) {
            if (log) addHistory("↩ Возвращены исходные значения");
            handler.postDelayed(this::refresh, 900);
        }
    }

    private void showHistory() {
        StringBuilder sb = new StringBuilder();
        if (history.isEmpty()) sb.append("История пока пустая.");
        else for (String s : history) sb.append(s).append('\n');
        new AlertDialog.Builder(this).setTitle("История").setMessage(sb.toString()).setPositiveButton("ОК", null).show();
    }

    private void addHistory(String s) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        history.add(0, ts + "  " + s);
        while (history.size() > 100) history.remove(history.size() - 1);
    }

    private void toggleBackground() {
        boolean v=!Prefs.backgroundEnabled(this); Prefs.backgroundEnabled(this,v);
        toast(v?"Фоновые режимы включены":"Фоновые режимы выключены");
        if(v) maybeOfferBatteryOptimization();
    }
    private void maybeOfferBatteryOptimization(){
        if(android.os.Build.VERSION.SDK_INT<23)return;
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null&&!pm.isIgnoringBatteryOptimizations(getPackageName())) new AlertDialog.Builder(this)
                .setTitle("Надёжный фон")
                .setMessage("Для работы при погашенном экране разреши приложению не использовать оптимизацию батареи. На Samsung также убери его из списка спящих приложений.")
                .setPositiveButton("Открыть",(d,w)->{try{startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:"+getPackageName())));}catch(Exception e){startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}})
                .setNegativeButton("Позже",null).show();
    }
    private JSONObject baseBgConfig(String mode,List<Models.Device>ts){JSONObject o=new JSONObject();try{o.put("mode",mode);JSONArray a=new JSONArray();if(ts!=null)for(Models.Device d:ts)a.put(d.key());o.put("targets",a);o.put("targetsMode","list");}catch(Exception ignored){}return o;}
    private void startBackground(JSONObject config){maybeOfferBatteryOptimization();BackgroundModeService.start(this,config);modeRunning=true;activeMode="🟢 "+config.optString("mode","ФОН")+" • фон";status.setText(activeMode+" запущен");}

    private void showSettings() {
        EditText ip = new EditText(this); ip.setSingleLine(true); ip.setText(Prefs.router(this));
        CheckBox bg = new CheckBox(this); bg.setText("Фоновые режимы по умолчанию"); bg.setChecked(Prefs.backgroundEnabled(this));
        LinearLayout box=fieldBox(); box.addView(label("Адрес роутера")); box.addView(ip); box.addView(bg);
        new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setMessage("Пароль хранится локально. Фон работает через видимый foreground service.")
                .setView(box)
                .setPositiveButton("Сохранить", (a,w)->{String r=ip.getText().toString().trim();if(r.isEmpty())r="192.168.0.1";Prefs.router(this,r);Prefs.backgroundEnabled(this,bg.isChecked());controller.setBase(r);Prefs.configured(this,false);if(bg.isChecked())maybeOfferBatteryOptimization();showLogin();})
                .setNeutralButton("Батарея",(a,w)->maybeOfferBatteryOptimization())
                .setNegativeButton("Отмена",null).show();
    }

    private int safeInt(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }
    private double safeDouble(String s, double d) { try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (Exception e) { return d; } }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (!Prefs.bgRunning(this)) modeRunning = false;
        if (!Prefs.bgRunning(this) && modeTask != null) handler.removeCallbacks(modeTask);
        if (modeStopTask != null) handler.removeCallbacks(modeStopTask);
        if (web != null) web.destroy();
    }
}
