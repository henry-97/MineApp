package io.github.mineapp.ext.hook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

/** 注入端的全局环境：Application、模块日志、用户选择（存在 Mine App 自己的私有 prefs 里）。 */
final class Env {
    private Env() { }

    static final String TAG = "MineExt";
    static final String MODE_ADB = "adb";
    static final String MODE_ROOT = "root";
    static final String MODE_SHIZUKU = "shizuku";

    private static final String PREFS = "mineapp_ext";

    static volatile XposedModule module;
    private static volatile Context app;

    @SuppressLint("PrivateApi")
    static Context app() {
        Context c = app;
        if (c != null) return c;
        try {
            c = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) { }
        app = c;
        return c;
    }

    /** 拿到真实 Context 时顺手记下，不全靠 ActivityThread 这个隐藏 API。 */
    static void setApp(Context c) {
        if (c != null && app == null) app = c.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        Context c = app();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String mode() {
        SharedPreferences p = prefs();
        String m = p == null ? MODE_ADB : p.getString("mode", MODE_ADB);
        return MODE_ROOT.equals(m) || MODE_SHIZUKU.equals(m) ? m : MODE_ADB;
    }

    static void setMode(String m) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString("mode", m).commit();
    }

    /** Root / Shizuku 不可用时是否退回原来的 adb tcpip 通道。默认开。 */
    static boolean fallback() {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean("fallback", true);
    }

    static void setFallback(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean("fallback", on).commit();
    }

    static void log(String msg) {
        XposedModule m = module;
        if (m != null) m.log(Log.INFO, TAG, msg);
    }

    static void log(String msg, Throwable t) {
        XposedModule m = module;
        if (m != null) m.log(Log.WARN, TAG, msg, t);
    }
}
