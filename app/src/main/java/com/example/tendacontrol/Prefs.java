package com.example.tendacontrol;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String P = "app";
    private Prefs() {}
    private static SharedPreferences p(Context c) { return c.getSharedPreferences(P, Context.MODE_PRIVATE); }
    public static String router(Context c) { return p(c).getString("router", "192.168.0.1"); }
    public static void router(Context c, String v) { p(c).edit().putString("router", v).apply(); }
    public static String label(Context c, String id, String fallback) { return p(c).getString("label_" + id, fallback); }
    public static void setLabel(Context c, String id, String value) { p(c).edit().putString("label_" + id, value).apply(); }
    public static boolean frozen(Context c, String id) { return p(c).getBoolean("frozen_" + id, false); }
    public static void frozen(Context c, String id, boolean value) { p(c).edit().putBoolean("frozen_" + id, value).apply(); }
    public static boolean configured(Context c) { return p(c).getBoolean("configured", false); }
    public static boolean backgroundEnabled(Context c) { return p(c).getBoolean("background_enabled", true); }
    public static void backgroundEnabled(Context c, boolean value) { p(c).edit().putBoolean("background_enabled", value).apply(); }
    public static boolean bgRunning(Context c) { return p(c).getBoolean("bg_running", false); }
    public static void bgRunning(Context c, boolean value) { p(c).edit().putBoolean("bg_running", value).apply(); }
    public static String bgMode(Context c) { return p(c).getString("bg_mode", ""); }
    public static void bgMode(Context c, String value) { p(c).edit().putString("bg_mode", value == null ? "" : value).apply(); }
    public static String bgConfig(Context c) { return p(c).getString("bg_config", null); }
    public static void bgConfig(Context c, String value) {
        if (value == null) p(c).edit().remove("bg_config").apply();
        else p(c).edit().putString("bg_config", value).apply();
    }
    public static void configured(Context c, boolean value) { p(c).edit().putBoolean("configured", value).apply(); }
}
