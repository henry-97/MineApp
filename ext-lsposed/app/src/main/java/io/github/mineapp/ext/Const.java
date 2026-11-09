package io.github.mineapp.ext;

/** 模块 App 与注入端（Mine App 进程）共用的常量。 */
public final class Const {
    private Const() { }

    public static final String TARGET_PKG = "com.henry.appmanager";
    public static final String MODULE_PKG = BuildConfig.APPLICATION_ID;

    /** 模块 App 里交出 Shizuku binder 的 provider */
    public static final String BINDER_AUTHORITY = MODULE_PKG + ".binder";
    public static final String METHOD_GET_BINDER = "getBinder";
    public static final String KEY_BINDER = "binder";

    /** 桌面快捷方式：显式指向 Mine App 的 MainActivity，由注入端拦截 */
    public static final String TARGET_MAIN = TARGET_PKG + ".MainActivity";
    public static final String ACTION_LAUNCH_FROZEN = "io.github.mineapp.ext.LAUNCH_FROZEN";
    /** 桌面上的「冻结应用」弹窗入口：列出全部已冻结应用，可搜索、点击即解冻并打开 */
    public static final String ACTION_PICKER = "io.github.mineapp.ext.PICK_FROZEN";
    public static final String SHORTCUT_PICKER_ID = "mx__picker";
    public static final String EXTRA_PKG = "pkg";
    public static final String SHORTCUT_PREFIX = "mx_";

    /** Shizuku 状态 */
    public static final int SHIZUKU_UNREACHABLE = -1;   // 拿不到 binder 来源：模块 App 被禁用 / 卸载
    public static final int SHIZUKU_NOT_RUNNING = 0;
    public static final int SHIZUKU_NO_PERMISSION = 1;
    public static final int SHIZUKU_DENIED = 2;          // 用户在授权框里点了拒绝（Shizuku 会永久记住）
    public static final int SHIZUKU_GRANTED = 3;
    public static final int SHIZUKU_TOO_OLD = 4;         // 服务端低于 v11
}
