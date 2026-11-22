package com.henry.appmanager;

/**
 * 经典版 stub：不含 root 通道实现。
 *
 * 由 build.sh 在 main / mig1 构建时替换真实现（真实现只在多通道版编译）。
 * 所有能力恒为 false —— 经典版的 Root/Shizuku 不只是「运行时禁用」，
 * 而是根本不带这段代码，减少体积与安全扫描误报。
 */
final class RootShell {

    /** 保留字段以兼容 Bridge 的赋值语句（经典版恒为 false） */
    static volatile boolean enabled = false;

    private RootShell() { }

    static boolean available() {
        return false;
    }

    static boolean active() {
        return false;
    }

    static String run(String cmd, int timeoutMs) throws Exception {
        throw new UnsupportedOperationException("classic build: root channel disabled");
    }
}
