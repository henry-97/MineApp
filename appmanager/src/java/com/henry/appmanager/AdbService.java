package com.henry.appmanager;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用管理器的数据层：把原 server.py 的取数与批量操作逐条移植过来。
 *
 * 与原实现的差异仅在于传输层 —— 不再走 HTTP，直接调用 {@link AdbClient}。
 * 排序规则、解析逻辑、成功判定全部保持一致。
 */
public class AdbService {

    private static final String TAG = "AdbService";
    /** 正式版关掉逐条命令日志；Log.w 的异常告警保留，方便用户报 bug 时抓 */
    private static final boolean DEBUG = false;
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    /** 电池设计容量（mAh）。Redmi Note 12 Turbo = 5000。 */
    private static final int BATTERY_DESIGN_MAH = 5000;

    private static final Map<Integer, String> HEALTH_TEXT = new HashMap<Integer, String>();
    static {
        HEALTH_TEXT.put(1, "未知");
        HEALTH_TEXT.put(2, "良好");
        HEALTH_TEXT.put(3, "过热");
        HEALTH_TEXT.put(4, "损坏");
        HEALTH_TEXT.put(5, "过压");
        HEALTH_TEXT.put(6, "故障");
        HEALTH_TEXT.put(7, "过冷");
    }

    private static final Pattern PSS_LINE =
            Pattern.compile("^([\\d,]+)K:\\s+([A-Za-z0-9_.]+)");
    private static final Pattern MEM_TOTAL =
            Pattern.compile("MemTotal:\\s+(\\d+)\\s+kB");
    private static final Pattern MEM_AVAIL =
            Pattern.compile("MemAvailable:\\s+(\\d+)\\s+kB");

    private final AdbClient.Log log;
    private AdbClient.KeyMaterial key;

    public AdbService(AdbClient.Log log) {
        this.log = log;
    }

    /**
     * 全进程共用同一个实例。
     *
     * MainActivity 与 KeepAliveService 若各建一个实例，就是两条独立的 transport，
     * 未授权时各弹一次授权框 —— 这也是「连续弹好几次」的来源之一。
     */
    private static AdbService shared;
    /** ApplicationContext，只用于 PackageManager 取应用名 */
    private static android.content.Context appCtx;

    public static synchronized AdbService shared(android.content.Context ctx, AdbClient.Log log) {
        if (shared == null) {
            AdbService s = new AdbService(log);
            // 保留 ApplicationContext：取应用名要用 PackageManager，不能持有 Activity
            appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            try {
                s.initKey(ctx.getFilesDir());
            } catch (Exception e) {
                Log.w(TAG, "密钥初始化失败: " + e);
            }
            shared = s;
        }
        return shared;
    }

    public void initKey(java.io.File dir) throws Exception {
        AdbClient tmp = new AdbClient(log);
        tmp.initKey(dir);
        key = tmp.exportKey();
    }

    // ---------- 连接管理 ----------
    //
    // 整个进程只维持一条到 adbd 的连接，所有命令复用它。
    //
    // 之前是「每条命令重新建连接」，代价很大：127.0.0.1:5555 上每个 TCP 连接
    // 在 adbd 眼里都是一条独立的 transport，未授权的密钥会为**每一条** transport
    // 单独弹一次「允许 USB 调试吗」。一次首屏加载要跑 6~8 条命令，于是用户会
    // 连续看到好几次授权框。
    //
    // 改成复用后：一次授权（勾选「一律允许」后永久有效），之后不再弹框。
    // 串行由 lock 保证；读命令在「流还没开起来」时自动重连重试一次，
    // 写命令不重试，避免 pm disable-user 被执行两遍。

    private AdbClient conn;
    private final Object lock = new Object();

    private AdbClient conn() throws Exception {
        if (conn != null && conn.isConnected()) return conn;
        if (conn != null) { conn.close(); conn = null; }
        AdbClient c = new AdbClient(log);
        c.applyKey(key);
        c.connect(HOST, PORT);
        conn = c;
        if (DEBUG) Log.i(TAG, "新建 adbd 连接，后续命令复用这条 transport");
        return c;
    }

    private void drop() {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }

    private String sh(String cmd) throws Exception {
        return sh(cmd, 30000, false);
    }

    /** 编译类命令可能耗时数十秒，需要单独放宽读超时。 */
    private String sh(String cmd, int timeoutMs) throws Exception {
        return sh(cmd, timeoutMs, false);
    }

    /**
     * @param retry 仅对幂等的读命令置 true：连接断了会重连再跑一次。
     *              写命令绝不能重试，否则可能重复执行。
     */
    private String sh(String cmd, int timeoutMs, boolean retry) throws Exception {
        synchronized (lock) {
            try {
                return exec(cmd, timeoutMs, false);
            } catch (AdbClient.AuthPendingException e) {
                // 手机上还挂着授权框，重试只会再弹一个，直接抛给上层提示用户
                drop();
                throw e;
            } catch (AdbClient.StreamNotOpenedException e) {
                // 流没开起来就断了 = 命令从未执行，任何命令重试都安全
                drop();
                Log.w(TAG, "连接失效，重连后重试: " + cmd);
                return exec(cmd, timeoutMs, false);
            } catch (Exception e) {
                drop();
                if (!retry) throw e;
                return exec(cmd, timeoutMs, false);
            }
        }
    }

    /**
     * 大输出命令走 raw（管道）模式。
     *
     * PTY 下如果本端提前放弃读取，设备上的子进程会永久卡在 n_tty_write，
     * 留下无法回收的 dumpsys；攒几条之后 adbd 就不再回应新的 OPEN，
     * 表现为「刷新几次之后运存数据取不到」。
     */
    private String exec(String cmd, int timeoutMs, boolean raw) throws Exception {
        AdbClient c = conn();
        String out = c.shell(cmd, timeoutMs, raw);
        int nl = out.indexOf('\n');
        String head = nl > 0 ? out.substring(0, nl) : out;
        if (head.length() > 50) head = head.substring(0, 50);
        if (DEBUG) Log.i(TAG, "CMD[" + cmd + "] len=" + out.length() + " head=" + head);
        return out;
    }

    // ---------- 长任务专用连接 ----------

    /**
     * AOT 编译这类命令一跑就是几分钟到十几分钟。如果和普通命令共用同一条
     * transport + 同一把锁，编译期间「进性能调优页」「刷新列表」全部排队，
     * 界面表现为卡死。
     *
     * adbd 支持多条 transport，用同一把已授权的密钥再开一条专供长任务，
     * 普通命令立刻就能执行；密钥已在手机上「一律允许」，新连接不会再弹框。
     */
    private AdbClient longConn;
    private final Object longLock = new Object();

    private String shLong(String cmd) throws Exception {
        synchronized (longLock) {
            if (longConn == null || !longConn.isConnected()) {
                if (longConn != null) {
                    try { longConn.close(); } catch (Exception ignored) { }
                    longConn = null;
                }
                AdbClient c = new AdbClient(log);
                c.applyKey(key);
                c.connect(HOST, PORT);
                longConn = c;
            }
            return longConn.shell(cmd, 0, false);
        }
    }

    /** 读命令：断线自动重连重试一次。 */
    private String shR(String cmd) throws Exception {
        return sh(cmd, 30000, true);
    }

    /** 与 server.py 的 adb_state 对应：返回 ok / unauthorized / disconnected。 */
    public String state() {
        synchronized (lock) {
            try {
                conn();
                return "ok";
            } catch (AdbClient.AuthPendingException e) {
                drop();
                return "unauthorized";
            } catch (Exception e) {
                drop();
                return "disconnected";
            }
        }
    }

    // ---------- 取数 ----------

    /**
     * 执行可能产生大输出的命令，并做完整性校验。
     *
     * 实测：单条命令偶发被截断（如 pm list packages 返回 13824 而非 19703 字节），
     * 会导致统计严重偏差。截断时输出通常不以换行结尾，据此重试并取最长结果。
     */
    private String shStable(String cmd) throws Exception {
        String best = "";
        for (int i = 0; i < 3; i++) {
            String out = shR(cmd);
            if (out.length() > best.length()) best = out;
            if (out.endsWith("\n")) return out;
        }
        Log.w(TAG, "命令输出疑似截断，已重试: " + cmd + " len=" + best.length());
        return best;
    }

    /**
     * 取包名列表。
     *
     * 实测：`pm list packages` 的输出会被偶发截断（19703 → 13824 字节），
     * 而截断后仍以换行结尾，无法用「是否以换行结尾」判断。截断只会让集合变短，
     * 因此这里跑最多 3 次、取元素最多的那次；连续两次一致即认为完整。
     */
    private Set<String> pkgs(String... extra) throws Exception {
        StringBuilder cmd = new StringBuilder("pm list packages");
        for (String e : extra) cmd.append(' ').append(e);
        Set<String> best = new HashSet<String>();
        int lastSize = -1;
        for (int i = 0; i < 3; i++) {
            Set<String> cur = new HashSet<String>();
            for (String line : shR(cmd.toString()).split("\n")) {
                line = line.trim();
                if (line.startsWith("package:")) cur.add(line.substring(8).trim());
            }
            if (cur.size() > best.size()) best = cur;
            if (cur.size() == lastSize) break;   // 连续两次一致
            lastSize = cur.size();
        }
        return best;
    }

    /** 解析 dumpsys meminfo 的 Total PSS by process；同一应用的子进程累加。 */
    private Map<String, Long> getPss() throws Exception {
        Map<String, Long> result = new HashMap<String, Long>();
        // 用 -c（紧凑机器可读格式）。整份输出只有 ~42KB，可以整段取回后本地过滤；
        // 不再走管道：`| grep` 在 PTY 下只会回前 56 字节（实测），拿不到数据。
        String best = shR("dumpsys meminfo -c");
        for (String line : best.split("\n")) {
            line = line.trim();
            if (!line.startsWith("proc,")) continue;
            String[] f = line.split(",");
            if (f.length < 5) continue;
            String cat = f[1].trim();
            if (cat.equals("native") || cat.equals("system")) continue;
            String name = f[2].trim();
            String base = name.split(":")[0];
            if (!base.contains(".")) continue;   // 排除 system / init 等非包名进程
            long kb;
            try {
                kb = Long.parseLong(f[4].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            Long old = result.get(base);
            result.put(base, (old == null ? 0 : old) + kb);
        }
        return result;
    }

    private long[] getMemInfo() throws Exception {
        long total = 0, avail = 0;
        for (String line : shR("cat /proc/meminfo").split("\n")) {
            Matcher m = MEM_TOTAL.matcher(line);
            if (m.find()) { total = Long.parseLong(m.group(1)); continue; }
            m = MEM_AVAIL.matcher(line);
            if (m.find()) avail = Long.parseLong(m.group(1));
        }
        return new long[]{total, avail};
    }

    /**
     * 被 disable-user 归档的应用（数据保留）。
     *
     * 说明：`pm list packages -d` 也会列出系统出厂就禁用的应用（如 devicelockcontroller）。
     * 曾尝试用 `dumpsys package <pkg> | grep -m1 'enabled=3'` 精确区分，但 grep -m1
     * 提前退出会让上游 dumpsys 收到 SIGPIPE 并卡死（实测挂住整条链路），
     * 故放弃该方案，接受这一处误报 —— 代价仅为列表里多显示一个系统组件。
     */
    private Set<String> getDisabled() throws Exception {
        Set<String> s = new HashSet<String>();
        for (String line : shR("pm list packages -d --user 0").split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) s.add(line.substring(8).trim());
        }
        return s;
    }

    private Set<String> getRestricted() throws Exception {
        Set<String> s = new HashSet<String>();
        for (String raw : shR("cmd appops query-op RUN_IN_BACKGROUND ignore").split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && line.contains(".") && !line.contains(" ")) s.add(line);
        }
        return s;
    }

    /**
     * 读取系统学习到的满电容量，返回 {last, min, max}（mAh）。每次刷新都重新取。
     *
     * 注意：grep 不能加 -m1 —— 提前退出会让上游 dumpsys 收到 SIGPIPE，
     * 实测会导致 shell 挂住。让它读完整个输出更稳。
     */
    private int[] getLearnedCapacity() {
        int last = 0, min = 0, max = 0;
        try {
            String out = shR("dumpsys batterystats | grep 'learned battery capacity'");
            for (String line : out.split("\n")) {
                java.util.regex.Matcher m =
                        Pattern.compile("(\\d+)\\s*mAh").matcher(line);
                if (!m.find()) continue;
                int v = Integer.parseInt(m.group(1));
                if (line.contains("Last learned")) last = v;
                else if (line.contains("Min learned")) min = v;
                else if (line.contains("Max learned")) max = v;
            }
        } catch (Exception e) {
            Log.w(TAG, "读取满电容量失败: " + e);
        }
        return new int[]{last, min, max};
    }

    private JSONObject getBattery() throws Exception {
        JSONObject o = new JSONObject();
        Map<String, String> info = new HashMap<String, String>();
        for (String line : shR("dumpsys battery").split("\n")) {
            int i = line.indexOf(':');
            if (i > 0) info.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        int level = (int) num(info, "level", 1.0);
        int health = (int) num(info, "health", 1.0);
        double temp = Math.round(num(info, "temperature", 10.0) * 10.0) / 10.0;
        int charge = (int) num(info, "Charge counter", 1.0);
        int[] cap = getLearnedCapacity();
        int learnedLast = cap[0], learnedMin = cap[1], learnedMax = cap[2];
        // 健康度只降不升 —— 全局只认历史最低学习值 Min，没有任何回退分支：
        //   · 不用 Last：单次偏高的学习值会把结果拉高，且充满电后 Last 会刷新成当前容量
        //   · 不用 charge counter：它反映的也是「这一次」的满电容量，同样可能偏高
        // Min 不存在（新机 / 从未完整充放过）时返回 0，界面显示「—」而不是一个假数字。
        // 系统给的 Min 自己也会变：充满一次电或重启后 batterystats 会重新学习容量，
        // Min 可能被刷新成更大的值 —— 于是健康度会「涨回去」（实测 87% → 90%）。
        // 这里自己记一份历史最低，只在「明显变高」时认为换了电池/重新校准才重置。
        int capMin = learnedMin;
        if (learnedMin > 0 && appCtx != null) {
            try {
                android.content.SharedPreferences sp =
                        appCtx.getSharedPreferences("app", 0);
                int stored = sp.getInt("cap_min", 0);
                if (stored <= 0) {
                    capMin = learnedMin;
                    sp.edit().putInt("cap_min", capMin).apply();
                } else if (learnedMin < stored) {
                    capMin = learnedMin;            // 更低 → 采纳，健康度只降不升
                    sp.edit().putInt("cap_min", capMin).apply();
                } else if (learnedMin > stored * 1.15) {
                    capMin = learnedMin;            // 高出 15% 以上 → 换电池或重新校准
                    sp.edit().putInt("cap_min", capMin).apply();
                } else {
                    capMin = stored;                // 正常波动 → 沿用历史最低
                }
            } catch (Throwable ignored) { }
        }

        int healthPct = 0;
        if (capMin > 0 && BATTERY_DESIGN_MAH > 0) {
            healthPct = (int) Math.round(capMin * 100.0 / BATTERY_DESIGN_MAH);
        }
        try {
            o.put("level", level);
            o.put("health", health);
            o.put("health_text", HEALTH_TEXT.containsKey(health) ? HEALTH_TEXT.get(health) : "未知");
            o.put("health_pct", healthPct);
            o.put("temp", temp);
            o.put("charge", charge);
            o.put("learned_last", learnedLast);
            o.put("learned_min", capMin);
            o.put("learned_max", learnedMax);
        } catch (Exception ignored) { }
        return o;
    }

    private static double num(Map<String, String> info, String key, double div) {
        try {
            return Double.parseDouble(info.get(key)) / div;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 常驻通知用的精简状态：电池 + 归档数 + 限制数。 */
    public String summaryJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("battery", getBattery());
            // 归档包含两种：被 disable-user 的，以及被 uninstall --user 0 的
            Set<String> archived = new HashSet<String>(getDisabled());
            Set<String> active = pkgs("--user", "0");
            Set<String> all = pkgs("-u", "--user", "0");
            if (all.isEmpty()) all = active;
            for (String p : all) {
                if (!active.contains(p)) archived.add(p);
            }
            o.put("archived", archived.size());
            o.put("restricted", getRestricted().size());
            // 内存占用（常驻通知里显示）。cat /proc/meminfo 很轻，不影响刷新耗时
            long[] mem = getMemInfo();
            if (mem[0] > 0) {
                o.put("mem_total", mem[0]);
                o.put("mem_avail", mem[1]);
                o.put("mem_pct", Math.round((mem[0] - mem[1]) * 1000.0 / mem[0]) / 10.0);
            }
        } catch (Exception e) {
            Log.w(TAG, "summary 失败: " + e);
        }
        return o.toString();
    }

    // ---------- AOT 预编译 ----------

    /**
     * 读取某个包真实的编译状态（主 apk 的 compiler filter）。
     * 输出形如 `arm64: [status=speed] [reason=cmdline] [primary-abi]`，
     * 取值：speed / speed-profile / verify / extract / quicken / ""。
     */
    public String aotStatus(String pkg) {
        try {
            String d = shR("dumpsys package " + pkg);
            int i = d.indexOf("Dexopt state:");
            if (i < 0) return "";
            int s = d.indexOf("[status=", i);
            if (s < 0) return "";
            int e = d.indexOf(']', s);
            if (e < 0) return "";
            return d.substring(s + 8, e).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 预编译 / 取消预编译。
     *   on  : cmd package compile -m speed  <pkg>   —— 全量 AOT，大应用可达数分钟
     *   off : cmd package compile -m verify <pkg>   —— 只校验不编译，释放 oat 产物
     *
     * 三个要点：
     * 1. **不加 -f**：已经是目标 filter 时系统会直接跳过，不会重复写盘。加了 -f 每次
     *    都强制重编，白耗时间与 IO（产物是原地替换，不会翻倍占空间，但也没必要）。
     * 2. **不设超时**（timeout=0）：大应用 speed 编译可能跑十几分钟，任何固定超时
     *    都会把「其实成功了」误报成失败。连接真断了会抛 IOException，不会永远卡住。
     * 3. 成败一律以编译后的**真实 status** 为准，不解析命令输出。
     */
    /**
     * 正在编译的包名（null = 空闲）。
     *
     * 同一时刻只允许一个 dexopt。WebView 重载后前端的 aotBusy 会归零，
     * 用户再点一次就会发出第二条 compile —— 两条 dexopt 抢同一把系统锁，
     * 后发的那条会失败，表现为「明明还在编译，却又报编译失败」。
     */
    private static volatile String compilingPkg;

    public String setAot(String pkg, boolean on) {
        JSONObject o = new JSONObject();
        synchronized (AdbService.class) {
            if (compilingPkg != null) {
                try {
                    o.put("ok", false);
                    o.put("busy", true);
                    o.put("pkg", compilingPkg);
                    o.put("msg", "busy: " + compilingPkg);
                } catch (Exception ignored) { }
                return o.toString();
            }
            compilingPkg = pkg;
        }
        try {
            String before = aotStatus(pkg);
            if (on && "speed".equals(before)) {
                o.put("ok", true);
                o.put("aot", true);
                o.put("skipped", true);
                o.put("msg", "already speed");
                return o.toString();
            }
            String cmd = on
                    ? "cmd package compile -m speed " + pkg
                    : "cmd package compile -m verify " + pkg;
            String r = shLong(cmd).trim();
            String after = aotStatus(pkg);
            boolean ok = on ? "speed".equals(after) : !"speed".equals(after);
            o.put("ok", ok);
            o.put("aot", on && ok);
            o.put("status", after);
            String msg = r.trim();
            if (!ok) {
                // dex2oat 对超大应用会直接放弃，命令只回一个 "Failure"，
                // 光把这个词弹给用户没有任何信息量。这里翻译成人话。
                if (msg.isEmpty() || msg.equalsIgnoreCase("Failure")
                        || msg.startsWith("Error")) {
                    msg = "系统拒绝全量编译（dex2oat 放弃），已保持 " + after
                            + "。插件多的大应用常见，speed-profile 本身已比未编译快。";
                }
            }
            o.put("msg", ok ? msg : msg);
        } catch (Exception e) {
            try {
                o.put("ok", false);
                o.put("aot", !on);
                o.put("msg", String.valueOf(e.getMessage()));
            } catch (Exception ignored) { }
        } finally {
            synchronized (AdbService.class) {
                compilingPkg = null;
            }
        }
        return o.toString();
    }

    // ---------- 性能调优 ----------

    /** 可写的调优项：{界面键, 命名空间, 设置键} */
    private static final String[][] TUNABLE = {
            {"save", "system", "POWER_SAVE_MODE_OPEN"},
            {"balanced", "system", "POWER_BALANCED_MODE_OPEN"},
            {"gpu", "global", "GPUTUNER_SWITCH"},
            {"game", "system", "cloud_periodic_game_enable"},
    };

    private static boolean truthy(String v) {
        v = v.trim().toLowerCase();
        return "1".equals(v) || "true".equals(v);
    }

    /** 调优面板所需的全部状态：可写项 + Millet 白名单 + 只读（需 root）项。 */
    public String tuningJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("perf", getPerfMode());
            for (String[] t : TUNABLE) {
                o.put(t[0], truthy(shR("settings get " + t[1] + " " + t[2])));
            }
            String millet = shR("settings get system MILLET_NO_RESTRICT_APP").trim();
            o.put("millet_count", millet.isEmpty() || "null".equals(millet)
                    ? 0 : millet.split(",").length);
            // 这三项不是 getprop 能读到的，得从 SchedBoostService 的 dump 里取
            String sb = shR("dumpsys SchedBoostService | grep -E 'enable_rtmode|templimit'");
            String rtmode = "", tlo = "", thi = "";
            for (String line : sb.split("\n")) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim();
                String v = line.substring(c + 1).trim();
                if (k.endsWith("enable_rtmode")) rtmode = v;
                else if (k.endsWith("rt_templimit_bottom")) tlo = v;
                else if (k.endsWith("rt_templimit_ceiling")) thi = v;
            }
            JSONObject r = new JSONObject();
            r.put("rtmode", rtmode);
            r.put("templimit", (tlo.isEmpty() || thi.isEmpty()) ? "" : (tlo + "~" + thi + "℃"));
            r.put("powermode", shR("getprop persist.sys.power.default.powermode").trim());
            r.put("governor",
                    shR("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").trim());
            o.put("root", r);
        } catch (Exception e) {
            Log.w(TAG, "tuning 失败: " + e);
        }
        return o.toString();
    }

    public String setTuning(String key, boolean on) {
        try {
            if ("perf".equals(key)) {
                setPerf(on);              // 走互斥逻辑
            } else {
                for (String[] t : TUNABLE) {
                    if (t[0].equals(key)) {
                        sh("settings put " + t[1] + " " + t[2] + " " + (on ? "1" : "0"));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "setTuning 失败: " + e);
        }
        return tuningJson();
    }

    /** Millet 白名单（豁免后台限制的应用），逗号分隔的包名。 */
    public String getMillet() {
        try {
            String v = shR("settings get system MILLET_NO_RESTRICT_APP").trim();
            return "null".equals(v) ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public String setMillet(String csv) {
        try {
            sh("settings put system MILLET_NO_RESTRICT_APP '" + csv + "'");
        } catch (Exception e) {
            Log.w(TAG, "setMillet 失败: " + e);
        }
        return getMillet();
    }

    // ---------- 性能模式 ----------

    /**
     * MIUI/HyperOS 的「性能模式」开关。
     * 实测 `settings put system POWER_PERFORMANCE_MODE_OPEN 1` 可写、且能保持。
     */
    private boolean getPerfMode() {
        try {
            String v = shR("settings get system POWER_PERFORMANCE_MODE_OPEN").trim();
            return "1".equals(v);
        } catch (Exception e) {
            return false;
        }
    }

    public String setPerf(boolean on) {
        JSONObject o = new JSONObject();
        try {
            // 三个模式互斥：省电 / 均衡 / 性能，同时只能有一个为 1。
            // 只开性能而不关省电，会被省电模式压回去 —— 这是「总回退」的根因。
            if (on) {
                sh("settings put system POWER_SAVE_MODE_OPEN 0");
                sh("settings put system POWER_BALANCED_MODE_OPEN 0");
                sh("settings put system POWER_PERFORMANCE_MODE_OPEN 1");
            } else {
                sh("settings put system POWER_PERFORMANCE_MODE_OPEN 0");
                sh("settings put system POWER_BALANCED_MODE_OPEN 1");
            }
            o.put("on", getPerfMode());
            o.put("adb_ok", true);
        } catch (Exception e) {
            try {
                o.put("on", false);
                o.put("adb_ok", false);
                o.put("adb_msg", String.valueOf(e.getMessage()));
            } catch (Exception ignored) { }
        }
        return o.toString();
    }

    // ---------- 主接口 ----------

    public String listJson(boolean withPss) {
        String st = state();
        if (!"ok".equals(st)) return errorPayload(st, withPss);
        try {
            return buildList(withPss);
        } catch (Exception e) {
            Log.w(TAG, "取数失败: " + e);
            return errorPayload("error", withPss);
        }
    }

    /**
     * 一次取出全部「包名 → 应用显示名」。
     *
     * 以前只查 AppNames.MAP（一张手写的 61 条表），表里没有的包一律回落到包名，
     * 于是「抖音极速版」这类应用看到的是 com.ss.android.ugc.aweme.lite。
     *
     * 改成问系统要真实 label：getInstalledApplications 一次 IPC 拿全表，
     * 比逐包 getApplicationInfo 快得多（600 个包是 1 次调用而不是 600 次）。
     * 依赖 manifest 里的 QUERY_ALL_PACKAGES —— 没有它，Android 11+ 只返回
     * 与自身有交互的少数包，大部分应用仍然拿不到名字。
     */
    private Map<String, String> labelMap() {
        Map<String, String> m = new HashMap<String, String>();
        if (appCtx == null) return m;
        try {
            android.content.pm.PackageManager pm = appCtx.getPackageManager();
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                try {
                    CharSequence cs = ai.loadLabel(pm);
                    if (cs == null) continue;
                    String s = cs.toString().trim();
                    // 少数应用把 label 写成包名或空串，这种交给兜底逻辑
                    if (s.isEmpty() || s.equals(ai.packageName)) continue;
                    m.put(ai.packageName, s);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable e) {
            Log.w(TAG, "取应用名失败: " + e);
        }
        return m;
    }

    private String buildList(boolean withPss) throws Exception {

        Set<String> active = pkgs("--user", "0");
        Set<String> all = pkgs("-u", "--user", "0");
        if (all.isEmpty()) all = active;
        Set<String> system = pkgs("-s");
        Set<String> disabled = getDisabled();
        if (DEBUG) Log.i(TAG, "active=" + active.size() + " all=" + all.size()
                + " system=" + system.size() + " restricted=" + getRestricted().size());
        Map<String, Long> pss = withPss ? getPss() : Collections.<String, Long>emptyMap();
        if (DEBUG) Log.i(TAG, "pss 条目数=" + pss.size());
        Set<String> restricted = getRestricted();
        Map<String, String> labels = labelMap();

        List<JSONObject> apps = new ArrayList<JSONObject>();
        List<String> sortedPkgs = new ArrayList<String>(all);
        Collections.sort(sortedPkgs);
        for (String pkg : sortedPkgs) {
            JSONObject a = new JSONObject();
            try {
                String label = labels.get(pkg);
                if (label == null) label = AppNames.MAP.containsKey(pkg) ? AppNames.MAP.get(pkg) : pkg;
                a.put("pkg", pkg);
                a.put("label", label);
                // 两种「归档」状态都要认：新方式 disable-user（数据保留），
                // 旧方式 uninstall --user 0（仅系统应用可 install-existing 恢复）
                a.put("frozen", disabled.contains(pkg) || !active.contains(pkg));
                a.put("system", system.contains(pkg));
                Long v = pss.get(pkg);
                a.put("pss", v == null ? 0 : v);
                a.put("restricted", restricted.contains(pkg));
            } catch (Exception ignored) { }
            apps.add(a);
        }

        final Map<String, JSONObject> byPkg = new HashMap<String, JSONObject>();
        for (JSONObject a : apps) byPkg.put(a.optString("pkg"), a);
        Collections.sort(apps, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                int rx = rank(x), ry = rank(y);
                if (rx != ry) return rx - ry;
                String lx = x.optString("label"), ly = y.optString("label");
                boolean namedX = !lx.equals(x.optString("pkg"));
                boolean namedY = !ly.equals(y.optString("pkg"));
                if (namedX != namedY) return namedX ? -1 : 1;
                boolean sysX = x.optBoolean("system"), sysY = y.optBoolean("system");
                if (sysX != sysY) return sysX ? 1 : -1;
                return lx.toLowerCase().compareTo(ly.toLowerCase());
            }
        });

        JSONArray arr = new JSONArray();
        int frozen = 0, restrictedCount = 0;
        for (JSONObject a : apps) {
            arr.put(a);
            if (a.optBoolean("frozen")) frozen++;
            if (a.optBoolean("restricted")) restrictedCount++;
        }

        JSONObject out = new JSONObject();
        try {
            out.put("apps", arr);
            out.put("active", active.size());
            out.put("total", all.size());
            out.put("frozen", frozen);
            out.put("restricted_count", restrictedCount);
            out.put("with_pss", withPss);
            out.put("battery", getBattery());
            out.put("adb_ok", true);
            out.put("adb_state", "ok");
            out.put("adb_msg", "");
            out.put("perf", getPerfMode());
            if (withPss) {
                long[] mem = getMemInfo();
                out.put("mem_total", mem[0]);
                out.put("mem_avail", mem[1]);
                out.put("mem_pct", mem[0] > 0
                        ? Math.round((mem[0] - mem[1]) * 1000.0 / mem[0]) / 10.0 : 0);
            }
        } catch (Exception ignored) { }
        return out.toString();
    }

    private static int rank(JSONObject a) {
        if (a.optBoolean("frozen")) return 0;
        if (a.optBoolean("restricted")) return 1;
        return 2;
    }

    private String errorPayload(String st, boolean withPss) {
        JSONObject out = new JSONObject();
        try {
            out.put("apps", new JSONArray());
            out.put("active", 0);
            out.put("total", 0);
            out.put("frozen", 0);
            out.put("restricted_count", 0);
            out.put("with_pss", withPss);
            out.put("battery", JSONObject.NULL);
            out.put("adb_ok", false);
            out.put("adb_state", st);
            out.put("adb_msg", "");
        } catch (Exception ignored) { }
        return out.toString();
    }

    /** action: freeze / unfreeze / restrict / unrestrict */
    public String batch(String pkgsJson, String action) {
        String st = state();
        JSONObject out = new JSONObject();
        JSONArray fail = new JSONArray();
        int ok = 0;
        List<String> items = new ArrayList<String>();
        try {
            JSONArray in = new JSONArray(pkgsJson);
            for (int i = 0; i < in.length(); i++) items.add(in.getString(i));
        } catch (Exception ignored) { }

        if (!"ok".equals(st)) {
            try {
                out.put("ok", 0);
                for (String p : items) fail.put(p);
                out.put("fail", fail);
                out.put("adb_ok", false);
                out.put("adb_state", st);
                out.put("adb_msg", "");
            } catch (Exception ignored) { }
            return out.toString();
        }

        // 释放需要区分「被禁用」与「已卸载当前用户」两种归档方式，
        // 因此先各取一次全集，避免逐个应用重复查询。
        Set<String> active = null, disabled = null;
        if ("unfreeze".equals(action)) {
            try {
                active = pkgs("--user", "0");
                disabled = getDisabled();
            } catch (Exception e) {
                Log.w(TAG, "读取状态失败: " + e);
            }
        }

        for (String p : items) {
            if (doOne(p, action, active, disabled)) ok++;
            else fail.put(p);
        }
        try {
            out.put("ok", ok);
            out.put("fail", fail);
            out.put("adb_ok", true);
            out.put("adb_state", "ok");
            out.put("adb_msg", "");
        } catch (Exception ignored) { }
        return out.toString();
    }

    private boolean doOne(String p, String action, Set<String> active, Set<String> disabled) {
        try {
            if ("freeze".equals(action)) {
                // 优先 disable-user：只改启用状态，APK 与用户数据完整保留。
                // 不能直接用 uninstall --user 0 —— 对用户安装的应用等于彻底卸载，
                // 数据会丢且 install-existing 无法恢复（已实测）。
                String r = sh("pm disable-user --user 0 " + p).toLowerCase();
                if (r.contains("disabled") || r.contains("success")) return true;
                // MIUI/HyperOS 拒绝禁用系统应用（Cannot disable system packages），
                // 退回卸载当前用户：APK 在系统分区不受影响、可由「释放」恢复，
                // 但用户数据可能丢失 —— UI 已就此提示。
                r = sh("pm uninstall --user 0 " + p).toLowerCase();
                return r.contains("success");
            }
            if ("unfreeze".equals(action)) {
                // 注意：对「已卸载当前用户」的应用，pm enable 会返回
                // "new state: enabled" 却并不真正恢复 —— 必须按状态选命令。
                if (disabled != null && disabled.contains(p)) {
                    String r = sh("pm enable " + p).toLowerCase();
                    return r.contains("enabled") || r.contains("success");
                }
                if (active != null && !active.contains(p)) {
                    // 系统应用走 uninstall --user 0 归档的，只能用 install-existing 恢复
                    String r = sh("cmd package install-existing " + p).toLowerCase();
                    return r.contains("installed for user") || r.contains("success");
                }
                return true;   // 本来就在启用状态
            }
            if ("restrict".equals(action)) {
                sh("cmd appops set " + p + " RUN_IN_BACKGROUND ignore");
                sh("am set-standby-bucket " + p + " restricted");
                return sh("cmd appops get " + p + " RUN_IN_BACKGROUND").contains("ignore");
            }
            if ("unrestrict".equals(action)) {
                sh("cmd appops set " + p + " RUN_IN_BACKGROUND allow");
                sh("am set-standby-bucket " + p + " rare");
                return sh("cmd appops get " + p + " RUN_IN_BACKGROUND").contains("allow");
            }
        } catch (Exception e) {
            Log.w(TAG, "操作失败 " + p + ": " + e);
        }
        return false;
    }

    public void close() {
        // 每条命令独立连接，无需在此释放
    }
}
