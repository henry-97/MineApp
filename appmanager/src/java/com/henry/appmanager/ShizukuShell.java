package com.henry.appmanager;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 经 Shizuku 执行 shell。
 *
 * Shizuku 是一个以 adb shell（或 root）身份常驻的代理服务，App 通过 binder 借用它的权限。
 * 好处：不需要 `adb tcpip 5555`，也不需要 root，重启后（用无线调试自启动）依然有效。
 *
 * 🔴 只在多通道版启用，且用户必须在「执行通道」里明确选择 Shizuku。
 *    enabled 由 Bridge 根据包名 + 偏好设置，AdbService 在每条命令前检查。
 */
final class ShizukuShell {

    /** 是否允许走 Shizuku（多通道版 + 用户选了 Shizuku 通道） */
    static volatile boolean enabled = false;

    /** 申请授权用的 requestCode，随便取，只要回调里对得上 */
    static final int REQ_CODE = 0x5A1;

    private ShizukuShell() { }

    /** 服务在跑（Shizuku App 已启动） */
    static boolean alive() {
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 本应用已拿到 Shizuku 授权 */
    static boolean granted() {
        try {
            return alive() && rikka.shizuku.Shizuku.checkSelfPermission()
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 等 binder 送达。
     *
     * App 冷启动时进程刚起来，Shizuku 还没把 binder 通过 provider 交过来，
     * 此时 pingBinder() 是 false。如果直接判定「不可用」，首屏列表就会误报
     * 「调试未连接」并且不再自动重试 —— 用户看到的就是一片空白。
     */
    static boolean waitAlive(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            if (alive()) return true;
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
        }
        return alive();
    }

    /** 本进程是否已经为「等 binder」付出过一次代价 */
    private static volatile boolean WAITED = false;

    /**
     * 只在本进程第一次判断时等 binder。
     *
     * 🔴 为什么不能每次都等：ShizukuShell.enabled 在「自动」通道下也是 true
     * （没有 Shizuku 的用户很常见），若每次 state() 都 waitAlive(1500)，
     * 每刷一次列表就白卡 1.5 秒。竞态只发生在冷启动的头几百毫秒，
     * 等一次就够；之后 alive() 是实时查询，服务后来起来了照样能认出来。
     */
    static boolean waitBinderOnce(long maxMs) {
        if (alive()) { WAITED = true; return true; }
        if (WAITED) return false;
        WAITED = true;
        return waitAlive(maxMs);
    }

    /** 现在这条命令该不该走 Shizuku */
    static boolean active() {
        return enabled && granted();
    }

    /**
     * 执行一条命令，返回 stdout + stderr。
     *
     * ShizukuRemoteProcess 是 Process 的子类，读写方式与普通进程一致；
     * 但它是跨进程的，close 一定要放在 finally，否则 binder 引用会泄漏。
     */
    /** 只打印前几条，用于确认命令确实走了 Shizuku，不刷屏 */
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    static String run(String cmd, int timeoutMs) throws Exception {
        if (LOGGED.getAndIncrement() < 5) {
            android.util.Log.i("ShizukuShell", "exec via shizuku: " + cmd);
        }
        Process p = newProcess(new String[]{"sh", "-c", cmd}, null, null);
        try {
            final InputStream out = p.getInputStream();
            final InputStream err = p.getErrorStream();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            // stderr 必须一起读：不读会把子进程管道写满，命令直接卡死
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    copy(err, buf, true);
                }
            });
            t.setDaemon(true);
            t.start();
            copy(out, buf, false);

            long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
            while (System.currentTimeMillis() < deadline) {
                try {
                    p.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(40);           // 普通 Process 的「还没退出」
                } catch (IllegalArgumentException notYet) {
                    // 🔴 ShizukuRemoteProcess.exitValue() 抛的是
                    //    IllegalArgumentException("process hasn't exited")，
                    //    不是 IllegalThreadStateException。不单独接住会一路冒到上层，
                    //    表现为「取数失败: process hasn't exited」，列表直接空白。
                    Thread.sleep(40);
                } catch (Throwable other) {
                    break;
                }
            }
            t.join(300);
            synchronized (buf) {
                return buf.toString("UTF-8");
            }
        } finally {
            try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 缓存反射到的 newProcess，避免每条命令都查一次 */
    private static java.lang.reflect.Method NEW_PROCESS;

    /**
     * 🔴 API 13 把 `Shizuku.newProcess(String[], String[], String)` 改成了 private
     * （隐藏 API 的常见做法），编译期调不到，只能反射。签名与旧版一致。
     */
    private static Process newProcess(String[] cmd, String[] env, String dir) throws Exception {
        java.lang.reflect.Method m = NEW_PROCESS;
        if (m == null) {
            m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            NEW_PROCESS = m;
        }
        return (Process) m.invoke(null, cmd, env, dir);
    }

    /** 把流读进同一个缓冲；stderr 与 stdout 交错时用锁保证字节不撕开 */
    private static void copy(InputStream in, ByteArrayOutputStream buf, boolean isErr) {
        byte[] b = new byte[4096];
        try {
            int n;
            while ((n = in.read(b)) > 0) {
                synchronized (buf) {
                    buf.write(b, 0, n);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
