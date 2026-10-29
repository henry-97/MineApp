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
    private boolean jumpToPage(int[] pager, int page) {
        int maxPage = pager[1] > 0 ? pager[1] : 8;   // 读不到页码时按最多 8 页算
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
        int cur = pager[0] > 0 ? pager[0] : 1;
        int diff = page - cur;
        if (diff == 0) return true;
        int fx = diff > 0 ? 950 : 200;      // 下一页：手指从右往左
        int tx = diff > 0 ? 200 : 950;
        for (int i = 0; i < Math.abs(diff); i++) {
            service.shellLong("input swipe " + fx + " 1200 " + tx + " 1200 260");
            try { Thread.sleep(320); } catch (InterruptedException ignored) { }
        }
        return true;
    }

    private String dumpHome() {
        service.shellLong("uiautomator dump /sdcard/mineapp_loc.xml");
        String xml = service.shellLong("cat /sdcard/mineapp_loc.xml");
        return xml == null ? "" : xml;
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
                    if (label.isEmpty()) return o.toString();
                    // 有缓存页号 = 已知位置，很快就完；没有 = 首次查找，要遍历桌面各页
                    boolean firstScan = wantPage < 1;
                    String baseHint = hint.isEmpty() ? "Looking for app" : hint;
                    startLocateNotice(firstScan ? baseHint + "（首次较慢）" : baseHint);
                    startTickToast(baseHint);

                    service.shellLong("input keyevent 3");
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { }

                    String xml = dumpHome();
                    int[] pager = parsePager(xml);
                    // 读不到页码（有的桌面没有小圆点）→ 按最多 8 页扫，靠滑动翻页
                    int total = pager[1] > 0 ? pager[1] : 8;

                    int[] hit = null;
                    int hitPage = -1;

                    // ⓪ 索引命中：索引**只当页号提示**用，坐标一律 dump 现取。
                    //
                    // 🔴 不能用索引里存的坐标，两个原因：
                    //  1. 那是建索引那一刻的快照，之后拖动图标、装新应用就全偏了；
                    //  2. 文件夹内的图标，当时记的是「文件夹里的坐标」，页号却是外层页号 ——
                    //     跳到外层页再用文件夹内坐标长按，必然落在空白处，
                    //     表现就是「定位偏了，长按变成了长按桌面」。
                    if (wantPage < 1) {
                        int ipg = -1;
                        try {
                            android.content.SharedPreferences sp =
                                    web.getContext().getSharedPreferences("app", 0);
                            org.json.JSONArray v = new org.json.JSONObject(
                                    sp.getString("locIndex", "{}")).optJSONArray(label);
                            if (v != null && v.length() >= 1) ipg = v.getInt(0);
                        } catch (Throwable ignored) { }

                        if (ipg >= 1) {
                            boolean jumped = jumpToPageSaved(ipg);
                            if (!jumped) jumped = jumpToPage(parsePager(dumpHome()), ipg);
                            if (jumped) {
                                String px = dumpHome();
                                int[] real = findNode(px, label, false);
                                if (real == null) real = findNodeLoose(px, label);
                                if (real == null) {
                                    // 可能在这个页的文件夹里：逐个点开找
                                    java.util.List<int[]> fs = findFolders(px);
                                    for (int i = 0; i < fs.size() && i < 8; i++) {
                                        int[] f = fs.get(i);
                                        service.shellLong("input tap " + f[0] + " " + f[1]);
                                        try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                                        real = findNode(dumpHome(), label, false);
                                        if (real != null) break;
                                        service.shellLong("input keyevent 3");
                                        try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                                    }
                                }
                                if (real != null) {
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
                                // 索引过期（应用挪走了 / 卸载了）：删掉这条，往下走全量扫描
                                dropIndex(label);
                            }
                        }
                    }

                    // ① 有缓存页号：直接点页码跳过去，一次 dump 就够
                    if (wantPage >= 1 && wantPage <= total && jumpToPage(pager, wantPage)) {
                        hit = findNode(dumpHome(), label, false);
                        if (hit != null) hitPage = wantPage;
                    }

                    // ② 没有缓存 / 缓存失效：逐页点页码找（不滑动，点圆点更快更稳）
                    if (hit == null) {
                        for (int pg = 1; pg <= total && pg <= 8; pg++) {
                            if (pg != (pager[0] > 0 ? pager[0] : -1)) {
                                if (!jumpToPage(pager, pg)) continue;
                            }
                            String px = dumpHome();
                            hit = findNode(px, label, false);
                            if (hit != null) { hitPage = pg; break; }
                            hit = findNodeLoose(px, label);        // 带角标后缀的再试一次
                            if (hit != null) { hitPage = pg; break; }
                            // 该页的文件夹：逐个点开找
                            java.util.List<int[]> folders = findFolders(px);
                            for (int i = 0; i < folders.size() && i < 6; i++) {
                                int[] f = folders.get(i);
                                service.shellLong("input tap " + f[0] + " " + f[1]);
                                try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                                hit = findNode(dumpHome(), label, false);
                                if (hit != null) { hitPage = pg; break; }
                                service.shellLong("input keyevent 3");
                                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
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
    private volatile boolean idxStop;

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
                try {
                    android.content.SharedPreferences sp =
                            web.getContext().getSharedPreferences("app", 0);
                    JSONObject index = new JSONObject(sp.getString("locIndex", "{}"));
                    int resumePage = sp.getInt("locIndexPage", 1);
                    if (resumePage < 1) resumePage = 1;

                    service.shellLong("input keyevent 3");
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                    int[] pager = parsePager(dumpHome());
                    int total = pager[1] > 0 ? pager[1] : 8;
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
                        if (idxStop || !isLauncherFront()) break;
                        jumpToPage(pager, page);
                        String xml = dumpHome();
                        added += indexPage(index, xml, page);
                        // 该页的文件夹
                        java.util.List<int[]> folders = findFolders(xml);
                        for (int i = 0; i < folders.size(); i++) {
                            if (idxStop || !isLauncherFront()) break;
                            int[] f = folders.get(i);
                            service.shellLong("input tap " + f[0] + " " + f[1]);
                            try { Thread.sleep(450); } catch (InterruptedException ignored) { }
                            added += indexPage(index, dumpHome(), page);
                            // 用 home 而不是 back：back 在文件夹编辑态下只退出编辑，
                            // 文件夹还开着，下一轮点坐标就会点到里面的应用
                            service.shellLong("input keyevent 3");
                            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
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
                        stopLocateNotice("已暂停，下次点「开始」会接着来");
                    }
                } catch (Throwable e) {
                    android.util.Log.w("Bridge", "buildindex 失败: " + e);
                    try { o.put("ok", false); } catch (Throwable ignored) { }
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
    private int indexPage(JSONObject index, String xml, int page) {
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
            // 等界面切回来再触发前端刷新
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        web.evaluateJavascript("window.__idxDone && window.__idxDone()", null);
                    } catch (Throwable e) {
                        dbg("__idxDone 调用失败: " + e);
                    }
                }
            }, 800);
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
                    android.widget.Toast.makeText(web.getContext(), msg,
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) { }
            }
        });
    }

    /** 桌面态提示：这时 App 已退到后台，WebView 里的 toast 看不见，必须用系统 Toast */
    private void toastOn(final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    android.widget.Toast.makeText(web.getContext(), msg,
                            android.widget.Toast.LENGTH_SHORT).show();
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
    private android.widget.Toast tickToast;
    private Runnable tickTask;

    private void startTickToast(final String base) {
        stopTickToast();
        final long t0 = System.currentTimeMillis();
        tickTask = new Runnable() {
            @Override public void run() {
                try {
                    if (tickToast == null) {
                        tickToast = android.widget.Toast.makeText(web.getContext(), base,
                                android.widget.Toast.LENGTH_LONG);
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
                            tickToast = android.widget.Toast.makeText(web.getContext(), result,
                                    android.widget.Toast.LENGTH_LONG);
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
    private static final String LOC_CH = "app_manager_locate";
    private static final int LOC_ID = 1002;
    private long locStartAt;

    private void notifyLocate(String text, boolean chronometer) {
        try {
            android.content.Context ctx = web.getContext();
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(
                            android.content.Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        LOC_CH, "定位提示", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new android.app.Notification.Builder(ctx, LOC_CH)
                    : new android.app.Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(chronometer)
                    .setWhen(locStartAt)
                    .setUsesChronometer(chronometer);
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
                        String xml = dumpHome();
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
