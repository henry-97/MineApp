package io.github.mineapp.ext.hook;

import java.io.IOException;

/** 替代一条 adbd transport 的命令通道。每个 AdbClient 实例对应一个会话。 */
interface ShellSession {

    /**
     * 执行一条命令，返回 stdout+stderr（与 adb shell v1 一致，两者合并）。
     *
     * @param idleTimeoutMs 无输出超时（对齐 AdbClient 的 socket 读超时语义），<= 0 表示不限
     * @throws NotStartedException 命令确定没有开始执行（调用方可安全重试）
     * @throws IOException         其他失败；会话随即不可用
     */
    String exec(String cmd, int idleTimeoutMs) throws IOException;

    boolean alive();

    void close();

    /** 用于状态展示："root" / "shizuku" */
    String name();

    class NotStartedException extends IOException {
        NotStartedException(String msg) { super(msg); }
    }
}
