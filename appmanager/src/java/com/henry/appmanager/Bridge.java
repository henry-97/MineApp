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
    /**
     * 定位专用线程池。
     *
     * 定位和「列表刷新」「索引构建」是彼此独立的功能，共用一个队列就会出现
     * 「正在刷新/正在建索引时点定位没反应」。AdbService 内部对命令有 synchronized，
     * 多线程调用只是排队执行，不会破坏协议，所以可以安全地分开。
     */
    private final ExecutorService locExec = Executors.newSingleThreadExecutor();

    public Bridge(AdbService service, WebView web) {
        this.service = service;
        this.web = web;
        // 通知栏那个「停止」按钮按下来时，走广播把两个停止标志立起来。
        // 光靠「前台不是桌面就停」不够：用户只是想滑动桌面看看、或者把图标拖一下，
        // 前台一直是桌面，检测不到，所以必须给一个能按的开关。
        try {
            android.content.IntentFilter f =
                    new android.content.IntentFilter(ACTION_STOP);
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    locStop = true;
                    idxStop = true;
                }
            };
            // 用 ApplicationContext 注册：Activity 被回收重建后，这个接收器依然有效，
            // 「停止」按钮才不会因为 Activity 重建而失灵。
            android.content.Context ac = web.getContext().getApplicationContext();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ac.registerReceiver(r, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                ac.registerReceiver(r, f);
            }
        } catch (Throwable ignored) { }
    }

    private static final String ACTION_STOP = "com.henry.appmanager.STOP_LOCATE";
    /**
     * 定位的停止标志（通知栏「停止」按钮 / 用户切走都会置起）。
     *
     * 🔴 必须是 **static**：定位/建索引跑在后台线程上，线程属于**旧的** Bridge 实例。
     *    索引一跑 1 分钟，这期间 Activity 常被系统回收重建，新建的 Bridge 注册的
     *    广播接收器只会去改**新实例**的标志，旧线程永远看不到 → 点了「停止」也没用。
     *    实测被投诉「无论点哪里都无法打断查找」。
     */
    private static volatile boolean locStop;
    private static volatile boolean idxStop;
    /** 本轮定位的起始时间，用来兜底超时（防止无限扫）。 */
    private volatile long locDeadline;
    /** 「碰屏幕就停」：后台线程轮询真触摸屏，命中置 true。 */
    private volatile boolean touchHit;
    private volatile boolean touchWatching;
    /** 当前跑的是建索引（提示文案用「已暂停」，定位用「已停止」）。 */
    private volatile boolean idxRunning;

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
    private void dispatchLoc(final int id, final Task task) {
        locExec.execute(new Runnable() {
            @Override
            public void run() {
                String json;
                try {
                    json = task.run();
                } catch (Throwable e) {
                    android.util.Log.w("Bridge", "定位任务失败: " + e, e);
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
                    // 🔴 分两段：先等安装把旧进程杀掉，再拉。
                    // 原来只有一段「拉起来就确认前台」—— 而拉起的瞬间安装还没开始，
                    // 应用本来就还在前台，第一轮就判定成功退出；紧接着 pm install 杀进程，
                    // 结果更新完停在桌面（实测 2026-10-28 00:39 那次就是这样）。
                    String relaunch = "(setsid sh -c '"
                            // 第 1 段：等进程消失（= 安装真的开始替换了），最多等 15 秒
                            + "for i in $(seq 1 150); do "
                            + "pidof " + pkg + " >/dev/null 2>&1 || break; "
                            + "sleep 0.1; "
                            + "done; "
                            // 第 2 段：进程没了再拉，拉到前台才退出
                            + "for i in $(seq 1 20); do "
                            + "am start --activity-single-top -n " + pkg + "/.MainActivity >> "
                            + log + " 2>&1; "
                            + "sleep 0.3; "
                            + "dumpsys activity activities 2>/dev/null "
                            + "| grep -q \"topResumedActivity.*" + pkg + "\" && exit 0; "
                            + "sleep 0.2; "
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
     * 在桌面上定位某个应用：回桌面 → dump → 按应用名找坐标 → 长按（图标放大 + 弹菜单）。
     *
     * 为什么这么绕：桌面布局在 com.miui.home 的私有库里，非 root 读不到；
     * launcher 也没有「按包名定位」的公开接口。唯一通用通道是无障碍树 ——
     * uiautomator dump 能拿到每个图标的 content-desc（就是应用名）和精确 bounds。
     *
     * 代价：dump 一次约 2.3 秒（JVM 启动 1.5~2s + 等 UI idle 0.5s），与扫描量无关。
     */
    /** 从 dump 出来的 XML 里取某个属性值 */
    private static String nodeAttr(String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "=\"([^\"]*)\"")
                .matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 在当前 dump 里找节点，返回中心坐标 {x, y}；找不到返回 null。
     *
     * @param wantFolder true=只找文件夹（RelativeLayout），false=只找应用图标（TextView）
     */
    private static int[] findNode(String xml, String label, boolean wantFolder) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;
            // 🔴 先精确匹配，再退到前缀匹配。
            // 只做前缀会把「QQ」错认成「QQ邮箱」（实测点定位跳到了 QQ邮箱）。
            // 前缀是为了覆盖「微信, 3 条通知」这种带角标后缀的描述。
            if (!desc.equals(label)) continue;
            String cls = nodeAttr(tag, "class");
            boolean isFolder = cls != null && cls.endsWith("RelativeLayout");
            if (isFolder != wantFolder) continue;
            String bounds = nodeAttr(tag, "bounds");
            if (bounds == null) continue;
            java.util.regex.Matcher b = java.util.regex.Pattern
                    .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
            if (!b.find()) continue;
            int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
            int x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
            return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
        }
        return null;
    }

    /** 精确匹配失败时的兜底：图标描述可能带角标后缀（「微信, 3 条通知」） */
    private static int[] findNodeLoose(String xml, String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;
            if (!desc.startsWith(label)) continue;
            // 🔴 后缀必须以标点或空格开头。只做前缀会把「QQ」错认成「QQ邮箱」
            String suffix = desc.substring(label.length());
            if (suffix.isEmpty()) continue;
            if ("，,：: ".indexOf(suffix.charAt(0)) < 0) continue;
            String cls = nodeAttr(tag, "class");
            if (cls == null || cls.endsWith("RelativeLayout")) continue;
            String bounds = nodeAttr(tag, "bounds");
            if (bounds == null) continue;
            java.util.regex.Matcher b = java.util.regex.Pattern
                    .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
            if (!b.find()) continue;
            int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
            int x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
            return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
        }
        return null;
    }

    /** 收集当前页所有文件夹的中心坐标 */
    private static java.util.List<int[]> findFolders(String xml) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;
            String cls = nodeAttr(tag, "class");
            if (cls == null || !cls.endsWith("RelativeLayout")) continue;
            if (!"true".equals(nodeAttr(tag, "clickable"))) continue;
            String bounds = nodeAttr(tag, "bounds");
            if (bounds == null) continue;
            java.util.regex.Matcher b = java.util.regex.Pattern
                    .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
            if (!b.find()) continue;
            int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
            int x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
            out.add(new int[]{(x1 + x2) / 2, (y1 + y2) / 2});
        }
        return out;
    }

    /**
     * 带名字的文件夹列表：{x, y, 文件夹名}。
     *
     * 定位时要靠它「直接打开目标文件夹」，而不是逐个点开试 ——
     * 每个文件夹一次 dump 约 2.3 秒，桌面有 6 个文件夹就是十几秒，
     * 用户看到的就是「明明建过索引还要从头翻」。
     */
    private static java.util.List<Object[]> findFoldersNamed(String xml) {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;
            String cls = nodeAttr(tag, "class");
            if (cls == null || !cls.endsWith("RelativeLayout")) continue;
            if (!"true".equals(nodeAttr(tag, "clickable"))) continue;
            String bounds = nodeAttr(tag, "bounds");
            if (bounds == null) continue;
            java.util.regex.Matcher b = java.util.regex.Pattern
                    .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
            if (!b.find()) continue;
            int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
            int x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
            out.add(new Object[]{(x1 + x2) / 2, (y1 + y2) / 2, desc});
        }
        return out;
    }

    /** 长按坐标：必须带 2~4px 位移，零距离会被当成点击直接打开应用 */
    private void longPress(int x, int y) {
        service.shellLong("input swipe " + x + " " + y + " " + (x + 4) + " " + y + " 1500");
    }

    /**
     * 解析桌面底部的页码指示器。
     *
     * dump 里有：
     *   content-desc="第 1 页共 3 页"  class=SeekBar   ← 当前页 / 总页数
     *   content-desc="第1屏"  class=ImageView  bounds=... ← 每个圆点，可直接点
     *
     * 返回：{current, total, x1,y1, x2,y2, ...}（每个点一组坐标）
     */
    private static int[] parsePager(String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        int cur = -1, total = -1;
        java.util.List<int[]> dots = new java.util.ArrayList<int[]>();
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;
            if (desc.contains("页共")) {
                java.util.regex.Matcher n = java.util.regex.Pattern
                        .compile("第\\s*(\\d+)\\s*页共\\s*(\\d+)\\s*页").matcher(desc);
                if (n.find()) {
                    cur = Integer.parseInt(n.group(1));
                    total = Integer.parseInt(n.group(2));
                }
            } else if (desc.matches("第\\s*\\d+\\s*屏")) {
                String bounds = nodeAttr(tag, "bounds");
                if (bounds == null) continue;
                java.util.regex.Matcher b = java.util.regex.Pattern
                        .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
                if (!b.find()) continue;
                int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
                int x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
                dots.add(new int[]{(x1 + x2) / 2, (y1 + y2) / 2});
            }
        }
        int[] out = new int[2 + dots.size() * 2];
        out[0] = cur;
        out[1] = total;
        for (int i = 0; i < dots.size(); i++) {
            out[2 + i * 2] = dots.get(i)[0];
            out[3 + i * 2] = dots.get(i)[1];
        }
        return out;
    }

    /**
     * 跳到第 page 页（1 基）。
     *
     * 优先点页码圆点（稳、快）；但**有的桌面/主题没有小圆点**，
     * 那就退回滑动：先把页滑到最左，再向右滑 page-1 次。
     */
    /**
     * 用户是否已经要求中断（定位/索引通用）。
     *
     * 🔴 以前 jumpToPage 里的检查写成 `locDeadline > 0 && (...)`，
     *    而 locDeadline 只有**定位**会设；建索引时它是 0 → 检查形同虚设，
     *    用户碰了屏幕（已提示「已暂停」）后它还会继续滑两下
     *    （实测被问到「显示已暂停但滚动了两次屏幕」）。
     */
    private boolean interrupted() {
        return touchHit || locStop || idxStop;
    }

    private boolean jumpToPage(int[] pager, int page) {
        int maxPage = pager[1] > 0 ? pager[1] : 3;   // 读不到页码时按 3 页算（不是 8：空页会让它一直滑）
        if (page < 1 || page > maxPage) return false;
        int idx = 2 + (page - 1) * 2;
        if (idx + 1 < pager.length) {
            // 有圆点：直接点
            service.shellLong("input tap " + pager[idx] + " " + pager[idx + 1]);
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            return true;
        }
        // 没圆点：按「当前页 → 目标页」的差值滑。
        //
        // 🔴 绝对不能「先无条件滑 8 次回最左」—— 逐页扫描时每页都会调一次
        //    jumpToPage，8 页 × 8 次就变成几十次无效滑动，
        //    用户看到屏幕一个劲乱滑（实测被投诉）。
        //    按差值滑，整轮扫描总共只滑 N-1 次，方向和次数都符合预期。
        int cur;
        if (pager[0] > 0) {
            cur = pager[0];
        } else {
            // 🔴 解析不到当前页时**不能直接当第 1 页**：实际可能已经在最后一页，
            //    那样 diff 会一次比一次大，滑动次数累加，表现是「在最后一页疯狂右滑、
            //    反复遍历同一页」（2026-10-29 实测被投诉）。先 HOME 回默认页再算。
            dbg("页号解析失败，先回默认页再跳");
            service.shellLong("input keyevent 3");
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            cur = 1;
        }
        int diff = page - cur;
        if (diff == 0) return true;
        int fx = diff > 0 ? 950 : 200;      // 下一页：手指从右往左
        int tx = diff > 0 ? 200 : 950;
        // 🔴 硬上限：差值算错也不许无限滑
        int steps = Math.min(Math.abs(diff), 8);
        for (int i = 0; i < steps; i++) {
            // 用户一碰屏幕就立刻收手：以前这里连滑 8 次 + 逐页修正，
            // 全程没有检查，用户自己也在滑，两边抢着滑，要滑到目标页才停
            // （实测被投诉「滑动很多次才停下」）。
            if (interrupted()) {
                dbg("跳页被打断（用户操作），停止滑动");
                return false;
            }
            service.shellLong("input swipe " + fx + " 1200 " + tx + " 1200 260");
            try { Thread.sleep(320); } catch (InterruptedException ignored) { }
        }
        // 🔴 滑完必须验证真的到了目标页：没到就**逐页修正**，不要放弃也不要继续瞎滑。
        //    没有圆点的机型只能靠滑，一次滑动跨的页数受惯性/屏幕尺寸影响，不总等于 1，
        //    所以「按差值滑 N 次」经常过冲或不足（实测目标第 2 页停在第 3 页）。
        if (interrupted()) return false;
        int[] after = parsePager(dumpHome(true));
        if (after[0] > 0 && after[0] != page) {
            dbg("跳页偏差：目标第 " + page + " 页，实际第 " + after[0] + " 页，开始逐页修正");
            for (int round = 0; round < 2 && after[0] > 0 && after[0] != page; round++) {
                int fix = page - after[0];
                int ffx = fix > 0 ? 950 : 200;
                int ftx = fix > 0 ? 200 : 950;
                int n = Math.min(Math.abs(fix), 8);
                for (int i = 0; i < n; i++) {
                    if (interrupted()) {
                        dbg("逐页修正被打断（用户操作）");
                        return false;
                    }
                    service.shellLong("input swipe " + ffx + " 1200 " + ftx + " 1200 260");
                    try { Thread.sleep(320); } catch (InterruptedException ignored) { }
                }
                if (interrupted()) return false;
                after = parsePager(dumpHome(true));
            }
            if (after[0] > 0 && after[0] != page) {
                dbg("跳页最终失败：目标第 " + page + " 页，停在第 " + after[0] + " 页");
                return false;
            }
        }
        return true;
    }

    private String dumpHome(boolean needPager) {
        // 🔴 必须用 --compressed。
        //
        // 桌面只要有一个动态组件（快递卡片、天气动画、音乐播放条），
        // uiautomator 就永远等不到 idle 状态，普通 dump 直接报：
        //     ERROR: could not get idle state
        // 于是页号解析全废（parsePager 返回 -1）→ 圆点坐标存不下来 →
        // 跳页只能盲滑 → 滑过头再修正 → 用户看到的就是「反复右滑」。
        //
        // --compressed 会跳过 idle 等待（实测同一时刻普通 dump 失败、compressed 成功），
        // 代价是 XML 里少了页码小圆点节点（所以 jumpToPageSaved 用不上，只能滑动跳页）。
        for (int i = 0; i < 3; i++) {
            // 重试期间用户可能已经切走了 —— 立刻停，别对着「设置」界面白 dump 三次
            if (i > 0 && needPager && userLeft()) return "";
            // 用户碰了屏幕就别再等了：一次 dump 2~4 秒，等它跑完用户早就以为没反应
            if (i > 0 && touchHit) return "";
            service.shellLong("uiautomator dump --compressed /sdcard/mineapp_loc.xml");
            String xml = service.shellLong("cat /sdcard/mineapp_loc.xml");
            int len = xml == null ? -1 : xml.length();
            boolean hasPage = xml != null && xml.contains("页共");
            dbg("dumpHome 第" + (i + 1) + "次: 长度=" + len + " 有页共=" + hasPage + " needPager=" + needPager);
            // 🔴 光有 <hierarchy> 不算成功：桌面刚 HOME 回来时常常只 dump 到
            //    3KB 左右的半成品（没有页号、没有图标），parsePager 直接返回 -1，
            //    后面就退化成盲滑。真实桌面 dump 都在 15KB 以上。
            // 🔴 长度门槛不可靠：实测 3070 / 6026 字节都可能「有 hierarchy 但没页号」，
            //    那是桌面还没渲染完的半成品。需要页号时必须确认「页共」节点真的在。
            //    不需要页号时（文件夹内 dump）门槛要低：小文件夹只有 2KB 出头，
            //    门槛设 3000 会逼它重试 3 次，整个索引被拖慢一倍。
            boolean ok = xml != null && xml.contains("<hierarchy")
                    && (needPager ? xml.contains("页共") : len > 1000);
            if (ok) return xml;
            // 🔴 需要页号却连续拿不到 = 人还在文件夹里（或文件夹编辑态）。
            //    这时前台仍然是桌面，userLeft() 判断不出来，就会白 dump 三次然后
            //    静默失败（实测：上一次定位停在文件夹里，下一次点定位直接没反应）。
            //    按一次 HOME 退回桌面根，再给一次机会。
            if (i == 2 && needPager && isLauncherFront()) {
                dbg("需要页号但拿不到，可能还在文件夹里 → 按 HOME 退回桌面再试");
                service.shellLong("input keyevent 3");
                try { Thread.sleep(600); } catch (InterruptedException ignored) { }
                service.shellLong("uiautomator dump --compressed /sdcard/mineapp_loc.xml");
                String x2 = service.shellLong("cat /sdcard/mineapp_loc.xml");
                if (x2 != null && x2.contains("页共")) {
                    dbg("HOME 后 dump 成功: 长度=" + x2.length());
                    return x2;
                }
            }
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
        }
        return "";
    }

    /**
     * 在桌面上定位某个应用：回桌面 → dump → 按应用名找坐标 → 长按。
     *
     * 桌面布局在 com.miui.home 私有库里（非 root 读不到），launcher 也没有
     * 「按包名定位」的公开接口。唯一通用通道是无障碍树：uiautomator dump
     * 能拿到每个图标的 content-desc（就是应用名）和精确 bounds。
     *
     * 找不到就逐个点开文件夹再找 —— 文件夹不展开时里面的图标进不了无障碍树。
     * 一次 dump 约 2.3 秒，所以只在必要时才翻文件夹。
     */
    @JavascriptInterface
    public void locate(final int id, final String arg) {
        dispatchLoc(id, new Task() {
            @Override
            public String run() {
                JSONObject o = new JSONObject();
                String raw = arg == null ? "" : arg;
                // 参数 "应用名|提示文案|缓存页号"：页号 -1 或省略表示不确定，需要逐页找
                String[] parts = raw.split("\\|", -1);
                String label = parts.length > 0 ? parts[0].trim() : "";
                String hint = parts.length > 1 ? parts[1].trim() : "";
                int wantPage = parts.length > 2 ? parseIntSafe(parts[2], -1) : -1;
                // 结束时的文案模板，{n} 会被替换成应用名（前端按当前语言给）
                String foundTpl = parts.length > 3 && !parts[3].trim().isEmpty()
                        ? parts[3].trim() : "✓ {n}";
                String nfTpl = parts.length > 4 && !parts[4].trim().isEmpty()
                        ? parts[4].trim() : "✗ {n}";
                try {
                    o.put("found", false);
                    locStop = false;
                    // 硬性超时：索引没命中时要全量扫，桌面页多 + 文件夹多能扫两分钟。
                    // 用户会觉得「停不下来」，所以给一个上限，到点就收尾并提示。
                    locDeadline = System.currentTimeMillis() + 40000L;
                    if (label.isEmpty()) return o.toString();
                    // 有缓存页号 = 已知位置，很快就完；没有 = 首次查找，要遍历桌面各页
                    boolean firstScan = wantPage < 1;
                    String baseHint = hint.isEmpty() ? "Looking for app" : hint;
                    startLocateNotice(firstScan ? baseHint + "（首次较慢）" : baseHint);
                    startTickToast(baseHint);

                    // 开始监听真人触摸（碰屏幕就停）。延后 700ms 再开：
                    // 点「定位」那一下手指抬起会在触摸屏上留事件，立刻监听会误判成
                    // 用户要打断，一启动就停。
                    main.postDelayed(new Runnable() {
                        @Override public void run() { startTouchWatch(); }
                    }, 700);

                    // 已经在桌面就不用按 HOME：HOME 会把页面拉回默认页（第 1 页），
                    // 紧接着再跳到索引页，视觉上就是「建了索引还从头翻」。
                    if (!isLauncherFront()){
                        service.shellLong("input keyevent 3");
                        // 🔴 等桌面真正渲染完再 dump：0.4 秒时 dump 常常拿不到
                        //    「第 N 页共 M 页」节点，parsePager 返回 -1，
                        //    跳页就会退化成盲滑（实测跳到第 3 页而不是第 2 页）。
                        try { Thread.sleep(900); } catch (InterruptedException ignored) { }
                    }

                    int[] hit = null;
                    int hitPage = -1;

                    // ⓪ 索引命中：索引**只当页号提示**用，坐标一律 dump 现取。
                    //
                    // 🔴 不能用索引里存的坐标，两个原因：
                    //  1. 那是建索引那一刻的快照，之后拖动图标、装新应用就全偏了；
                    //  2. 文件夹内的图标，当时记的是「文件夹里的坐标」，页号却是外层页号 ——
                    //     跳到外层页再用文件夹内坐标长按，必然落在空白处，
                    //     表现就是「定位偏了，长按变成了长按桌面」。
                    // 🔴 始终先查索引，不能因为「前端有缓存页号」就跳过。
                    //
                    //    前端 locCache 里存的 page 是**上一次定位成功的页号**，桌面一挪就过期。
                    //    以前的条件是 if (wantPage < 1) 才查索引 —— 只要缓存里有值就永远
                    //    不查索引，直接去那个过期的页号找，表现就是「明明有索引还是找不到」。
                    //    索引（建索引那一刻的全量快照）比单条缓存可靠得多。
                    if (true) {
                        int ipg = -1;
                        String idxFolder = "";
                        boolean indexHasData = false;
                        try {
                            android.content.SharedPreferences sp =
                                    web.getContext().getSharedPreferences("app", 0);
                            org.json.JSONObject idxObj = new org.json.JSONObject(
                                    sp.getString("locIndex", "{}"));
                            indexHasData = idxObj.length() > 0;
                            org.json.JSONArray v = idxObj.optJSONArray(label);
                            if (v != null && v.length() >= 1) {
                                ipg = v.getInt(0);
                                if (v.length() >= 2) idxFolder = v.optString(1, "");
                            }
                        } catch (Throwable ignored) { }

                        // 🔴 索引有内容、但里面没有这个应用 → 桌面上根本没有它的图标
                        //    （应用只装在系统里、从没拖到桌面，或者被拖进了抽屉）。
                        //    这种情况再怎么翻也找不到，直接说清楚，别让用户等 30 秒。
                        if (ipg < 1 && indexHasData) {
                            dbg("索引里没有: " + label + " → 桌面无图标");
                            service.shellLong("rm -f /sdcard/mineapp_loc.xml");
                            stopLocateNotice("桌面上没有「" + label + "」的图标");
                            endTickToast("桌面上没有「" + label + "」的图标");
                            o.put("noicon", true);
                            return o.toString();
                        }

                        if (ipg >= 1) {
                            dbg("索引命中: " + label + " → 第 " + ipg + " 页, 文件夹=[" + idxFolder + "]");
                            if (userLeft()) return o.toString();
                            boolean jumped = jumpToPageSaved(ipg);
                            if (!jumped) {
                                // 跳页失败要么是没存圆点、要么是刚被用户打断 —— 先确认一次
                                if (userLeft()) return o.toString();
                                // dumpHome 返回空串 = 用户切走了（或界面读不到），直接收尾，
                                // 否则 parsePager 拿到 -1 会继续 HOME + 盲滑
                                String px0 = dumpHome(true);
                                if (px0.isEmpty()) return o.toString();
                                jumped = jumpToPage(parsePager(px0), ipg);
                            }
                            if (jumped) {
                                if (userLeft()) return o.toString();
                                String px = dumpHome(false);
                                int[] real = findNode(px, label, false);
                                if (real == null) real = findNodeLoose(px, label);
                                // 🔴 索引记了文件夹名就直接开它 —— 以前是逐个点开试，
                                //    桌面 6 个文件夹就是十几秒，用户以为「建了索引还从头翻」。
                                if (real == null && idxFolder != null && !idxFolder.isEmpty()) {
                                    // 🔴 同名文件夹可能有多个（用户那页就有两个「旅行交通」）。
                                    //    以前找到第一个同名文件夹就 break，应用在第二个里就必然失败，
                                    //    失败后还会把索引删掉 —— 实测「迪士尼乐园」就是这么丢的。
                                    //    现在：所有同名文件夹都试一遍。
                                    java.util.List<Object[]> fs = findFoldersNamed(px);
                                    for (Object[] f : fs) {
                                        if (userLeft()) return o.toString();
                                        if (!idxFolder.equals(f[2])) continue;
                                        service.shellLong("input tap " + f[0] + " " + f[1]);
                                        try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                                        String fx = dumpHome(false);
                                        real = findNode(fx, label, false);
                                        if (real == null) real = findNodeLoose(fx, label);
                                        if (real != null) break;
                                        // 用 BACK 关文件夹：保留当前页，下一页同名文件夹的坐标才还有效。
                                        // （HOME 会跳回默认页，后面再点就点到别的图标上了）
                                        // 关之前先看用户有没有自己点了东西，别把他的应用一起关掉
                                        if (userLeft()) return o.toString();
                                        service.shellLong("input keyevent 4");
                                        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                                        String bx = dumpHome(false);
                                        if (bx.isEmpty()) return o.toString();   // 用户切走了
                                        if (parsePager(bx)[0] <= 0) {           // 还在文件夹里（编辑态）
                                            service.shellLong("input keyevent 4");
                                            try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                                        }
                                    }
                                }
                                if (real == null) {
                                    // 兜底（旧索引没有文件夹字段，或同名文件夹都没命中）：
                                    // 这一页的文件夹逐个点开找。每步都能被打断。
                                    java.util.List<int[]> fs = findFolders(px);
                                    for (int i = 0; i < fs.size() && i < 12; i++) {
                                        if (userLeft()) return o.toString();
                                        int[] f = fs.get(i);
                                        service.shellLong("input tap " + f[0] + " " + f[1]);
                                        try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                                        real = findNode(dumpHome(false), label, false);
                                        if (real != null) break;
                                        // 🔴 先看用户有没有自己点了东西：他打开的应用不要被我们按 HOME 顶掉，
                                        //    否则「点哪里都打断不了」（实测投诉）。
                                        if (userLeft()) return o.toString();
                                        service.shellLong("input keyevent 3");
                                        try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                                    }
                                }
                                if (real != null) {
                                    // 找到的那一瞬间也要确认用户没打断：否则会出现
                                    // 「提示已停止，但屏幕还在跳页、还长按了图标」
                                    if (userLeft()) return o.toString();
                                    clearIndexMiss(label);
                                    o.put("found", true);
                                    o.put("x", real[0]);
                                    o.put("y", real[1]);
                                    o.put("page", ipg);
                                    longPress(real[0], real[1]);
                                    service.shellLong("rm -f /sdcard/mineapp_loc.xml");
                                    stopLocateNotice(foundTpl.replace("{n}", label));
                                    endTickToast(foundTpl.replace("{n}", label));
                                    return o.toString();
                                }
                                // 🔴 不能一次没找到就删索引：同名文件夹、桌面刚好在动画、
                                //    dump 拿到半成品，都会让「明明在桌面上」的应用这次找不到。
                                //    以前直接 dropIndex → 索引条目永久消失，按钮也跟着消失
                                //    （实测「迪士尼乐园」就是这么被误删的）。改成计次，连续 3 次才删。
                                bumpIndexMiss(label);
                            }
                        }
                    }

                    // 🔴 索引命中了却没找到（跳页失败 / 同名文件夹都没有）→ 落到全量扫描。
                    //    这条路以前**没有任何打断检查**：用户碰了屏幕、提示也弹了，
                    //    它照样再扫 20 秒最后还长按图标（实测被投诉「滑动三次都没停」）。
                    if (userLeft()) return o.toString();

                    // 🔴 索引没命中才 dump 拿页号。
                    //    以前是开头无条件 dump 一次（2.3 秒），索引命中时这次 dump 纯属浪费，
                    //    叠加 HOME 回默认页，观感就是「建了索引还从头翻」。
                    String xml = dumpHome(true);
                    if (xml.isEmpty()) return o.toString();      // 用户切走了
                    int[] pager = parsePager(xml);
                    // 🔴 读不到页数时**不能默认 8 页**：桌面只有 3 页的话后面 5 页全是空的，
                    //    表现就是「在最后一页疯狂右滑」。默认 3 页，够覆盖绝大多数桌面。
                    int total = pager[1] > 0 ? pager[1] : 3;

                    // ① 有缓存页号：直接点页码跳过去，一次 dump 就够
                    if (wantPage >= 1 && wantPage <= total) {
                        if (userLeft()) return o.toString();
                        if (jumpToPage(pager, wantPage)) {
                            String px1 = dumpHome(false);
                            if (px1.isEmpty()) return o.toString();     // 用户切走了
                            hit = findNode(px1, label, false);
                            if (hit != null) hitPage = wantPage;
                        }
                    }

                    // ② 没有缓存 / 缓存失效：逐页点页码找（不滑动，点圆点更快更稳）
                    if (hit == null) {
                        for (int pg = 1; pg <= total && pg <= 8; pg++) {
                            // 用户随时可以打断：切到别的应用、按了通知栏「停止」、或者超时
                            if (userLeft()) return o.toString();
                            if (pg != (pager[0] > 0 ? pager[0] : -1)) {
                                if (!jumpToPage(pager, pg)) continue;
                            }
                            String px = dumpHome(false);
                            if (px.isEmpty()) return o.toString();     // 用户切走了
                            hit = findNode(px, label, false);
                            if (hit != null) { hitPage = pg; break; }
                            hit = findNodeLoose(px, label);        // 带角标后缀的再试一次
                            if (hit != null) { hitPage = pg; break; }
                            // 该页的文件夹：逐个点开找
                            java.util.List<int[]> folders = findFolders(px);
                            for (int i = 0; i < folders.size() && i < 12; i++) {
                                if (userLeft()) return o.toString();
                                int[] f = folders.get(i);
                                service.shellLong("input tap " + f[0] + " " + f[1]);
                                try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                                String fx = dumpHome(false);
                                if (fx.isEmpty()) return o.toString();
                                hit = findNode(fx, label, false);
                                if (hit != null) { hitPage = pg; break; }
                                // 用 BACK 关文件夹：保留当前页，否则后面的页号判断全错
                                if (userLeft()) return o.toString();
                                service.shellLong("input keyevent 4");
                                try { Thread.sleep(280); } catch (InterruptedException ignored) { }
                                String bx = dumpHome(false);
                                if (bx.isEmpty()) return o.toString();
                                if (parsePager(bx)[0] <= 0) {          // 还在文件夹里（编辑态）
                                    service.shellLong("input keyevent 4");
                                    try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                                }
                            }
                            if (hit != null) break;
                        }
                    }

                    if (hit == null) {
                        service.shellLong("rm -f /sdcard/mineapp_loc.xml");
                        stopLocateNotice(nfTpl.replace("{n}", label));
                        endTickToast(nfTpl.replace("{n}", label));
                        return o.toString();
                    }
                    // 长按之前最后确认一次：用户已经打断就别再动手了
                    if (userLeft()) return o.toString();
                    o.put("found", true);
                    o.put("x", hit[0]);
                    o.put("y", hit[1]);
                    o.put("page", hitPage);
                    longPress(hit[0], hit[1]);
                    service.shellLong("rm -f /sdcard/mineapp_loc.xml");
                    stopLocateNotice(foundTpl.replace("{n}", label));
                    endTickToast(foundTpl.replace("{n}", label));
                } catch (Throwable e) {
                    android.util.Log.w("Bridge", "locate 失败: " + e);
                } finally {
                    locDeadline = 0;      // 超时判据只对定位有效，别影响后面的建索引
                    stopTouchWatch();     // 撤掉触摸监听
                    dbg("finally: locResultShowing=" + locResultShowing
                            + " tickToast=" + (tickToast != null));
                    if (!locResultShowing) stopTickToast();
                    // 出错时也要撤掉通知，避免一直挂着
                    try {
                        android.app.NotificationManager nm =
                                (android.app.NotificationManager) web.getContext().getSystemService(
                                        android.content.Context.NOTIFICATION_SERVICE);
                        nm.cancel(LOC_ID);
                    } catch (Throwable ignored) { }
                }
                return o.toString();
            }
        });
    }

    private static int parseIntSafe(String v, int def) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    // ==================== 桌面索引 ====================
    /**
     * 一次性遍历桌面所有页 + 所有文件夹，把「应用名 → 第几页/坐标」存成索引。
     *
     * 之后每次定位先查索引：命中就直接点页码跳过去 + 长按，**一次 dump 都不用**。
     * 代价是首次几分钟（每页/每文件夹一次 dump，约 2.3 秒），所以做成手动触发 + 可续传。
     *
     * 中断判定：每步之前看一眼前台是不是桌面 —— 用户切去别的应用就立刻停。
     */
    @JavascriptInterface
    public void stopindex(int id, String arg) {
        idxStop = true;
    }

    @JavascriptInterface
    public void buildindex(int id, String arg) {
        dispatchHeavy(id, new Task() {
            @Override
            public String run() {
                JSONObject o = new JSONObject();
                idxStop = false;
                locStop = false;          // 静态标志：上次定位按过「停止」不能把索引一起带停
                locDeadline = 0;          // 建索引不看定位的超时判据
                idxRunning = true;
                try {
                    android.content.SharedPreferences sp =
                            web.getContext().getSharedPreferences("app", 0);
                    JSONObject index = new JSONObject(sp.getString("locIndex", "{}"));
                    int resumePage = sp.getInt("locIndexPage", 1);
                    if (resumePage < 1) resumePage = 1;
                    // 🔴 上次完成已超过 24 小时 = 索引过期，桌面多半变了。
                    //    这时候不能接着半成品续传（会得到一份新旧混杂的索引），
                    //    直接从第 1 页重来。
                    long lastDoneAt = sp.getLong("locIndexAt", 0L);
                    boolean staleIdx = lastDoneAt > 0L
                            && System.currentTimeMillis() - lastDoneAt > 24L * 3600L * 1000L;
                    // 🔴 只要上次完整跑完过（locIndexAt > 0），这次点「开始」就是**重建**：
                    //    必须清空旧索引。否则 index.has(name) 会跳过所有旧条目，
                    //    新数据永远写不进去 —— 表现是「重建了好几次，文件夹名还是旧的」。
                    //    只有「上次被打断、从没跑完」（resumePage > 1）才续传。
                    boolean rebuild = lastDoneAt > 0L && resumePage <= 1;
                    if (rebuild || staleIdx){
                        resumePage = 1;
                        index = new JSONObject();
                        sp.edit().putInt("locIndexPage", 1).apply();
                        dbg(rebuild ? "重建索引：清空旧数据" : "索引已过期，从头重建");
                    }

                    service.shellLong("input keyevent 3");
                    try { Thread.sleep(900); } catch (InterruptedException ignored) { }
                    // 用户已经切走了就别再 dump 三次（每次 2.3 秒，白等 7 秒）
                    if (userLeft()) return o.toString();
                    // 开触摸监听：建索引全程「碰屏幕就停」（以前只有定位有，索引只能靠切应用，
                    // 实测被投诉「滑动屏幕无法打断，也没有提示」）。延后 800ms，
                    // 免得把点「开始」那一下的手指抬起当成用户操作。
                    main.postDelayed(new Runnable() {
                        @Override public void run() { startTouchWatch(); }
                    }, 800);
                    int[] pager = parsePager(dumpHome(true));
                    // 🔴 读不到页数时**不能默认 8 页**：桌面只有 3 页的话，
                    //    后面 5 页全是空的，表现就是「在最后一页疯狂右滑」。
                    int total = pager[1] > 0 ? pager[1] : 0;
                    if (total <= 0) {
                        total = 3;      // 多数桌面 2~4 页，按 3 页试比按 8 页安全得多
                        dbg("读不到页数，按 3 页试");
                    }
                    // 把圆点坐标存下来，之后跳页连 dump 都不用
                    StringBuilder dots = new StringBuilder();
                    for (int i = 2; i + 1 < pager.length; i += 2) {
                        if (dots.length() > 0) dots.append(';');
                        dots.append(pager[i]).append(',').append(pager[i + 1]);
                    }
                    sp.edit().putString("locDots", dots.toString()).putInt("locTotal", total).apply();
                    // 上次的续传点可能超出当前总页数（桌面变少了 / 上次跑完了）→ 从头来
                    if (resumePage > total) resumePage = 1;

                    int added = 0, page = resumePage;
                    notifyLocate(resumePage > 1
                            ? "从第 " + resumePage + "/" + total + " 页继续索引"
                            : "开始遍历桌面 · 共 " + total + " 页 · 可随时打断", false);
                    for (; page <= total && page <= 8; page++) {
                        if (idxStop || touchHit || !isLauncherFront()) break;
                        jumpToPage(pager, page);
                        String xml = dumpHome(false);
                        added += indexPage(index, xml, page, "");
                        // 该页的文件夹（带名字：定位时能直接打开目标文件夹，不用逐个试）
                        java.util.List<Object[]> folders = findFoldersNamed(xml);
                        for (int i = 0; i < folders.size(); i++) {
                            if (idxStop || touchHit || !isLauncherFront()) break;
                            Object[] f = folders.get(i);
                            String fname = (String) f[2];
                            service.shellLong("input tap " + ((Integer) f[0]) + " " + ((Integer) f[1]));
                            try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                            added += indexPage(index, dumpHome(false), page, fname);
                            // 🔴 用 BACK 关文件夹，不要用 HOME。
                            //    HOME 一定会跳回桌面**第 1 页**（实测「第 1 页共 3 页」），
                            //    之后为了接着索引当前页的文件夹，又得从第 1 页滑回来 ——
                            //    用户看到的就是「打开文件夹 → 滑回去 → 左滑两下 → 再打开」
                            //    每处理一个文件夹折腾一遍（实测被投诉）。
                            //    BACK 只是关掉文件夹，页面停在原处，一下都不用滑。
                            service.shellLong("input keyevent 4");
                            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                        }
                        // 每页文件夹处理完，确认还停在本页（BACK 万一没关掉文件夹、或用户手动滑过）。
                        // 顺便把 pager 换成实测值：下一轮 jumpToPage 靠 pager[0] 算差值，
                        // 用开局那份旧的会导致「多滑一页再修正」。
                        int[] nowP = parsePager(dumpHome(true));
                        if (nowP[0] > 0) {
                            pager = nowP;
                            if (nowP[0] != page) {
                                dbg("索引：页号偏了(" + nowP[0] + "→" + page + ")，纠正");
                                jumpToPage(pager, page);
                            }
                        }
                        // 每页存一次盘，随时中断都不丢
                        sp.edit().putString("locIndex", index.toString())
                                .putInt("locIndexPage", page + 1).apply();
                        notifyLocate("正在遍历第 " + page + "/" + total + " 页 · 已索引 "
                                + index.length() + " 个应用 · 可随时操作手机打断", false);
                    }
                    boolean done = page > total;
                    if (done) {
                        sp.edit().putInt("locIndexPage", 1)
                                .putLong("locIndexAt", System.currentTimeMillis()).apply();
                    }
                    o.put("ok", true);
                    o.put("done", done);
                    o.put("count", index.length());
                    o.put("added", added);
                    if (done) {
                        stopLocateNotice("索引完成，共 " + index.length() + " 个应用");
                        dbg("索引完成，回 App");
                        backToApp();
                    } else {
                        // 被用户打断的，别硬拉回来
                        dbg("索引被中断（停在第 " + page + " 页），已暂停");
                        stopLocateNotice("已暂停，下次点「开始」会接着来");
                    }
                    idxRunning = false;
                    stopTouchWatch();
                } catch (Throwable e) {
                    android.util.Log.w("Bridge", "buildindex 失败: " + e);
                    try { o.put("ok", false); } catch (Throwable ignored) { }
                } finally {
                    // 提前 return 的分支（比如刚开始就发现用户切走了）也要清干净，
                    // 否则监听线程和 idxRunning 会一直挂着
                    idxRunning = false;
                    stopTouchWatch();
                }
                return o.toString();
            }
        });
    }

    /**
     * 把一屏里的应用图标记进索引：应用名 → [页号]。
     *
     * 🔴 只存页号，**不存坐标**。原因：
     *  - 坐标是建索引那一刻的快照，桌面一改（拖图标、装新应用）就全偏了；
     *  - 文件夹内的图标，此时 dump 出来的是「文件夹里的坐标」，
     *    但页号是外层页号 —— 两者根本不在一个坐标系，拿去长按必然落到空白处。
     * 定位时靠页号跳过去，坐标一律现场 dump 取，准确率 100%。
     *
     * 已存在的条目**不覆盖**：桌面层级的记录先写入，比文件夹内的更可信。
     */
    private int indexPage(JSONObject index, String xml, int page, String folder) {
        int n = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;
            String cls = nodeAttr(tag, "class");
            if (cls == null || !cls.endsWith("TextView")) continue;   // 只收应用图标
            // 去掉角标后缀：「微信，3 条通知」→「微信」
            String name = desc;
            int cut = -1;
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '，' || c == ',' || c == '：' || c == ':') { cut = i; break; }
            }
            if (cut > 0) name = name.substring(0, cut);
            if (name.isEmpty() || index.has(name)) continue;
            try {
                org.json.JSONArray v = new org.json.JSONArray();
                v.put(page);
                v.put(folder == null ? "" : folder);   // 第二项：所在文件夹名，空 = 直接在桌面
                index.put(name, v);
                n++;
            } catch (Throwable ignored) { }
        }
        return n;
    }

    /** 用已保存的圆点坐标跳页：不 dump，几十毫秒 */
    private boolean jumpToPageSaved(int page) {
        try {
            android.content.SharedPreferences sp =
                    web.getContext().getSharedPreferences("app", 0);
            String dots = sp.getString("locDots", "");
            if (dots.isEmpty()) return false;
            String[] arr = dots.split(";");
            if (page < 1 || page > arr.length) return false;
            String[] xy = arr[page - 1].split(",");
            service.shellLong("input tap " + xy[0] + " " + xy[1]);
            try { Thread.sleep(350); } catch (InterruptedException ignored) { }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 从索引里删掉一条（索引过期时用，避免下次还踩同一个坑） */
    /**
     * 索引命中但这次没找到 → 记一次失败。
     *
     * 🔴 连续 3 次才认定索引真的过期（应用被卸载/挪走）并删除条目。
     *    一次失败就删太激进：同名文件夹只试了第一个、桌面在动画导致 dump 半成品、
     *    用户刚好滑动了一下，都会失败，但应用其实好好地在桌面上。
     */
    private void bumpIndexMiss(String label) {
        try {
            android.content.SharedPreferences sp =
                    web.getContext().getSharedPreferences("app", 0);
            String k = "locMiss_" + label;
            int n = sp.getInt(k, 0) + 1;
            if (n >= 3) {
                sp.edit().remove(k).apply();
                dropIndex(label);
                dbg("索引连续 3 次未命中，删除条目: " + label);
            } else {
                sp.edit().putInt(k, n).apply();
                dbg("索引未命中第 " + n + " 次（不删）: " + label);
            }
        } catch (Throwable ignored) { }
    }

    private void clearIndexMiss(String label) {
        try {
            web.getContext().getSharedPreferences("app", 0)
                    .edit().remove("locMiss_" + label).apply();
        } catch (Throwable ignored) { }
    }

    private void dropIndex(String label) {
        try {
            android.content.SharedPreferences sp =
                    web.getContext().getSharedPreferences("app", 0);
            org.json.JSONObject idx = new org.json.JSONObject(sp.getString("locIndex", "{}"));
            if (idx.has(label)) {
                idx.remove(label);
                sp.edit().putString("locIndex", idx.toString()).apply();
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 回到 App 自己。
     *
     * 建索引是后台跑的，跑完时用户还停在桌面、什么都不知道。
     * 自动回来 + 触发前端刷新，「上次完成：刚刚」就能立刻看到。
     */
    private void backToApp() {
        try {
            android.content.Context ctx = web.getContext();
            // 🔴 普通 startActivity 会被 Android 10+ 的后台启动限制拦住（实测调了没反应）。
            //    App 自己有 ADB 通道，用 shell 身份 am start 就不受这个限制。
            boolean launched = false;
            try {
                service.shellLong("am start -n " + ctx.getPackageName() + "/.MainActivity");
                launched = true;
            } catch (Throwable ignored) { }
            if (!launched) {
                android.content.Intent i = ctx.getPackageManager()
                        .getLaunchIntentForPackage(ctx.getPackageName());
                if (i == null) return;
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                ctx.startActivity(i);
            }
            // 等界面切回来再触发前端刷新。
            // 🔴 注入可能落空：索引跑 1 分钟的期间 App 常被系统回收，WebView
            //    重建后 window.__idxDone 还不存在，evaluateJavascript 返回 "null"
            //    且不抛异常 —— 以前只注入一次，失败就停在列表页了。
            //    这里按 0.8s / 2s / 3.5s / 5s 重试，直到拿到非 null 结果。
            final int[] delays = {800, 2000, 3500, 5000};
            for (int k = 0; k < delays.length; k++) {
                final long dl = delays[k];
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            web.evaluateJavascript(
                                    "window.__idxDone && window.__idxDone()",
                                    new android.webkit.ValueCallback<String>() {
                                        @Override public void onReceiveValue(String v) {
                                            dbg("__idxDone 返回: " + v);
                                        }
                                    });
                        } catch (Throwable e) {
                            dbg("__idxDone 调用失败: " + e);
                        }
                    }
                }, dl);
            }
        } catch (Throwable e) {
            dbg("backToApp 失败: " + e);
        }
    }

    /** 前台是不是桌面 —— 用来判断用户有没有主动切走 */
    private boolean isLauncherFront() {
        try {
            String out = service.shellLong(
                    "dumpsys activity activities | grep -m1 topResumedActivity");
            return out != null && out.contains("com.miui.home");
        } catch (Throwable e) {
            return true;      // 读不到就当还在桌面，别误停
        }
    }

    /**
     * 用户切到别的应用了就停手，返回 true 表示调用方应当直接收尾返回。
     *
     * 🔴 定位的「索引命中」分支以前完全没做这个检查 —— 用户点完定位去用别的
     *    App，它还在后台翻桌面、长按图标。实测被投诉「查找应用时无法打断」。
     */
    /**
     * 开始监听「真人触摸」。
     *
     * 🔴 为什么要走 evdev：定位时前台一直是桌面，用户点哪里都检测不到
     *    （「切应用就停」只覆盖他打开别的 App 的情况）。而 `input tap` 是
     *    InputManager 注入的，**不会出现在触摸屏设备节点**（实测 event7 = goodix_ts
     *    在注入 tap 后 0 字节），所以 evdev 里出现的事件必然是真人手指。
     *    实测被反复投诉「无论点哪里都无法打断查找」。
     *
     * 用 setsid 丢到后台（实测进程能存活），定位循环只查文件大小，不阻塞命令通道。
     */
    private void startTouchWatch() {
        if (touchWatching) return;
        touchWatching = true;
        touchHit = false;
        dbg("触摸监听已启动 idxMode=" + idxRunning);
        new Thread(new Runnable() {
            @Override public void run() {
                while (touchWatching) {
                    long dl = locDeadline;
                    // locDeadline = 0 表示不限时（建索引要跑几分钟，不能用定位的 40 秒）
                    if (dl > 0 && System.currentTimeMillis() > dl) break;
                    try {
                        // 走普通连接，不占长连接；timeout 保证最多阻塞 5 秒
                        String out = service.shellQuick(
                                "timeout 5 getevent -c 1 /dev/input/event7", 7000);
                        if (out == null) continue;
                        for (String ln : out.split("\n")) {
                            String t = ln.trim();
                            if (t.isEmpty() || t.startsWith("add device")
                                    || t.startsWith("name:")) continue;
                            // 真实事件行形如：0003 0039 00000c1a
                            if (t.matches("^[0-9a-fA-F]{4}\\s+[0-9a-fA-F]{4}\\s+[0-9a-fA-F]+$")) {
                                if (!touchWatching) return;     // 已经收尾了，别再多弹一条
                                touchHit = true;
                                final boolean idxMode = idxRunning;
                                // 🔴 立刻给反馈：定位/索引线程可能正卡在一次 2~5 秒的
                                //    dumpHome 里，等它跑到下一个检查点再提示，
                                //    用户早就以为「没反应」了（实测被这么反馈）。
                                main.post(new Runnable() {
                                    @Override public void run() {
                                        dbg("检测到真人触摸 → 立即提示并收尾 idxMode=" + idxMode);
                                        if (idxMode) {
                                            stopLocateNotice("已暂停（你碰了屏幕）");
                                            endTickToast("已暂停");
                                        } else {
                                            stopLocateNotice("已停止（你碰了屏幕）");
                                            endTickToast("已停止");
                                        }
                                    }
                                });
                                return;
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            }
        }).start();
    }

    private void stopTouchWatch() {
        touchWatching = false;
        touchHit = false;
    }

    /** 用户碰过屏幕没有（只看真触摸屏，注入事件不会进来）。 */
    private boolean touchDetected() {
        return touchHit;
    }

    private boolean userLeft() {
        if (locStop) {
            dbg("用户按了「停止」");
            try {
                service.shellLong("rm -f /sdcard/mineapp_loc.xml");
                stopLocateNotice("已停止");
                endTickToast("已停止");
            } catch (Throwable ignored) { }
            return true;
        }
        // 兜底超时：全量扫描最多 40 秒，到点收尾，别让用户觉得「停不下来」
        if (locDeadline > 0 && System.currentTimeMillis() > locDeadline) {
            dbg("定位超时，收尾");
            try {
                service.shellLong("rm -f /sdcard/mineapp_loc.xml");
                stopLocateNotice("已停止（超时，建议重建索引）");
                endTickToast("已停止（超时）");
            } catch (Throwable ignored) { }
            return true;
        }
        // 用户碰了屏幕（真触摸屏事件）→ 立刻让位。这是「点哪里都能打断」的关键：
        // 定位/索引时前台一直是桌面，光靠「切应用」判断不出来。
        // 提示已经由监听线程立即发过（见 startTouchWatch），这里只负责收尾。
        if (touchWatching && touchDetected()) {
            dbg("检测到真人触摸，停止定位/索引");
            try { service.shellLong("rm -f /sdcard/mineapp_loc.xml"); } catch (Throwable ignored) { }
            return true;
        }
        if (isLauncherFront()) return false;
        dbg("用户切走了，停止定位/索引");
        try {
            service.shellLong("rm -f /sdcard/mineapp_loc.xml");
            stopLocateNotice("已停止（你切换了应用）");
            endTickToast("已停止");
        } catch (Throwable ignored) { }
        return true;
    }

    /** 排查用日志：MIUI 下 logcat 看不到应用日志，写文件 */
    private void dbg(String m) {
        try {
            // 🔴 不能写 /sdcard 根目录：App 没有 WRITE_EXTERNAL_STORAGE，会静默失败
            java.io.File d = web.getContext().getExternalFilesDir(null);
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(d, "loc.log"), true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date())
                    + "  " + m + "\n");
            w.close();
        } catch (Throwable ignored) { }
    }

    /** 长版 Toast（3.5 秒）—— 定位结果用这个，短版一闪就过、用户根本看不清 */
    private void toastLong(final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    makeUiToast(msg, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) { }
            }
        });
    }

    /** 桌面态提示：这时 App 已退到后台，WebView 里的 toast 看不见，必须用系统 Toast */
    private void toastOn(final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    makeUiToast(msg, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) { }
            }
        });
    }

    /**
     * 会走秒的 Toast —— 定位过程中一直挂在屏幕上，让用户知道没卡死。
     *
     * 复用**同一个** Toast 对象反复 setText + show：Android 会把内容换掉并重置计时，
     * 而不是排一串新的，所以能做出「一直显示 + 秒数在跳」的效果。
     * 通知栏那条继续留着当兜底（万一 MIUI 把后台 Toast 拦了）。
     */
    private UiToast tickToast;
    private Runnable tickTask;

    /**
     * 原生 Toast 统一走这里：**跟随应用主题**（浅色=白底深字，深色=深底浅字）。
     *
     * 为什么不用系统默认样式：系统 Toast 只跟随**系统**的深浅色，App 里切了主题就两边不一致
     * （应用内黑、应用外白，实测被投诉）。
     *
     * 限制：Android 11+ 对后台应用的自定义 Toast 视图有限制。所以先 makeText 把文本建好
     * （保证一定能显示），再尝试 setView 换成我们的样式；失败就退回系统样式，不会空白。
     */
    private static class UiToast {
        final android.widget.Toast toast;
        final android.widget.TextView tv;      // null = 用的是系统样式
        UiToast(android.widget.Toast t, android.widget.TextView v) { toast = t; tv = v; }
        void setText(String s) {
            try {
                if (tv != null) tv.setText(s); else toast.setText(s);
            } catch (Throwable ignored) { }
        }
        void show() { try { toast.show(); } catch (Throwable ignored) { } }
        void cancel() { try { toast.cancel(); } catch (Throwable ignored) { } }
    }

    /** 前端 applyTheme() 上报当前主题，供原生 Toast 用同一套配色。 */
    @JavascriptInterface
    public void theme(int id, String arg) {
        darkUi = "1".equals(arg);
    }

    private static volatile Boolean darkUi;   // null = 前端还没上报过

    private boolean isDarkUi() {
        Boolean v = darkUi;
        if (v != null) return v;
        try {
            int m = web.getContext().getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable e) {
            return false;
        }
    }

    private UiToast makeUiToast(String text, int duration) {
        android.content.Context c = web.getContext().getApplicationContext();
        android.widget.Toast t = android.widget.Toast.makeText(c, text, duration);
        // 🔴 不要 setView 自定义视图：Android 11+ 会**丢掉后台应用的自定义 Toast**，
        //    实测改成自定义视图后，「正在寻找应用」整条提示直接不显示了（比样式不一致严重得多）。
        //    应用外的 Toast 只能跟**系统**深浅色；应用内那条 HTML Toast 才是跟 App 主题走的。
        return new UiToast(t, null);
    }

    private void startTickToast(final String base) {
        stopTickToast();
        final long t0 = System.currentTimeMillis();
        tickTask = new Runnable() {
            @Override public void run() {
                try {
                    if (tickToast == null) {
                        tickToast = makeUiToast(base, android.widget.Toast.LENGTH_LONG);
                    }
                    long sec = (System.currentTimeMillis() - t0) / 1000;
                    tickToast.setText(sec >= 1 ? base + "  " + sec + "s" : base);
                    tickToast.show();
                } catch (Throwable ignored) { }
                main.postDelayed(this, 900);
            }
        };
        main.post(tickTask);
    }

    /**
     * 结果提示：**不新建 Toast**，直接把正在显示的那条文字换成结果，继续刷新 3.6 秒。
     *
     * 🔴 试过两条路都不行：
     *  1. cancel 掉过程 Toast 再新建一个 —— 新建的被系统丢弃，用户看不到「已找到」；
     *  2. 用独立对象并行 show —— 同样被丢弃（MIUI 对后台应用的 Toast 有节流）。
     * 唯一稳定的是「同一个对象、同一条刷新链，只换文字」——
     * 因为「正在寻找应用」就是这么显示出来的，路径已验证可行。
     *
     * locResultShowing 用来让 finally 别把这条结果提示一并清掉。
     */
    private volatile boolean locResultShowing;

    private static final String LOC_RES_CH = "app_manager_locate_result";
    private static final int LOC_RES_ID = 1003;

    /**
     * 结果提示走 **heads-up 通知**（屏幕顶部横幅）。
     *
     * 🔴 后台 Toast 靠不住：日志证明 show() 调了 5 次，但 App 在后台超过十几秒后
     * MIUI 就把 Toast 拦掉了，屏幕上一个字都没有。
     * 高优先级渠道的通知会以横幅弹出，不受后台 Toast 限制。
     */
    private void notifyResult(String text) {
        try {
            android.content.Context ctx = web.getContext();
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(
                            android.content.Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        LOC_RES_CH, "定位结果", android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);          // 横幅但不吵
                ch.enableVibration(false);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new android.app.Notification.Builder(ctx, LOC_RES_CH)
                    : new android.app.Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(text)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(4500);
            nm.notify(LOC_RES_ID, b.build());
        } catch (Throwable e) {
            dbg("notifyResult 失败: " + e);
        }
    }

    private void endTickToast(final String result) {
        dbg("endTickToast 进入: " + result + " tickToast=" + (tickToast != null));
        notifyResult(result);
        try {
            if (tickTask != null) main.removeCallbacks(tickTask);
            tickTask = null;
            locResultShowing = true;
            final long t0 = System.currentTimeMillis();
            tickTask = new Runnable() {
                @Override public void run() {
                    try {
                        if (tickToast == null) {
                            tickToast = makeUiToast(result, android.widget.Toast.LENGTH_LONG);
                        }
                        tickToast.setText(result);
                        tickToast.show();
                    } catch (Throwable ignored) { }
                    dbg("  tick show ok, tickToast=" + (tickToast != null));
                    if (System.currentTimeMillis() - t0 < 3600) {
                        main.postDelayed(this, 900);
                    } else {
                        main.postDelayed(new Runnable() {
                            @Override public void run() { stopTickToast(); }
                        }, 1200);
                    }
                }
            };
            main.post(tickTask);
        } catch (Throwable e) {
            dbg("endTickToast 异常: " + e);
            toastLong(result);
        }
    }

    private void stopTickToast() {
        try {
            if (tickTask != null) main.removeCallbacks(tickTask);
            if (tickToast != null) tickToast.cancel();
        } catch (Throwable ignored) { }
        tickTask = null;
        tickToast = null;
        locResultShowing = false;
    }

    /**
     * 定位过程提示：用**通知**而不是 Toast。
     *
     * 🔴 Android 11+ 禁止后台应用弹 Toast —— 点定位后马上回桌面，App 进入后台，
     * toastOn 就再也不显示了（实测确认）。改用通知，并用 setUsesChronometer(true)
     * 让系统自己走秒，比手动刷文字更稳、更省电。
     */
    // 🔴 渠道 id 带 v2：通知渠道的重要性创建后**不能修改**，
    //    旧渠道是 IMPORTANCE_DEFAULT，被 HyperOS 的 ranker_group 折叠进「更多通知」，
    //    用户完全看不到进度。要提权只能换新 id。
    private static final String LOC_CH = "app_manager_locate_v2";
    private static final int LOC_ID = 1002;
    private long locStartAt;
    private UiToast locToast;

    /** 屏幕底部的 Toast：比通知栏可靠得多（HyperOS 会把通知折叠进「更多通知」）。 */
    private void toastLoc(final String text) {
        try {
            final android.content.Context ctx = web.getContext();
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        // 先取消上一条，否则连续调用会排队，进度提示永远慢好几拍
                        if (locToast != null) locToast.cancel();
                        locToast = makeUiToast(text, android.widget.Toast.LENGTH_SHORT);
                        locToast.show();
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    private void notifyLocate(String text, boolean chronometer) {
        toastLoc(text);
        try {
            android.content.Context ctx = web.getContext();
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(
                            android.content.Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        LOC_CH, "定位提示", android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.setShowBadge(false);
                // 提权到 HIGH 是为了不被折叠，但遍历时每页都会更新，不能真的响
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new android.app.Notification.Builder(ctx, LOC_CH)
                    : new android.app.Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setGroup(null)          // 明确不参与系统自动分组（否则会被折叠成「更多通知」）
                    .setShowWhen(chronometer)
                    .setWhen(locStartAt)
                    .setUsesChronometer(chronometer);
            // 通知栏直接给一个「停止」按钮：滑动桌面这种操作前台还是桌面，检测不到，
            // 用户必须有个能按的开关
            try {
                android.content.Intent stop = new android.content.Intent(ACTION_STOP);
                stop.setPackage(ctx.getPackageName());
                int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    piFlags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                }
                b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止",
                        android.app.PendingIntent.getBroadcast(ctx, 1, stop, piFlags));
            } catch (Throwable ignored) { }
            nm.notify(LOC_ID, b.build());
        } catch (Throwable e) {
            try {
                java.io.FileWriter w = new java.io.FileWriter("/sdcard/mineapp_notif.log", true);
                w.write("notifyLocate 失败: " + e + "\n");
                w.close();
            } catch (Throwable ignored) { }
        }
    }

    private void startLocateNotice(String text) {
        locStartAt = System.currentTimeMillis();
        notifyLocate(text, true);
    }

    /** 结束：先把结果写进通知，2 秒后撤掉 */
    private void stopLocateNotice(String result) {
        try {
            android.content.Context ctx = web.getContext();
            final android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(
                            android.content.Context.NOTIFICATION_SERVICE);
            if (result != null) {
                notifyLocate(result, false);
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        try { nm.cancel(LOC_ID); } catch (Throwable ignored) { }
                    }
                }, 2000);
            } else {
                nm.cancel(LOC_ID);
            }
        } catch (Throwable ignored) { }
    }

    /** 按已知坐标长按（缓存命中时用，省掉 2.3 秒的 dump） */
    @JavascriptInterface
    public void tapat(int id, final String arg) {
        // 参数 "page|x,y"：page 为 -1 表示不跳页
        final String raw = arg == null ? "" : arg;
        dispatchLoc(id, new Task() {
            @Override
            public String run() {
                JSONObject o = new JSONObject();
                try {
                    String pageStr = "";
                    String xyStr = raw;
                    int bar = raw.indexOf('|');
                    if (bar >= 0) { pageStr = raw.substring(0, bar); xyStr = raw.substring(bar + 1); }
                    String[] p = xyStr.split(",");
                    if (p.length < 2) { o.put("ok", false); return o.toString(); }
                    int x = parseIntSafe(p[0], -1);
                    int y = parseIntSafe(p[1], -1);
                    if (x < 0 || y < 0) { o.put("ok", false); return o.toString(); }
                    service.shellLong("input keyevent 3");
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                    int pg = parseIntSafe(pageStr, -1);
                    if (pg >= 1 && !jumpToPageSaved(pg)) {
                        String xml = dumpHome(false);
                        jumpToPage(parsePager(xml), pg);
                    }
                    longPress(x, y);
                    o.put("ok", true);
                    toastOn("按上次记录的位置，若不对请再点一次重新查找");
                } catch (Throwable e) {
                    android.util.Log.w("Bridge", "tapat 失败: " + e);
                }
                return o.toString();
            }
        });
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
