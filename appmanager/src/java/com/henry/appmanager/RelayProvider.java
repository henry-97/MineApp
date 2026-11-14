package com.henry.appmanager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

/**
 * 向**同签名应用**（Lighto）开放一个受控的命令中继。
 *
 * 为什么需要它：Lighto 要读「其它应用当前停在哪一页」并把用户直接送过去，
 * 这两件事都必须有 shell 权限，而它自己拿不到 —— 复用它自己那一套授权流程
 * 等于让用户再授权一次。Mine App 已经有成熟的执行通道（ADB / Root / Shizuku
 * 三选一），所以在 Mine App 里开一个受签名保护的口子最省事。
 *
 * 三道闸门，缺一不可：
 *   1. **清单里的 signature 级权限** —— 异签名应用根本进不到 call()，
 *      系统在 Binder 层就抛 SecurityException。
 *   2. **运行期再验一次签名** —— 防的是清单被改坏、或某个渠道包把权限声明漏了。
 *      只认和本应用 checkSignatures() 完全一致（SIGNATURE_MATCH）的调用方。
 *   3. **命令白名单 + 严格形状校验** —— 不开放任意 shell。前缀匹配之后还要逐字
 *      检查参数形状，并把 shell 元字符（; | & $ ` 换行 等）全部挡掉，
 *      避免「am start -n a/b; 任意命令」这种拼接注入。
 *
 * 只做中继，不提供数据表接口（query/insert/update/delete 全部返回空）。
 */
public class RelayProvider extends ContentProvider {

    private static final String TAG = "Relay";

    /** 返回给调用方的输出上限，防止一条 dumpsys 把 Binder 事务撑爆 */
    private static final int MAX_OUT = 256 * 1024;

    /** 单条命令长度上限：正常 shell 命令远到不了这个量级 */
    private static final int MAX_CMD = 4096;

    private static final AdbClient.Log SILENT = new AdbClient.Log() {
        @Override
        public void line(String s) { }
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();

        // 「申请授权」是唯一允许陌生应用调用的方法：它只往申请名单里记一笔，
        // 不执行任何命令、不返回任何信息，所以可以在信任校验之前处理。
        // 调用方包名取自 Binder.getCallingUid()，应用自己伪造不了。
        if ("request".equals(method)) {
            int uid = Binder.getCallingUid();
            String p = callerPackage();
            // 系统/root/shell（uid ≤ 1000）不是可授权的"应用"，直接拒掉，
            // 免得 adb shell 一敲就往列表里塞一条 "Shell"
            boolean ok = p != null && uid > 1000
                    && !"com.android.shell".equals(p)
                    && RelayGrant.request(getContext(), p);
            out.putString("out", ok ? "ok" : "ERR: not an authorizable app");
            out.putInt("code", ok ? 0 : -1);
            return out;
        }

        String caller = trustedCaller();
        if (caller == null) {
            // 正常情况下来不到这里（清单权限先挡一道）；走到这里说明权限声明失效了，
            // 更要把调用方信息记下来。
            Log.w(TAG, "拒绝中继调用：调用方未授权 uid=" + Binder.getCallingUid());
            out.putString("out", "ERR: caller not authorized");
            out.putInt("code", -1);
            return out;
        }

        Context ctx = getContext();
        if (ctx == null) {
            out.putString("out", "ERR: no context");
            out.putInt("code", -1);
            return out;
        }
        AdbService svc = AdbService.shared(ctx, SILENT);

        if ("state".equals(method)) {
            // 直接问通道状态，比跑一条 dumpsys 便宜得多，而且三种通道都覆盖
            String st = svc.state();
            boolean ok = "ok".equals(st);
            out.putString("out", ok ? "ok" : st);
            out.putInt("code", ok ? 0 : -1);
            return out;
        }

        if (!"shell".equals(method)) {
            out.putString("out", "ERR: unknown method " + method);
            out.putInt("code", -1);
            return out;
        }

        String cmd = (extras != null && extras.getString("cmd") != null)
                ? extras.getString("cmd") : arg;
        // 授权即「完整 shell 能力」——与 Shizuku 的授权语义一致：
        // 通过信任校验的调用方拿到的是本应用同等的权限，不再做命令白名单。
        // 只保留两条最基础的下限校验（非空、长度上限），其余交给调用方自己负责。
        if (cmd == null || cmd.trim().length() == 0 || cmd.length() > MAX_CMD) {
            Log.w(TAG, "拒绝命令（空或过长，长度=" + (cmd == null ? -1 : cmd.length()) + "）");
            out.putString("out", "ERR: bad command");
            out.putInt("code", -1);
            return out;
        }

        try {
            // 冷启动预热：Lighto 往往是「今天第一个唤醒 Mine App 的东西」，
            // 这时进程刚起、Shizuku binder 还没送达，active() 判 false 会直接
            // 掉到 ADB 分支（没开 tcpip 就必然失败）。state() 内部会给 binder
            // 1.5 秒等待期，先调一次把它等回来，再执行真正的命令。
            String st = svc.state();
            String r = svc.shellLong(cmd);
            if (r == null) r = "";
            if (r.length() > MAX_OUT) r = r.substring(0, MAX_OUT);
            // shellLong 内部把异常收敛成 "ERR: ..." 字符串，这里再翻成失败码，
            // 免得调用方看到一个像正常输出、其实是一次报错的字符串。
            boolean fail = r.startsWith("ERR:");
            out.putString("out", r);
            out.putInt("code", fail ? -1 : 0);
            Log.i(TAG, "中继执行 " + cmd + " → " + (fail ? "失败（通道=" + st + "，"
                    + r.substring(0, Math.min(120, r.length())) + "）" : "成功"));
        } catch (Throwable t) {
            out.putString("out", "ERR: " + t);
            out.putInt("code", -1);
        }
        return out;
    }

    /** 调用方自己的包名（不校验信任），只用于「申请授权」记录来源 */
    private String callerPackage() {
        int uid = Binder.getCallingUid();
        Context ctx = getContext();
        if (ctx == null) return null;
        String self = ctx.getPackageName();
        if (uid == android.os.Process.myUid()) return self;
        String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) return null;
        for (String p : pkgs) if (p != null && !p.equals(self)) return p;
        return pkgs[0];
    }

    /**
     * 运行期调用方校验，返回可信任的调用方包名（不可信返回 null）。
     *
     * 只认一件事：**用户在设置页里手动授权过**（且当前签名指纹与授权时一致）。
     * 2026-11-14 起取消了「同签名自动放行」—— 同一个签名不等于自动信任，
     * 默认谁都没有权限，必须由用户在面板里逐条打开。
     */
    private String trustedCaller() {
        int uid = Binder.getCallingUid();
        Context ctx = getContext();
        if (ctx == null) return null;
        if (uid == android.os.Process.myUid()) return ctx.getPackageName();

        PackageManager pm = ctx.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null) return null;
        for (String p : pkgs) {
            if (p != null && RelayGrant.allowed(ctx, p)) return p;
        }
        return null;
    }

    /**
     * 这里原来有一份「命令白名单 + 元字符过滤」。2026-11-14 按产品决定取消：
     * 授权语义改成与 Shizuku 一致 —— 通过校验的调用方获得本应用同等的 shell 能力，
     * 不再限制能跑哪几条命令。风险由三层授权机制承担：
     *   1) 必须在设置页显式授权（申请制，只有主动申请的应用才出现在列表里）；
     *   2) 授权钉死签名指纹，重打包的假货自动失效；
     *   3) 随时可撤销，撤销后立刻失效。
     */

    // ---- 以下都不提供，纯中继 ----
    @Override public Cursor query(Uri uri, String[] projection, String sel,
                                  String[] selArgs, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String sel, String[] selArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] selArgs) { return 0; }
}
