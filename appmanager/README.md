# Mine App · 应用管理器（工程笔记）

> 这是工程内部的实现笔记：ADB 协议细节、踩过的坑、构建链路。
> 项目总览、安装说明、赞助与协议请看[根目录 README](../README.md)。

一个**不需要 root、不需要 Shizuku**的安卓应用管理工具。用 USB 调试授权换来 shell 权限，
在一台普通手机上完成「归档 / 还原 / 限制 / 解限 / AOT 预编译 / 白名单 / 性能模式」这些
通常要连电脑敲命令才能做的事。

- 包名：`com.henry.appmanager`
- 当前版本：**1.3.2**（versionCode 24）
- 体积：约 525 KB（6 套图标 + 12 个 alias）
- minSdk 26 / targetSdk 35，实测机型：小米（HyperOS / Android 15，1080×2400）

---

## 一、它能做什么

### 应用管理（主界面）

| 操作 | 实际执行 | 说明 |
|---|---|---|
| **归档** | `pm disable-user --user 0 <pkg>` | 只改启用状态，APK 与用户数据**完整保留**。失败时对系统应用回退 `pm uninstall --user 0` |
| **还原** | `pm enable <pkg>` / `cmd package install-existing <pkg>` | 两种归档方式都认，自动选对恢复路径 |
| **限制** | `cmd appops set <pkg> RUN_IN_BACKGROUND ignore` | 掐掉后台运行权限（MIUI 的「限制后台」同款） |
| **解限** | `cmd appops set <pkg> RUN_IN_BACKGROUND allow` | 恢复后台运行 |

- 列表展示：应用名、包名、运存占用（MB / 占比）、上次打开时间
- 排序：默认 / 运存 / 闲置 / 名称；支持按应用名或包名搜索
- 状态胶囊：右下角显示 **正常 / 已限制 / 已归档**；**系统**标记以小胶囊跟在应用名右侧
- 多选后批量操作，勾选内容决定按钮可用性（见下文「交互规则」）
- 底部汇总条：`已选 N · 共 M` + 清空按钮靠左，内存汇总贴最右
- **列表缓存**：拉到的列表写进 `localStorage`，1 分钟内重进 App 直接用快照、不再重拉；
  换图标会把进程带走，所以这条主要就是给「换完图标回来」用的。
  手动刷新按钮和任何批量操作之后都走 `load(true)` 强制拉新。

### 外观（设置页）

| 项 | 说明 |
|---|---|
| **应用名称** | Mine App / 应用管理器，改的是 activity-alias 的 label |
| **应用图标** | 6 套任选：**方块 / 四叶草** × **墨绿 / 金色 / 黑色**，选完桌面图标立即变；默认第一套（方块·墨绿） |
| **主题** | 跟随系统 / 浅色 / 深色 |
| **语言** | 跟随系统 / 中文 / English |
| **界面提示** | 一键隐藏/显示三处 ⓘ（标题旁、电池行尾、性能模式行尾） |

图标实现要点：Android 不能在运行时改 `label`/`icon`，所以工程里预置了
**2 种名称 × 6 套图标 = 12 个 `activity-alias`**，切换时用
`PackageManager.setComponentEnabledSetting` 先启用目标、再停用其余，
并写进 `SharedPreferences`；App 启动时 `Bridge.restoreAlias()` 会把偏好重新落回去
（覆盖安装后 manifest 会重置成默认，不还原就会「图标变回去」）。

### AOT 预编译

- 每行右侧有 `AOT` 开关：**已预编译＝绿色划开**
- 点击后开关变成**进度条**，左侧实时显示百分比（1 位小数）
- 后台执行 `cmd package compile -m speed <pkg>`，关闭则 `-m verify`
- **幂等**：动手前先读真实状态，已经是 `speed` 就直接提示「无需重复编译」
- **不设超时**：大应用编译十几分钟也等它跑完，不会误报失败

### Millet 白名单

- 编辑 `settings system MILLET_NO_RESTRICT_APP`（MIUI 的后台豁免名单）
- 底部实时显示「已选 N · 共 M」，支持一键清空
- 排序条有「已选」分段，只看已勾选项，逐个取消勾选

### 性能与状态

- **性能模式**常驻开关（`POWER_PERFORMANCE_MODE_OPEN`），系统回退后自动写回
- **常驻通知**两行：
  `Mine App · 电池 良好 · 健康87% · 29.9℃`
  `内存 7.3/14.8G (49.2%)　归档 6 · 限制 37`
- **电池健康度** = 历史最低学习容量（Min）÷ 设计容量 5000 mAh，**只降不升、无任何回退**
- 点电池那一行可看 Max / Last / Min 三个学习值

### 设置页

- 应用名称切换（`应用管理器` / `Mine App`，同时改桌面图标名）
- 主题：跟随系统 / 浅色 / 深色
- 语言：跟随系统 / 中文 / English

---

## 二、交互规则

**底部按钮**按勾选内容判定，没意义的操作直接置灰：

| 勾选情况 | 归档 | 还原 | 限制 | 解限 |
|---|:--:|:--:|:--:|:--:|
| 未勾选任何应用 | ✗ | ✗ | ✗ | ✗ |
| 全部是已归档 | ✗ | ✓ | ✗ | ✗ |
| 全部是已限制 | ✓ | ✗ | ✗ | ✓ |
| 全部是正常应用 | ✓ | ✗ | ✓ | ✗ |
| 已归档 + 正常混选 | ✗ | ✗ | ✗ | ✗ |
| 调试通道断开 / 正在执行 | ✗ | ✗ | ✗ | ✗ |

**进入设置**：主界面**从左往右滑**（侧滑翻页动画）、或点标题 / 标题旁的 ⓘ；
设置页**从右往左滑**返回。

---

## 三、技术特点（为什么这么小、这么快）

1. **自实现 ADB 协议客户端**（`AdbClient.java`）
   直接用 Java 实现 CNXN / AUTH / OPEN / WRTE / OKAY / CLSE，连手机本机的
   `127.0.0.1:5555`。不依赖 adb 可执行文件、不依赖 Shizuku、不需要 root。

2. **单连接复用**
   一条 TCP 连接 = 一条 transport = 一次授权弹窗。全进程单例 + `synchronized` 串行，
   避免每执行一条命令就弹一次「允许 USB 调试」。

3. **无 Gradle 构建**
   aapt2 → javac → d8 → zipalign → apksigner 五步直出 APK，构建 10 秒级，产物不到 100 KB。

4. **前端只有一份**
   HTML/CSS/JS 全部写在 `termux/webui/frontend.py` 的 `HTML = r'''...'''` 里，
   构建时由 `tools/gen_assets.py` 提取并注入一层「fetch → Java 桥」的 shim 写进
   `assets/index.html`。改界面只改一处，不会出现两个版本走偏。

5. **常驻通知与界面共用同一个 `AdbService`**，不额外开 transport。

---

## 四、环境需求

### 手机端

- Android 8.0（API 26）及以上；小米 / HyperOS 实测可用
- 开启开发者选项 → **USB 调试**、**USB 安装**
- 不需要 root，不需要装 Shizuku / Termux

### 电脑端（只在首次安装和每次重启后需要）

- Windows（本仓库脚本为 Git Bash 编写）
- `adb`：Android SDK platform-tools 里的 `adb`
- 构建才需要：JDK 17、build-tools 35、platform-35（见 `build.sh` 顶部说明）

---

## 五、新机安装配置（第一次）

```bash
ADB=adb          # 或写成你本机 platform-tools 的绝对路径

# 1. 数据线连电脑，手机上点「允许 USB 调试」并勾选「一律允许」
$ADB devices                     # 确认能看到设备序列号 + device

# 2. （小米必做）关掉安装二次确认，否则 install 会被拦成 INSTALL_FAILED_USER_RESTRICTED
$ADB shell settings put global adb_install_need_confirm 0

# 3. 安装
$ADB install -r out/app-manager.apk

# 4. 关键一步：让 adbd 同时监听 TCP 5555，App 才能连 127.0.0.1:5555
$ADB tcpip 5555

# 5. 手机上打开 Mine App
#    首次会弹「允许 USB 调试吗」，勾「一律允许使用这台计算机进行调试」→ 允许
#    这一次之后就再也不弹了
```

完成后可以拔掉数据线，App 正常工作。

### 每次手机重启后

`service.adb.tcp.port` 会失效（重启不保留），App 会显示「调试通道未连接」。
此时用电脑跑一次即可：

```bash
$ADB tcpip 5555
```

> 想彻底免电脑，只有两条路：root 后写 `persist.adb.tcp.port`，或自己实现
> Android 11+ 的「无线调试」配对协议。当前版本没有做。

---

## 六、构建

```bash
cd android-app/appmanager
./build.sh
# 产物：out/app-manager.apk
```

脚本依次做：

1. `tools/gen_assets.py` 从 `frontend.py` 提取 HTML + 包名映射，生成 `assets/index.html`、`AppNames.java`
2. `tools/gen_icon.py` 生成自适应图标各密度 PNG
3. `aapt2 compile` / `aapt2 link`（生成 `R.java`）
4. `javac`（`-bootclasspath android.jar`）
5. `d8` 转 dex
6. `zipalign` + `apksigner` 签名

**改图标或改前端后必须升 `versionCode`**，否则 MIUI 桌面会沿用旧图标缓存。

### 发布签名

`build.sh` 缺密钥时会自动 `keytool` 生成一个，方便本地调试；**对外发布请用专用密钥**，
不要用 Android SDK 自带的 `CN=Android Debug` 调试证书——那份密钥是公开的，
别人可以签一个同签名的 APK 覆盖安装到用户手机上。

换密钥后签名不同，用户必须先卸载旧版才能装新版，所以这件事要在**第一次发布之前**定下来。

---

## 七、目录结构

```
android-app/
├── appmanager/                  ← APK 工程
│   ├── build.sh                 构建脚本（无 Gradle）
│   ├── src/AndroidManifest.xml  版本号、12 个 activity-alias（改名+换图标，自动生成）
│   ├── src/java/com/henry/appmanager/
│   │   ├── AdbClient.java       ADB 协议实现（CNXN/AUTH/OPEN/WRTE/OKAY/CLSE）
│   │   ├── AdbService.java      业务层：列表、归档、限制、AOT、电池、内存
│   │   ├── Bridge.java          JS ↔ Java 桥（单线程串行执行）
│   │   ├── KeepAliveService.java 常驻通知 + 性能模式保持
│   │   ├── MainActivity.java    WebView 容器
│   │   └── AppNames.java        包名→中文名（自动生成）
│   ├── res/                     图标、字符串、主题
│   ├── assets/index.html        前端（自动生成，勿手改）
│   └── tools/                   gen_assets.py / gen_icon.py / gen_manifest.py
├── termux/webui/frontend.py     ★ 前端唯一真源（HTML/CSS/JS 都在这里）
└── adbprobe/                    协议调试用 Python 客户端（排查 ADB 问题）
```

---

## 八、已知限制与风险

- **重启后需电脑跑一次 `adb tcpip 5555`**，见上。
- **系统应用归档要谨慎**：`pm disable-user` 对绝大多数系统应用可用，但归档
  `com.android.systemui`、桌面、`com.miui.securitycenter` 这类关键组件可能导致
  黑屏 / 无法操作。真出问题可用 `adb shell pm enable <包名>` 救回。
- **AOT 会占空间**：从 `speed-profile` 升到 `speed` 是全量编译，巨型应用
  （如微信）产物可达数百 MB 级；重复编译同一档位是原地替换，**不会翻倍**。
- **电池健康度只看历史最低值**：新机或从未完整充放过时读不到 Min，会显示「—」，
  这是刻意的——宁可不显示，也不给一个偏高的假数字。
- 常驻通知刻意使用系统模板：改用自定义布局会被 HyperOS 折叠进「更多通知」，
  主通知栏就看不到了。

---

## 九、版本记录

对外版本从 **1.3.2** 起算。下面 2.x / 3.x 的编号是**内部迭代序号**，与对外版本无关，
保留下来是为了记住每一步改了什么。

| 版本 | 要点 |
|---|---|
| 1.3.2 | 首个公开版本：6 套图标、归档 / 限制 / AOT / 性能模式、列表 1 分钟缓存；构建改为正式版（去掉 `debuggable`） |
| 3.2 | 试过第三套「四叶·满」（白边更满），效果不好，已删除，回到 6 套 |
| 3.1 | 四叶草试分两档 → 9 套图标 / 18 个 alias（3.2 已回退） |
| 3.0 | 四叶草改 45°：花瓣朝上下左右、绿 X 负形；白色范围放大 |
| 2.9 | 四叶草 45° 定稿（白瓣 + 绿底）；`build.sh` 补上 `gen_manifest.py` |
| 2.8 | 四叶草图标定稿：圆头花瓣、瓣间镂空十字、中心白锁；列表加 1 分钟本地快照缓存（换图标后重进不再重拉） |
| 2.7 | 6 套图标（方块/四叶草 × 绿/金/黑）可在设置页切换；12 个 alias 承载名称×图标；三处 ⓘ 统一 11.5px 并可一键隐藏；汇总条改为「已选/共 + 清空」靠左、内存贴右 |
| 2.6 | 底部按钮按勾选内容智能置灰；「系统」改为应用名右侧小胶囊；右下角固定显示 正常/已限制/已归档 |
| 2.5 | 标题旁加 ⓘ；主界面右滑进设置、设置页左滑返回（侧滑动画） |
| 2.4 | AOT 去掉超时误报，改为回读真实 dexopt 状态；已编译直接跳过 |
| 2.3 | AOT 进度条显示百分比（1 位小数） |
| 2.2 | AOT 由文字按钮改为开关 + 进度条 |
| 2.1 | 通知标题与主界面统一为「电池 良好 · 健康87% · 29.9℃」且不加粗 |
| 2.0 | 白名单页加「已选/共」「清空」「已选」筛选 |
| 1.6 | 常驻通知显示内存、归档/限制数量 |
| 1.0 | 首版：列表、归档/还原、限制/解限、性能模式、电池健康 |
