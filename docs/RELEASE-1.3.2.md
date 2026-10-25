# Mine App 1.3.2

> 第一个公开版本。不用 root、不用 Shizuku 的 Android 应用管理器。
>
> 安装后首次需要让 adbd 监听 TCP：`adb tcpip 5555`（或用「无线调试」配对）。
> 详见 [README](../README.md)。

---

## 为什么选择 Mine App？

### 1. 极致轻量，零侵入性

- **< 1MB 纯净体积**：全链路采用轻量构建体系，不带冗余运行时，无第三方臃肿 SDK。
- **< 10MB 运行总占用**：即使长久运行并缓存全机应用清单，存储占用依然克制在 10MB 以内。
- **完全自包含**：手机直连本地 adbd（`127.0.0.1:5555`），不依赖外部 Root 权限或 Shizuku 服务。

### 2. 老旧与卡顿设备性能回血

- **AOT 全局静态编译**：调用系统底层 `cmd package compile -m speed`，将字节码提前转化为机器码，
  老旧低主频 CPU 也能获得丝滑的启动与滑动体验。
- **实时 PSS 内存排序与透视**：直观定位内存杀手，一键批量释放被蚕食的运行内存。

### 3. 常驻性能调度 × 极致省电

- **前台满血 + 后台冰封**：开启性能模式锁住 CPU/GPU 响应档位；同时利用 `pm disable-user`（应用归档）
  与系统级后台限制，斩断后台链式唤醒与算力偷跑，实现「性能全开，功耗反降」的能效反差。

---

## 这个版本包含

- **6 套图标**：方块 / 四叶草 × 墨绿 / 金 / 黑，可在设置页随时切换，桌面图标立即生效
- **两种应用名**：Mine App / 应用管理器
- **归档、还原、限制、解限**，支持多选批量操作
- **AOT 编译**，带实时进度百分比
- **性能模式**，被系统回退后自动重新施加
- **PSS 内存排序** + 底部实时内存汇总
- **常驻通知**：电池健康、内存占用、归档 / 限制数量
- **列表 1 分钟本地缓存**：重进 App 不再重复拉取
- 深浅主题、中英文切换

正式版构建：关闭 `debuggable`、`d8 --release`、关闭 WebView 远程调试、不写调试日志。

---

<details>
<summary>English</summary>

## Why Mine App?

### 1. Featherweight & Self-Contained

- **< 1MB Binary Size**: Engineered without bulky SDKs or Gradle runtime overhead.
- **< 10MB Total Footprint**: Stays strictly under 10MB of storage even after caching full device metadata.
- **Zero External Dependencies**: Direct local adbd loopback (`127.0.0.1:5555`) communication — no Root required, no Shizuku daemon needed.

### 2. Breathe New Life into Aging Devices

- **Ahead-of-Time (AOT) Speed Compilation**: Leverages Android's native compilation engine (`-m speed`)
  to pre-compile bytecode into native machine code, drastically cutting cold-launch delays and frame
  drops on older CPUs.
- **PSS-Based Memory Inspection**: Identify and purge memory-hogging processes with real-time
  aggregated metrics and batch operations.

### 3. Sustained Performance Meets Extended Battery Life

- **Full-Throttle UI + Frozen Background**: Enforces high-performance governor states for immediate UI
  responsiveness, while strictly throttling or archiving (`pm disable-user`) non-essential services.
  Prevents background resource starvation and parasitic battery drain.

## What's in this release

- **6 icon sets**: blocks / clover × green / gold / black, switchable in settings, applied instantly
- **Two app names**: Mine App / 应用管理器
- **Archive, restore, restrict, unrestrict**, with multi-select batch actions
- **AOT compilation** with live progress
- **Performance mode**, automatically re-applied if the ROM reverts it
- **PSS memory sorting** with a live aggregate bar
- **Ongoing notification**: battery health, memory usage, archive / restrict counts
- **1-minute list cache** so re-entering the app does not refetch
- Light / dark theme, Chinese / English

Release build: `debuggable` off, `d8 --release`, WebView remote debugging disabled, no debug logging.

</details>
