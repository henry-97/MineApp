# -*- coding: utf-8 -*-
"""生成 src/AndroidManifest.xml。

桌面入口 = 2 种名称 × 6 套图标 = 12 个 activity-alias，同一时间只启用一个。
Android 无法在运行时改 label / icon，只能靠切换 alias 实现「App 内换名、换图标」。
alias 名规律：Alias<Mine|Mgr><Sq|Cl><G|Y|K>，例如 AliasMineSqG、AliasMgrClK。

🔴 组件名必须写**全限定类名**（com.henry.appmanager.MainActivity），不能用 ".MainActivity"。
   多通道版靠 aapt2 的 --rename-manifest-package 改 applicationId，相对写法会
   解析成 <新包名>.MainActivity —— 那个类不存在，装上就是点不开。

改图标清单时同步改这里和 tools/gen_icon.py 的 SHAPES / COLORS。
"""
import os
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "src", "AndroidManifest.xml")

NAMES = [("Mine", "Mine App"), ("Mgr", "应用管理器")]
KEYS = ["sq_g", "sq_y", "sq_k", "cl_g", "cl_y", "cl_k"]
DEFAULT_ALIAS = "AliasMineSqG"

HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/gen_manifest.py 生成，不要手改；改完跑一次 gen_manifest.py。 -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.henry.appmanager"
    android:versionCode="223"
    android:versionName="4.9.3">

    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
    <!-- 应用管理器必须能列出全部应用才能取到真实应用名（否则 Android 11+ 只返回
         与自身有交互的少数包，绝大多数应用只能显示包名） -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
    <!-- Shizuku：客户端侧只需要这两个声明，provider 组件由对方 App 提供 -->
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

    <application
        android:label="Mine App"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:allowBackup="false">
        <meta-data
            android:name="moe.shizuku.client.V3_SUPPORT"
            android:value="true" />

        <!-- 🔴 必须声明这个 provider：Shizuku 是通过它把 binder 交给客户端的。
             不声明的话 pingBinder() 恒为 false —— 表现为「Shizuku 里已授权，
             但 App 一直说 Shizuku 未运行」，PC 端授权了也会显示「调试未连接」。 -->
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="__SHIZUKU_AUTHORITY__"
            android:enabled="true"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

        <activity
            android:name="com.henry.appmanager.MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden" />

        <!-- 12 个桌面入口：名称 × 图标，同一时间只启用一个 -->
'''

ALIAS = '''        <activity-alias
            android:name=".%s"
            android:targetActivity="com.henry.appmanager.MainActivity"
            android:exported="true"
            android:enabled="%s"
            android:label="%s"
            android:icon="@mipmap/ic_launcher_%s"
            android:roundIcon="@mipmap/ic_launcher_%s">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>
'''

TAIL = '''
        <service
            android:name="com.henry.appmanager.KeepAliveService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="显示电池与应用状态并保持用户选择的性能模式" />
        </service>

        <!-- 无障碍服务：只为「读取桌面控件树」而存在，不订阅事件内容。
             作用是把 uiautomator dump（1.5~3 秒/次）换成直接读节点树（几十毫秒/次）。 -->
        <service
            android:name="com.henry.appmanager.A11yService"
            android:exported="true"
            android:label="@string/a11y_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/a11y_service" />
        </service>
    </application>
</manifest>
'''


EDITION = (sys.argv[1] if len(sys.argv) > 1 else "main").lower()
if EDITION not in ("main", "multi"):
    EDITION = "main"


def main():
    out = [HEAD]
    for tag, label in NAMES:
        for key in KEYS:
            parts = key.split("_")
            name = "Alias%s%s%s" % (tag, parts[0].capitalize(), parts[1].upper())
            out.append(ALIAS % (name, "true" if name == DEFAULT_ALIAS else "false",
                                label, key, key))
    out.append(TAIL)
    # 多通道版最终包名是 com.henry.appmanager.multi（aapt2 rename），
    # provider 的 authority 是字面量、不会被 rename 重写，所以在这里就按版本拼好。
    pkg = "com.henry.appmanager" + (".multi" if EDITION == "multi" else "")
    body = "".join(out).replace("__SHIZUKU_AUTHORITY__", pkg + ".shizuku")
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    print("manifest ok  aliases=%d  default=%s" % (len(NAMES) * len(KEYS), DEFAULT_ALIAS))


if __name__ == "__main__":
    main()
