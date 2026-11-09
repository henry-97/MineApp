package io.github.mineapp.ext.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 常驻 shell 会话：Root 用本地 su 进程，Shizuku 用 Shizuku.newProcess("sh")，协议完全相同。
 *
 * 不每条命令起一个进程：Mine App 每 3 秒就要跑一次 cat /proc/meminfo，
 * 而 Magisk 默认每次授予都会弹一个 Toast。一个 AdbClient 对应一个常驻会话，
 * 普通连接和长任务连接各一条，与原来两条 transport 的并发模型一致。
 *
 * 命令写成 `( eval '<cmd>' ) </dev/null 2>&1; printf '\n<marker>\n'`：
 * 子 shell 防止 exit 把会话带走，stdin 置空防止命令吃掉后续输入，
 * marker 每条命令随机生成，命令输出伪造不了。
 */
final class PersistentShell implements ShellSession {

    private static final SecureRandom RNG = new SecureRandom();

    private final Process proc;
    private final String name;
    private final OutputStream stdin;
    private final Object bufLock = new Object();
    private byte[] buf = new byte[64 * 1024];
    private int len;
    private boolean eof;
    private volatile boolean closed;

    private PersistentShell(Process proc, String name) {
        this.proc = proc;
        this.name = name;
        this.stdin = proc.getOutputStream();
        startReader(proc.getInputStream());
        // shell 自身的 stderr（命令的 stderr 已经并进 stdout）只需排空，免得管道写满卡住
        drain(proc.getErrorStream());
    }

    private void startReader(final InputStream out) {
        Thread t = new Thread(() -> {
            byte[] tmp = new byte[16384];
            try {
                int n;
                while ((n = out.read(tmp)) > 0) {
                    synchronized (bufLock) {
                        if (len + n > buf.length) {
                            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
                        }
                        System.arraycopy(tmp, 0, buf, len, n);
                        len += n;
                        bufLock.notifyAll();
                    }
                }
            } catch (IOException ignored) {
            } finally {
                synchronized (bufLock) {
                    eof = true;
                    bufLock.notifyAll();
                }
            }
        }, "mineext-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static void drain(final InputStream err) {
        if (err == null) return;
        Thread t = new Thread(() -> {
            byte[] tmp = new byte[4096];
            try {
                while (err.read(tmp) > 0) { /* discard */ }
            } catch (IOException ignored) { }
        }, "mineext-stderr");
        t.setDaemon(true);
        t.start();
    }

    /** 包装一个已启动的 shell 进程，并用 `id -u` 确认它真的能用；授权框要等用户点，给足 30 秒。 */
    static PersistentShell open(Process p, String name, String expectUid) throws IOException {
        PersistentShell s = new PersistentShell(p, name);
        String id;
        try {
            id = s.exec("id -u", 30000).trim();
        } catch (IOException e) {
            s.close();
            throw new IOException(name + " shell did not respond (denied or timed out)");
        }
        if (expectUid != null && !expectUid.equals(id)) {
            s.close();
            throw new IOException(name + " shell runs as uid " + id);
        }
        return s;
    }

    static PersistentShell openRoot() throws IOException {
        Process p;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("su not found");
        }
        return open(p, "root", "0");
    }

    @Override
    public synchronized String exec(String cmd, int idleTimeoutMs) throws IOException {
        if (!alive()) throw new NotStartedException(name + " shell is gone");
        String nonce = Long.toHexString(RNG.nextLong()) + Long.toHexString(RNG.nextLong());
        String marker = "__MINEEXT_" + nonce + "__";
        byte[] tail = ("\n" + marker + "\n").getBytes(StandardCharsets.UTF_8);
        String script = "( eval '" + cmd.replace("'", "'\\''") + "' ) </dev/null 2>&1; "
                + "printf '\\n" + marker + "\\n'\n";
        synchronized (bufLock) {
            len = 0;
        }
        try {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            close();
            throw new NotStartedException("write to " + name + " shell failed: " + e.getMessage());
        }

        int scanFrom = 0;
        long deadline = idleTimeoutMs > 0 ? System.currentTimeMillis() + idleTimeoutMs : 0;
        synchronized (bufLock) {
            while (true) {
                int at = indexOf(buf, len, tail, scanFrom);
                if (at >= 0) {
                    String out = new String(buf, 0, at, StandardCharsets.UTF_8);
                    len = 0;
                    if (buf.length > 1024 * 1024) buf = new byte[64 * 1024];
                    return out;
                }
                scanFrom = Math.max(0, len - tail.length + 1);
                if (eof) {
                    close();
                    throw new IOException(name + " shell exited");
                }
                int before = len;
                try {
                    if (deadline == 0) {
                        bufLock.wait();
                    } else {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) {
                            close();
                            throw new SocketTimeoutException(name + " shell timed out");
                        }
                        bufLock.wait(left);
                    }
                } catch (InterruptedException e) {
                    close();
                    throw new IOException("interrupted");
                }
                // 有新输出就顺延，与 socket 读超时一样按「空闲」计
                if (len > before && idleTimeoutMs > 0) {
                    deadline = System.currentTimeMillis() + idleTimeoutMs;
                }
            }
        }
    }

    private static int indexOf(byte[] hay, int hayLen, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hayLen - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** 进程退出时 stdout 必然 EOF；不用 exitValue()，远程进程的异常类型不可靠。 */
    @Override
    public boolean alive() {
        if (closed) return false;
        synchronized (bufLock) {
            return !eof;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { stdin.close(); } catch (IOException ignored) { }
        try { proc.destroy(); } catch (Throwable ignored) { }
    }

    @Override
    public String name() {
        return name;
    }
}
