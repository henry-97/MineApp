package io.github.mineapp.ext;

import android.app.Activity;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import io.github.libxposed.service.XposedService;

/**
 * 模块唯一的界面：是否已激活 + 是否隐藏桌面图标。其余设置都在 Mine App 的设置页里。
 *
 * 桌面图标是 activity-alias（.Launcher），清单里默认 disabled，首次打开本页时按开关状态启用。
 * 这样做是因为 Android 10+ 对「申请了权限、清单里默认有桌面入口、但入口被禁用」的应用
 * 会补一个指向应用信息页的占位图标，默认就禁用的入口不受影响，隐藏才是真隐藏。
 * 隐藏后从 LSPosed 的模块列表点进来（MODULE_SETTINGS）。
 */
public class MainActivity extends Activity implements App.Listener {

    private static final String PREF_HIDE = "hide_icon";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences prefs = getSharedPreferences("module", MODE_PRIVATE);
        applyIcon(prefs.getBoolean(PREF_HIDE, false));

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, 0, 0, pad);
        root.addView(status);

        Switch hide = new Switch(this);
        hide.setText(zh ? "隐藏桌面图标" : "Hide launcher icon");
        hide.setTextSize(16);
        hide.setGravity(Gravity.CENTER_VERTICAL);
        hide.setChecked(prefs.getBoolean(PREF_HIDE, false));
        hide.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(PREF_HIDE, on).apply();
            applyIcon(on);
        });
        root.addView(hide);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.setListener(this);
        render(App.service());
        // 框架推 service 是异步的，刚启动时给它一点时间再判「未激活」
        main.postDelayed(() -> render(App.service()), 1500);
    }

    @Override
    protected void onPause() {
        App.setListener(null);
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onServiceChanged(XposedService service) {
        main.post(() -> render(service));
    }

    private void render(XposedService s) {
        if (s == null) {
            status.setText(zh ? "未激活\n请在 LSPosed 中启用本模块" : "Not activated\nEnable this module in LSPosed");
            return;
        }
        String line = zh ? "已激活" : "Activated";
        try {
            line += "\n" + s.getFrameworkName() + " " + s.getFrameworkVersion()
                    + " · API " + s.getApiVersion();
            List<String> scope = s.getScope();
            if (scope == null || !scope.contains(Const.TARGET_PKG)) {
                line += zh ? "\n作用域未包含 Mine App" : "\nMine App is not in scope";
            }
        } catch (Throwable ignored) { }
        status.setText(line);
    }

    private void applyIcon(boolean hidden) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, getPackageName() + ".Launcher"),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
