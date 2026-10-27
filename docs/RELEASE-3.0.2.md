# Mine App 3.0.2

> 更新流程的三处体验修正。

---

## 一、按钮变宽不再把右边挤出去

「下载并安装」5 个字一出现，右边的「每 24 小时自动检查并更新」就被顶到屏幕边缘。

两处调整：

- 按钮文案收短为「**更新**」——上下文已经很明确，不需要再说「下载并」
- 自动检查那行改成可压缩（`flex:1; min-width:0` + 省略号），**再宽也不会溢出**

## 二、点击后状态常驻

点「更新」之后，左侧从「v 3.0」或「发现新版本」变成 **「将自动重启」**，并且一直显示，不会 1 秒后弹回去。

其余状态（检查中 / 已是最新 / 检查失败）仍然是临时提示，1 秒后回到版本号——它们只是反馈，不是进行中。

## 三、装完自动回到设置页

以前更新完成后 App 会被系统杀掉，停在桌面，用户得自己找图标、再点进设置。

现在装完会自动重新拉起，并直接落在设置页：

```
pm install -r xxx.apk ; sleep 1 ; am start -n com.henry.appmanager/.MainActivity
```

关键点：`am start` 是**设备侧的 shell 进程**执行的，不会随 App 进程被安装杀掉。所以装完那一刻它还在，能把新版本拉起来。

配合一个一次性的标记（`open_settings_after_update`，装前写入、启动后读一次即清除），新版起来就知道该跳到设置页。

---

## What's new (English)

- The "Update" button no longer pushes the auto-check label off-screen — shorter label plus a
  shrinkable, ellipsised auto-check line.
- Clicking update now switches the version label to a persistent **"Will restart"** state instead of
  reverting after one second.
- After installing, the app relaunches itself and lands on the Settings sheet. The relaunch is done
  by a device-side shell (`am start`) that survives the app being killed by the installer.
