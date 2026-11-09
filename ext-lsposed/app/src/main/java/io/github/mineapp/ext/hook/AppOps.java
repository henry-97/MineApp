package io.github.mineapp.ext.hook;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 直接调用 Mine App 自己的 AdbService.batch() 冻结 / 解冻。
 *
 * 不自己拼 pm 命令：Mine App 区分「disable-user」和「uninstall --user 0」两种归档方式，
 * 解冻时要按状态选命令，复用它的实现最稳；命令照样走当前执行通道（ADB / Root / Shizuku）。
 */
final class AppOps {
    private AppOps() { }

    static volatile ClassLoader targetCl;

    /** 区分本模块发起的 batch 与用户在 Mine App 界面里的手动操作 */
    private static final ThreadLocal<Boolean> INTERNAL = new ThreadLocal<>();

    static boolean internal() {
        return Boolean.TRUE.equals(INTERNAL.get());
    }

    private static Object service(Context ctx) throws Exception {
        ClassLoader cl = targetCl;
        Class<?> svc = cl.loadClass("com.henry.appmanager.AdbService");
        Class<?> logI = cl.loadClass("com.henry.appmanager.AdbClient$Log");
        Object log = Proxy.newProxyInstance(cl, new Class<?>[]{logI}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == a[0];
                    default: return "MineExtLog";
                }
            }
            return null;
        });
        // shared() 已有实例就直接复用，与 Mine App 界面、常驻服务共享同一组连接
        Method shared = svc.getMethod("shared", Context.class, logI);
        return shared.invoke(null, ctx, log);
    }

    /** 走当前执行通道跑一条 shell 命令；失败（含通道不可用）返回空串。 */
    static String shell(Context ctx, String cmd, int timeoutMs) {
        try {
            Object s = service(ctx);
            String out = (String) s.getClass().getMethod("shellQuick", String.class, int.class)
                    .invoke(s, cmd, timeoutMs);
            return out == null ? "" : out;
        } catch (Throwable t) {
            Env.log("shell failed: " + cmd, t);
            return "";
        }
    }

    /**
     * @param action "freeze" / "unfreeze"
     * @return 成功的包名
     */
    static List<String> batch(Context ctx, String action, Collection<String> pkgs) throws Exception {
        List<String> ok = new ArrayList<>();
        if (pkgs.isEmpty()) return ok;
        Object s = service(ctx);
        String json;
        INTERNAL.set(Boolean.TRUE);
        try {
            json = (String) s.getClass().getMethod("batch", String.class, String.class)
                    .invoke(s, new JSONArray(pkgs).toString(), action);
        } finally {
            INTERNAL.remove();
        }
        JSONObject r = new JSONObject(json);
        if (!r.optBoolean("adb_ok", false)) {
            throw new IllegalStateException("channel " + r.optString("adb_state", "unavailable"));
        }
        JSONArray fail = r.optJSONArray("fail");
        List<String> failed = new ArrayList<>();
        if (fail != null) for (int i = 0; i < fail.length(); i++) failed.add(fail.optString(i));
        for (String p : pkgs) if (!failed.contains(p)) ok.add(p);
        return ok;
    }
}
