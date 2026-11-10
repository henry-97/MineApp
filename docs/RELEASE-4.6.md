# Mine App 4.6

> 从这个版本开始，**两个版本合并到一个 Release 发布**：下面挂两个 APK，按包名选你要的那个。

## 修正 Shizuku 授权行的按钮位置

Shizuku 未运行时，「授权」按钮被挤到了屏幕边缘 —— 因为同一行里还塞了一句「先启动 Shizuku 再点授权」的说明。

那句说明已经删掉（真的没启动 Shizuku 时，点授权本来就会弹出「Shizuku 没在运行：请先打开 Shizuku App…」的提示，信息并不丢）。现在这一行只剩：

```
Shizuku 授权    未授权    [授权]
```

## 下载哪个

| 文件 | 包名 | 说明 |
|---|---|---|
| `MineApp-4.6.apk` | `com.henry.appmanager` | **标准版**，只走 ADB |
| `MineApp-4.6-multi.apk` | `com.henry.appmanager.multi` | **多通道版**，多出 Root / Shizuku 执行通道 |

两个包名不同，可以同时安装，更新通道互不干扰。App 内检查更新时会按自己所属的通道挑对应的包，不会装错。

## 版本

4.6（versionCode 214）。

> **升级提示**：覆盖安装不会丢已有的归档与限制，也不会丢 adb 授权，
> 已选的执行通道与 Shizuku 授权都会保留。
