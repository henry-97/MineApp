# Mine App 3.0

> 从「能用」到「好用」的一版：保活、电池、更新三条主线都重做了。

---

## 一、保活：默认就该活得好好的

### 自动加入电源管理白名单

启动时把自己写进系统的 `MILLET_NO_RESTRICT_APP` 名单，之后系统不会按「很少使用」把它压进 RARE 桶。

**这条记录不在界面上展示。** 白名单页本来就只列第三方应用，所以用户看不到自己，也就没有机会误删。

### 默认隐藏最近任务卡片

卡片不在最近任务里，就没有被手滑划掉的可能。

设置页留了「显示后台卡片」开关，默认关闭。文案里写清了理由：

> 本应用会自动允许自身后台运行。显示后台卡片反而更容易被误杀，所以默认不显示。
> 后台只做少量 shell 查询，能耗极低，可放心常驻。
> 开启通知和自启动可进一步降低被误杀的概率。

**一个实测出来的细节**：`setExcludeFromRecents` 只影响之后创建的任务，对已经在最近任务里的条目不重新评估。所以隐藏必须赶在任务进 recents **之前**设——现在放在 `MainActivity.onCreate` 里做。

## 二、电池：不再写死 5000 mAh

2.0.9 及以前，健康度的分母是个常量：

```java
private static final int BATTERY_DESIGN_MAH = 5000;
```

只对 5000 mAh 的机型成立。换 4500 的机器虚低 10%，换 6000 的虚高 20%，而且用户完全看不出来。

**Android 没有公开 API 能拿到设计容量**，sysfs 节点在多数 MIUI 机型上对整个 shell 都 Permission denied（实测连 `ls` 都进不去）。

所以改成四级兜底：

```
① 系统节点 charge_full_design（µAh）
   ↓ 读不到
② 用户在设置里填的值
   ↓ 没填
③ 内置机型表（17 个小米/红米/POCO，按 ro.product.device 匹配）
   ↓ 没命中
④ 兜底 5000，并标注「估算」
```

电池详情页会显示当前值**和它的来源**（系统读取 / 手动设置 / 按机型表匹配 / 估算），填错至少是可见的。

设置页新增「电池设计容量」，旁边就是「去关于本机页查看 →」——那里明确写着 `5000mAh(typ)`。

## 三、更新：下载、安装、重启，一步到位

2.0.9 的「检查更新」发现新版只是把按钮变成「去下载」，点开浏览器网页。用户还得自己下 APK、过系统安装器的二次确认，新手很容易卡住。

现在走自己的 ADB 通道，等价于 `adb install`：

```
① 下载 APK 到外部私有目录
② cp 到 /data/local/tmp/      ← 关键一步
③ pm install -r
```

**第 ② 步不能省**：实测 `/sdcard` 下的文件 `system_server` 打不开（FUSE 挂载，报 `Failed to load asset path ... from fd`），直接装必然失败。

点「下载并安装」后是进度条：下载阶段显示百分比，装包阶段显示「更新中……」，装完提示重启。失败会自动退回浏览器下载，不会把用户卡死。

「每 24 小时自动检查并更新」开关默认关闭——不发请求，除非你点了检查或主动开了它。

**更新不会丢授权**：adb 的授权记录在系统侧，与安装包无关。

## 四、设置页：一屏装下

原来的每一项都是「标题一行 + 按钮一行」，六项要滚两屏。现在标签在左、控件在右，一行一个。

顺手删掉了三句显而易见的话：

- 「左滑主界面或点标题可再次进入本页」
- 「应用名称会同时改变桌面图标的名称」
- 「以上步骤只需…」之外的两句啰嗦说明

「界面提示」加了个 ⓘ，点一下会解释它指的是什么。

## 五、修掉的问题

| 问题 | 原因 |
|---|---|
| 底部「内存」汇总口径写错 | 文档里说「已选应用合计」，实际是整机占用；已按代码修正 |
| 设置页文案里的 `<br>` 被当成文字显示 | `applyLang` 用 `textContent` 赋值，标签被转义；改为按 `data-html` 区分 |
| 指引弹窗里 `adb tcpip 5555` 被挤换行 | `.info-step` 是 flex 容器，代码块成了独立 flex 项被压缩；每步包一层 `<span>` |
| APK 徽章写着 525 KB | 旧构建的遗留值，实际 549 KB |

---

## What's new (English)

- **Staying alive**: the app allowlists itself on launch (never shown in the UI) and hides its own
  recents card by default, so it cannot be swiped away by accident. A switch in Settings brings the
  card back if you want it.
- **Battery health is honest now**: the design capacity was hardcoded to 5000 mAh, which was off by
  up to ±20% on other devices. It now resolves in four tiers — sysfs node → your input → a built-in
  model table → a labelled estimate — and the battery sheet shows which source is in use.
- **In-app updates**: download → copy to `/data/local/tmp` → `pm install -r`, all through the app's
  own ADB channel. A progress bar shows the download percentage and switches to "Updating…" during
  install. Falls back to the browser if anything fails.
- **Settings fits on one screen**: labels on the left, controls on the right, one row per setting.
- **Fixes**: a wrong description of the footer memory figure, HTML tags leaking into localised text,
  and a code block that wrapped inside the setup guide.
