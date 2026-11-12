package com.henry.appmanager;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 独立版应用管理器：不再依赖 Termux / Python / HTTP。
 *
 * 链路：本 Activity → AdbService → AdbClient(纯 Java ADB 协议) → adbd → shell 权限
 */
public class MainActivity extends Activity {

    private static final String PAGE_BG = "#FAF9F6";

    private WebView web;
    /** 监听应用装卸更新：列表里的 versionCode 和 AOT 状态都会变，需要重拉 */
    private android.content.BroadcastReceiver pkgReceiver;
    private AdbService service;
    private Bridge bridge;

    /** 正式版（无 android:debuggable）返回 false：WebView 远程调试与 JS 日志都据此关闭 */
    private boolean isDebuggable() {
        return (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * 隐藏/显示本应用在最近任务里的卡片。
     *
     * 为什么要在 onCreate 里就调一次：AppTask.setExcludeFromRecents 只影响**之后**创建的
     * 任务，对已经在最近任务里的条目不会重新评估。所以「默认隐藏」必须赶在任务进
     * recents 之前设好，否则首次启动会漏出一张卡片（实测确认过这个时序）。
     *
     * MIUI 那种「上锁」没有公开 API，这里只做隐藏。
     */
    static boolean applyHideFromRecents(android.content.Context ctx, boolean hide) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.AppTask> tasks = am.getAppTasks();
            for (android.app.ActivityManager.AppTask t : tasks) {
                t.setExcludeFromRecents(hide);
            }
            return !tasks.isEmpty();
        } catch (Throwable e) {
            android.util.Log.w("MainActivity", "隐藏后台卡片失败: " + e);
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 默认隐藏：赶在任务进入最近任务之前设好（见 applyHideFromRecents 注释）
        boolean hide = getSharedPreferences("app", 0).getBoolean("hide_recents", true);
        applyHideFromRecents(this, hide);

        // 必须在 setContentView 之前：该标志在 generateLayout() 里才被读取
        prepareEdgeToEdge();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(PAGE_BG));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        // 必须开这两个：否则 WebView 会按 980px 的默认视口渲染再整体缩放，
        // 表现为字号偏小、字形被横向压扁（meta viewport 也不生效）。
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // 正式版一律关掉 WebView 远程调试（chrome://inspect 能挂上来直接改页面），
        // 只在 debuggable 构建里打开，方便开发期看 console。
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(isDebuggable());
        }
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (isDebuggable()) {
                    android.util.Log.i("WebConsole",
                            m.message() + " @" + m.lineNumber());
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectInsets();
            }

            /**
             * 设置页的 GitHub / 打赏是 http(s) 外链。不拦的话 WebView 会直接跳过去，
             * 整个 App 界面就被网页顶掉了，返回键也回不来。这里统一交给系统浏览器。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                return openExternal(req == null ? null : req.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternal(url == null ? null : android.net.Uri.parse(url));
            }

            private boolean openExternal(android.net.Uri uri) {
                if (uri == null) return false;
                String scheme = uri.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, uri);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    android.util.Log.w("Main", "打开外链失败: " + e);
                }
                return true;
            }
        });

        try {
            ensureKey();
        } catch (Exception e) {
            showFatal("密钥初始化失败：" + e.getMessage());
            return;
        }
        service = AdbService.shared(this, new AdbClient.Log() {
            @Override
            public void line(String msg) { /* 逐包日志仅调试期需要，已关闭 */ }
        });

        bridge = new Bridge(service, web);
        web.addJavascriptInterface(bridge, "Bridge");
        // 覆盖安装会把无障碍解绑，用户不会想到是它导致「查找应用」失灵
        healA11yBinding();
        // 覆盖安装后 manifest 里的 alias 会重置成默认，这里把用户选过的名称/图标重新落回去
        bridge.restoreAlias();

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // decorView 已挂载，此时才能安全设置系统栏外观。
        // 先按浅色铺底；前端 applyTheme() 会在脚本跑起来后立刻纠正（深色主题会调 setnight）。
        applyBarAppearance(getWindow(), false);
        web.loadUrl("file:///android_asset/index.html");

        // 应用装/卸/更新后，AOT 记录会因 versionCode 变化而失效 —— 主动重拉一次，
        // 否则用户切回来看到的还是旧状态，得手动点刷新才纠正。
        pkgReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                if (web == null) return;
                web.post(new Runnable() {
                    @Override public void run() {
                        web.evaluateJavascript("(typeof load === 'function') && load(true)", null);
                    }
                });
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pkgReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(pkgReceiver, f);
            }
        } catch (Exception ignored) { }

        startKeepAlive();
    }

    /**
     * 让内容真正铺满屏幕，状态栏与手势区透明。
     * 页面通过 --sat / --sab 两个 CSS 变量自行避让，避免被状态栏或底部手势条遮挡。
     */
    /**
     * 系统栏配色。
     *
     * 实测这台 ROM（HyperOS / Android 15）会忽略 setDecorFitsSystemWindows(false)，
     * 窗口始终被状态栏与手势条内缩；此时若把栏设成透明，未覆盖区域会露出系统默认黑底
     * —— 就是之前上下两条黑边。所以这里直接把栏染成页面底色，视觉上与页面连成一片。
     */
    /** 启动常驻通知服务：显示电池/归档/限制状态，并周期性保持性能模式。 */
    private void startKeepAlive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            }
            Intent it = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it);
            else startService(it);
        } catch (Exception e) {
            // 通知服务起不来不应影响主功能
        }
    }

    private void prepareEdgeToEdge() {
        Window w = getWindow();
        // targetSdk 35 起系统强制 edge-to-edge，窗口天然铺满全屏；
        // 这里只需把栏设为透明并保证深色图标，页面用 --sat / --sab 自行避让。
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
    }

    /**
     * 系统栏外观：dark=true 时底色变深、图标转白；false 时浅底黑字。
     *
     * 🔴 以前这里只下 LIGHT 位（图标颜色），底色始终由系统/窗口背景决定，
     *    所以页面切到深色后顶上仍是一条浅色状态栏（两个版本都有）。
     *    现在底色和图标一起跟着主题走，改由前端 applyTheme() 通过 Bridge.setnight 驱动。
     */
    static void applyBarAppearance(Window w, boolean dark) {
        if (w == null) return;
        // 深色用页面同一套底色（--bg:#141413），浅色用 --bg:#faf9f6
        int bg;
        try {
            bg = Color.parseColor(dark ? "#141413" : "#FAF9F6");
        } catch (Throwable e) {
            bg = dark ? 0xFF141413 : 0xFFFAF9F6;
        }
        try {
            // Android 12/14 上直接生效；Android 15 起系统接管会忽略这两行，
            // 所以下面再把窗口底色一并换掉做兜底。
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
        } catch (Throwable ignored) { }
        try {
            // 系统栏透明时透出的是 decorView 背景，这里必须一起改，
            // 否则深色主题下栏区仍是浅色（正是用户看到的现象）
            w.getDecorView().setBackgroundColor(bg);
        } catch (Throwable ignored) { }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ic = w.getInsetsController();
            if (ic != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                // 深色底 → 清掉 LIGHT 位，图标变白
                ic.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    /**
     * 把底部系统栏（手势条）高度写进 CSS 变量，避免固定底栏被遮住。
     * 顶部无需避让 —— 窗口本身已被状态栏内缩。
     */
    private void injectInsets() {
        if (web == null) return;
        WindowInsets insets = web.getRootWindowInsets();
        if (insets == null) return;
        int top, bottom;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            top = bars.top;
            bottom = bars.bottom;
        } else {
            top = insets.getSystemWindowInsetTop();
            bottom = insets.getSystemWindowInsetBottom();
        }
        float d = getResources().getDisplayMetrics().density;
        web.evaluateJavascript(
                "document.documentElement.style.setProperty('--sat','"
                + Math.round(top / d) + "px');"
                + "document.documentElement.style.setProperty('--sab','"
                + Math.round(bottom / d) + "px');", null);
    }

    /**
     * 首次运行生成 2048 位 RSA 密钥，写入应用私有目录。
     * 第一次连接会触发手机上的授权弹窗，勾选「一律允许」后永久生效。
     */
    private void ensureKey() throws Exception {
        File priv = new File(getFilesDir(), "adbkey.pk8");
        File blob = new File(getFilesDir(), "adbkey.blob");
        if (priv.exists() && blob.exists()) return;
        String[] names = {"adbkey.pk8", "adbkey.blob"};
        File[] targets = {priv, blob};
        for (int i = 0; i < names.length; i++) {
            InputStream in;
            try {
                in = getAssets().open(names[i]);
            } catch (Exception e) {
                return;   // 无内置密钥则交由 AdbClient 自行生成
            }
            FileOutputStream out = new FileOutputStream(targets[i]);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.close();
            in.close();
        }
    }

    /**
     * 覆盖安装后自动补回无障碍服务绑定。
     *
     * 🔴 系统在应用覆盖安装时会解绑它的无障碍服务，用户必须自己进「设置 → 无障碍」
     *    重新打开。不补的后果：点「开始」建索引时日志只写一句「无无障碍」，
     *    「应用查找」整体不可用，而用户完全不会联想到是升级导致的（实测反复踩）。
     *
     * 只在下面两条同时成立时才动手，避免擅自改系统开关：
     *  1. 建过索引（locIndexAt > 0）—— 说明这个功能是他在用的；
     *  2. 当前绑定为空 —— 用户开着 TalkBack 等别的服务时绝不覆盖。
     */
    private void healA11yBinding() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(2500);                     // 别和首屏抢资源
                    if (A11yService.ready()) return;         // 还开着，没事
                    if (getSharedPreferences("app", 0).getLong("locIndexAt", 0L) <= 0L) return;
                    if (service == null || !"ok".equals(service.state())) return;
                    String cur = service.shellQuick(
                            "settings get secure enabled_accessibility_services", 3000);
                    cur = cur == null ? "" : cur.trim();
                    if (!cur.isEmpty() && !"null".equals(cur)) return;   // 已有别的服务，不碰
                    String comp = getPackageName() + "/" + A11yService.class.getName();
                    service.shellLong("settings put secure enabled_accessibility_services " + comp);
                    service.shellLong("settings put secure accessibility_enabled 1");
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    private void showFatal(String msg) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(msg);
        tv.setTextSize(15);
        tv.setPadding(48, 120, 48, 48);
        setContentView(tv);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) injectInsets();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 前台状态同步给 Bridge：只有在前台才往 WebView 推实时内存。
     * 后台时界面不可见，推过去只是空跑；通知栏由 Service 自己 notify，不受影响。
     */
    @Override
    protected void onResume() {
        super.onResume();
        Bridge.FOREGROUND = true;
    }

    @Override
    protected void onPause() {
        Bridge.FOREGROUND = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (pkgReceiver != null) {
            try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
            pkgReceiver = null;
        }
        if (bridge != null) bridge.shutdown();
        if (service != null) service.close();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
