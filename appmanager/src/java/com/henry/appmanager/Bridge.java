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
     * 打开「关于手机 / 我的设备」页 —— 电池设计容量在那里。
     *
     * 为什么不是电池设置页：实测 Redmi Note 12 Turbo / HyperOS，
     * 「我的设备」页直接写着「5000mAh(typ) 电池容量」，而「省电与电池 → 电池保护」
     * 只有「电池健康度：正常」这种状态词，没有容量也没有百分比。
     * 用户要填设计容量，就该看得到这个数字的页面。
     *
     * DEVICE_INFO_SETTINGS 是标准 action，各家 ROM 都解析，落点会因厂商略有差异。
     */
    /**
     * 保活初始化：把自己加进电源管理白名单，并按保存的设置隐藏最近任务卡片。
     * 前端启动时调一次。
     */
    @JavascriptInterface
    public void keepalive(int id, String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() {
                service.ensureSelfWhitelisted();
                boolean hide = false;
                try {
                    // 默认隐藏：卡片不在最近任务里，就没有被误划的可能
                    hide = web.getContext().getSharedPreferences("app", 0)
                            .getBoolean("hide_recents", true);
                } catch (Throwable ignored) { }
                boolean applied = applyHideFromRecents(hide);
                // 刚更新完？把「自动打开设置页」的标记取出来并清掉，前端据此跳转
                boolean openSettings = false;
                try {
                    android.content.SharedPreferences sp =
                            web.getContext().getSharedPreferences("app", 0);
                    openSettings = sp.getBoolean("open_settings_after_update", false);
                    if (openSettings) {
                        sp.edit().putBoolean("open_settings_after_update", false).apply();
                    }
                } catch (Throwable ignored) { }
                return "{\"ok\":true,\"hide_recents\":" + hide
                        + ",\"applied\":" + applied
                        + ",\"open_settings\":" + openSettings + "}";
            }
        });
    }

    /**
     * 开关「隐藏后台卡片」。
     *
     * 用 ActivityManager.AppTask.setExcludeFromRecents —— 这是唯一能让自己的任务
     * 不出现在最近任务里的公开 API。MIUI 那种「上锁」没有公开接口，做不到，
     * 只能在界面上提示用户手动上锁。
     *
     * 返回 applied=false 表示系统没接受（个别 ROM 会拒绝），前端据此提示用户改用手动方式。
     */
    @JavascriptInterface
    public void hiderecents(int id, String arg) {
        final boolean hide = "1".equals(arg);
        dispatch(id, new Task() {
            @Override
            public String run() {
                boolean applied = applyHideFromRecents(hide);
                try {
                    web.getContext().getSharedPreferences("app", 0).edit()
                            .putBoolean("hide_recents", hide).apply();
                } catch (Throwable ignored) { }
                return "{\"ok\":" + applied + ",\"hide_recents\":" + hide + "}";
            }
        });
    }

    /** 真正去改任务属性。实现放在 MainActivity，那边 onCreate 也要用。 */
    private boolean applyHideFromRecents(boolean hide) {
        return MainActivity.applyHideFromRecents(web.getContext(), hide);
    }

    /** 自更新的进度：idle / download / install / done / fail */
    private static volatile String instPhase = "idle";
    private static volatile int instPct = 0;
    private static volatile long instBytes = 0, instTotal = 0;

    /**
     * 前端每 300ms 拉一次，用来画进度条。
     *
     * 🔴 这里**不能走 dispatch**：worker 是单线程池，installupdate 会独占它跑完整个
     * 下载 + 安装，排在后面的进度查询永远轮不到 —— 界面就一直是 0%，直到进程被重启。
     * 所以只读几个 volatile 字段，直接在 main 线程回调，不占队列。
     */
    @JavascriptInterface
    public void installprogress(int id, String arg) {
        try {
            JSONObject o = new JSONObject();
            o.put("phase", instPhase);
            o.put("pct", instPct);
            o.put("bytes", instBytes);
            o.put("total", instTotal);
            final String payload = JSONObject.quote(o.toString());
            main.post(new Runnable() {
                @Override
                public void run() {
                    web.evaluateJavascript(
                            "window.__bridgeResult(" + id + "," + payload + ")", null);
                }
            });
        } catch (Throwable ignored) { }
    }

    /**
     * 应用内静默安装更新。三步，缺一不可：
     *
     *   ① 下载 APK 到外部私有目录 getExternalFilesDir()
     *   ② cp 到 /data/local/tmp/ —— 关键一步。实测 /sdcard 下的文件 system_server 打不开
     *      （FUSE 挂载，报 "Failed to load asset path ... from fd"），pm install 直接失败；
     *      /data/local/tmp 是 shell 可写、system_server 可读的位置，adb install 本来就用它。
     *   ③ pm install -r —— 走自己的 ADB 通道，等价于 adb install，无系统安装器弹窗
     *
     * 装完本进程会被系统杀掉，属正常现象（新版本随即启动）。
     * 前提是签名一致 —— 自己发的包必然一致；一旦换签名，这里会失败，退回浏览器下载。
     */
    @JavascriptInterface
    public void installupdate(int id, String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() {
                java.io.File apk = null;
                String tmp = "/data/local/tmp/mineapp_update.apk";
                instPhase = "download";
                instPct = 0;
                instBytes = 0;
                instTotal = 0;
                try {
                    apk = new java.io.File(web.getContext().getExternalFilesDir(null), "update.apk");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(arg).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "MineApp");
                    int code = c.getResponseCode();
                    if (code != 200) return err("HTTP " + code);
                    instTotal = c.getContentLength();
                    java.io.InputStream in = c.getInputStream();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(apk);
                    byte[] buf = new byte[16384];
                    int n;
                    long total = 0;
                    int mark = 0;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        total += n;
                        instBytes = total;
                        if (instTotal > 0) instPct = (int) (total * 100 / instTotal);
                        // 每 ~3% 刷一次，避免过于频繁
                        if (instPct >= mark + 3) mark = instPct;
                    }
                    fos.close();
                    in.close();
                    c.disconnect();

                    instPct = 100;
                    // 先让进度条填满停留 1 秒再开始装 —— 否则界面还空着就被重启了，观感很差
                    instPhase = "finishing";
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                    instPhase = "install";
                    // 装完要自动回到设置页：先落一个标记（同一份 app 数据，覆盖安装后还在）
                    String pkg = web.getContext().getPackageName();
                    web.getContext().getSharedPreferences("app", 0).edit()
                            .putBoolean("open_settings_after_update", true).apply();

                    String log = "/data/local/tmp/mineapp_update.log";
                    // 🔴 拉起必须「先脱离、再安装」。
                    // 直接把 am start 串在 pm install 后面是错的：安装过程中本进程被杀，
                    // 同一条 shell 里后面的 sleep/am start 也一起没了（实测：更新成功但停在桌面）。
                    // setsid 把拉起任务脱离当前会话，它就不再受安装影响；
                    // --activity-single-top 让重复调用只触发 onNewIntent，不会叠出多个实例。
                    // 不再固定 sleep 等：每轮先试着拉起，再用 dumpsys 确认是否真的到前台，
                    // 成功就立刻退出。安装完成的那一刻就能拉起，比死等 2 秒快得多；
                    // 装得慢也不会漏（最多重试 15 轮 ≈ 8 秒）。
                    String relaunch = "(setsid sh -c '"
                            + "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do "
                            + "am start --activity-single-top -n " + pkg + "/.MainActivity >> "
                            + log + " 2>&1; "
                            + "sleep 0.2; "
                            + "dumpsys activity activities 2>/dev/null "
                            + "| grep -q \"topResumedActivity.*" + pkg + "\" && exit 0; "
                            + "sleep 0.3; "
                            + "done' >/dev/null 2>&1 &)";

                    String res = service.shellLong("cp '" + apk.getAbsolutePath() + "' " + tmp
                            + " ; echo '--- update '$(date) >> " + log
                            + " ; " + relaunch
                            + " ; echo 'relaunch detached' >> " + log
                            + " ; pm install -r " + tmp + " >> " + log + " 2>&1"
                            + " ; echo 'install returned' >> " + log
                            + " ; cat " + log);
                    service.shellLong("rm -f " + tmp);

                    boolean ok = res != null && res.contains("Success");
                    instPhase = ok ? "done" : "fail";
                    JSONObject o = new JSONObject();
                    o.put("ok", ok);
                    o.put("bytes", total);
                    if (!ok) o.put("msg", res == null ? "" : res.trim());
                    return o.toString();
                } catch (Throwable e) {
                    instPhase = "fail";
                    return err(String.valueOf(e.getMessage()));
                } finally {
                    if (apk != null) apk.delete();
                }
            }
        });
    }

    private String err(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("msg", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    /**
     * 批量同步 AOT 状态。
     *
     * 前端把「哪些包已 AOT」记在 localStorage（pkg@ver），pm clear / 重装后这份记录就没了，
     * 但设备上的编译结果还在 —— 于是界面全显示「未开启」，与实际不符。
     * 这里用 `dumpsys package dexopt` 一次性把所有包的 compiler status 取回来，
     * 前端据此把 status=speed 的包标成已开启。
     */
    @JavascriptInterface
    public void aotsync(int id, String arg) {
        dispatchHeavy(id, new Task() {
            @Override
            public String run() {
                try {
                    // 全量 dump 有 430KB / 4000 行，传回来太重（实测拿不到完整内容）。
                    // 在设备侧先用 grep 只留「包名行」和「status=speed 行」，量级降到几十行。
                    String out = service.shellLong(
                            "dumpsys package dexopt | grep -E '^  \\[|status=speed\\]'");
                    JSONObject map = new JSONObject();
                    String cur = null;
                    String[] lines = out.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String t = lines[i].trim();
                        // 包名行形如 "[com.tencent.mm]"：方括号包裹、内部无空格
                        if (t.length() > 2 && t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']'
                                && t.indexOf(' ') < 0 && t.indexOf('.') > 0) {
                            cur = t.substring(1, t.length() - 1);
                        } else if (cur != null && t.contains("status=")) {
                            int a = t.indexOf("status=") + 7;
                            int b = t.indexOf(']', a);
                            String st = b > a ? t.substring(a, b) : "";
                            if (!map.has(cur)) map.put(cur, st);   // 只取首个（主 abi）
                        }
                    }
                    return map.toString();
                } catch (Throwable e) {
                    android.util.Log.w("Bridge", "aotsync 失败: " + e);
                    return "{}";
                }
            }
        });
    }

    /**
     * 跳到某个系统设置页。这两项 App 无法自动授予：
     *   通知 —— Android 13+ 要用户点「允许」，这里直接打开本应用的通知设置；
     *   自启动 —— MIUI 存在 com.miui.powerkeeper 私有库里，只能跳过去让用户手动开，
     *             顺带失败时退回应用详情页（那里也有自启动入口）。
     */
    @JavascriptInterface
    public void opensetting(int id, String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() {
                try {
                    String pkg = web.getContext().getPackageName();
                    String cmd;
                    if ("power".equals(arg)) {
                        // 直达「电量详情」页（就是带省电策略四个单选项的那页）。
                        // 实测：PowerDetailActivity 必须带 --es package_name，否则自己 finish 掉退回桌面。
                        // 策略本身存在 com.miui.powerkeeper 私有库，shell 写不了，只能跳过去让用户点。
                        String out = service.shellLong("am start -n com.miui.securitycenter"
                                + "/com.miui.powercenter.legacypowerrank.PowerDetailActivity"
                                + " --es package_name " + pkg);
                        if (out == null || out.contains("Error")) {
                            service.shellLong("am start -a android.settings.APPLICATION_DETAILS_SETTINGS"
                                    + " -d package:" + pkg);
                        }
                        return "{\"ok\":true}";
                    }
                    if ("notif".equals(arg)) {
                        cmd = "am start -a android.settings.APP_NOTIFICATION_SETTINGS"
                            + " --es android.provider.extra.APP_PACKAGE " + pkg;
                    } else {
                        // 先试 MIUI 的自启动页；失败（有的 ROM 没这个 Activity）再退应用详情页。
                        // 不用 shell 的 || —— 交给 Java 判返回值更可靠，也便于打日志。
                        String out = service.shellLong("am start -n com.miui.securitycenter"
                                + "/com.miui.permcenter.autostart.AutoStartManagementActivity");
                        android.util.Log.i("Bridge", "autostart -> " + out);
                        if (out == null || out.contains("Error")) {
                            service.shellLong("am start -a android.settings.APPLICATION_DETAILS_SETTINGS"
                                    + " -d package:" + pkg);
                        }
                        return "{\"ok\":true}";
                    }
                    service.shellLong(cmd);
                    return "{\"ok\":true}";
                } catch (Throwable e) {
                    return "{\"ok\":false}";
                }
            }
        });
    }

    @JavascriptInterface
    public void battSettings(int id, String arg) {
        dispatch(id, new Task() {
            @Override
            public String run() {
                try {
                    android.content.Intent i =
                            new android.content.Intent("android.settings.DEVICE_INFO_SETTINGS");
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
                    // 找 Release 里第一个 .apk 资产 —— 有它才能做应用内静默安装
                    org.json.JSONArray assets = r.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            org.json.JSONObject a = assets.optJSONObject(i);
                            if (a == null) continue;
                            String nm = a.optString("name");
                            if (nm != null && nm.toLowerCase().endsWith(".apk")) {
                                o.put("apk", a.optString("browser_download_url"));
                                o.put("size", a.optLong("size"));
                                break;
                            }
                        }
                    }
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

            // 🔴 只在状态真的不一致时才改。
            // 无条件重设会广播 PACKAGE_CHANGED，桌面重新查组件时可能把图标当新组件重排，
            // 表现就是「更新后图标跑到应用列表末尾」。默认名称/图标的情况下，
            // 覆盖安装后 manifest 里的状态本来就对，这里一次都不用调。
            android.content.ComponentName on = new android.content.ComponentName(pkg, pkg + "." + target);
            if (pm.getComponentEnabledSetting(on) != en) {
                pm.setComponentEnabledSetting(on, en, flags);
            }
            String[] all = allAliases();
            for (int i = 0; i < all.length; i++) {
                if (all[i].equals(target)) continue;
                android.content.ComponentName off =
                        new android.content.ComponentName(pkg, pkg + "." + all[i]);
                if (pm.getComponentEnabledSetting(off) != dis) {
                    pm.setComponentEnabledSetting(off, dis, flags);
                }
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
