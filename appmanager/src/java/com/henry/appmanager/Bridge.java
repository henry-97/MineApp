package com.henry.appmanager;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JS ↔ Java 桥。前端调用 Bridge.list(id, "") 等，结果通过
 * window.__bridgeResult(id, json) 回传。
 *
 * 所有 adb 操作都放到单线程池：AdbClient 的 socket 不是线程安全的，
 * 串行执行也顺带保证了操作的先后顺序。
 */
public class Bridge {

    private final AdbService service;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /**
     * 长任务专用线程池（目前只有 AOT 编译）。
     *
     * 原来所有请求共用 worker 单线程，AOT 一跑就是几分钟，期间「进性能调优页」
     * 「刷新列表」全部排在后面，表现就是界面被卡住。长任务拆出去之后，
     * 普通请求立刻就能执行（传输层另有一条连接，见 AdbService.shLong）。
     */
    private final ExecutorService heavy = Executors.newSingleThreadExecutor();

    public Bridge(AdbService service, WebView web) {
        this.service = service;
        this.web = web;
    }

    private void dispatch(final int id, final Task task) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String json;
                try {
                    json = task.run();
                } catch (Throwable e) {
                    // 捕获 Throwable：若这里漏出异常，worker 线程会静默死掉，
                    // 前端 promise 永远等不到回调，界面就卡在「加载中」
                    android.util.Log.w("Bridge", "任务失败: " + e, e);
                    json = "{\"adb_ok\":false,\"adb_state\":\"error\",\"apps\":[],\"total\":0}";
                }
                final String payload = JSONObject.quote(json);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(
                                "window.__bridgeResult(" + id + "," + payload + ")", null);
                    }
                });
            }
        });
    }

    private interface Task { String run() throws Exception; }

    /** 与 dispatch 相同，只是跑在 heavy 线程池上，不阻塞普通请求 */
    private void dispatchHeavy(final int id, final Task task) {
        heavy.execute(new Runnable() {
            @Override
            public void run() {
                String json;
                try {
                    json = task.run();
                } catch (Throwable e) {
                    android.util.Log.w("Bridge", "长任务失败: " + e, e);
                    json = "{\"ok\":false,\"msg\":\"" + e.getMessage() + "\"}";
                }
                final String payload = JSONObject.quote(json);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(
                                "window.__bridgeResult(" + id + "," + payload + ")", null);
                    }
                });
            }
        });
    }

    @JavascriptInterface
    public void list(int id, String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() { return service.listJson(true); }
        });
    }

    /**
     * 检查 GitHub 上的最新 Release。
     *
     * 只在两种情况下发请求：用户主动点「检查更新」，或开了自动检查且距上次超过 24 小时。
     * 只做一次 GET，不上传任何东西，也没有任何埋点。
     */
    /**
     * 保存用户填的电池设计容量（mAh）。传 0 表示清空、回到自动探测。
     * 存在 SharedPreferences 里，重启后仍然有效。
     */
    /**
     * 打开系统的电池设置页，方便用户自己去看电池健康度。
     *
     * 用 ACTION_POWER_USAGE_SUMMARY 而不是厂商私有页面 —— 这个 intent 各家 ROM 都能解析，
     * 落到「设置 → 电池」。电池健康度具体在哪个子页面各家不同，只能用户自己点进去。
     */
    @JavascriptInterface
    public void battSettings(int id, String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() {
                try {
                    android.content.Intent i =
                            new android.content.Intent("android.intent.action.POWER_USAGE_SUMMARY");
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    web.getContext().startActivity(i);
                    return "{\"ok\":true}";
                } catch (Throwable e) {
                    return "{\"ok\":false}";
                }
            }
        });
    }

    @JavascriptInterface
    public void design(int id, String arg) {
        int v = 0;
        try { v = Integer.parseInt(arg.trim()); } catch (Throwable ignored) { }
        final int mah = v;
        dispatch(id, new Task() {
            @Override
            public String run() {
                service.setDesignMah(mah);
                return "{\"ok\":true,\"design_mah\":" + mah + "}";
            }
        });
    }

    @JavascriptInterface
    public void update(int id, String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() {
                java.net.HttpURLConnection c = null;
                try {
                    java.net.URL u = new java.net.URL(
                            "https://api.github.com/repos/henry-97/MineApp/releases/latest");
                    c = (java.net.HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setRequestProperty("User-Agent", "MineApp");
                    int code = c.getResponseCode();
                    JSONObject o = new JSONObject();
                    if (code != 200) {
                        o.put("ok", false);
                        o.put("code", code);
                        return o.toString();
                    }
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    JSONObject r = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                    o.put("ok", true);
                    o.put("tag", r.optString("tag_name"));
                    o.put("url", r.optString("html_url"));
                    o.put("name", r.optString("name"));
                    return o.toString();
                } catch (Throwable e) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("ok", false);
                        o.put("msg", String.valueOf(e.getMessage()));
                        return o.toString();
                    } catch (Exception ignored) {
                        return "{\"ok\":false}";
                    }
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    @JavascriptInterface
    public void freeze(int id, String pkgs) {
        batch(id, pkgs, "freeze");
    }

    @JavascriptInterface
    public void unfreeze(int id, String pkgs) {
        batch(id, pkgs, "unfreeze");
    }

    @JavascriptInterface
    public void restrict(int id, String pkgs) {
        batch(id, pkgs, "restrict");
    }

    @JavascriptInterface
    public void unrestrict(int id, String pkgs) {
        batch(id, pkgs, "unrestrict");
    }

    @JavascriptInterface
    public void perf(final int id, final String on) {
        final boolean want = "1".equals(on);
        // 记录偏好，供常驻服务周期性把性能模式写回去
        if (web != null && web.getContext() != null) {
            web.getContext().getSharedPreferences("app", 0)
                    .edit().putBoolean("perf_keep", want).apply();
        }
        dispatch(id, new Task() {
            @Override
            public String run() { return service.setPerf(want); }
        });
    }

    /** arg: "read" 读取；"key=1/0" 写入 */
    @JavascriptInterface
    public void tune(final int id, final String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() {
                int i = arg.indexOf('=');
                if (i < 0) return service.tuningJson();
                return service.setTuning(arg.substring(0, i), "1".equals(arg.substring(i + 1)));
            }
        });
    }

    /** arg: "" 读取白名单；否则把该字符串写回 */
    @JavascriptInterface
    public void millet(final int id, final String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() {
                return arg.isEmpty()
                        ? "{\"list\":\"" + service.getMillet().replace("\"", "") + "\"}"
                        : "{\"list\":\"" + service.setMillet(arg).replace("\"", "") + "\"}";
            }
        });
    }

    /** 各应用最后一次被前台使用的时间（毫秒），供「闲置」排序使用。 */
    @JavascriptInterface
    public void usage(final int id, String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() { return lastUsedJson(); }
        });
    }

    /**
     * 改走 adb 读 dumpsys usagestats。
     * UsageStatsManager 需要「使用情况访问权限」，没授予就返回空 —— 见 AdbService.usageJson。
     */
    private String lastUsedJson() {
        return service.usageJson();
    }

    /** arg 形如 "com.foo|1"：1 = 预编译，0 = 取消预编译 */
    @JavascriptInterface
    public void aot(final int id, final String arg) {
        dispatchHeavy(id, new Task() {
            @Override
            public String run() {
                int i = arg.lastIndexOf('|');
                if (i < 0) return "{\"ok\":false}";
                return service.setAot(arg.substring(0, i), "1".equals(arg.substring(i + 1)));
            }
        });
    }

    /**
     * 启动风险提示里点「退出」时调用 —— 直接结束 Activity。
     *
     * 用 web.post 而不是 runOnUiThread：Bridge 没有 Activity 引用，
     * 从 WebView 的 context 取最省事；web.post 保证在 UI 线程执行。
     */
    @JavascriptInterface
    public void exitApp() {
        web.post(new Runnable() {
            @Override
            public void run() {
                android.content.Context c = web.getContext();
                if (!(c instanceof android.app.Activity)) return;
                // 只 finish 的话常驻通知还挂着，看起来像没退干净 —— 一并停掉
                try {
                    c.stopService(new android.content.Intent(c, KeepAliveService.class));
                } catch (Exception ignored) { }
                ((android.app.Activity) c).finish();
            }
        });
    }

    /** 单应用清理后台（am kill），不改变自启动 */
    @JavascriptInterface
    public void kill(final int id, final String arg) {
        // arg = "包名|清理前PSS(KB)"，PSS 由前端带上（就是列表里显示的那个数）
        final String[] parts = (arg == null ? "" : arg).split("\\|");
        final String pkg = parts.length > 0 ? parts[0] : "";
        long pss = 0;
        if (parts.length > 1) {
            try { pss = Long.parseLong(parts[1].trim()); } catch (Exception ignored) { }
        }
        final long before = pss;
        dispatch(id, new Task() {
            @Override
            public String run() { return service.killApp(pkg, before); }
        });
    }

    /** arg = "manager" | "mine"：切换桌面名称，图标保持不变 */
    @JavascriptInterface
    public void name(final int id, final String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() { return setAlias(arg, null); }
        });
    }

    /** arg = "sq_g" | "cl_y" …：切换桌面图标，名称保持不变 */
    @JavascriptInterface
    public void icon(final int id, final String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() { return setAlias(null, arg); }
        });
    }

    /** 6 套图标，顺序与 tools/gen_icon.py 的 SHAPES × COLORS 一致 */
    static final String[] ICON_KEYS = {
            "sq_g", "sq_y", "sq_k",
            "cl_g", "cl_y", "cl_k"};

    /** 该图标键当前是否还存在于清单里（删减造型后旧键会失效） */
    static boolean isKnownIcon(String key) {
        if (key == null) return false;
        for (int i = 0; i < ICON_KEYS.length; i++) if (ICON_KEYS[i].equals(key)) return true;
        return false;
    }

    /** "mine"|"manager" + "sq_g" → "AliasMineSqG" */
    static String aliasName(String mode, String key) {
        String m = "manager".equals(mode) ? "Mgr" : "Mine";
        int i = key.indexOf('_');
        String shape = i < 0 ? "sq" : key.substring(0, i);
        String color = i < 0 ? "g" : key.substring(i + 1);
        return "Alias" + m
                + Character.toUpperCase(shape.charAt(0)) + shape.substring(1)
                + color.toUpperCase();
    }

    static String[] allAliases() {
        String[] out = new String[2 * ICON_KEYS.length];
        int n = 0;
        for (int i = 0; i < ICON_KEYS.length; i++) out[n++] = aliasName("mine", ICON_KEYS[i]);
        for (int i = 0; i < ICON_KEYS.length; i++) out[n++] = aliasName("manager", ICON_KEYS[i]);
        return out;
    }

    /**
     * 桌面入口 = 名称 × 图标，共 18 个 activity-alias。
     * 传 null 表示该项沿用已保存的偏好。先启用目标再停用其余，避免桌面出现「图标消失」的空档。
     */
    private String setAlias(String which, String iconKey) {
        try {
            android.content.Context ctx = web.getContext();
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            String pkg = ctx.getPackageName();
            android.content.SharedPreferences sp = ctx.getSharedPreferences("app", 0);
            String mode = sp.getString("name_mode", "mine");
            String key = sp.getString("icon_key", "sq_g");

            // 图标清单可能被删减（比如去掉了某一套造型），存着的键就失效了。
            // 这里先兜回默认值，否则 aliasName 会拼出一个不存在的组件，
            // setComponentEnabledSetting 抛异常，最后桌面上一个入口都不剩。
            if (!isKnownIcon(key)) key = ICON_KEYS[0];

            if (which != null) mode = "manager".equals(which) ? "manager" : "mine";
            if (iconKey != null) {
                if (!isKnownIcon(iconKey)) return "{\"ok\":false}";
                key = iconKey;
            }

            String target = aliasName(mode, key);
            int en = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            int dis = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            int flags = android.content.pm.PackageManager.DONT_KILL_APP;

            pm.setComponentEnabledSetting(new android.content.ComponentName(pkg, pkg + "." + target),
                    en, flags);
            String[] all = allAliases();
            for (int i = 0; i < all.length; i++) {
                if (all[i].equals(target)) continue;
                pm.setComponentEnabledSetting(
                        new android.content.ComponentName(pkg, pkg + "." + all[i]), dis, flags);
            }

            // 记下当前选择：常驻通知的标题要跟着变，升级/重启后也要能还原
            sp.edit().putString("name_mode", mode).putString("icon_key", key).apply();
            return "{\"ok\":true,\"alias\":\"" + target + "\"}";
        } catch (Exception e) {
            android.util.Log.w("Bridge", "alias 切换失败: " + e);
            return "{\"ok\":false}";
        }
    }

    /** 启动时把偏好里的名称/图标重新落到 alias 上（覆盖安装后 manifest 会重置为默认） */
    public void restoreAlias() {
        worker.execute(new Runnable() {
            @Override
            public void run() { setAlias(null, null); }
        });
    }

    private void batch(final int id, final String pkgs, final String action) {
        dispatch(id, new Task() {
            @Override
            public String run() { return service.batch(pkgs, action); }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
        heavy.shutdownNow();
    }
}
