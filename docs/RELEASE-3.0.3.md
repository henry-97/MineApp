# Mine App 3.0.3

> 一个参数的调整：装完自动拉起的等待时间从 1 秒放宽到 1.5 秒。

---

## 改了什么

3.0.2 引入了「装完自动拉起并回到设置页」：

```bash
pm install -r xxx.apk ; sleep 1 ; am start -n com.henry.appmanager/.MainActivity
```

`sleep 1` 是留给系统完成安装的时间。实测 1 秒偏紧 —— `pm install` 返回 `Success` 之后，系统还要走完 dexopt、广播、进程清理，这时 `am start` 可能撞上「应用正在更新」而被丢弃。

改成 `sleep 1.5`，给系统多留半秒。

## 为什么值得单独发一版

自动拉起如果失败，用户看到的就是「更新完停在桌面」—— 和没有这个功能一样。这种「偶发失效」最难排查，不如把余量给足。

代价只是更新过程多等 0.5 秒。

---

## What's new (English)

- Increased the delay before the post-install relaunch from 1 s to 1.5 s.
- `pm install` returning `Success` does not mean the package manager has finished — dexopt, broadcasts
  and process cleanup still follow. Launching too early can be dropped, leaving the user on the home
  screen after an update. Half a second of extra margin is cheaper than an intermittent failure.
