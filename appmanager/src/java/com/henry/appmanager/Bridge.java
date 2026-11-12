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
        // 登记给 KeepAliveService 做内存推送用（Activity 重建时会被新实例覆盖）
        LIVE_WEB = new java.lang.ref.WeakReference<WebView>(web);
        initChannelOnce();      // 仅首次安装时自动判定，之后尊重用户选择
        syncChannel();
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

    /**
     * 纯网络任务（目前只有「检查更新」）专用线程池。
     *
     * 🔴 检查更新只发 HTTP 请求、完全不碰 ADB，如果跟列表刷新一起排 worker 单线程，
     *    前台正好在刷列表（采 PSS 要好几秒甚至几十秒）时，点「检查」会一直没反应 ——
     *    用户看到的就是「检查更新卡住了」。给它一条独立通道即可。
     */
    private final ExecutorService netExec = Executors.newSingleThreadExecutor();

    private void dispatchNet(final int id, final Task task) {
        netExec.execute(new Runnable() {
            @Override
            public void run() {
                String json;
                try {
                    json = task.run();
                } catch (Throwable e) {
                    android.util.Log.w("Bridge", "网络任务失败: " + e, e);
                    json = "{\"ok\":false,\"msg\":\"" + e + "\"}";
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
                            + "am start --activity-single-top -n " + pkg + "/" + MAIN_ACT_CLASS + " >> "
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
    /**
     * 这个节点是不是桌面文件夹。
     *
     * 🔴 不能只看 class：MIUI 的文件夹是 `RelativeLayout`，而 Pixel / AOSP 是
     *    `FrameLayout` —— 以前只认 RelativeLayout，导致非 MIUI 桌面上
     *    **文件夹从来没被识别过**，里面的应用永远索引不到（实测 Pixel 两个文件夹被完全跳过）。
     *
     * 现在按「可点击 + 长得像文件夹」判断：
     *   - class 是 RelativeLayout（MIUI）
     *   - 或者无障碍名是 "Folder: ..." / 含「文件夹」「个应用」（各家通用文案）
     */
    private static boolean isFolderNode(String tag, String desc) {
        if (desc == null || desc.isEmpty()) return false;
        if (!"true".equals(nodeAttr(tag, "clickable"))) return false;
        // 🔴 MIUI 的应用图标在无障碍树里也可能是 clickable 的 RelativeLayout，
        //    但它们的 text 和 content-desc 是同一个应用名；真文件夹 text 为空。
        //    不加这一条，一页会被识别成十几个「文件夹」，点下去全是启动应用。
        String txt = nodeAttr(tag, "text");
        if (txt != null && !txt.isEmpty() && txt.equals(desc)) return false;
        String cls = nodeAttr(tag, "class");
        if (cls != null && cls.endsWith("RelativeLayout")) return true;
        String low = desc.toLowerCase();
        if (low.startsWith("folder:")) return true;
        if (desc.contains("文件夹") || desc.contains("个应用")) return true;
        return false;
    }

    private static java.util.List<int[]> findFolders(String xml) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;
            if (!isFolderNode(tag, desc)) continue;
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
    /**
     * 等文件夹真的打开。
     *
     * 判据：桌面上的文件夹节点**消失**了。
     * 桌面 XML 里每个文件夹都有一个 isFolderNode 节点，打开文件夹后这些节点不再出现，
     * 而文件夹内容里不会有「文件夹」节点 —— 这个特征比「比长度」稳。
     *
     * @param pageFolders 打开前该页的文件夹数量
     * @return 文件夹内容树；超时没打开返回 null
     */
    private String waitFolderView(int pageFolders) {
        if (!A11yService.ready()) {
            // 经典路径：一次 dump 就要 2~3 秒，轮询没有意义，保持原来的固定等待
            try { Thread.sleep(450); } catch (InterruptedException ignored) { }
            return dumpHome(false);
        }
        for (int i = 0; i < 6; i++) {
            try { Thread.sleep(i == 0 ? 220 : 150); } catch (InterruptedException ignored) { }
            String ax = A11yService.dumpXml();
            if (ax == null || ax.length() < 800 || !ax.contains("<hierarchy")) continue;
            if (findFoldersNamed(ax).size() < pageFolders) {
                // 兜底：如果点开后前台不是桌面，说明点中的是应用图标（启动器把图标
                // 也暴露成 clickable RelativeLayout），这种不能当文件夹索引，退回去跳过。
                String pkg = firstPackage(ax), lp = launcherPkg();
                if (pkg != null && lp != null && !pkg.equals(lp)) {
                    dbg("点开的是应用 " + pkg + "，不是文件夹，跳过");
                    doBack();
                    waitBackToLauncher(pageFolders);
                    return null;
                }
                return ax;
            }
        }
        return null;
    }

    /** 取 XML 里第一个带 package 属性的节点值 */
    private static String firstPackage(String xml) {
        if (xml == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*package=\"([^\"]+)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** 等 BACK 把文件夹关掉、桌面重新回到前台，避免下一次点击落空 */
    private void waitBackToLauncher(int pageFolders) {
        if (!A11yService.ready()) {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            return;
        }
        for (int i = 0; i < 6; i++) {
            try { Thread.sleep(120); } catch (InterruptedException ignored) { }
            String ax = A11yService.dumpXml();
            if (ax == null) continue;
            if (findFoldersNamed(ax).size() >= pageFolders) return;
        }
    }

    /** Shizuku 授权结果监听（只注册一次，回调后摘掉） */
    private rikka.shizuku.Shizuku.OnRequestPermissionResultListener permListener;

    /** 当前屏幕尺寸，用来过滤掉「相邻页面」的节点（见 findFoldersNamed 的注释） */
    private static int SW = 0, SH = 0;

    private static java.util.List<Object[]> findFoldersNamed(String xml) {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;
            if (!isFolderNode(tag, desc)) continue;
            String bounds = nodeAttr(tag, "bounds");
            if (bounds == null) continue;
            java.util.regex.Matcher b = java.util.regex.Pattern
                    .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
            if (!b.find()) continue;
            int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
            int x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
            int cx = (x1 + x2) / 2, cy = (y1 + y2) / 2;
            // 🔴 无障碍树会把**相邻页面**的节点一起返回，它们的 x 会超过屏宽
            //    （实测下一页的文件夹在 x=1096、1197，屏幕只有 1080 宽）。
            //    不过滤的话，一页被算成 17 个「文件夹」，其中大部分点在屏幕外，
            //    表现为「点了没反应」，还会把 pageFolders 计数抬高导致判断失真。
            if (SW > 0 && (cx < 0 || cx >= SW || cy < 0 || cy >= SH)) continue;
            out.add(new Object[]{cx, cy, desc,
                    nodeAttr(tag, "class"), nodeAttr(tag, "text")});
        }
        return out;
    }

    /**
     * 这一页的文件夹名能不能用来定位。
     *
     * 判据：有重名，或者名字是占位符（"Folder: null, 2 items" 这种）。
     * 只要有一个不满足，**整页**改用序号标识 —— 一半按名字一半按序号会乱。
     *
     * MIUI 的文件夹名是「常用App」这类真实名字，不受影响，仍走名字匹配。
     */
    private static boolean folderNamesAreUseless(java.util.List<Object[]> folders) {
        if (folders.size() <= 1) return false;      // 只有一个时，名字再烂也只有它能选
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Object[] f : folders) {
            String n = (String) f[2];
            if (n == null || n.trim().isEmpty()) return true;
            String low = n.trim().toLowerCase();
            if (low.startsWith("folder:") || low.equals("null")) return true;
            if (!seen.add(n)) return true;          // 重名
        }
        return false;
    }

    /** 长按坐标：必须带 2~4px 位移，零距离会被当成点击直接打开应用 */
    private void longPress(int x, int y) {
        doLongPress(x, y);
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
        java.util.List<String> dotDescs = new java.util.ArrayList<String>();
        while (m.find()) {
            String tag = m.group();
            String desc = nodeAttr(tag, "content-desc");
            if (desc == null || desc.isEmpty()) continue;

            // ① MIUI：「第 1 页共 3 页」
            if (desc.contains("页共")) {
                java.util.regex.Matcher n = java.util.regex.Pattern
                        .compile("第\\s*(\\d+)\\s*页共\\s*(\\d+)\\s*页").matcher(desc);
                if (n.find()) {
                    cur = Integer.parseInt(n.group(1));
                    total = Integer.parseInt(n.group(2));
                }
                continue;
            }

            // ② 其他 ROM 的常见写法：「第 1/3 屏」「1/3」「Page 1 of 3」「1 of 3」
            //    🔴 不绑定 MIUI 文案 —— 很多国产 ROM 有页码，只是格式不同。
            if (cur < 0) {
                int[] pt = parsePagerText(desc);
                if (pt != null) {
                    // 「N/M」这种纯数字格式容易误匹配（时间、日期、版本号），
                    // 要求节点落在屏幕下方 1/4 区域（页码指示器就在那儿）
                    boolean numericOnly = desc.trim().matches("^\\d+\\s*[/／]\\s*\\d+$");
                    if (!numericOnly || inBottomArea(tag)) {
                        cur = pt[0];
                        total = pt[1];
                        continue;
                    }
                }
            }

            // ③ 圆点：MIUI「第 N 屏」或通用「Page N」
            if (desc.matches("第\\s*\\d+\\s*屏") || desc.matches("(?i)page\\s*\\d+")) {
                String bounds = nodeAttr(tag, "bounds");
                if (bounds == null) continue;
                java.util.regex.Matcher b = java.util.regex.Pattern
                        .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
                if (!b.find()) continue;
                int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2));
                int x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
                dots.add(new int[]{(x1 + x2) / 2, (y1 + y2) / 2});
                dotDescs.add(desc);
            }
        }

        // ④ 只有圆点没有文字时：圆点个数 = 总页数；
        //    当前页优先看 selected="true" 的那个，退而求其次看「第 N 屏」里的 N
        if (total < 0 && dots.size() > 1) {
            total = dots.size();
            if (cur < 0) {
                for (int i = 0; i < dotDescs.size(); i++) {
                    java.util.regex.Matcher n = java.util.regex.Pattern
                            .compile("(\\d+)").matcher(dotDescs.get(i));
                    if (n.find()) { cur = Integer.parseInt(n.group(1)); break; }
                }
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

    /** 从一段无障碍文本里解析「当前页 / 总页数」，认不出返回 null。 */
    private static int[] parsePagerText(String desc) {
        String s = desc.trim();
        java.util.regex.Matcher n;
        // 第 1/3 屏、第 1／3 页
        n = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*[/／]\\s*(\\d+)\\s*[页屏]").matcher(s);
        if (n.find()) return new int[]{Integer.parseInt(n.group(1)), Integer.parseInt(n.group(2))};
        // Page 1 of 3 / Page 1/3
        n = java.util.regex.Pattern.compile("(?i)page\\s*(\\d+)\\s*(?:of|[/／])\\s*(\\d+)").matcher(s);
        if (n.find()) return new int[]{Integer.parseInt(n.group(1)), Integer.parseInt(n.group(2))};
        // 1 of 3
        n = java.util.regex.Pattern.compile("^(\\d+)\\s+of\\s+(\\d+)$").matcher(s);
        if (n.find()) return new int[]{Integer.parseInt(n.group(1)), Integer.parseInt(n.group(2))};
        // 纯 1/3
        n = java.util.regex.Pattern.compile("^(\\d+)\\s*[/／]\\s*(\\d+)$").matcher(s);
        if (n.find()) {
            int a = Integer.parseInt(n.group(1)), b = Integer.parseInt(n.group(2));
            if (a >= 1 && a <= b && b <= 20) return new int[]{a, b};
        }
        return null;
    }

    /** 节点是否落在屏幕下方 1/4（页码指示器通常在这个区域）。 */
    private static boolean inBottomArea(String tag) {
        String bounds = nodeAttr(tag, "bounds");
        if (bounds == null) return false;
        java.util.regex.Matcher b = java.util.regex.Pattern
                .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
        if (!b.find()) return false;
        int y1 = Integer.parseInt(b.group(2)), y2 = Integer.parseInt(b.group(4));
        int centerY = (y1 + y2) / 2;
        return centerY > 1800;      // 1080×2400 屏幕上，1800 约在 3/4 处
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
            doTap(pager[idx], pager[idx + 1]);
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
            doHome();
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
            doSwipe(fx, 1200, tx, 1200, 260);
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
                    doSwipe(ffx, 1200, ftx, 1200, 260);
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

    /**
     * 桌面读不到任何页码信息时的兜底：**靠滑动数页数**（L2）。
     *
     * 思路：页码 = 滑动次数。先把页面滑到最左（再往右滑内容不再变化 = 到边界），
     * 再从最左一路左滑，每滑一次取一份「页面指纹」，指纹重复 = 绕回或到头。
     *
     * 成功返回总页数，并且**结束时停在页面最左那一页**；失败返回 0。
     * 负一屏（Google Discover / 智能助理）和循环滚动桌面都在这里被挡住：
     *   - 负一屏里几乎没有可点的应用图标 → 指纹里条目太少，识别后退回来
     *   - 循环桌面 → 指纹会重复，seen 集合直接终止
     */
    private int probeSweep() {
        final int MAX = 10;             // 超过 10 页就不再往下数，够用了
        doHome();
        try { Thread.sleep(700); } catch (InterruptedException ignored) { }
        String prev = pageSignature(dumpHome(false));
        if (prev.isEmpty()) {
            dbg("滑动探测：桌面 dump 为空，放弃");
            return 0;
        }

        // ① 一路右滑，直到内容不再变化（= 已经在最左一页）
        for (int i = 0; i < MAX; i++) {
            if (interrupted()) return 0;
            swipePage(false);
            String cur = pageSignature(dumpHome(false));
            if (cur.isEmpty()) return 0;
            if (cur.equals(prev)) break;              // 滑不动了
            // 🔴 判「还在不在桌面」只看顶层 Activity，不看图标名有没有重叠。
            //    以前用 sharesDock（两页有同名 content-desc 就算同桌面），在两页
            //    应用完全不重名时会判成「离开了桌面」，第一页就 break，页数变 1 —— 
            //    表现就是「只索引到首屏」。
            if (!isLauncherFront()) {
                dbg("滑动探测：右侧离开桌面，退回");
                swipePage(true);
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                break;
            }
            prev = cur;
        }
        // ② 从最左一页开始往左数，指纹重复或离开桌面即到头
        java.util.Set<String> seen = new java.util.HashSet<String>();
        seen.add(prev);
        int total = 1;
        for (int i = 0; i < MAX; i++) {
            if (interrupted()) return 0;
            swipePage(true);
            String cur = pageSignature(dumpHome(false));
            if (cur.isEmpty()) break;
            if (!seen.add(cur)) break;                // 绕回已见过的页 = 到头
            // 同 ①：用顶层 Activity 判断，而不是同名图标重叠
            if (!isLauncherFront()) {
                dbg("滑动探测：左侧离开桌面，停止");
                swipePage(false);                     // 退回最后一页真实桌面
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                break;
            }
            total++;
        }
        // ③ 滑回最左页，让调用方从第 1 页开始索引
        for (int i = 1; i < total; i++) {
            if (interrupted()) return 0;
            swipePage(false);
        }
        dbg("滑动探测：共 " + total + " 页");
        return total;
    }

    /**
     * 一页的「指纹」：所有**图标尺寸**的可点击节点，content-desc 排序后拼接。
     *
     * 🔴 必须按尺寸过滤：负一屏（Google Discover）的新闻卡片也是 clickable + 有 content-desc，
     *    不过滤会把负一屏当成一个正常页面，实测「单页桌面被数成 2 页」。
     *    应用图标和文件夹都在 400px 见方以内，新闻卡片是通栏宽。
     */
    private static String pageSignature(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        java.util.TreeSet<String> set = new java.util.TreeSet<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            if (!"true".equals(nodeAttr(tag, "clickable"))) continue;
            String d = nodeAttr(tag, "content-desc");
            if (d == null || d.isEmpty()) continue;
            String bounds = nodeAttr(tag, "bounds");
            if (bounds == null) continue;
            java.util.regex.Matcher b = java.util.regex.Pattern
                    .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
            if (!b.find()) continue;
            int w = Integer.parseInt(b.group(3)) - Integer.parseInt(b.group(1));
            int h = Integer.parseInt(b.group(4)) - Integer.parseInt(b.group(2));
            if (w > 400 || h > 400) continue;      // 通栏卡片/横幅，不是桌面图标
            set.add(d);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append('|');
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 无页码桌面的**全量扫描**（索引没命中时的兜底）。
     *
     * 从最左页开始逐页 dump，页内找不到就逐个点开文件夹找，然后左滑去下一页，
     * 直到「页面指纹重复」（到头或绕回）。页号用滑动次数自洽，不依赖任何页码文本。
     *
     * 找到返回节点坐标（并通过 pageOut[0] 带回页号），找不到返回 null。
     */
    private int[] sweepScan(String label, int[] pageOut) {
        final int MAX = 10;
        doHome();
        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int pg = 1; pg <= MAX; pg++) {
            if (userLeft()) return null;
            String px = dumpHome(false);
            if (px.isEmpty()) return null;
            if (!seen.add(pageSignature(px))) break;      // 到头 / 绕回
            int[] hit = findNode(px, label, false);
            if (hit == null) hit = findNodeLoose(px, label);
            if (hit != null) {
                if (pageOut != null) pageOut[0] = pg;
                dbg("滑动扫描命中: " + label + " → 第 " + pg + " 页");
                return hit;
            }
            // 该页的文件夹：文件夹不展开时里面的图标进不了无障碍树
            java.util.List<int[]> folders = findFolders(px);
            for (int i = 0; i < folders.size() && i < 12; i++) {
                if (userLeft()) return null;
                doTap(folders.get(i)[0], folders.get(i)[1]);
                try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                String fx = dumpHome(false);
                if (fx.isEmpty()) return null;
                hit = findNode(fx, label, false);
                if (hit == null) hit = findNodeLoose(fx, label);
                if (hit != null) {
                    if (pageOut != null) pageOut[0] = pg;
                    dbg("滑动扫描命中: " + label + " → 第 " + pg + " 页（文件夹内）");
                    return hit;
                }
                doBack();
                try { Thread.sleep(280); } catch (InterruptedException ignored) { }
            }
            if (pg < MAX) swipePage(true);
        }
        return null;
    }

    /** 翻页：toNext=true 左滑去下一页，false 右滑回上一页。 */
    private void swipePage(boolean toNext) {
        int fx = toNext ? 950 : 200;
        int tx = toNext ? 200 : 950;
        doSwipe(fx, 1200, tx, 1200, 260);
        try { Thread.sleep(320); } catch (InterruptedException ignored) { }
    }

    // ==================== 输入通道：无障碍优先，退回 shell 注入 ====================
    //
    // 无障碍可用时，HOME / 返回 / 点击 / 长按 / 滑动全部走 AccessibilityService：
    //   · 不需要 adb 授权，重启后也不用补
    //   · 不会被小米的「USB 调试（安全设置）」拦住（issue #3 的根因）
    // 拿不到服务（用户没开、或被系统关掉）就原样退回 `input`，行为不变。

    /** 输入退回 shell 注入时只记一次 —— 用来区分「真走了无障碍」还是「悄悄退回了」。 */
    private static boolean inputFallbackLogged;

    private void noteInputFallback(String what) {
        if (inputFallbackLogged) return;
        inputFallbackLogged = true;
        dbg("输入退回 shell 注入（首个）: " + what);
    }

    private void doHome() {
        if (A11yService.home()) return;
        noteInputFallback("home");
        service.shellLong("input keyevent 3");
    }

    private void doBack() {
        if (A11yService.back()) return;
        noteInputFallback("back");
        service.shellLong("input keyevent 4");
    }

    /** 起止同点 + 短时长 = 点击；长时长 = 长按。 */
    private void doTap(Object ox, Object oy) {
        int x = parseIntSafe(String.valueOf(ox), -1);
        int y = parseIntSafe(String.valueOf(oy), -1);
        if (x < 0 || y < 0) return;
        if (A11yService.gesture(x, y, x, y, 60)) return;
        noteInputFallback("tap");
        service.shellLong("input tap " + x + " " + y);
    }

    private void doLongPress(int x, int y) {
        // 长按时长沿用原来的 1500ms：部分 ROM 对 500ms 的长按判定不稳
        if (A11yService.gesture(x, y, x, y, 1500)) return;
        noteInputFallback("longpress");
        service.shellLong("input swipe " + x + " " + y + " " + (x + 4) + " " + y + " 1500");
    }

    private void doSwipe(int x1, int y1, int x2, int y2, int ms) {
        if (A11yService.gesture(x1, y1, x2, y2, ms)) return;
        noteInputFallback("swipe");
        service.shellLong("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
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
        // 🔴 无障碍可用时优先走它：读一次是几十毫秒，uiautomator dump 要 1.5~3 秒。
        //    拿不到页号、或树太短，就当没读到，继续走下面的 dump 兜底 ——
        //    两条路径的 XML 格式完全一致，下游解析一行都不用改。
        if (A11yService.ready()) {
            String last = null;
            for (int i = 0; i < 3; i++) {
                String ax = A11yService.dumpXml();
                if (ax != null && ax.length() > 1000 && ax.contains("<hierarchy")) {
                    last = ax;
                    // 用 parsePager 判断，而不是死盯「页共」两个字：
                    // 别的桌面可能是「第 1/3 屏」「Page 1 of 3」「1/3」甚至只有圆点，
                    // 只认「页共」会让它们每次都白重试 3 轮（3×400ms，白等 1 秒多）。
                    int[] pg = parsePager(ax);
                    boolean hasP = pg != null && pg.length > 1 && pg[1] > 0;
                    if (!needPager || hasP) {
                        dbg("dumpHome 无障碍: 长度=" + ax.length() + " 有页码=" + hasP);
                        return ax;
                    }
                }
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            }
            if (last != null) {
                // 连读三次都没有「页共」= 这个桌面本身就不暴露页码（Pixel / AOSP）。
                // 直接把最后一份有效树交回去，让上层走滑动探测。
                // 🔴 别退回 uiautomator 再试三遍：它读的是同一个无障碍树，结果一样，
                //    只是每次要多花 2.8 秒 —— 实测定位白白多等 9 秒。
                dbg("dumpHome 无障碍：桌面无页码，交给滑动探测 长度=" + last.length());
                return last;
            }
            dbg("dumpHome 无障碍读不到窗口，退回 uiautomator");
        }

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
                doHome();
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
                // 注入被系统拒绝时，后面每一步（HOME / 滑动 / 点击）都做不了。
                // 以前这种情况会在 1 秒后报「已停止（你切换了应用）」，把系统限制
                // 说成了用户操作（issue #3）。入口处先拦一道，让前端弹可操作的提示。
                // 无障碍是首选通道；没有时仍走旧的注入 + uiautomator 路径做兼容。
                // 只有两条路都不通，才提示用户去开无障碍。
                boolean a11y = A11yService.ready();
                Boolean canInject = a11y ? Boolean.TRUE : injectAllowed();
                if (canInject != null && !canInject) {
                    dbg("无障碍与注入都不可用：中止（needA11y）");
                    return "{\"ok\":false,\"needA11y\":true}";
                }
                String raw = arg == null ? "" : arg;
                // 参数 "应用名|提示文案|缓存页号"：页号 -1 或省略表示不确定，需要逐页找
                String[] parts = raw.split("\\|", -1);
                String label = parts.length > 0 ? parts[0].trim() : "";
                // 第 6 段是包名：桌面的 title 是「拖图标那一刻」的语言快照，切过语言就和
                // 应用列表对不上（索引里是 微信、列表里是 WeChat），查不到时用它兜底。
                String pkgArg = parts.length > 5 ? parts[5].trim() : "";
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
                        doHome();
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
                            if (v == null && pkgArg.length() > 0) v = idxObj.optJSONArray(pkgArg);
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
                            if (userLeft()) {
                        dbg("建索引：用户已切走，放弃");
                        return "{\"ok\":false,\"left\":true}";
                    }
                            boolean jumped = jumpToPageSaved(ipg);
                            if (!jumped) {
                                // 跳页失败要么是没存圆点、要么是刚被用户打断 —— 先确认一次
                                if (userLeft()) return o.toString();
                                // dumpHome 返回空串 = 用户切走了（或界面读不到），直接收尾，
                                // 否则 parsePager 拿到 -1 会继续 HOME + 盲滑
                                String px0 = dumpHome(true);
                                if (px0.isEmpty()) {
                                    // 🔴 无页码桌面（Pixel / AOSP）：读不到页码不是失败，
                                    //    按「HOME 回第 1 页 → 左滑 (page-1) 次」过去。
                                    dbg("无页码，改用滑动跳页 → 第 " + ipg + " 页");
                                    jumped = jumpToPageBySwipe(ipg);
                                } else {
                                    jumped = jumpToPage(parsePager(px0), ipg);
                                }
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
                                    // 索引里存成 "#2" 的（占位文件夹名的桌面，如 Pixel）：
                                    // 直接点该页第 3 个文件夹，不用挨个试
                                    int wantIdx = idxFolder.startsWith("#")
                                            ? parseIntSafe(idxFolder.substring(1), -1) : -1;
                                    for (int fi = 0; fi < fs.size(); fi++) {
                                        Object[] f = fs.get(fi);
                                        if (userLeft()) return o.toString();
                                        if (wantIdx >= 0) {
                                            if (fi != wantIdx) continue;
                                        } else if (!idxFolder.equals(f[2])) {
                                            continue;
                                        }
                                        doTap(f[0], f[1]);
                                        try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                                        String fx = dumpHome(false);
                                        real = findNode(fx, label, false);
                                        if (real == null) real = findNodeLoose(fx, label);
                                        if (real != null) break;
                                        // 用 BACK 关文件夹：保留当前页，下一页同名文件夹的坐标才还有效。
                                        // （HOME 会跳回默认页，后面再点就点到别的图标上了）
                                        // 关之前先看用户有没有自己点了东西，别把他的应用一起关掉
                                        if (userLeft()) return o.toString();
                                        doBack();
                                        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                                        String bx = dumpHome(false);
                                        if (bx.isEmpty()) return o.toString();   // 用户切走了
                                        if (parsePager(bx)[0] <= 0) {           // 还在文件夹里（编辑态）
                                            doBack();
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
                                        doTap(f[0], f[1]);
                                        try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                                        real = findNode(dumpHome(false), label, false);
                                        if (real != null) break;
                                        // 🔴 先看用户有没有自己点了东西：他打开的应用不要被我们按 HOME 顶掉，
                                        //    否则「点哪里都打断不了」（实测投诉）。
                                        if (userLeft()) return o.toString();
                                        doHome();
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
                    if (xml.isEmpty()) {
                        // 读不到页码 ≠ 用户切走了：可能是无页码桌面（Pixel / AOSP）。
                        // 真切走才收尾，否则改用滑动扫描（每页 dump + 翻文件夹，滑到内容重复为止）。
                        if (userLeft()) return o.toString();
                        dbg("无页码，改用滑动扫描全量查找");
                        int[] pgOut = new int[]{-1};
                        hit = sweepScan(label, pgOut);
                        if (hit != null) hitPage = pgOut[0];
                    } else {
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
                                doTap(f[0], f[1]);
                                try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                                String fx = dumpHome(false);
                                if (fx.isEmpty()) return o.toString();
                                hit = findNode(fx, label, false);
                                if (hit != null) { hitPage = pg; break; }
                                // 用 BACK 关文件夹：保留当前页，否则后面的页号判断全错
                                if (userLeft()) return o.toString();
                                doBack();
                                try { Thread.sleep(280); } catch (InterruptedException ignored) { }
                                String bx = dumpHome(false);
                                if (bx.isEmpty()) return o.toString();
                                if (parsePager(bx)[0] <= 0) {          // 还在文件夹里（编辑态）
                                    doBack();
                                    try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                                }
                            }
                            if (hit != null) break;
                        }
                    }
                    }   // ← else：有页码的分支到此结束

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
    /**
     * root 直读桌面数据库：一次全表扫描，换掉「翻页 + dump + 点开每个文件夹」。
     *
     * 拿不到 root、桌面不是 Launcher3 系、解析失败都返回 null —— 调用方退回无障碍遍历。
     * 所以这是一条纯加速路径，失败不影响功能。
     */
    private JSONObject rootIndex() {
        // root 直读是多通道版的专有能力：普通版只走「无障碍 + 逐页遍历」。
        // 两个版本编译的是同一份源码，只能靠包名区分。
        if (!isMulti()) return null;
        // 只有明确选「Root」通道才碰 su。默认通道是 ADB，按承诺不申请 root
        if (!"root".equals(execChannel())) return null;
        String dst = null;
        try {
            android.content.Context ctx = web.getContext();
            String pre = "";
            if (!"0".equals(service.shellQuick("id -u", 4000).trim())) {
                if (!"0".equals(service.shellQuick("su -c id", 4000).trim())) return null;
                pre = "su -c \"";               // Magisk 机型：adb shell 是 2000，得走 su
            }
            String home = service.shellQuick("cmd package resolve-activity --brief "
                    + "-a android.intent.action.MAIN -c android.intent.category.HOME | tail -1", 8000).trim();
            int slash = home.indexOf('/');
            if (slash <= 0) return null;
            String db = "/data/data/" + home.substring(0, slash) + "/databases/launcher.db";
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return null;
            dir.mkdirs();
            dst = dir.getAbsolutePath() + "/ldb.sqlite";
            // 拷到应用自己的外部目录：不用跟 SELinux context 较劲，应用直接读得到
            service.shellQuick(pre + "cp " + db + " " + dst + " && chmod 666 " + dst
                    + (pre.isEmpty() ? "" : "\""), 20000);

            MiniSqlite sql = new MiniSqlite(new java.io.File(dst));
            String[] cols = sql.columns("favorites");
            if (cols == null) return null;
            int iIntent = colIdx(cols, "intent"), iTitle = colIdx(cols, "title");
            int iCont = colIdx(cols, "container"), iScreen = colIdx(cols, "screen");
            int iCx = colIdx(cols, "cellX"), iCy = colIdx(cols, "cellY");
            if (iIntent < 0 || iCont < 0 || iScreen < 0) return null;
            java.util.List<String[]> rows = sql.rows("favorites");
            if (rows == null || rows.isEmpty()) return null;

            // 先把每行登记成「潜在文件夹」：别的行的 container 指向它的 _id
            java.util.HashMap<String, String[]> byId = new java.util.HashMap<String, String[]>();
            java.util.HashSet<String> used = new java.util.HashSet<String>();
            for (int i = 0; i < rows.size(); i++) {
                String[] r = rows.get(i);
                if (r[0] != null) byId.put(r[0], r);
                used.add(cell(r, iCont));
            }

            // 没名字的文件夹（Pixel 的 Dock 文件夹就是）只能按「本页第几个」标识，
            // 与无障碍路径的 "#0/#1" 约定一致 —— 定位侧本来就支持这种写法。
            java.util.HashMap<String, String> anon = new java.util.HashMap<String, String>();
            java.util.HashMap<String, java.util.List<String[]>> grp =
                    new java.util.HashMap<String, java.util.List<String[]>>();
            for (int i = 0; i < rows.size(); i++) {
                String[] r = rows.get(i);
                if (!cell(r, iTitle).isEmpty() || !used.contains(r[0])) continue;
                String pg = cell(r, iScreen);
                java.util.List<String[]> l = grp.get(pg);
                if (l == null) grp.put(pg, l = new java.util.ArrayList<String[]>());
                l.add(r);
            }
            for (java.util.List<String[]> l : grp.values()) {
                java.util.Collections.sort(l, new java.util.Comparator<String[]>() {
                    @Override public int compare(String[] a, String[] b) {
                        int d = num(cell(a, iCy)) - num(cell(b, iCy));
                        return d != 0 ? d : num(cell(a, iCx)) - num(cell(b, iCx));
                    }
                });
                for (int i = 0; i < l.size(); i++) anon.put(l.get(i)[0], "#" + i);
            }

            JSONObject index = new JSONObject();
            for (int i = 0; i < rows.size(); i++) {
                String[] r = rows.get(i);
                String intent = cell(r, iIntent);
                int cp = intent.indexOf("component=");
                if (cp < 0) continue;
                String comp = intent.substring(cp + 10);
                int semi = comp.indexOf(';');
                if (semi > 0) comp = comp.substring(0, semi);
                if (comp.indexOf('/') <= 0) continue;
                String name = cell(r, iTitle);
                if (name.isEmpty()) name = comp.substring(0, comp.indexOf('/'));
                if (index.has(name)) continue;
                int cont, screen;
                try {
                    cont = Integer.parseInt(cell(r, iCont).trim());
                    screen = Integer.parseInt(cell(r, iScreen).trim());
                } catch (NumberFormatException e) { continue; }
                int page;
                String folder = "";
                if (cont == -101) {
                    page = screen + 1;                                  // 桌面
                } else if (cont == -100) {
                    page = 1;                                           // Dock 每页都在，记到第 1 页
                } else if (cont > 0) {
                    String[] f = byId.get(Integer.toString(cont));       // 文件夹内
                    if (f == null) continue;
                    page = num(cell(f, iScreen)) + 1;
                    folder = cell(f, iTitle);
                    if (folder.isEmpty()) folder = anon.containsKey(f[0]) ? anon.get(f[0]) : "";
                } else {
                    continue;                                           // 小部件 / 搜索框之类
                }
                org.json.JSONArray v = new org.json.JSONArray();
                v.put(page);
                v.put(folder);
                index.put(name, v);
                // 桌面的 title 是「拖图标那一刻」的语言快照，系统切过语言就和应用列表对不上
                // （索引里是 微信、列表里是 WeChat）。包名与语言无关，同时写一份做兜底键。
                String pkg = comp.substring(0, comp.indexOf('/'));
                if (!index.has(pkg)) index.put(pkg, v);
            }
            if (index.length() == 0) return null;

            ctx.getSharedPreferences("app", 0).edit()
                    .putString("locIndex", index.toString())
                    .putInt("locIndexPage", 1)
                    .putLong("locIndexAt", System.currentTimeMillis()).apply();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("done", true);
            o.put("root", true);
            o.put("count", index.length());
            o.put("added", index.length());
            return o;
        } catch (Throwable e) {
            android.util.Log.w("Bridge", "rootIndex 失败: " + e);
            return null;
        } finally {
            if (dst != null) new java.io.File(dst).delete();   // 桌面布局是隐私，用完即删
        }
    }

    private static int colIdx(String[] cols, String name) {
        for (int i = 0; i < cols.length; i++) if (name.equalsIgnoreCase(cols[i])) return i;
        return -1;
    }

    private static String cell(String[] r, int i) {
        return i >= 0 && i < r.length && r[i] != null ? r[i] : "";
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * 构建应用位置索引：全量遍历桌面（无障碍 / 注入）。
     * 🔴 这个 @JavascriptInterface 曾经在 3.9.0 的重构里丢失，导致 JS 调不到、
     *    shim 静默返回空对象、界面一律显示「索引构建失败」，而日志一条都没有。
     */
    @JavascriptInterface
    public void buildindex(int id, String arg) {
        dbg("buildindex 调用");          // 探针 A：请求是否到达后端
        dispatchHeavy(id, new Task() {
            @Override
            public String run() {
                // root 直读桌面库：不发一个手势、不翻一页，几百毫秒出结果。
                // 放在注入检查之前 —— 这条路径压根不需要注入权限。
                dbg("buildindex 开始执行");   // 探针 B：任务是否真的跑起来
                long tRoot = System.currentTimeMillis();
                JSONObject fast = rootIndex();
                if (fast != null) {
                    // 带上耗时：root 路径正常几百毫秒，若这里出现 3~4 秒，
                    // 基本就是首次 su 授权框在等人点「允许」，或者 heavy 队列在排队
                    dbg("root 直读索引完成：" + fast.optInt("count") + " 个应用，耗时 "
                            + (System.currentTimeMillis() - tRoot) + "ms");
                    stopLocateNotice("索引完成（root 直读桌面库）："
                            + fast.optInt("count") + " 个应用");
                    return fast.toString();
                }
                JSONObject o = new JSONObject();
                // 无障碍是首选路径。没开时不再直接失败，也不擅自跳设置页，
                // 而是交回前端让用户二选一：经典查找 / 去开启无障碍。
                // 多通道版若已拿到 root，上面 rootIndex() 已经返回，走不到这里。
                boolean forceClassic = "classic".equals(arg == null ? "" : arg.trim());
                boolean a11y = A11yService.ready();
                if (!a11y && !forceClassic) {
                    // 顺便探测注入，告诉前端「经典」这条路是否真的可行
                    boolean canClassic = !Boolean.FALSE.equals(injectAllowed());
                    dbg("无无障碍，等用户选择（needA11y, canClassic=" + canClassic + "）");
                    return "{\"ok\":false,\"needA11y\":true,\"canClassic\":"
                            + canClassic + "}";
                }
                if (!a11y) {
                    // 用户已明确选「经典查找」：注入被拒就真的没别的路了
                    Boolean canInject = injectAllowed();
                    if (canInject != null && !canInject) {
                        dbg("经典查找：注入被拒（needInject）");
                        return "{\"ok\":false,\"needInject\":true}";
                    }
                }
                dbg(a11y ? "路径：无障碍读取" : "路径：经典（模拟操作 + dump）");
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

                    doHome();
                    try { Thread.sleep(900); } catch (InterruptedException ignored) { }
                    android.util.DisplayMetrics dm = web.getContext().getResources().getDisplayMetrics();
                    SW = dm.widthPixels;
                    SH = dm.heightPixels;
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
                    boolean sweepMode = false;
                    if (total <= 0) {
                        // 完全读不到页码（Pixel / AOSP 桌面）：改用滑动探测数页数。
                        // 探测失败才认为这台设备的桌面真的不支持。
                        dbg("读不到页码，改用滑动探测");
                        total = probeSweep();
                        if (total <= 0) {
                            dbg("滑动探测也失败：当前桌面不支持应用查找");
                            o.put("ok", false);
                            o.put("unsupported", true);
                            // 必须先把 App 拉回前台：前端用的是 HTML 模态，
                            // 界面不在前台时用户什么都看不到（实测点了「开始」毫无反应）。
                            backToApp();
                            return o.toString();
                        }
                        sweepMode = true;
                        pager = new int[]{-1, total};     // 无圆点、无当前页
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
                    // 滑动探测结束时停在最左页，从它开始按差值左滑，不用再按 HOME
                    int sweepCur = 1;
                    notifyLocate(resumePage > 1
                            ? "从第 " + resumePage + "/" + total + " 页继续索引"
                            : "开始遍历桌面 · 共 " + total + " 页 · 可随时打断", false);
                    // 上限 20 页：只为防「页数算错导致死循环」，正常桌面远小于此。
                    // 以前写死 8 页，桌面超过 8 页时后面几页永远索引不到。
                    for (; page <= total && page <= 20; page++) {
                        if (idxStop || touchHit || !isLauncherFront()) break;
                        if (sweepMode) {
                            // 🔴 这里不能用 jumpToPage：它读不到当前页时会按 HOME，
                            //    而 HOME 回的是「默认页」，不一定是最左页 —— 差值就错了。
                            while (sweepCur < page) {
                                if (interrupted()) break;
                                swipePage(true);
                                sweepCur++;
                            }
                        } else {
                            jumpToPage(pager, page);
                        }
                        String xml = dumpHome(false);
                        added += indexPage(index, xml, page, "");
                        // 该页的文件夹（带名字：定位时能直接打开目标文件夹，不用逐个试）
                        java.util.List<Object[]> folders = findFoldersNamed(xml);
                        // 🔴 有的桌面（Pixel / AOSP）所有文件夹的无障碍名**完全一样**
                        //    （"Folder: null, 2 items"），靠名字定位必然点错、只能一个个试。
                        //    这种情况下改用「该页第几个文件夹」来标识（"#0"、"#1"…）——
                        //    同一页的 dump 顺序稳定，索引和定位时拿到的序号一致。
                        boolean useFolderIndex = folderNamesAreUseless(folders);
                        if (useFolderIndex && !folders.isEmpty()) {
                            dbg("文件夹名不可用（重复或占位），改用序号标识，本页 " + folders.size() + " 个");
                        }
                        for (int i = 0; i < folders.size(); i++) {
                            if (idxStop || touchHit || !isLauncherFront()) break;
                            Object[] f = folders.get(i);
                            String fname = useFolderIndex ? ("#" + i) : (String) f[2];
                            // 🔴 不能「点完就 dump」：MIUI 会丢掉部分快速手势，
                            //    实测 31 个文件夹里有 14 个没打开，每次白等约 1 秒。
                            //    改成「确认读到文件夹内容才算数」，读不到重试一次，仍不行跳过。
                            String fx = null;
                            for (int attempt = 0; attempt < 2 && fx == null; attempt++) {
                                doTap(f[0], f[1]);
                                fx = waitFolderView(folders.size());
                                if (fx == null) {
                                    dbg("文件夹没打开（第 " + (attempt + 1) + " 次）: " + fname);
                                }
                            }
                            if (fx != null) {
                                added += indexPage(index, fx, page, fname);
                            } else {
                                dbg("文件夹跳过（点不开）: " + fname);
                            }
                            // 🔴 用 BACK 关文件夹，不要用 HOME。
                            //    HOME 一定会跳回桌面**第 1 页**（实测「第 1 页共 3 页」），
                            //    之后为了接着索引当前页的文件夹，又得从第 1 页滑回来 ——
                            //    用户看到的就是「打开文件夹 → 滑回去 → 左滑两下 → 再打开」
                            //    每处理一个文件夹折腾一遍（实测被投诉）。
                            //    BACK 只是关掉文件夹，页面停在原处，一下都不用滑。
                            doBack();
                            // 等桌面真的回来再点下一个，否则下一次点击会落在还没关掉的文件夹上
                            waitBackToLauncher(folders.size());
                        }
                        // 每页文件夹处理完，确认还停在本页（BACK 万一没关掉文件夹、或用户手动滑过）。
                        // 顺便把 pager 换成实测值：下一轮 jumpToPage 靠 pager[0] 算差值，
                        // 用开局那份旧的会导致「多滑一页再修正」。
                        // 滑动模式下没有页码可测，这一步纯属白等 3 秒（实测每页多花 9 秒），跳过
                        // 没开过文件夹就没必要再读一遍：那一 dump 纯粹是白等
                        if (!sweepMode && !folders.isEmpty()) {
                            int[] nowP = parsePager(dumpHome(true));
                            if (nowP[0] > 0) {
                                pager = nowP;
                                if (nowP[0] != page) {
                                    dbg("索引：页号偏了(" + nowP[0] + "→" + page + ")，纠正");
                                    jumpToPage(pager, page);
                                }
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
    /**
     * 无页码桌面专用：HOME 回第 1 页，再左滑 (page-1) 次。
     *
     * 前提是 HOME 落在最左一页 —— Pixel / AOSP 桌面确实如此；
     * 若某些桌面 HOME 回到「默认页」，这里会偏，所以只在读不到页码时才用。
     */
    private boolean jumpToPageBySwipe(int page) {
        if (page < 1) return false;
        doHome();
        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        for (int i = 1; i < page; i++) {
            if (interrupted()) {
                dbg("滑动跳页被打断");
                return false;
            }
            swipePage(true);
        }
        return true;
    }

    private boolean jumpToPageSaved(int page) {
        try {
            android.content.SharedPreferences sp =
                    web.getContext().getSharedPreferences("app", 0);
            String dots = sp.getString("locDots", "");
            if (dots.isEmpty()) return false;
            String[] arr = dots.split(";");
            if (page < 1 || page > arr.length) return false;
            String[] xy = arr[page - 1].split(",");
            doTap(xy[0], xy[1]);
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
                service.shellLong("am start -n " + ctx.getPackageName() + "/" + MAIN_ACT_CLASS);
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
    /**
     * 当前 ROM 的桌面（launcher）包名，惰性解析一次后缓存。
     *
     * 🔴 以前这里写死 `com.miui.home`，只在 MIUI / HyperOS 上成立。
     *    在模拟器（nexuslauncher）或任何其他厂商的桌面上，前台判断永远为 false，
     *    表现就是「点开始建索引，1 秒后提示用户切走了、直接中止」——
     *    实测在 Android 14 模拟器上必现。
     */
    private String launcherPkg;

    private String launcherPkg() {
        if (launcherPkg != null) return launcherPkg;
        try {
            String out = service.shellLong("cmd package resolve-activity --brief "
                    + "-a android.intent.action.MAIN -c android.intent.category.HOME | tail -1");
            if (out != null) {
                String s = out.trim();
                int slash = s.indexOf('/');
                if (slash > 0) {
                    launcherPkg = s.substring(0, slash);
                    dbg("桌面包名 = " + launcherPkg);
                    return launcherPkg;
                }
            }
        } catch (Throwable ignored) { }
        return null;      // 解析失败 → 调用方按「不误停」处理
    }

    private boolean isLauncherFront() {
        try {
            String out = service.shellLong(
                    "dumpsys activity activities | grep -m1 topResumedActivity");
            if (out == null) return true;
            String lp = launcherPkg();
            if (lp != null) return out.contains(lp);
            // 拿不到桌面包名时退回旧判据，至少 MIUI 上还能用
            return out.contains("com.miui.home");
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

    /**
     * 主 Activity 的**完整类名**。
     *
     * 🔴 多通道版靠 aapt2 --rename-manifest-package 改 applicationId，
     *    但 Java 类包名不变。所以拼 am start 时不能写 pkg + "/.MainActivity"，
     *    否则会解析成 <新包名>.MainActivity —— 那个类不存在，拉不回前台。
     */
    private static final String MAIN_ACT_CLASS = "com.henry.appmanager.MainActivity";
    /**
     * activity-alias 所在的命名空间。
     *
     * 🔴 声明写的是相对名 ".AliasMineSqG"，aapt2 会**按原始 package** 展开成
     *    com.henry.appmanager.AliasMineSqG；--rename-manifest-package 只改 manifest
     *    的 package 字段，不会重写已展开的组件名。
     *    所以多通道版的 alias 仍在这个命名空间下，切换时必须用它，而不是 getPackageName()。
     */
    static final String COMP_NS = "com.henry.appmanager.";

    private static final String LOC_RES_CH = "app_manager_locate_result";
    private static final int LOC_RES_ID = 1003;

    // ==================== 注入能力探测 ====================
    private static final String INJECT_CH = "app_manager_inject_hint";
    private static final int INJECT_ID = 1004;
    /** 探测成功过就记住，省掉后续每次的往返（关掉开关必须重启，本进程内不会变）。 */
    private static volatile boolean injectOk;

    /**
     * 探测 shell(uid 2000) 能否注入输入事件。
     *
     * 🔴 为什么不能只读 persist.security.adbinput：那是小米专有属性，
     *    非小米 ROM 上恒为空串，照「!=1 就拦」会把 Pixel / 三星用户全拦死。
     *    这里直接拿一次无害注入去试，任何 ROM 都适用。
     *
     * 用 keyevent 0（KEYCODE_UNKNOWN）：注入权限是在 InputManagerService 里
     * 按调用方 uid 检查的，早于任何按键语义处理，所以它同样会被拦；
     * 而它对系统没有任何实际影响。
     */
    private Boolean injectAllowed() {
        if (injectOk) return Boolean.TRUE;
        try {
            String r = service.shellLong("sh -c 'input keyevent 0 2>&1; echo RC=$?'");
            if (r == null || r.trim().isEmpty()) return null;
            // 只看退出码不可靠（input 遇到非法参数也返 0），有明确拒绝证据才下结论
            if (r.contains("SecurityException") || r.contains("INJECT_EVENTS")
                    || r.contains("Permission Denial")) return Boolean.FALSE;
            if (r.contains("RC=0")) {
                injectOk = true;
                return Boolean.TRUE;
            }
            return null;          // 认不出来 → 放行，宁可不禁
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 注入被拒时的提示：系统 Toast + 一条能点开开发者选项的通知。
     *
     * 文案由前端传进来（应用内支持中英切换，Java 侧不该写死一种语言）。
     *
     * 只负责**发通知**：正文交给通知栏（可展开看全），应用内的提示条由前端显示 ——
     * 系统 Toast 只有两行，「允许通过 USB 调试修改权限或模拟点击」会被截断，
     * 用户看不出该开哪个开关（实测确认）。
     * 通知留在通知栏，点一下直接跳到开发者选项，重启回来还在。
     */
    @JavascriptInterface
    public void hintinject(int id, String arg) {
        String title = "开启「USB 调试（安全设置）」";
        // 🔴 收起状态只会显示 title + contentText 的一小段，所以**动作要放进这两行**；
        //    完整说明放 bigText，展开才看。之前把长句塞进 contentText，收起时只剩
        //    「系统…」，实测完全读不出要干什么。
        String shortText = "允许通过 USB 调试修改权限或模拟点击";
        String body = "应用查找要靠模拟点击桌面才能遍历。请到「开发者选项」打开"
                + "「USB 调试（安全设置）」—— 它下面写着「允许通过 USB 调试修改权限或模拟点击」，"
                + "不要开成上面那个「USB 调试」。打开后重启手机（这个开关必须重启才生效），"
                + "再回来点「开始」。";
        try {
            JSONObject j = new JSONObject(arg == null ? "{}" : arg);
            String v;
            v = j.optString("title", "");
            if (v.length() > 0) title = v;
            v = j.optString("text", "");
            if (v.length() > 0) shortText = v;
            v = j.optString("body", "");
            if (v.length() > 0) body = v;
        } catch (Throwable ignored) { }

        notifyInjectHint(title, shortText, body);
    }

    private void notifyInjectHint(String title, String shortText, String body) {
        try {
            android.content.Context ctx = web.getContext();
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(
                            android.content.Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        INJECT_CH, "调试通道提示", android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(false);
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }
            android.content.Intent it = new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            int pf = android.os.Build.VERSION.SDK_INT >= 23
                    ? android.app.PendingIntent.FLAG_UPDATE_CURRENT
                      | android.app.PendingIntent.FLAG_IMMUTABLE
                    : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            android.app.PendingIntent pi =
                    android.app.PendingIntent.getActivity(ctx, 0, it, pf);
            android.app.Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new android.app.Notification.Builder(ctx, INJECT_CH)
                    : new android.app.Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(shortText)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                    // 单独分组：HyperOS 会把同一 App 的未分组通知自动打包，
                    // 收起时用最早那条（常驻状态）的正文当组摘要 —— 实测把我们这段
                    // 说明整段顶掉，只剩「系统…」两个字。
                    .setGroup(INJECT_CH)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false);
            nm.notify(INJECT_ID, b.build());
        } catch (Throwable e) {
            dbg("notifyInjectHint 失败: " + e);
        }
    }

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
     * 后台 Toast 就再也不显示了（实测确认）。改用通知，并用 setUsesChronometer(true)
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

    /**
     * 当前发行通道的 tag 前缀。
     *
     * 靠**包名**判断，不用编译期开关：多通道版包名以 .multi 结尾，
     * 以后 build.sh 只改 applicationId，这里自动走对通道，不会出现
     * 「忘了定义宏，两个版本查到同一个 release」的事故。
     *
     *   主版本      com.henry.appmanager        → tag  v3.9.0
     *   多通道版    com.henry.appmanager.multi  → tag  multi-v3.9.0
     */
    private String releasePrefix() {
        try {
            String pn = web.getContext().getPackageName();
            if (pn != null && pn.endsWith(".multi")) return "multi-v";
        } catch (Throwable ignored) { }
        return "v";
    }

    /** 是否多通道版：只有它才有多通道授权能力 */
    private boolean isMulti() {
        String pn = web.getContext().getPackageName();
        return pn != null && pn.endsWith(".multi");
    }

    /**
     * 读「执行通道」偏好，并归一化成 adb / root / shizuku 三选一。
     *
     * 4.0.1 起去掉了「自动」：它语义含糊（到底走哪条说不清），而且作为默认值
     * 容易让人以为「已经能用了」，实际什么都没发生。现在默认 ADB，
     * 想用 Shizuku / Root 自己选，界面上一眼就能看出当前走的是哪条。
     *
     * 老版本存下的 "auto"（以及任何未知值）一律按 ADB 处理。
     */
    private String execChannel() {
        String c = "adb";
        try {
            c = web.getContext().getSharedPreferences("app", 0)
                    .getString("execChannel", "adb");
        } catch (Throwable ignored) { }
        return ("root".equals(c) || "shizuku".equals(c)) ? c : "adb";
    }

    /** 把「执行通道」偏好同步给各 Shell：Shizuku 与 Root 互斥，二者都不依赖 ADB */
    private void syncChannel() {
        try {
            String c = execChannel();
            ShizukuShell.enabled = isMulti() && "shizuku".equals(c);
            RootShell.enabled = isMulti() && "root".equals(c);
        } catch (Throwable e) {
            ShizukuShell.enabled = false;
            RootShell.enabled = false;
        }
    }

    /** 常见 su 路径，存在即认为设备已 root（不实际执行 su，避免弹授权框） */
    private static boolean hasRootBinary() {
        return RootShell.available();
    }

    /**
     * 只在「首次安装并首次打开」时自动判定一次执行通道：
     * 有 Shizuku → Shizuku，有 root → Root，都没有 → ADB。
     *
     * 🔴 判据是偏好里「有没有这个键」，而不是值：
     *    - 用户手动选过 → 键存在，这里直接返回，绝不覆盖
     *    - 覆盖更新   → 键仍在，保持用户原来的选择
     *    - 卸载重装   → 数据清空，重新判定一次
     * 
     * 以前把默认值写死成 ADB，导致 Shizuku/root 用户装完还要自己去设置里找，
     * 属于「默认值没帮上忙」。
     */
    private void initChannelOnce() {
        try {
            android.content.SharedPreferences sp =
                    web.getContext().getSharedPreferences("app", 0);
            if (sp.contains("execChannel")) return;      // 判定过 / 用户选过，不再插手
            String c = "adb";
            // 冷启动瞬间 binder 可能还没送到，给它 600ms；毕竟只判这一次
            if (ShizukuShell.alive() || ShizukuShell.waitAlive(600)) c = "shizuku";
            else if (hasRootBinary()) c = "root";
            sp.edit().putString("execChannel", c).apply();
            android.util.Log.i("Bridge", "首次安装自动选择通道: " + c);
        } catch (Throwable ignored) { }
    }

    /** 申请 Shizuku 授权：Shizuku App 会弹「是否允许本应用使用 Shizuku」 */
    @JavascriptInterface
    public void shizukuask(int id, String arg) {
        dispatch(id, new Task() {
            @Override public String run() {
                JSONObject o = new JSONObject();
                try {
                    if (!isMulti()) {
                        o.put("ok", false); o.put("msg", "only-multi");
                        return o.toString();
                    }
                    if (!ShizukuShell.alive()) {
                        o.put("ok", false); o.put("msg", "not-running");
                        return o.toString();
                    }
                    if (ShizukuShell.granted()) {
                        o.put("ok", true); o.put("granted", true);
                        return o.toString();
                    }
                    if (permListener == null) {
                        permListener = new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                            @Override
                            public void onRequestPermissionResult(int requestCode, int grantResult) {
                                // 授权回调在 binder 线程，摘监听要回主线程
                                main.post(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            rikka.shizuku.Shizuku
                                                    .removeRequestPermissionResultListener(permListener);
                                        } catch (Throwable ignored) { }
                                        permListener = null;
                                    }
                                });
                            }
                        };
                        rikka.shizuku.Shizuku.addRequestPermissionResultListener(permListener);
                    }
                    rikka.shizuku.Shizuku.requestPermission(ShizukuShell.REQ_CODE);
                    o.put("ok", true);
                    o.put("asked", true);
                } catch (Throwable e) {
                    try { o.put("ok", false); o.put("msg", String.valueOf(e)); } catch (Exception ignored) { }
                }
                return o.toString();
            }
        });
    }

    /** 直接回结果，不排队（给毫秒级的偏好类接口用） */
    private void replyNow(final int id, final String json) {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    web.evaluateJavascript("window.__bridgeResult(" + id + ","
                            + JSONObject.quote(json) + ")", null);
                } catch (Throwable ignored) { }
            }
        });
    }

    /** 执行通道现状：root / shizuku / adb */
    @JavascriptInterface
    public void chanstate(int id, String arg) {
        // 🔴 这里不能走 dispatch：dispatch 是单线程池，list（要采 PSS，好几秒）
        //    排前面时，设置页的通道行会先空白一阵才冒出来（真实踩到过）。
        //    本接口只读 SharedPreferences，微秒级，直接同步做完再回调。
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("multi", isMulti());
            o.put("chan", execChannel());
        } catch (Exception ignored) { }
        replyNow(id, o.toString());
    }

    /** 选择执行通道。只影响走哪条 shell 路径，不影响 ADB/无障碍读取 */
    @JavascriptInterface
    public void setchan(final int id, String arg) {
        final String v = arg == null ? "" : arg.trim();
        JSONObject o = new JSONObject();
        try {
            String c = ("root".equals(v) || "shizuku".equals(v)) ? v : "adb";
            web.getContext().getSharedPreferences("app", 0)
                    .edit().putString("execChannel", c).apply();
            syncChannel();          // 立即生效，不用重启 App
            o.put("ok", true);
            o.put("chan", c);
            o.put("shizuku", ShizukuShell.granted());
        } catch (Exception ignored) { }
        replyNow(id, o.toString());
    }

    /** Shizuku 状态：服务是否在跑、本应用是否已获授权。仅多通道版使用 */
    @JavascriptInterface
    public void shizukustate(int id, String arg) {
        // 同 chanstate：只查 binder 和权限，毫秒级，不跟列表抢单线程
        JSONObject o = new JSONObject();
        try {
            boolean alive = false, granted = false;
            try {
                alive = rikka.shizuku.Shizuku.pingBinder();
                granted = alive && rikka.shizuku.Shizuku.checkSelfPermission()
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) { }
            o.put("ok", true);
            o.put("alive", alive);
            o.put("granted", granted);
            o.put("multi", isMulti());
            o.put("chan", execChannel());
        } catch (Exception ignored) { }
        replyNow(id, o.toString());
    }


    /**
     * 主界面底部的实时内存 —— 由 KeepAliveService 主动推送，前端不轮询。
     *
     * KeepAliveService 每 3 秒查一次内存（通知栏本来就要查），刷新完顺手调
     * {@link #pushMem} 把同一份数据推给页面。
     * 这样「一条命令的结果」只算一次，通知和主界面同时更新，前端连定时器都不用挂。
     *
     * 用弱引用：Activity 被回收重建时不会拖住旧 WebView，新 Bridge 构造时会重新登记。
     */
    private static volatile java.lang.ref.WeakReference<WebView> LIVE_WEB = null;

    /**
     * App 是否在前台。后台时不再往 WebView 推内存 —— 界面根本看不见，
     * 推了只是白白唤醒 JS 引擎。通知栏不受影响，那是 Service 自己直接 notify 的。
     */
    static volatile boolean FOREGROUND = false;

    static void pushMem(final long total, final long avail, final double pct) {
        if (!FOREGROUND) return;          // 后台：只更新通知栏，不推页面
        final WebView w = (LIVE_WEB == null) ? null : LIVE_WEB.get();
        if (w == null) return;
        w.post(new Runnable() {
            @Override public void run() {
                try {
                    w.evaluateJavascript("window.__onMem&&window.__onMem("
                            + total + "," + avail + "," + pct + ")", null);
                } catch (Throwable ignored) { }
            }
        });
    }

    /** 从 tag 里取版本号：v4.6 → 4.6，multi-v4.5 → 4.5，认不出来 → 空串 */
    private static String verOfTag(String tag) {
        if (tag.startsWith("multi-v")) return tag.substring("multi-v".length());
        if (tag.startsWith("v") || tag.startsWith("V")) return tag.substring(1);
        return "";
    }

    /** 比较 "4.10" 与 "4.9"：按点分段转数字比，非数字尾巴直接忽略 */
    private static int cmpVer(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = numHead(i < pa.length ? pa[i] : "");
            int y = numHead(i < pb.length ? pb[i] : "");
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    /** 取字符串开头的数字："4beta" → 4，"9" → 9，无数字 → 0 */
    private static int numHead(String s) {
        int v = 0, i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
        }
        return v;
    }

    /**
     * 这个 Release 里有没有指定通道的 APK。
     *
     * 多通道版只认文件名含 multi 的，标准版只认不含的 —— 这是防串包的最后一道闸：
     * 最新版本如果只发了标准版包，多通道版会因这里返回 false 而显示「已是最新」，
     * 绝不会退而求其次装成本标准版。
     */
    private static boolean hasAssetFor(JSONObject rel, boolean multi) {
        org.json.JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return false;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String nm = a.optString("name", "").toLowerCase();
            if (!nm.endsWith(".apk")) continue;
            if (nm.contains("multi") == multi) return true;
        }
        return false;
    }

    /**
     * 操作历史：返回 {ok, limit, items:[{t,p,a}]}。
     * 应用名不在这里返回 —— 前端已经有完整的应用列表，用它补比后端逐条查 shell 快得多。
     */
    @JavascriptInterface
    public void history(int id, String arg) {
        JSONObject o = new JSONObject();
        try {
            android.content.Context c = web.getContext();
            o.put("ok", true);
            o.put("limit", OpHistory.limit(c));
            o.put("items", new org.json.JSONArray(OpHistory.list(c)));
        } catch (Throwable e) {
            try { o.put("ok", false); } catch (Exception ignored) { }
        }
        replyNow(id, o.toString());
    }

    /** 设置保存条数（0 = 关闭，最大 10000） */
    @JavascriptInterface
    public void historylimit(int id, String arg) {
        int lim = OpHistory.limit(web.getContext());
        try {
            lim = OpHistory.setLimit(web.getContext(), Integer.parseInt(arg == null ? "" : arg.trim()));
        } catch (Throwable ignored) { }
        replyNow(id, "{\"ok\":true,\"limit\":" + lim + "}");
    }

    @JavascriptInterface
    public void historyclear(int id, String arg) {
        try { OpHistory.clear(web.getContext()); } catch (Throwable ignored) { }
        replyNow(id, "{\"ok\":true}");
    }

    /**
     * 免责声明的「已同意」标记。
     *
     * 前端原来只存 localStorage —— 而 WebView 的存储会被系统在存储紧张时清掉，
     * 用户过段时间打开就被再弹一次（实测被反馈）。这里额外存一份 SharedPreferences，
     * 那是 App 私有数据，不受 WebView 清理影响。
     */
    @JavascriptInterface
    public void riskok(int id, String arg) {
        boolean ok = false;
        try {
            ok = web.getContext().getSharedPreferences("app", 0)
                    .getBoolean("risk_ok", false);
        } catch (Throwable ignored) { }
        replyNow(id, "{\"ok\":" + ok + "}");
    }

    @JavascriptInterface
    public void riskagree(int id, String arg) {
        try {
            web.getContext().getSharedPreferences("app", 0).edit()
                    .putBoolean("risk_ok", true).apply();
        } catch (Throwable ignored) { }
        replyNow(id, "{\"ok\":true}");
    }

    /**
     * 用户手动点了刷新 → 让常驻服务立刻补一次全量（电池健康 / 归档 / 限制），
     * 不用等 3 分钟的定时周期。
     */
    @JavascriptInterface
    public void refreshstat(int id, String arg) {
        boolean ok = true;
        try {
            KeepAliveService.requestRefreshNow();
        } catch (Throwable e) {
            ok = false;
        }
        replyNow(id, "{\"ok\":" + ok + "}");
    }

    /**
     * 主题明暗同步：前端 applyTheme() 决定 html.dark 之后，把结果告诉系统，
     * 让状态栏/手势条的图标跟着翻色。
     *
     * 🔴 不做这一步，深色界面顶上会一直顶一条浅色状态栏（两个版本都有）。
     * 必须在主线程改 WindowInsetsController，所以走 main.post。
     */
    @JavascriptInterface
    public void setnight(final int id, String arg) {
        final boolean dark = "1".equals(arg) || "dark".equals(arg);
        main.post(new Runnable() {
            @Override public void run() {
                boolean ok = true;
                try {
                    android.content.Context c = web.getContext();
                    // WebView 自己的底色也要跟着换：深色下过度滚动露出的那一条不能是白的
                    try {
                        web.setBackgroundColor(dark ? 0xFF141413 : 0xFFFAF9F6);
                    } catch (Throwable ignored) { }
                    if (c instanceof android.app.Activity) {
                        MainActivity.applyBarAppearance(
                                ((android.app.Activity) c).getWindow(), dark);
                    } else {
                        ok = false;
                    }
                } catch (Throwable e) {
                    ok = false;
                }
                try {
                    web.evaluateJavascript("window.__bridgeResult(" + id + ","
                            + JSONObject.quote("{\"ok\":" + ok + "}") + ")", null);
                } catch (Throwable ignored) { }
            }
        });
    }

    /** 跳到系统的无障碍设置页，让用户手动开启本服务。 */
    @JavascriptInterface
    public void a11ysettings(int id, String arg) {
        main.post(new Runnable() {
            @Override public void run() {
                android.content.Context ctx = web.getContext();
                // 类名固定写全限定：多通道版包名被 rename 过，组件名仍是 com.henry.appmanager.*
                android.content.ComponentName cn = new android.content.ComponentName(
                        ctx.getPackageName(), "com.henry.appmanager.A11yService");
                // 优先直达「已下载的应用 → MineApp应用查找」这一页，失败再退回总列表
                try {
                    android.content.Intent it = new android.content.Intent(
                            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                    it.putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, cn);
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(it);
                    dbg("无障碍设置：已跳到本服务详情页");
                    return;
                } catch (Throwable ignored) {
                    dbg("本服务详情页不可用，退回无障碍总列表");
                }
                try {
                    android.content.Intent it = new android.content.Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(it);
                } catch (Throwable e) {
                    dbg("打开无障碍设置失败: " + e);
                }
            }
        });
    }

    @JavascriptInterface
    public void update(int id, String arg) {
        // 走独立线程：纯 HTTP，不该被「刷新列表」那种重活堵住
        dispatchNet(id, new Task() {
            @Override
            public String run() {
                java.net.HttpURLConnection c = null;
                try {
                    // 🔴 不能用 /releases/latest：它只返回一条，两个发行通道没法分开。
                    //    改成读列表，本地按 tag 前缀各挑各的。
                    java.net.URL u = new java.net.URL(
                            "https://api.github.com/repos/henry-97/MineApp"
                            + "/releases?per_page=30");
                    c = (java.net.HttpURLConnection) u.openConnection();
                    // 这两个超时是 socket 层的：只在 connect / read 时计时，
                    // 排队等待（哪怕在队列里排了几十秒）根本不进这个计时器，
                    // 所以「前台刷列表导致检查更新超时」不会发生。
                    // 给 15 秒是因为 GitHub 走代理偶尔会慢，8 秒偏紧。
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
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
                    String prefix = releasePrefix();
                    boolean multi = prefix.startsWith("multi");
                    org.json.JSONArray arr = new org.json.JSONArray(
                            new String(bos.toByteArray(), "UTF-8"));

                    // 挑版本：不再按 tag 前缀找通道，改成「先找编号最新的 Release，
                    // 再看里面有没有我这个通道的包」。
                    //
                    // 4.6 起只发一个 vX 挂两个 APK，multi-vX 不会再出现，所以不需要
                    // 「先找 multi-v、找不到再回落」那套了（那套还有个隐患：同时存在
                    // multi-v4.6 和 v4.7 时会先命中较旧的 4.6）。
                    String curVer = "";
                    try {
                        curVer = web.getContext().getPackageManager()
                                .getPackageInfo(web.getContext().getPackageName(), 0).versionName;
                    } catch (Throwable ignored) { }

                    JSONObject latest = null;
                    String latestVer = "";
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject x = arr.optJSONObject(i);
                        if (x == null) continue;
                        if (x.optBoolean("draft")) continue;
                        if (!multi && x.optBoolean("prerelease")) continue;
                        String v = verOfTag(x.optString("tag_name", ""));
                        if (v.length() == 0) continue;
                        if (latest == null || cmpVer(v, latestVer) > 0) {
                            latest = x;
                            latestVer = v;
                        }
                    }
                    o.put("ok", true);
                    // 条件不满足一律当「已是最新」，不报错、更不回退去找旧版本
                    if (latest == null
                            || cmpVer(latestVer, curVer) <= 0
                            || !hasAssetFor(latest, multi)) {
                        o.put("tag", "");
                        o.put("ver", "");
                        o.put("url", "");
                        o.put("name", "");
                        return o.toString();
                    }
                    JSONObject r = latest;
                    String tag = r.optString("tag_name", "");
                    o.put("tag", tag);
                    o.put("ver", latestVer);
                    o.put("url", r.optString("html_url"));
                    o.put("name", r.optString("name"));
                    // 资产也要分开挑：主版本拿 MineApp-x.y.z.apk，
                    // 多通道版拿文件名含 Multi 的那个。挑不到就留空，
                    // 前端会退成「打开下载页」而不是装错包。
                    org.json.JSONArray assets = r.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            org.json.JSONObject a = assets.optJSONObject(i);
                            if (a == null) continue;
                            String nm = a.optString("name");
                            if (nm == null) continue;
                            String low = nm.toLowerCase();
                            if (!low.endsWith(".apk")) continue;
                            if (low.contains("multi") != multi) continue;
                            o.put("apk", a.optString("browser_download_url"));
                            o.put("size", a.optLong("size"));
                            break;
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

    /**
     * 自定义 adbd 的 TCP 端口（默认 5555，只有用户主动点了「自定义端口」才会调用）。
     *
     * 参数是端口号字符串。越界（<1024 或 >65535）直接拒绝 —— 1024 以下要 root，
     * adb tcpip 本身也会失败，放过去只会让用户以为设置成功了却连不上。
     */
    @JavascriptInterface
    public void setport(int id, String arg) {
        final int p = parseIntSafe(arg == null ? "" : arg.trim(), -1);
        dispatch(id, new Task() {
            @Override
            public String run() {
                JSONObject o = new JSONObject();
                try {
                    if (p < AdbService.PORT_MIN || p > AdbService.PORT_MAX) {
                        o.put("ok", false);
                        o.put("msg", "range");
                        return o.toString();
                    }
                    boolean applied = service.setPort(p);
                    o.put("ok", applied);
                    o.put("port", service.getPort());
                    if (!applied) o.put("msg", "range");
                } catch (Throwable e) {
                    try {
                        o.put("ok", false);
                        o.put("msg", String.valueOf(e.getMessage()));
                    } catch (Exception ignored) { }
                }
                return o.toString();
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
            android.content.ComponentName on = new android.content.ComponentName(pkg, COMP_NS + target);
            if (pm.getComponentEnabledSetting(on) != en) {
                pm.setComponentEnabledSetting(on, en, flags);
            }
            String[] all = allAliases();
            for (int i = 0; i < all.length; i++) {
                if (all[i].equals(target)) continue;
                android.content.ComponentName off =
                        new android.content.ComponentName(pkg, COMP_NS + all[i]);
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
