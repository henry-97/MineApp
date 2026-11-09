package io.github.mineapp.ext.hook;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.github.mineapp.ext.Const;

/**
 * 桌面快捷方式点进来的 MainActivity 实例：不加载 WebView，透明窗口，
 * 解冻 → 拉起目标应用 → 自己退出。
 */
final class ShortcutLauncher {
    private ShortcutLauncher() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static boolean isOurs(Intent it) {
        if (it == null) return false;
        return Const.ACTION_LAUNCH_FROZEN.equals(it.getAction())
                || Const.ACTION_PICKER.equals(it.getAction());
    }

    /** 在 Activity.onCreate（父类）已执行之后调用。 */
    static void handle(final Activity a, Intent it) {
        if (Build.VERSION.SDK_INT >= 30) a.setTranslucent(true);
        a.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        a.getWindow().setDimAmount(0f);
        Env.setApp(a);
        AutoFreeze.ensure(a);

        if (Const.ACTION_PICKER.equals(it.getAction())) {
            Picker.show(a);
            return;
        }
        open(a, it.getStringExtra(Const.EXTRA_PKG));
    }

    /** 解冻（如果冻着）→ 拉起目标应用 → 收掉这个透明 Activity。 */
    static void open(final Activity a, final String pkg) {
        final PackageManager pm = a.getPackageManager();
        final ApplicationInfo ai = pkg == null ? null : FrozenApps.info(pm, pkg);
        if (ai == null) {
            done(a, zh() ? "应用已不存在" : "App not found");
            return;
        }
        final String label = FrozenApps.label(pm, ai);
        if (!FrozenApps.isFrozen(pm, ai)) {
            launch(a, pkg, label, 0);
            return;
        }
        Toast.makeText(a, (zh() ? "正在解冻 " : "Unfreezing ") + label, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            try {
                List<String> ok = AppOps.batch(a, "unfreeze", Collections.singletonList(pkg));
                if (!ok.contains(pkg)) err = zh() ? "解冻失败" : "Unfreeze failed";
            } catch (Throwable t) {
                Env.log("shortcut unfreeze " + pkg, t);
                err = (zh() ? "解冻失败：执行通道不可用，请打开 Mine App 检查" : "Unfreeze failed: shell channel unavailable")
                        + " (" + t.getMessage() + ")";
            }
            final String e = err;
            MAIN.post(() -> {
                if (e != null) {
                    done(a, e);
                } else {
                    AutoFreeze.onUnfrozen(a, pkg);
                    launch(a, pkg, label, 0);
                }
            });
        }, "mineext-unfreeze").start();
    }

    /**
     * 先让执行通道（shell 权限，不受「后台启动 Activity」限制）去拉起，应用内 startActivity 兜底。
     *
     * 只用 startActivity 的话，透明的空白页发起的启动在 Android 12+ / MIUI / HyperOS 上会被静默拦下
     * （既不抛异常也没有界面）；而且刚解冻时本进程的 PackageManager 还可能查不到启动入口。
     */
    private static void launch(final Activity a, final String pkg, final String label, final int attempt) {
        if (a.isFinishing() || a.isDestroyed()) {
            Env.log("shortcut: activity already finishing, cannot launch " + pkg);
            return;
        }
        final Intent li = a.getPackageManager().getLaunchIntentForPackage(pkg);
        // 查不到入口时先等一下 PackageManager 刷新；shell 的 monkey 不依赖这个，所以只等 1 秒
        if (li == null && attempt < 5) {
            MAIN.postDelayed(() -> launch(a, pkg, label, attempt + 1), 200);
            return;
        }
        new Thread(() -> {
            final boolean viaShell = startViaShell(a, pkg, li);
            MAIN.post(() -> {
                if (viaShell) {
                    Env.log("shortcut: launched " + pkg + " via shell");
                    doneLater(a);
                    return;
                }
                if (li != null) {
                    try {
                        a.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        Env.log("shortcut: launched " + pkg + " via startActivity");
                        doneLater(a);
                        return;
                    } catch (Throwable t) {
                        Env.log("shortcut: startActivity failed for " + pkg, t);
                    }
                }
                done(a, (zh() ? "已解冻，但无法打开 " : "Unfrozen, but cannot open ") + label);
            });
        }, "mineext-launch").start();
    }

    private static boolean startViaShell(Activity a, String pkg, Intent li) {
        String cmd;
        if (li != null && li.getComponent() != null) {
            cmd = "am start --user 0 -n '" + li.getComponent().flattenToString() + "'";
        } else {
            cmd = "monkey -p '" + pkg + "' -c android.intent.category.LAUNCHER 1";
        }
        String out = AppOps.shell(a, cmd, 15000);
        Env.log("shortcut: `" + cmd + "` -> " + out.trim());
        if (out.isEmpty() || out.contains("Error") || out.contains("Exception")
                || out.contains("No activities found")) {
            return false;
        }
        return out.contains("Starting:") || out.contains("Events injected: 1");
    }

    /** 目标应用的任务建立之前就移除自己的任务，部分系统会把刚发起的启动一起取消，所以稍等再收尾。 */
    static void doneLater(final Activity a) {
        MAIN.postDelayed(() -> done(a, null), 600);
    }

    static void done(Activity a, String msg) {
        if (msg != null) Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
        if (a.isFinishing() || a.isDestroyed()) return;
        a.finishAndRemoveTask();
        a.overridePendingTransition(0, 0);
    }

    static boolean zh() {
        return Locale.getDefault().getLanguage().startsWith("zh");
    }
}
