package io.github.mineapp.ext;

import android.app.Application;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** 进程启动时就登记监听：框架会在模块启用时把 XposedService 推过来，借此判断是否已激活。 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    public interface Listener {
        void onServiceChanged(XposedService service);
    }

    private static volatile XposedService service;
    private static volatile Listener listener;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService s) {
        service = s;
        Listener l = listener;
        if (l != null) l.onServiceChanged(s);
    }

    @Override
    public void onServiceDied(XposedService s) {
        service = null;
        Listener l = listener;
        if (l != null) l.onServiceChanged(null);
    }

    static XposedService service() {
        return service;
    }

    static void setListener(Listener l) {
        listener = l;
    }
}
