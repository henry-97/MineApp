# -*- coding: utf-8 -*-
"""生成 src/AndroidManifest.xml。

桌面入口 = 2 种名称 × 6 套图标 = 12 个 activity-alias，同一时间只启用一个。
Android 无法在运行时改 label / icon，只能靠切换 alias 实现「App 内换名、换图标」。
alias 名规律：Alias<Mine|Mgr><Sq|Cl><G|Y|K>，例如 AliasMineSqG、AliasMgrClK。

改图标清单时同步改这里和 tools/gen_icon.py 的 SHAPES / COLORS。
"""
import os

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "src", "AndroidManifest.xml")

NAMES = [("Mine", "Mine App"), ("Mgr", "应用管理器")]
KEYS = ["sq_g", "sq_y", "sq_k", "cl_g", "cl_y", "cl_k"]
DEFAULT_ALIAS = "AliasMineSqG"

HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/gen_manifest.py 生成，不要手改；改完跑一次 gen_manifest.py。 -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.henry.appmanager"
    android:versionCode="47"
    android:versionName="1.3.2">

    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
    <!-- 应用管理器必须能列出全部应用才能取到真实应用名（否则 Android 11+ 只返回
         与自身有交互的少数包，绝大多数应用只能显示包名） -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

    <application
        android:label="Mine App"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:allowBackup="false">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden" />

        <!-- 12 个桌面入口：名称 × 图标，同一时间只启用一个 -->
'''

ALIAS = '''        <activity-alias
            android:name=".%s"
            android:targetActivity=".MainActivity"
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
            android:name=".KeepAliveService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="显示电池与应用状态并保持用户选择的性能模式" />
        </service>
    </application>
</manifest>
'''


def main():
    out = [HEAD]
    for tag, label in NAMES:
        for key in KEYS:
            parts = key.split("_")
            name = "Alias%s%s%s" % (tag, parts[0].capitalize(), parts[1].upper())
            out.append(ALIAS % (name, "true" if name == DEFAULT_ALIAS else "false",
                                label, key, key))
    out.append(TAIL)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(out))
    print("manifest ok  aliases=%d  default=%s" % (len(NAMES) * len(KEYS), DEFAULT_ALIAS))


if __name__ == "__main__":
    main()
