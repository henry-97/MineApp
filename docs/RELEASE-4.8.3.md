# Mine App 4.8.3 · 双版本更新

> 一个 Release 挂两个 APK，按**包名**选你要的那个：
> `MineApp-4.8.3.apk`（经典版） / `MineApp-4.8.3-multi.apk`（多通道版）。

## 双版本共同改动

### 设置页：Shizuku 授权状态并到「执行通道」行

以前「Shizuku 授权」单独占一行，设置页因此多出一行高度。现在并到「执行通道」行的右端：

```
执行通道  Root          Shizuku 已授权
执行通道  ADB           Shizuku 未授权   [授权]
```

- **已授权** → 绿色 `Shizuku 已授权`，不显示按钮
- **未授权** → 灰色 `Shizuku 未授权`，右侧出现「授权」按钮
- 顺手删掉「选择 ADB/Shizuku 时不会申请 root」这句提示——信息价值低，还挤占同一行

经典版没有 Shizuku 通道，这一行本来就不显示，因此无变化。

## 经典版（`com.henry.appmanager`）

**本版无专属改动**（Shizuku 相关 UI 在经典版中本就不出现）。

## 多通道版（`com.henry.appmanager.multi`）

本版改动集中在多通道版的设置页：

- 「Shizuku 授权」不再单独成行，改为 `执行通道` 行右侧的状态文字
- 已授权绿色 / 未授权灰色，未授权时附带「授权」按钮

## 版本

4.8.3（versionCode 219）

## 下载

| 文件 | 包名 | 说明 |
|---|---|---|
| `MineApp-4.8.3.apk` | `com.henry.appmanager` | **经典版**，只走 ADB |
| `MineApp-4.8.3-multi.apk` | `com.henry.appmanager.multi` | **多通道版**，多出 Root / Shizuku 执行通道 |

> **升级提示**：覆盖安装不会丢已有的归档与限制，操作历史与设置项都保留。
