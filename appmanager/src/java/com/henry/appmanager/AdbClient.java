package com.henry.appmanager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * 最小 ADB 客户端：不依赖 adb 二进制，纯 Java 实现 host 侧协议。
 *
 * 只覆盖本工具需要的三件事：CNXN 握手、AUTH 认证、shell 服务。
 * 协议细节见 AOSP packages/modules/adb/protocol.txt。
 */
public class AdbClient {

    public interface Log { void line(String s); }

    /** 已发送公钥但迟迟等不到 CNXN：通常是手机上还挂着未处理的授权弹窗。 */
    public static class AuthPendingException extends IOException {
        public AuthPendingException(String m) { super(m); }
    }

    /**
     * 流还没 OPEN 成功就断了（对端没回 OKAY）。
     *
     * 这类失败一定发生在命令真正开始执行之前，所以重连后重试是安全的，
     * 不会把 pm disable-user 之类的写操作执行两遍。
     */
    public static class StreamNotOpenedException extends IOException {
        public StreamNotOpenedException(String m) { super(m); }
    }

    private boolean connected;

    // 命令字：ASCII 小端读作 int
    static final int A_CNXN = 0x4e584e43; // "CNXN"
    static final int A_AUTH = 0x48545541; // "AUTH"
    static final int A_OPEN = 0x4e45504f; // "OPEN"
    static final int A_OKAY = 0x59414b4f; // "OKAY"
    static final int A_CLSE = 0x45534c43; // "CLSE"
    static final int A_WRTE = 0x45545257; // "WRTE"

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static final int VERSION = 0x01000000;
    static final int MAXDATA = 1024 * 1024;   // 与真实 adb 一致
    static final byte[] EMPTY = new byte[0];

    private final Log log;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private PrivateKey priv;
    private byte[] pubBlob;
    private String pubComment = "app@android";
    private String pubStr;   // 含注释的完整公钥串，优先使用

    public AdbClient(Log log) { this.log = log; }

    // ---------- 密钥 ----------

    /** 首次运行生成 2048 位 RSA，之后复用，避免每次都弹授权框。 */
    public void initKey(File dir) throws Exception {
        File fPriv = new File(dir, "adbkey.pk8");
        File fPub = new File(dir, "adbkey.blob");
        if (fPriv.exists() && fPub.exists()) {
            byte[] pk8 = readAll(fPriv);
            priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pk8));
            pubBlob = readAll(fPub);
            // 注释参与系统对「已授权密钥」的匹配：若与当初被授权时不同，
            // 每次连接都会重新弹授权框。因此优先使用原样的公钥串。
            File fStr = new File(dir, "adbkey.pubstr");
            if (fStr.exists()) {
                pubStr = new String(readAll(fStr), "UTF-8").trim();
            }
            log.line("复用已有密钥 (" + pubBlob.length + " 字节公钥"
                    + (pubStr != null ? "，含原注释" : "") + ")");
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        priv = kp.getPrivate();
        pubBlob = androidPubKeyBlob((RSAPublicKey) kp.getPublic());
        writeAll(fPriv, priv.getEncoded());
        writeAll(fPub, pubBlob);
        log.line("已生成 2048 位 RSA 密钥对");
    }

    /**
     * Android 的 adb 公钥不是标准 X.509，而是自定义结构（524 字节）：
     *   uint32 len(=64, 模长字数) | uint32 n0inv | n[64] | rr[64] | int32 exponent
     * 全部小端，n0inv = -n^-1 mod 2^32，rr = R^2 mod n（R = 2^2048）。
     * 该算法已用本机真实 adbkey.pub 逐字段验证。
     */
    static byte[] androidPubKeyBlob(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.modInverse(two32).negate().mod(two32);
        BigInteger r = BigInteger.ONE.shiftLeft(2048);
        BigInteger rr = r.multiply(r).mod(n);

        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        b.put(fixedLE(n, 256));
        b.put(fixedLE(rr, 256));
        b.putInt(e.intValue());
        return b.array();
    }

    /** 定长大端整数转小端字节数组，尾部对齐。 */
    static byte[] fixedLE(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < be.length && i < len; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    // ---------- 连接与认证 ----------

    public void connect(String host, int port) throws Exception {
        nextLocalId = 1;
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        log.line("TCP 已连接 " + host + ":" + port);

        send(A_CNXN, VERSION, MAXDATA, "host::\0".getBytes("UTF-8"));
        log.line("已发送 CNXN (version=0x01000000)");

        boolean sentSig = false;
        boolean sentPub = false;
        while (true) {
            // 等待用户点「允许」时可能耗时较久，超时放宽到 60 秒
            socket.setSoTimeout(60000);
            Msg m;
            try {
                m = read();
            } catch (java.net.SocketTimeoutException e) {
                if (sentPub) throw new AuthPendingException("等待手机授权超时");
                throw e;
            }
            log.line("  <- " + cmdName(m.cmd) + " arg0=" + m.arg0
                    + " arg1=" + m.arg1 + " len=" + m.data.length
                    + (m.data.length > 0 && m.data.length <= 40
                       ? " data=" + toHex(m.data) : ""));
            if (m.cmd == A_CNXN) {
                String banner = new String(m.data, "UTF-8").trim();
                log.line("握手完成，设备 banner: " + banner);
                connected = true;
                return;
            }
            if (m.cmd == A_AUTH) {
                if (m.arg0 == AUTH_TOKEN) {
                    if (!sentSig) {
                        byte[] sig = sign(m.data);
                        send(A_AUTH, AUTH_SIGNATURE, 0, sig);
                        sentSig = true;
                        log.line("已发送签名 (" + sig.length + " 字节)，等待设备校验");
                    } else if (!sentPub) {
                        String pk = (pubStr != null)
                                ? pubStr
                                : Base64.getEncoder().encodeToString(pubBlob) + " " + pubComment;
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0,
                                (pk + "\0").getBytes("UTF-8"));
                        sentPub = true;
                        log.line("密钥未被信任，已发送公钥" + (pubStr != null ? "（原注释）" : ""));
                    } else {
                        throw new IOException("认证失败：公钥已发送但未获授权");
                    }
                }
                continue;
            }
            log.line("未预期的包: " + cmdName(m.cmd));
        }
    }

    /**
     * SHA1 的 DigestInfo 前缀（ASN.1 DER）。
     * adb 的 AUTH token 本身就被当作「已算好的摘要」直接塞进这里，
     * 不再做一次 SHA1 —— 这点与 Java 的 "SHA1withRSA" 完全不同，
     * 后者会先哈希，导致签名必然被 adbd 拒绝。
     */
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
        0x05, 0x00, 0x04, 0x14
    };

    private byte[] sign(byte[] token) throws Exception {
        byte[] block = new byte[SHA1_DIGEST_INFO.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO, 0, block, 0, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, block, SHA1_DIGEST_INFO.length, token.length);
        // 私钥 + PKCS1Padding 的 ENCRYPT_MODE = PKCS#1 v1.5 type-1 填充 + 裸 RSA
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, priv);
        return c.doFinal(block);
    }

    /** 自测用：对固定数据签名并返回十六进制，便于与 Python 独立实现比对。 */
    public String signHex(byte[] data) throws Exception {
        return toHex(sign(data));
    }

    static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    // ---------- shell ----------

    /** 执行一条 shell 命令并返回 stdout。每次调用独立 OPEN 一个 shell 服务。 */
    private int nextLocalId = 1;

    public String shell(String command) throws Exception {
        return shell(command, 30000);
    }

    /** 编译类命令可能耗时很久，需要单独放宽读超时。 */
    public String shell(String command, int timeoutMs) throws Exception {
        return shell(command, timeoutMs, false);
    }

    /**
     * @param raw true 时用 `shell,raw:`（管道）而不是 `shell:`（PTY）。
     *            大输出命令建议用 raw：一旦本端放弃读取，子进程写管道会拿到
     *            EPIPE 立刻退出；而 PTY 下它会永远卡在 n_tty_write，
     *            在设备上留下僵尸 dumpsys，攒多了新的 OPEN 就再也等不到 OKAY。
     */
    public String shell(String command, int timeoutMs, boolean raw) throws Exception {
        // 每次用新的 local-id：复用同一个 id 时，设备可能因上一条流尚未完全关闭而立即 CLSE
        int localId = nextLocalId++;
        if (nextLocalId > 0x7FFFFFF0) nextLocalId = 1;
        // 带管道的命令仍走 PTY：实测 raw 模式下管道输出会被截断（如 dexopt 只回 45 字节）
        String svc = (raw && command.indexOf('|') < 0) ? "shell,raw:" : "shell:";
        send(A_OPEN, localId, 0, (svc + command + "\0").getBytes("UTF-8"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remoteId = 0;
        int pkts = 0;
        // 等待 OKAY 用短超时：复用的连接可能已被 adbd 悄悄丢掉，
        // 此时等满 30 秒毫无意义，8 秒内没开起来就重连重试。
        // timeoutMs <= 0 表示「不设总超时」（编译类命令），但开流阶段仍限 8 秒。
        socket.setSoTimeout(timeoutMs > 0 ? Math.min(8000, timeoutMs) : 8000);
        while (true) {
            Msg m;
            try {
                m = read();
            } catch (IOException e) {
                // 主动关掉这条流，别把设备侧的子进程留在那里空转
                abort(localId, remoteId);
                // 流还没开起来就断 = 命令肯定没跑过，调用方可以安全重试
                if (remoteId == 0) throw new StreamNotOpenedException(e.getMessage());
                throw e;
            }
            // 设备发来的包：arg0 是「设备的 socket id」，arg1 才是「本端的 local-id」。
            // 这一点搞反过：把 arg0 当成本端 id 去比对，就永远匹配不上，
            // 于是 remoteId 一直是 0、给设备的 ACK 也带错 id —— 小输出（单个 WRTE）
            // 侥幸能跑完，像 dumpsys meminfo（40KB、要多个 WRTE 才流完）就会卡死。
            if (m.cmd == A_OKAY) {
                if (m.arg1 == localId) {
                    remoteId = m.arg0;
                    // 流已建立，改用命令自身的超时（0 = 不设超时，等编译自己跑完）
                    socket.setSoTimeout(timeoutMs);
                }
            } else if (m.cmd == A_WRTE) {
                if (m.arg1 != localId) continue;
                pkts++;
                buf.write(m.data, 0, m.data.length);
                send(A_OKAY, localId, remoteId, EMPTY);
            } else if (m.cmd == A_CLSE) {
                if (m.arg1 != localId) continue;
                // adb 的流是双向关闭的：设备先发 CLSE 之后，本端必须回一个 CLSE，
                // 否则设备侧那条 stream 不会被释放。
                try { send(A_CLSE, localId, remoteId, EMPTY); } catch (IOException ignored) { }
                log.line("CLSE 包数=" + pkts + " 字节=" + buf.size()
                        + " arg0=" + m.arg0 + " localId=" + localId);
                break;
            }
        }
        return buf.toString("UTF-8");
    }

    /** 放弃一条流：给设备回 CLSE，让它把子进程收掉。失败无所谓，连接随后就会关。 */
    private void abort(int localId, int remoteId) {
        try {
            if (remoteId != 0) send(A_CLSE, localId, remoteId, EMPTY);
        } catch (IOException ignored) { }
    }

    /** 已解析好的密钥材料，便于为每条命令新建连接时复用，避免重复解析 PEM。 */
    public static class KeyMaterial {
        PrivateKey priv;
        byte[] blob;
        String pubStr;
    }

    public KeyMaterial exportKey() {
        KeyMaterial k = new KeyMaterial();
        k.priv = priv;
        k.blob = pubBlob;
        k.pubStr = pubStr;
        return k;
    }

    public void applyKey(KeyMaterial k) {
        priv = k.priv;
        pubBlob = k.blob;
        pubStr = k.pubStr;
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public void close() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    // ---------- 协议底层 ----------

    static class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd);
        b.putInt(arg0);
        b.putInt(arg1);
        b.putInt(data.length);
        b.putInt(checksum(data));
        b.putInt(cmd ^ 0xFFFFFFFF);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = readFully(24);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // data_check，不校验
        int magic = b.getInt();
        if ((magic ^ 0xFFFFFFFF) != m.cmd) {
            throw new IOException("包头 magic 不匹配: " + cmdName(m.cmd));
        }
        if (len < 0 || len > 1024 * 1024) {
            throw new IOException("非法 data_length: " + len);
        }
        m.data = len > 0 ? readFully(len) : EMPTY;
        return m;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("连接被对端关闭");
            off += r;
        }
        return buf;
    }

    static int checksum(byte[] d) {
        int s = 0;
        for (byte x : d) s += (x & 0xFF);
        return s;
    }

    static String cmdName(int c) {
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(c).array();
        return new String(b);
    }

    // ---------- 文件 ----------

    static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            return o.toByteArray();
        }
    }

    static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
        f.setReadable(false, false);
        f.setReadable(true, true);
    }
}
