package io.github.mineapp.ext.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.mineapp.ext.Const;

/** LSPosed 入口（libxposed API 101+）。只在 Mine App 主进程里干活。 */
public class ModuleMain extends XposedModule {

    private String processName = "";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Env.module = this;
        processName = param.getProcessName();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Const.TARGET_PKG.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        if (!Const.TARGET_PKG.equals(processName)) return;
        ClassLoader cl = param.getClassLoader();
        AppOps.targetCl = cl;
        Env.log("loaded into " + processName + ", framework " + getFrameworkName()
                + " " + getFrameworkVersion() + " API " + getApiVersion());
        Transport.install(this, cl);
        installUi(cl);
        installShortcuts(cl);
    }

    /**
     * 1. 快捷方式启动的 MainActivity：只跑 Activity.onCreate（父类），跳过 WebView 等全部初始化；
     * 2. 常驻服务起来时注册关屏监听，自动冻结才能在 Mine App 界面没开过的情况下生效；
     * 3. 用户在 Mine App 里手动冻结 / 解冻的应用，不再算「临时解冻」。
     */
    private void installShortcuts(ClassLoader cl) {
        try {
            Class<?> act = cl.loadClass("com.henry.appmanager.MainActivity");
            final Method superOnCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hook(act.getDeclaredMethod("onCreate", Bundle.class)).intercept(chain -> {
                Activity a = (Activity) chain.getThisObject();
                Intent it = a.getIntent();
                if (!ShortcutLauncher.isOurs(it)) return chain.proceed();
                getInvoker(superOnCreate).setType(Invoker.Type.ORIGIN)
                        .invokeSpecial(a, chain.getArg(0));
                try {
                    ShortcutLauncher.handle(a, it);
                } catch (Throwable t) {
                    // 父类 onCreate 已跑过，绝不能再落回原方法
                    Env.log("shortcut launch failed", t);
                    a.finish();
                }
                return null;
            });
        } catch (Throwable t) {
            Env.log("hook MainActivity.onCreate failed", t);
        }

        try {
            Class<?> svc = cl.loadClass("com.henry.appmanager.KeepAliveService");
            hook(svc.getDeclaredMethod("onCreate")).intercept(chain -> {
                Object r = chain.proceed();
                AutoFreeze.ensure((Context) chain.getThisObject());
                return r;
            });
        } catch (Throwable t) {
            Env.log("hook KeepAliveService failed", t);
        }

        try {
            Class<?> svc = cl.loadClass("com.henry.appmanager.AdbService");
            hook(svc.getDeclaredMethod("batch", String.class, String.class)).intercept(chain -> {
                Object r = chain.proceed();
                if (!AppOps.internal()) {
                    try {
                        JSONArray arr = new JSONArray((String) chain.getArg(0));
                        List<String> pkgs = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) pkgs.add(arr.getString(i));
                        AutoFreeze.unmark(pkgs);
                    } catch (Throwable ignored) { }
                }
                return r;
            });
        } catch (Throwable t) {
            Env.log("hook AdbService.batch failed", t);
        }
    }

    /**
     * 1. Bridge 构造完、loadUrl 之前，给同一个 WebView 加上 window.MineExt
     *    （addJavascriptInterface 只对之后加载的页面生效）；
     * 2. 页面加载完（MainActivity 里那个 WebViewClient 的 onPageFinished）后注入设置项。
     */
    private void installUi(ClassLoader cl) {
        Class<?> act;
        try {
            act = cl.loadClass("com.henry.appmanager.MainActivity");
        } catch (Throwable t) {
            Env.log("MainActivity not found", t);
            return;
        }

        try {
            Class<?> bridge = cl.loadClass("com.henry.appmanager.Bridge");
            for (Constructor<?> c : bridge.getDeclaredConstructors()) {
                final int webIdx = indexOf(c.getParameterTypes(), WebView.class);
                if (webIdx < 0) continue;
                hook(c).intercept(chain -> {
                    Object r = chain.proceed();
                    WebView web = (WebView) chain.getArg(webIdx);
                    if (web != null) {
                        Env.setApp(web.getContext());
                        AutoFreeze.ensure(web.getContext());
                        web.addJavascriptInterface(new ExtBridge(web), "MineExt");
                    }
                    return r;
                });
            }
            // 构造函数可能被内联进 onCreate
            deoptimize(act.getDeclaredMethod("onCreate", Bundle.class));
        } catch (Throwable t) {
            Env.log("hook Bridge failed", t);
        }

        // onPageFinished 由框架虚调用，不会被内联；匿名类编号不固定，逐个找
        boolean hooked = false;
        for (int i = 1; i <= 32 && !hooked; i++) {
            try {
                Class<?> c = cl.loadClass(act.getName() + "$" + i);
                if (!WebViewClient.class.isAssignableFrom(c)) continue;
                Method m = c.getDeclaredMethod("onPageFinished", WebView.class, String.class);
                hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    WebView web = (WebView) chain.getArg(0);
                    if (web != null) web.evaluateJavascript(InjectJs.SCRIPT, null);
                    return r;
                });
                hooked = true;
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            } catch (Throwable t) {
                Env.log("hook onPageFinished failed", t);
            }
        }
        if (!hooked) Env.log("WebViewClient.onPageFinished not found; settings UI not injected");
    }

    private static int indexOf(Class<?>[] types, Class<?> want) {
        for (int i = 0; i < types.length; i++) {
            if (want.isAssignableFrom(types[i])) return i;
        }
        return -1;
    }
}
