package io.github.mineapp.ext.hook;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 把 Mine App 的 AdbClient 换成 Root / Shizuku 通道。
 *
 * AdbService 的普通连接、长任务连接、state() 探活全都只经过 AdbClient 的
 * connect / shell / isConnected / close 四个方法，所以只 hook 这四个：
 * connect 时按用户选择建一个 ShellSession 挂在这个 AdbClient 实例上，
 * 之后 shell() 直接走会话，不再碰 socket。上层的锁、重试、超时逻辑原样生效。
 *
 * 全部用 PASSTHROUGH：PROTECTIVE 模式下 hooker 抛异常会「当作没 hook」继续跑原方法，
 * 而原 shell() 在没建 socket 的实例上只会 NPE。我们需要把 IOException 原样交给 AdbService。
 */
final class Transport {
    private Transport() { }

    private static final Map<Object, ShellSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Object> CLIENTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** 最近一次 connect 实际用上的通道：adb / root / shizuku / adb-fallback / none */
    static volatile String active = "none";
    static volatile String lastError = "";
    static volatile boolean installed;

    private static Method closeMethod;
    private static Constructor<?> notOpenedCtor;

    static boolean install(XposedModule m, ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.henry.appmanager.AdbClient");
            Method connect = c.getDeclaredMethod("connect", String.class, int.class);
            Method shell = c.getDeclaredMethod("shell", String.class, int.class, boolean.class);
            Method isConnected = c.getDeclaredMethod("isConnected");
            closeMethod = c.getDeclaredMethod("close");
            try {
                notOpenedCtor = cl.loadClass("com.henry.appmanager.AdbClient$StreamNotOpenedException")
                        .getConstructor(String.class);
            } catch (Throwable ignored) {
                // 老版本没有这个异常：退化成普通 IOException，只是少一次安全重试
            }

            m.hook(connect).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Object self = chain.getThisObject();
                CLIENTS.add(self);
                drop(self);
                String mode = Env.mode();
                if (Env.MODE_ADB.equals(mode)) {
                    active = Env.MODE_ADB;
                    return chain.proceed();
                }
                try {
                    ShellSession s = Env.MODE_ROOT.equals(mode)
                            ? PersistentShell.openRoot() : ShizukuClient.openShell();
                    SESSIONS.put(self, s);
                    active = s.name();
                    lastError = "";
                    return null;
                } catch (Throwable t) {
                    lastError = t.getMessage() == null ? t.toString() : t.getMessage();
                    Env.log(mode + " unavailable: " + lastError);
                    if (!Env.fallback()) {
                        active = "none";
                        throw new IOException(mode + " unavailable: " + lastError);
                    }
                    active = "adb-fallback";
                    return chain.proceed();
                }
            });

            m.hook(shell).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Object self = chain.getThisObject();
                ShellSession s = SESSIONS.get(self);
                if (s == null) return chain.proceed();
                String cmd = (String) chain.getArg(0);
                int timeout = (Integer) chain.getArg(1);
                try {
                    return s.exec(cmd, timeout);
                } catch (ShellSession.NotStartedException e) {
                    drop(self);
                    throw notOpened(e.getMessage());
                } catch (IOException e) {
                    drop(self);
                    throw e;
                } catch (Throwable t) {
                    drop(self);
                    throw new IOException(t.toString(), t);
                }
            });

            m.hook(isConnected).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Object self = chain.getThisObject();
                ShellSession s = SESSIONS.get(self);
                if (s == null) return chain.proceed();
                if (s.alive()) return Boolean.TRUE;
                drop(self);
                return Boolean.FALSE;
            });

            m.hook(closeMethod).intercept(chain -> {
                drop(chain.getThisObject());
                return chain.proceed();
            });

            // isConnected() / close() 这种小方法在 AOT（Mine App 自己就推荐 speed 编译）后
            // 很可能被内联进调用方，hook 就被绕过了。把调用方全部退回解释执行。
            int deopt = 0;
            for (String name : new String[]{
                    "com.henry.appmanager.AdbService", "com.henry.appmanager.AdbClient"}) {
                for (Method x : cl.loadClass(name).getDeclaredMethods()) {
                    if (m.deoptimize(x)) deopt++;
                }
            }

            installed = true;
            Env.log("deoptimized " + deopt + " caller methods");
            Env.log("transport hooks installed");
            return true;
        } catch (Throwable t) {
            Env.log("AdbClient layout not recognized; transport hooks skipped", t);
            return false;
        }
    }

    private static IOException notOpened(String msg) {
        if (notOpenedCtor != null) {
            try {
                return (IOException) notOpenedCtor.newInstance(msg);
            } catch (Throwable ignored) { }
        }
        return new IOException(msg);
    }

    private static void drop(Object client) {
        ShellSession s = SESSIONS.remove(client);
        if (s != null) s.close();
    }

    /**
     * 切换通道后断开所有现存连接。AdbService 发现 isConnected() == false 会自己重连，
     * 重连时 connect hook 按新设置建会话。
     */
    static void resetAll() {
        List<Object> all;
        synchronized (CLIENTS) {
            all = new ArrayList<>(CLIENTS);
        }
        for (Object c : all) {
            try {
                closeMethod.invoke(c);
            } catch (Throwable t) {
                drop(c);
            }
        }
        active = "none";
        lastError = "";
    }
}
