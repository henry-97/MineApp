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

    /** 常见 su 路径，按优先级排列（Magisk 主要放 /system/bin，老 SuperSU 在 xbin） */
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sd/xbin/su", "/data/local/bin/su", "/data/local/xbin/su",
    };

    private static volatile String SU = null;
    private static volatile boolean SU_RESOLVED = false;

    /**
     * 解析 su 的绝对路径。
     *
     * 🔴 两个坑都要绕开：
     *    1. 不能直接 `new ProcessBuilder("su", ...)` 依赖 PATH ——
     *       实测 App 进程的 PATH 里没有 /system/xbin，su 明明装着，
     *       也会报 `Cannot run program "su": error=13, Permission denied`。
     *    2. 不能用 canExecute() 做筛选 —— SELinux 会把「权限位有 x」的
     *       文件判成不可执行，明明 exists() 为真却 canExecute() 为假。
     *       所以这里只用 exists() 挑路径，能不能跑交给实际执行去验证。
     */
    private static String resolveSu() {
        if (SU_RESOLVED) return SU;
        synchronized (RootShell.class) {
            if (SU_RESOLVED) return SU;
            for (String p : SU_PATHS) {
                try {
                    if (new File(p).exists()) {
                        SU = p;
                        SU_RESOLVED = true;
                        return p;
                    }
                } catch (Throwable ignored) { }
            }
            SU = "su";              // 都没找到，最后交给 PATH 碰碰运气
            SU_RESOLVED = true;
            return SU;
        }
    }

    /**
     * su 二进制是否存在且可执行。
     *
     * 只看文件、不实际执行 —— 执行会弹 Magisk 授权框，不能拿来做探测。
     */
    static boolean available() {
        if (FAILED) return false;
        return !"su".equals(resolveSu());
    }

    /**
     * 现在这条命令该不该走 su。
     *
     * 🔴 不拿 available() 当闸门：文件检测会因为「目录权限」误判 ——
     *    实测某镜像里 /system/xbin 是 root:shell 0750，other 进不去目录，
     *    new File("/system/xbin/su").exists() 直接返回 false，明明有 su 也用不上。
     *    用户既然明确选了 Root 通道，就应该真去试一次；起不来再置 FAILED。
     */
    static boolean active() {
        return enabled && !FAILED;
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
        final String exe = resolveSu();
        if (LOGGED.getAndIncrement() < 5) {
            android.util.Log.i("RootShell", "exec via " + exe + ": " + cmd);
        }
        Process p;
        try {
            p = new ProcessBuilder(exe, "-c", cmd)
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
                String out = buf.toString("UTF-8");
                if (LOGGED.get() < 8) {
                    android.util.Log.i("RootShell", "  <- " + out.length() + "B: "
                            + (out.length() > 100 ? out.substring(0, 100) : out).replace('\n', '|'));
                }
                return out;
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
