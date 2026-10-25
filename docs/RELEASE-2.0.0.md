# Mine App 2.0.0

> 从 1.3.2 到 2.0.0，一共 34 个内部构建（versionCode 24 → 58）。
> 这一版补齐了「看清内存、清掉内存」这条主线，也修掉了几个数字不准的老问题。

---

## 新功能

| 功能 | 说明 |
|---|---|
| **单应用清理** | 每行一个 **×**，走 `am kill` 只清后台进程——不动自启动、不改启用状态。清完会告诉你**实际释放了多少运存** |
| **活跃监测** | 还在跑的应用，内存行左侧点一个**呼吸绿点**（按系统 oom 档位判断，纯缓存和"刚用完退后台"都不算） |
| **启动风险提示** | 每次启动弹窗说明能力边界，附「使用须知」链接与 [退出] / [同意并使用] |
| **真实应用名** | 批量读「启动项 label + 应用 label」两轮解析。百度网盘、TIM 这类把名字写在 Activity 上的应用，现在也能正确显示 |
| **关于区** | 版本号、GitHub 仓库、打赏入口、GPL-3.0 许可说明 |
| **列表增量渲染** | 一次铺 40 行，滚到底再铺；重绘保留滚动位置 |

---

## 重要修复

### 🔴 运存数字虚高一倍（最严重）

`dumpsys meminfo -c` 会**把同一个 pid 输出两行**——一行 PSS、一行 RSS。原来按包名直接累加，
等于每个进程算了两遍：

```
抖音极速版：显示 2848 MB  →  实际 566 MB
```

现在先按 pid 去重取 Min（PSS ≤ RSS 恒成立），再按包名汇总不同进程。

### 🔴 自身内存虚高

每次刷新都重新解析 598 个应用的名字，而 `loadLabel` 会为每个应用加载资源表，
进程内存瞬间冲到 455 MB、卡 5~7 秒。加了缓存后：

```
刷新峰值：455 MB → 137 MB
稳态：123 MB
```

### 🔴 所有应用都显示「从未打开」

`UsageStatsManager` 需要「使用情况访问权限」，没授予时**返回空列表**——连微信都显示从未打开。
改成读 `dumpsys usagestats`（设备端先 grep 过滤，3.6MB → 360KB），不再需要额外权限。

### 其它修复

- **AOT 假进度条**：原来按预估时间推算百分比，会在 99% 停七八分钟。改为显示**已用时**
- **AOT 阻塞界面**：编译跑在独立线程池，编译期间照常刷新列表
- **电池健康度回跳**：取历史最低值，避免个别偏高的学习值把结果拉高
- **清理提示延迟**：1.7 秒 → 0.3 秒（不再跑两遍全量 meminfo，改用轮询 `pidof`）
- **刷新图标偏心摇晃**：`↻` 字符的字形不在 em 框正中，换成对称 SVG
- **列表不再包含本应用**：避免误点「归档」把自己冻结、界面打不开

---

## 升级说明

- **覆盖安装即可**，不用先卸载（签名与 1.3.2 一致）
- 升级后如果列表里的运存数字变化很大——那是**修正后的真实值**，之前虚高一倍
- 手机重启后仍需补执行一次 `adb tcpip 5555`

---

## What's new (English)

- **Per-app cleanup** with the amount of memory actually freed
- **Activity monitor** — a pulsing green dot on apps that are genuinely running
- **Startup risk notice** with a link to the full usage notes
- **Real app names** resolved from launcher activities, not just `<application android:label>`
- **Fixed: memory figures were roughly doubled** — `dumpsys meminfo -c` emits each pid twice
  (PSS + RSS); the app summed both. Every number is now correct.
- **Fixed: "never used" for every app** — `UsageStatsManager` returns nothing without the usage-access
  permission; now read from `dumpsys usagestats`
- **Fixed: self memory spike on refresh** (455 MB → 137 MB) by caching the app-name map
- **Fixed: fake AOT progress bar** — now shows elapsed time instead of a made-up percentage
- **Fixed: cleanup toast latency** (1.7 s → 0.3 s)
