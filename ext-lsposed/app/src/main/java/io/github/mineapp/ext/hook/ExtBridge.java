package io.github.mineapp.ext.hook;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.mineapp.ext.BuildConfig;
import io.github.mineapp.ext.Const;

/**
 * 注入到 Mine App WebView 的 window.MineExt。
 *
 * 与原 Bridge 一样异步：MineExt.call(action, arg, id)，结果经 window.__mineExtResult(id, json) 回传。
 * 不能同步返回 —— 试 su、拿 Shizuku binder、等授权框都会阻塞，会把页面 JS 线程卡住。
 */
public final class ExtBridge {

    private final WebView web;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** 等授权框可能要几十秒，单独一条线程，不堵住状态查询 */
    private final ExecutorService waiter = Executors.newSingleThreadExecutor();

    ExtBridge(WebView web) {
        this.web = web;
    }

    @JavascriptInterface
    public void call(final String action, final String arg, final int id) {
        ExecutorService ex = "requestShizuku".equals(action) || "freezeTemp".equals(action) ? waiter : worker;
        ex.execute(() -> {
            String json;
            try {
                json = handle(action, arg == null ? "" : arg);
            } catch (Throwable t) {
                Env.log("MineExt." + action + " failed", t);
                json = "{\"ok\":false,\"err\":" + JSONObject.quote(String.valueOf(t.getMessage())) + "}";
            }
            final String payload = JSONObject.quote(json);
            web.post(() -> web.evaluateJavascript(
                    "window.__mineExtResult&&window.__mineExtResult(" + id + "," + payload + ")", null));
        });
    }

    private String handle(String action, String arg) throws Exception {
        switch (action) {
            case "status":
                return status(null).toString();
            case "setMode": {
                if (!Env.MODE_ADB.equals(arg) && !Env.MODE_ROOT.equals(arg)
                        && !Env.MODE_SHIZUKU.equals(arg)) {
                    return "{\"ok\":false,\"err\":\"bad mode\"}";
                }
                Env.setMode(arg);
                String err = null;
                if (Env.MODE_ROOT.equals(arg)) {
                    // 先试一次 su：授权框在这里弹，结果马上反馈到界面
                    try {
                        PersistentShell.openRoot().close();
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                }
                Transport.resetAll();
                return status(err).toString();
            }
            case "fallback":
                Env.setFallback("1".equals(arg));
                return status(null).toString();
            case "reconnect":
                Transport.resetAll();
                return status(null).toString();
            case "requestShizuku": {
                int st = ShizukuClient.requestPermission();
                if (st == Const.SHIZUKU_GRANTED && Env.MODE_SHIZUKU.equals(Env.mode())) {
                    Transport.resetAll();
                }
                return status(null).toString();
            }
            case "frozenList":
                return FrozenApps.list(web.getContext().getApplicationContext()).toString();
            case "pin": {
                JSONObject o = new JSONObject();
                o.put("result", FrozenApps.pin(web.getContext().getApplicationContext(), arg));
                return o.toString();
            }
            case "pinPicker": {
                JSONObject o = new JSONObject();
                o.put("result", FrozenApps.pinPicker(web.getContext().getApplicationContext()));
                return o.toString();
            }
            case "freezeTemp": {
                java.util.List<String> ok = AutoFreeze.freezeTemp(web.getContext().getApplicationContext());
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("frozen", ok.size());
                o.put("left", AutoFreeze.tempUnfrozen().size());
                return o.toString();
            }
            case "setAutoFreeze": {
                int m;
                try {
                    m = Integer.parseInt(arg.trim());
                } catch (NumberFormatException e) {
                    return "{\"ok\":false,\"err\":\"bad minutes\"}";
                }
                AutoFreeze.setDelayMinutes(m);
                return "{\"ok\":true,\"autofreeze\":" + AutoFreeze.delayMinutes() + "}";
            }
            case "appInfo": {
                // MIUI / HyperOS 有单独的「桌面快捷方式」权限，默认可能是拒绝，只能去应用信息页开
                web.post(() -> {
                    try {
                        web.getContext().startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + Const.TARGET_PKG)));
                    } catch (Throwable t) {
                        Env.log("open app info failed", t);
                    }
                });
                return "{\"ok\":true}";
            }
            default:
                return "{\"ok\":false,\"err\":\"unknown action\"}";
        }
    }

    private JSONObject status(String err) throws Exception {
        JSONObject o = new JSONObject();
        String mode = Env.mode();
        o.put("ok", err == null);
        o.put("incompatible", !Transport.installed);
        o.put("mode", mode);
        o.put("fallback", Env.fallback());
        o.put("active", Transport.active);
        o.put("err", err != null ? err : Transport.lastError);
        o.put("ver", BuildConfig.VERSION_NAME);
        // 只在用得上时才查：首次查询可能要向模块要 binder
        if (Env.MODE_SHIZUKU.equals(mode)) o.put("shizuku", ShizukuClient.state());
        return o;
    }
}
