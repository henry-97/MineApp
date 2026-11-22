package com.henry.appmanager;

/**
 * 经典版 stub：不含 Shizuku 通道实现。
 *
 * 由 build.sh 在 main / mig1 构建时替换真实现。
 * 所有能力恒为 false，不引用任何 moe.shizuku / rikka.shizuku 的类，
 * 因此经典版的 dex 里不会再有 Shizuku 的客户端代码与 provider 依赖。
 */
final class ShizukuShell {

    /** 保留字段以兼容 Bridge 的赋值语句（经典版恒为 false） */
    static volatile boolean enabled = false;

    /** 权限请求码，Bridge 里会传，但经典版不会真的发请求 */
    static final int REQ_CODE = 0x5A1;

    private ShizukuShell() { }

    static boolean alive() {
        return false;
    }

    static boolean granted() {
        return false;
    }

    static boolean waitAlive(long maxMs) {
        return false;
    }

    static boolean waitBinderOnce(long maxMs) {
        return false;
    }

    static boolean active() {
        return false;
    }

    static String run(String cmd, int timeoutMs) throws Exception {
        throw new UnsupportedOperationException("classic build: shizuku channel disabled");
    }
}
