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

    private static final String TAG = "KeepAlive";
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
    private static final int FULL_EVERY = 20;

    /** 上次全量刷新拿到的电池段与计数，小刷新时沿用 */
    private JSONObject cachedBattery;
    private int cachedArchived = -1;
    private int cachedRestricted = -1;
    private int tickCount = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AdbService service;
    private volatile boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    refresh(tickCount % FULL_EVERY == 0);
                    tickCount++;
                    handler.postDelayed(tick, TICK_MS);
                }
            }, "keepalive-refresh").start();
        }
    };

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
            if (full) {
                cachedBattery = s.optJSONObject("battery");
                cachedArchived = s.optInt("archived", -1);
                cachedRestricted = s.optInt("restricted", -1);
            }
            JSONObject b = cachedBattery;
            if (b == null) {
                text = "调试未连接";
            } else {
                // 电池整段都塞进标题行，格式与主界面完全一致：
                // `电池 良好 · 健康87% · 29.4℃`
                // 正文只留「内存 + 归档/限制」，整体高度从 3 行压到 2 行
                String ht = b.optString("health_text", "");
                int hpct = b.optInt("health_pct", 0);
                double temp = b.optDouble("temp", 0);

                StringBuilder head = new StringBuilder();
                if (!ht.isEmpty()) head.append("电池 ").append(ht);
                if (hpct > 0) {
                    if (head.length() > 0) head.append(" · ");
                    head.append("健康").append(hpct).append('%');
                }
                if (temp > 0) {
                    if (head.length() > 0) head.append(" · ");
                    head.append(fmtTemp(temp)).append("℃");
                }
                if (head.length() > 0) title += " · " + head;

                StringBuilder sb = new StringBuilder();
                long totalKb = s.optLong("mem_total", 0);
                long availKb = s.optLong("mem_avail", 0);
                if (totalKb > 0) {
                    double g = 1024.0 * 1024.0;
                    sb.append("内存 ")
                      .append(String.format(java.util.Locale.US, "%.1f/%.1fG",
                              (totalKb - availKb) / g, totalKb / g))
                      .append(String.format(java.util.Locale.US, " (%.1f%%)",
                              s.optDouble("mem_pct", 0)));
                }
                if (sb.length() > 0) sb.append("　");
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
                    pkg, pkg + "." + Bridge.aliasName(mode, key));
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
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
