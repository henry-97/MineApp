package com.henry.appmanager;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务：只做一件事 —— 把当前窗口的控件树序列化成 uiautomator dump 同款 XML。
 *
 * 为什么要它：现在读一次桌面是 `uiautomator dump` → 写 /sdcard → cat 回来，
 * 一次 1.5~3 秒；建一次索引要 25~30 次，所以慢到一分多钟。
 * 直接读 AccessibilityNodeInfo 只要几十毫秒。
 *
 * 🔴 序列化格式必须和 dump 完全一致（hierarchy / node / content-desc / class /
 *    bounds / clickable），这样 parsePager、findNode、findFolders、pageSignature
 *    一个都不用改，两条读取路径可以随时切换、互为兜底。
 *
 * 本服务不订阅任何事件内容，只被调用时读一次当前窗口，不做任何存储或上报。
 */
public class A11yService extends AccessibilityService {
    private static volatile A11yService inst;

    /** 服务是否已连接（用户在系统设置里打开之后才是 true）。 */
    static boolean ready() {
        return inst != null;
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
    }

    /** 不订阅事件内容：只在被调用时读一次当前窗口。 */
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        inst = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 回桌面。不需要 adb 授权，也不会被「USB 调试（安全设置）」拦住。 */
    static boolean home() {
        A11yService s = inst;
        if (s == null) return false;
        try { return s.performGlobalAction(GLOBAL_ACTION_HOME); } catch (Throwable e) { return false; }
    }

    /** 返回键。 */
    static boolean back() {
        A11yService s = inst;
        if (s == null) return false;
        try { return s.performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable e) { return false; }
    }

    /**
     * 发一次手势：点击（起止同点）、长按（起止同点 + 长时长）、滑动（起止不同点）。
     *
     * 🔴 dispatchGesture 是异步的，而且回调走主线程 —— 这里投到主线程发，
     *    再用 latch 等到 onCompleted，否则调用方紧接着就去 dump，会读到手势之前的界面。
     */
    static boolean gesture(final int x1, final int y1, final int x2, final int y2,
                           final int durationMs) {
        final A11yService s = inst;
        if (s == null) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    Path path = new Path();
                    path.moveTo(Math.max(0, x1), Math.max(0, y1));
                    if (x1 != x2 || y1 != y2) path.lineTo(Math.max(0, x2), Math.max(0, y2));
                    GestureDescription.Builder b = new GestureDescription.Builder();
                    b.addStroke(new GestureDescription.StrokeDescription(path, 0,
                            Math.max(1, durationMs)));
                    ok[0] = s.dispatchGesture(b.build(),
                            new AccessibilityService.GestureResultCallback() {
                                @Override public void onCompleted(GestureDescription g) {
                                    latch.countDown();
                                }
                                @Override public void onCancelled(GestureDescription g) {
                                    latch.countDown();
                                }
                            }, null);
                } catch (Throwable ignored) {
                } finally {
                    if (!ok[0]) latch.countDown();      // 没发出去就别让调用方干等
                }
            }
        });
        try { latch.await(Math.max(1, durationMs) + 1500L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) { }
        return ok[0];
    }

    /** 当前窗口的控件树 → XML；读不到返回 null（调用方退回 uiautomator dump）。 */
    static String dumpXml() {
        A11yService s = inst;
        if (s == null) return null;
        AccessibilityNodeInfo root;
        try {
            root = s.getRootInActiveWindow();
        } catch (Throwable e) {
            return null;
        }
        if (root == null) return null;
        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">");
        try {
            walk(root, sb);
        } catch (Throwable e) {
            return null;                 // 树读到一半断了就整体放弃，别返回半截
        }
        sb.append("</hierarchy>");
        return sb.toString();
    }

    private static void walk(AccessibilityNodeInfo n, StringBuilder sb) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append("<node index=\"").append(n.getChildCount())
          .append("\" text=\"").append(esc(n.getText()))
          .append("\" class=\"").append(esc(n.getClassName()))
          .append("\" package=\"").append(esc(n.getPackageName()))
          .append("\" content-desc=\"").append(esc(n.getContentDescription()))
          .append("\" clickable=\"").append(n.isClickable() ? "true" : "false")
          .append("\" enabled=\"").append(n.isEnabled() ? "true" : "false")
          .append("\" bounds=\"[").append(r.left).append(',').append(r.top)
          .append("][").append(r.right).append(',').append(r.bottom).append("]\">");
        int cnt = n.getChildCount();
        for (int i = 0; i < cnt; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            walk(c, sb);
        }
        sb.append("</node>");
    }

    /** XML 属性转义：& 必须最先替换，否则会把后面生成的实体再转一次。 */
    private static String esc(CharSequence cs) {
        if (cs == null || cs.length() == 0) return "";
        String s = cs.toString();
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = null;
            if (c == '&') rep = "&amp;";
            else if (c == '<') rep = "&lt;";
            else if (c == '>') rep = "&gt;";
            else if (c == '"') rep = "&quot;";
            else if (c == '\n') rep = " ";
            if (rep == null) {
                if (b != null) b.append(c);
            } else {
                if (b == null) b = new StringBuilder(s.length() + 16).append(s, 0, i);
                b.append(rep);
            }
        }
        return b == null ? s : b.toString();
    }
}
