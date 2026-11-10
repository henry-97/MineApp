package com.henry.appmanager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * 经 su 在本机执行 shell。
 *
 * 与 ShizukuShell 对等 —— 让「Root」成为一条**不依赖 ADB** 的独立通道：
 * 选了 Root 之后，即使用户没做过 `adb tcpip 5555`、也没插 USB，
 * 冻结 / 限制 / 读应用列表照样能用。
 *
 * 在此之前 Root 只影响「建索引时给 launcher.db 加一层 su -c」，
 * 其余命令仍然走 ADB，没连 ADB 时选 Root 等于什么都干不了。
 *
 * 🔴 只在多通道版启用，且用户必须明确选「Root」通道。
 *    Magisk 首次调用会弹授权框，进程会一直阻塞到用户点「允许/拒绝」，
 *    所以超时不能设太短（与 ADB 一致，默认 30 秒）。
 */
final class RootShell {

    /** 是否允许走 root（多通道版 + 用户选了 Root 通道） */
    static volatile boolean enabled = false;

    /** 一旦彻底起不来（Magisk 拒绝 / 无 su），就别每条命令都白等一遍 */
    private static volatile boolean FAILED = false;

    private RootShell() { }

    /**
     * su 二进制是否存在。
     *
     * 只看文件、不实际执行 —— 执行会弹 Magisk 授权框，不能拿来做探测。
     * 覆盖 Magisk（/system/bin/su）、SuperSU（/system/xbin/su 等）常见位置。
     */
    static boolean available() {
        if (FAILED) return false;
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sd/xbin/su", "/data/local/bin/su", "/data/local/xbin/su",
        };
        for (String p : paths) {
            try {
                if (new File(p).exists()) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /** 现在这条命令该不该走 su */
    static boolean active() {
        return enabled && available();
    }

    private static final java.util.concurrent.atomic.AtomicInteger LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 执行 `su -c cmd`，返回合并后的 stdout + stderr。
     *
     * 用 ProcessBuilder 而不是 Runtime.exec(String)，后者会按空格切字符串，
     * 命令里带引号、重定向时会散架。
     */
    static String run(String cmd, int timeoutMs) throws Exception {
        if (LOGGED.getAndIncrement() < 5) {
            android.util.Log.i("RootShell", "exec via su: " + cmd);
        }
        Process p;
        try {
            p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            // su 起不来（被 Magisk 拒绝、二进制不可执行）→ 本进程内不再重试
            FAILED = true;
            throw e;
        }
        try {
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread t = new Thread(new Runnable() {
                @Override public void run() { copy(in, buf); }
            });
            t.setDaemon(true);
            t.start();

            long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
            while (System.currentTimeMillis() < deadline) {
                try {
                    p.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(40);
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

    private static void copy(InputStream in, ByteArrayOutputStream buf) {
        byte[] b = new byte[4096];
        try {
            int n;
            while ((n = in.read(b)) > 0) {
                synchronized (buf) { buf.write(b, 0, n); }
            }
        } catch (Throwable ignored) {
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
