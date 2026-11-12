package com.henry.appmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * 常驻通知 + 性能模式保持。
 *
 * 通知里显示电池健康度、温度、已归档/已限制数量；同时每隔一段时间检查
 * 性能模式是否被系统回退，若用户开启过则重新写回。
 *
 * 注意：所有 adb 调用都在后台线程串行执行，避免与界面同时占用连接。
 */
public class KeepAliveService extends Service {

    /**
     * 渠道 ID 带版本号：渠道的重要性一旦创建就无法修改，改用 IMPORTANCE_DEFAULT
     * （无声、无振动）才能让它留在通知主区域，而不是被系统折叠进「更多通知」。
     */
    private static final String CHANNEL_ID = "app_manager_status_v2";
    private static final int NOTICE_ID = 1001;
    /**
     * 通知刷新节奏：**3 秒小刷新 / 60 秒大刷新**。
     *
     * 小刷新只跑 `cat /proc/meminfo`（极轻），用来让「内存」这一行实时跳动；
     * 电池、归档数、限制数这些几十秒才变一次的数据，每 20 次（=60 秒）才全量取一次。
     * 原来每次刷新都跑一遍 `dumpsys battery` + `dumpsys batterystats`（输出很大）
     * 加 4 条 pm/appops，高频下纯属浪费。
     */
    private static final long TICK_MS = 3000L;
    /** 全量刷新（电池健康 / 归档 / 限制）的间隔：亮屏 3 分钟、熄屏 30 分钟 */
    private static final long FULL_GAP_ON_MS = 180000L;
    private static final long FULL_GAP_OFF_MS = 1800000L;

    /** 供 Bridge 触发「立即刷新」用；进程内静态引用，避免持有 Service 实例 */
    private static volatile Handler sHandler;
    private static volatile Runnable sTick;
    /** 上次全量刷新的时刻；0 表示还没跑过，首轮强制全量 */
    private volatile long lastFullAt = 0;
    /** 用户手动点了刷新 → 下一轮强制全量，不看时间间隔 */
    private static volatile boolean sForceFull = false;

    /** 上次全量刷新拿到的电池段与计数，小刷新时沿用 */
    private JSONObject cachedBattery;
    private int cachedArchived = -1;
    private int cachedRestricted = -1;
    private int tickCount = 0;

    /**
     * 内存快照，给主界面底部实时显示用。
     *
     * 🔴 以前底部那行「内存 x/yG (p%)」只在手动刷新列表时才更新，
     *    而通知栏是 3 秒一刷，同一块屏幕上两个数字各走各的，看着像主界面卡住了。
     *    这里每 3 秒（和通知同频）写一份静态快照，Bridge 直接读，
     *    **不额外跑任何 shell** —— 通知本来就要算这次内存。
     */
    static volatile long LIVE_MEM_TOTAL = 0;
    static volatile long LIVE_MEM_AVAIL = 0;
    static volatile double LIVE_MEM_PCT = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 亮屏广播接收器：熄灭期间是低频刷新，亮屏要立刻补一次 */
    private android.content.BroadcastReceiver screenOnReceiver;
    private AdbService service;
    private volatile boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    // 全量刷新（电池健康 / 归档数 / 限制数）按**时间**判断，不跟 tick 次数挂钩：
                    // 亮屏 3 分钟一次，熄屏 30 分钟一次。以前用 tickCount % 20，
                    // 改间隔时周期会被一起放大，后台一度变成 5 分钟。
                    boolean on = screenOn();
                    long gap = on ? FULL_GAP_ON_MS : FULL_GAP_OFF_MS;
                    // sForceFull：用户手动点了刷新，本轮必须全量，不看时间间隔
                    boolean full = sForceFull || (lastFullAt == 0) || (now - lastFullAt >= gap);
                    if (sForceFull) sForceFull = false;
                    refresh(full);
                    if (full) lastFullAt = now;
                    tickCount++;
                    // 便于验证刷新频率：亮屏应约 3 秒一条，熄屏约 60 秒一条
                    android.util.Log.d("KeepAlive", "tick full=" + full
                            + " screen=" + on + " mem=" + LIVE_MEM_PCT + "%");
                    handler.postDelayed(tick, nextIntervalMs());
                }
            }, "keepalive-refresh").start();
        }
    };

    private boolean screenOn() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * 内存刷新间隔 —— 只看屏幕，不看 App 在不在前台。
     *
     * 亮屏时用户随时可能下拉通知栏看数字，3 秒一刷；熄屏没人看得到，降到 60 秒。
     * （内存读的是 ActivityManager，不跑 shell，3 秒的代价本来就很小。）
     */
    private long nextIntervalMs() {
        return screenOn() ? TICK_MS : 60000L;
    }

    /**
     * 用户手动点了刷新 → 立刻补一次全量（电池/归档/限制），不用等 3 分钟。
     * Bridge 通过静态入口调用，不持有 Service 实例，避免生命周期纠缠。
     */
    static void requestRefreshNow() {
        Handler h = sHandler;
        Runnable t = sTick;
        if (h != null && t != null) {
            sForceFull = true;          // 否则时间没到 3 分钟，这一轮还是会走轻量分支
            h.removeCallbacks(t);
            h.post(t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTICE_ID, buildNotification(appLabel(), "正在读取状态…"));

        // 与界面共用同一个 AdbService / 同一条连接，避免多开 transport 触发重复授权
        service = AdbService.shared(this, new AdbClient.Log() {
            @Override
            public void line(String msg) { }
        });
        running = true;
        sHandler = handler;
        sTick = tick;

        // 屏幕亮起时立刻刷一次：熄灭期间是 60 秒才跑一轮，用户点亮屏幕后
        // 不应该还看到熄灭前的旧数字。
        try {
            screenOnReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    if (!running) return;
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                }
            };
            registerReceiver(screenOnReceiver,
                    new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON));
        } catch (Throwable ignored) { }

        // 延后首轮刷新：界面刚启动就要跑一整套取数，两者抢同一把锁会把首屏拖慢好几秒
        handler.postDelayed(tick, 8000);
    }

    /**
     * 性能模式常开：用户开了就一直守着。
     *
     * MIUI 的 powerkeeper 在电量低、温度高、切省电模式时会把 POWER_PERFORMANCE_MODE_OPEN
     * 改回去 —— 界面原来只在「列表刷新」时才纠正，两次刷新之间就一直是关的。
     * 这里挪到常驻服务，每轮都查一次；电量低于 20% 时不再强推，交给系统决定。
     */
    private boolean lowBattery() {
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return pct > 0 && pct < 20;
        } catch (Throwable e) {
            return false;
        }
    }
    /** 1 分钟内被系统取消超过这个次数，就停止对抗，交给用户处理 */
    private static final int PERF_REVERT_LIMIT = 3;
    private static final long PERF_WINDOW_MS = 60000L;

    private void keepPerfMode() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("app", 0);
            if (!sp.getBoolean("perfWanted", false)) return;
            if (sp.getBoolean("perfPaused", false)) return;   // 已暂停，等用户重新打开
            if (lowBattery()) return;
            if (service == null) return;
            if (service.perfOn()) return;                     // 还开着，什么都不做

            // 到这里说明「用户要开、但系统把它关了」—— 记一次
            long now = System.currentTimeMillis();
            java.util.ArrayList<Long> ts = new java.util.ArrayList<Long>();
            String raw = sp.getString("perfReverts", "");
            if (raw != null && raw.length() > 0) {
                for (String t : raw.split(",")) {
                    try { ts.add(Long.parseLong(t.trim())); } catch (Exception ignored) { }
                }
            }
            ts.add(now);
            // 只保留最近 1 分钟内的
            java.util.ArrayList<Long> keep = new java.util.ArrayList<Long>();
            for (Long t : ts) if (now - t <= PERF_WINDOW_MS) keep.add(t);

            if (keep.size() > PERF_REVERT_LIMIT) {
                // 系统在反复取消 —— 停止对抗，让用户在界面上看到并自己决定
                StringBuilder sb = new StringBuilder();
                for (Long t : keep) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(t);
                }
                sp.edit().putBoolean("perfPaused", true)
                        .putString("perfReverts", sb.toString()).apply();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Long t : keep) {
                if (sb.length() > 0) sb.append(",");
                sb.append(t);
            }
            sp.edit().putString("perfReverts", sb.toString()).apply();
            service.setPerf(true);
        } catch (Throwable ignored) { }
    }

    private void refresh(boolean full) {
        keepPerfMode();
        String text;
        String title = appLabel();
        try {
            // 性能模式的写回只在全量刷新时做：每 3 秒写一次 settings 太浪费
            if (full) {
                SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
                if (sp.getBoolean("perf_keep", false)) {
                    service.setPerf(true);
                }
            }

            JSONObject s = new JSONObject(service.summaryJson(full));
            // 内存每次都会带上（full 与否都有）。这一份同时喂给通知栏和主界面 ——
            // 前端不轮询，由这里主动推过去，一条命令的结果两处共用。
            if (s.has("mem_total")) {
                LIVE_MEM_TOTAL = s.optLong("mem_total", 0);
                LIVE_MEM_AVAIL = s.optLong("mem_avail", 0);
                LIVE_MEM_PCT = s.optDouble("mem_pct", 0);
                Bridge.pushMem(LIVE_MEM_TOTAL, LIVE_MEM_AVAIL, LIVE_MEM_PCT);
            }
            if (full) {
                cachedBattery = s.optJSONObject("battery");
                cachedArchived = s.optInt("archived", -1);
                cachedRestricted = s.optInt("restricted", -1);
                // 限制补做紧跟全量刷新，不单独定时：以后调 FULL_GAP 时这里自动同步。
                // setAppInactive 会被「用户打开应用」清除，不补的话限制会静默失效。
                service.reassertRestrictions();
            }
            JSONObject b = cachedBattery;
            if (b == null) {
                text = "调试未连接";
            } else {
                // 电池整段都塞进标题行。文案刻意压短：
                // 原来「电池 良好 · 健康97% · 34℃」在某些机型上宽度不够，
                // 缩小字号后仍差一个「℃」放不下（实测 400px 宽的通知卡就是这么挤）。
                // 现在去掉健康词、把「电池健康」连写，`Mine App · 电池健康 97% · 34℃`
                // 比原来少 5 个字符，小幅度的机型差异也能兜住。
                String ht = b.optString("health_text", "");
                int hpct = b.optInt("health_pct", 0);
                double temp = b.optDouble("temp", 0);

                StringBuilder head = new StringBuilder();
                if (hpct > 0) {
                    head.append("电池健康 ").append(hpct).append('%');
                } else if (!ht.isEmpty()) {
                    head.append("电池").append(ht);      // 读不到百分比时退回文字描述
                }
                if (temp > 0) {
                    if (head.length() > 0) head.append(" · ");
                    head.append(fmtTemp(temp)).append("℃");
                }
                if (head.length() > 0) title += " · " + head;

                // 正文只留内存占用百分比（不再带 x.x/y.yG 和括号），
                // 这样「内存 61%　归档 3 · 限制 0」怎么都放得下。
                StringBuilder sb = new StringBuilder();
                double memPct = s.optDouble("mem_pct", -1);
                if (memPct >= 0) {
                    sb.append("内存 ")
                      .append(String.format(java.util.Locale.US, "%.0f%%", memPct));
                }
                // 用「 · 」而不是全角空格：全角空格视觉上就是一段空白，
                // 「内存 66%　归档 10」看着像两个字段黏在一起、少了分隔符。
                if (sb.length() > 0) sb.append(" · ");
                sb.append("归档 ").append(cachedArchived < 0 ? 0 : cachedArchived);
                sb.append(" · 限制 ").append(cachedRestricted < 0 ? 0 : cachedRestricted);
                text = sb.toString().trim();
            }
        } catch (Exception e) {
            text = "读取状态失败";
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTICE_ID, buildNotification(title, text));
    }

    /**
     * 当前桌面名称。用户在设置页切换「应用管理器 / Mine App」时，实际改的是
     * activity-alias 的 label，这里按偏好拼出当前 alias 名，再读它的 label，
     * 标题就能跟着变（也自带多语言）。
     */
    private String appLabel() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            String pkg = getPackageName();
            String mode = getSharedPreferences("app", MODE_PRIVATE)
                    .getString("name_mode", "mine");
            String key = getSharedPreferences("app", MODE_PRIVATE)
                    .getString("icon_key", "sq_g");
            android.content.ComponentName a = new android.content.ComponentName(
                    pkg, Bridge.COMP_NS + Bridge.aliasName(mode, key));
            CharSequence l = pm.getActivityInfo(a, 0).loadLabel(pm);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Exception ignored) { }
        return getString(R.string.app_name);
    }

    /** 温度只保留一位小数，整数不带「.0」，与主界面显示一致。 */
    private static String fmtTemp(double t) {
        String s = String.format(java.util.Locale.US, "%.1f", t);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private Notification buildNotification(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        // 通知标题行被系统模板默认加粗。用 Span 把它压回常规字重，
        // 观感与 App 主界面的「电池 良好 · 健康87% · 29.9℃」保持一致。
        // 注意：不能改用自定义 RemoteViews —— HyperOS 会把带自定义视图的通知
        // 收进「更多通知」分区，主通知栏里就看不到了。
        //
        // 🔴 不要在这里加 RelativeSizeSpan 缩字号：试过，字号是变小了，
        //    但字距仍按原字号排，看着「间距被拉开」，反而不舒服；
        //    而且缩小后照样差一个字符放不下。宽度问题改用缩短文案解决。
        android.text.SpannableString st = new android.text.SpannableString(title);
        st.setSpan(new android.text.style.TypefaceSpan("sans-serif"),
                0, st.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        b.setContentTitle(st)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false);
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "状态显示", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        // 常驻状态条不该打断用户：关掉提示音与亮屏
        ch.setBypassDnd(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ch);
        // 清掉旧渠道（IMPORTANCE_LOW，会被折叠进「更多通知」）
        nm.deleteNotificationChannel("app_manager_status");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        sHandler = null;
        sTick = null;
        handler.removeCallbacksAndMessages(null);
        try {
            if (screenOnReceiver != null) unregisterReceiver(screenOnReceiver);
        } catch (Throwable ignored) { }
        screenOnReceiver = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
