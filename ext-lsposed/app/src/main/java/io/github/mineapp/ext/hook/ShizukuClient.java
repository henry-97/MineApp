package io.github.mineapp.ext.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.mineapp.ext.Const;
import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

/**
 * 在 Mine App 进程里直接当 Shizuku 客户端：以 com.henry.appmanager 的身份 attach、申请授权、开进程。
 *
 * 唯一绕不开的是拿 binder：Shizuku 服务端只把 binder 推给清单里声明了
 * API_V23 权限和 ShizukuProvider 的应用，而注入代码改不了 Mine App 的清单。
 * 所以按顺序尝试：
 *   1. Sui（Zygisk 版 Shizuku）：任何应用都能直接拿到，不经过模块；
 *   2. 向模块 App 的 BinderProvider 要一次（系统按需拉起模块进程，拿完即可退出）。
 * 拿到之后的授权、执行全在本进程，授权框里显示的也是 Mine App。
 */
final class ShizukuClient {
    private ShizukuClient() { }

    private static final int REQ = 0x5e1;
    private static final Uri BINDER_URI = Uri.parse("content://" + Const.BINDER_AUTHORITY);

    /** Shizuku 的监听器默认回调到主线程，而我们常常就在主线程之外干等，单独给一个线程。 */
    private static final Handler HANDLER;
    static {
        HandlerThread t = new HandlerThread("mineext-shizuku");
        t.start();
        HANDLER = new Handler(t.getLooper());
    }

    /** ensureBinder 成功；授权与否由 state() 继续判断 */
    private static final int READY = 100;

    /** 确保本进程持有可用的 Shizuku binder。返回 READY，或 Const.SHIZUKU_NOT_RUNNING / UNREACHABLE。 */
    private static synchronized int ensureBinder(long waitMs) {
        if (Shizuku.pingBinder()) return READY;

        try {
            if (Sui.init(Const.TARGET_PKG) && awaitReady(waitMs)) return READY;
        } catch (Throwable ignored) { }

        Context ctx = Env.app();
        if (ctx == null) return Const.SHIZUKU_NOT_RUNNING;
        IBinder binder;
        try {
            Bundle b = ctx.getContentResolver().call(BINDER_URI, Const.METHOD_GET_BINDER, null, null);
            binder = b == null ? null : b.getBinder(Const.KEY_BINDER);
        } catch (Throwable t) {
            // Unknown authority：模块 App 没装或被禁用
            Env.log("binder provider unreachable: " + t);
            return Const.SHIZUKU_UNREACHABLE;
        }
        if (binder == null || !binder.pingBinder()) return Const.SHIZUKU_NOT_RUNNING;

        final CountDownLatch l = new CountDownLatch(1);
        Shizuku.OnBinderReceivedListener lis = l::countDown;
        Shizuku.addBinderReceivedListenerSticky(lis, HANDLER);
        try {
            Shizuku.onBinderReceived(binder, Const.TARGET_PKG);
            l.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        } finally {
            Shizuku.removeBinderReceivedListener(lis);
        }
        return Shizuku.pingBinder() ? READY : Const.SHIZUKU_NOT_RUNNING;
    }

    private static boolean awaitReady(long waitMs) throws InterruptedException {
        final CountDownLatch l = new CountDownLatch(1);
        Shizuku.OnBinderReceivedListener lis = l::countDown;
        Shizuku.addBinderReceivedListenerSticky(lis, HANDLER);
        try {
            l.await(waitMs, TimeUnit.MILLISECONDS);
        } finally {
            Shizuku.removeBinderReceivedListener(lis);
        }
        return Shizuku.pingBinder();
    }

    static int state() {
        int r = ensureBinder(3000);
        if (r != READY) return r;
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return Const.SHIZUKU_TOO_OLD;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return Const.SHIZUKU_GRANTED;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) return Const.SHIZUKU_DENIED;
            return Const.SHIZUKU_NO_PERMISSION;
        } catch (Throwable t) {
            Env.log("shizuku state", t);
            return Const.SHIZUKU_NOT_RUNNING;
        }
    }

    /**
     * 以 Mine App 的身份弹出 Shizuku 授权框，等用户选完（最多 60 秒）。
     * 返回 state() 的最新结果。
     */
    static int requestPermission() {
        int st = state();
        if (st != Const.SHIZUKU_NO_PERMISSION) return st;
        final CountDownLatch l = new CountDownLatch(1);
        Shizuku.OnRequestPermissionResultListener lis = (code, result) -> {
            if (code == REQ) l.countDown();
        };
        Shizuku.addRequestPermissionResultListener(lis, HANDLER);
        try {
            Shizuku.requestPermission(REQ);
            l.await(60, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Env.log("requestPermission", t);
        } finally {
            Shizuku.removeRequestPermissionResultListener(lis);
        }
        return state();
    }

    /** 开一个常驻 sh 会话；身份是 Shizuku 服务端的身份（shell 2000，或以 root 启动时的 0）。 */
    static PersistentShell openShell() throws IOException {
        int st = state();
        if (st != Const.SHIZUKU_GRANTED) throw new IOException("shizuku state " + st);
        Process p;
        try {
            // Shizuku.newProcess 在 API 13 里改成了 private，但服务端接口仍在；常驻 sh 正适合它
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            p = (Process) m.invoke(null, new String[]{"sh"}, null, null);
        } catch (InvocationTargetException e) {
            throw new IOException("shizuku newProcess failed: " + e.getCause());
        } catch (Throwable t) {
            throw new IOException("shizuku newProcess unavailable: " + t);
        }
        return PersistentShell.open(p, "shizuku", null);
    }
}
