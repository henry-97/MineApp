<p align="center">
  <b>简体中文</b> · <a href="README.en.md">English</a>
</p>

<p align="center">
  <img src="docs/app-icon.png" width="96" alt="Mine App">
</p>

<h1 align="center">Mine App · 应用管理器</h1>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-3.8.1-0F6E56">
  <img alt="APK" src="https://img.shields.io/badge/APK-553%20KB-0F6E56">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-26-0F6E56">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-35-0F6E56">
  <img alt="license" src="https://img.shields.io/badge/license-GPL--3.0-0F6E56">
</p>

> **Mine App（中文名「应用管理器」）是一款专让卡顿安卓设备焕发活力的纯净应用管理器。**
>
> - **真·零依赖**：不引入 AndroidX 等任何第三方库，只用系统 API —— 全部代码编译后仅 **33 KB**，连同六套图标整体不足 1 MB，含本地数据总计不足 10 MB。构建侧也不依赖 Gradle，不依赖 Root，无需常驻 Shizuku。
> - **重启不丢限制**：归档与限制都写在系统状态里，**手机重启后依然生效**，不需要重新设置。重启后即使暂未 adb 授权，也只是暂时不能刷新显示应用列表，已经限制、归档过的应用不会因此放开；等你方便时再授权一次就能刷新列表，经常重启也不必顾虑。
> - **增效提速·降能耗**：通过「归档冻结」与「后台限制」彻底封锁流氓进程与冗余唤醒；在常驻性能模式确保前台高响应的同时，大幅降低整机待机功耗。
> - **性能模式不费电**：它只解除 CPU 峰值主频上限，不会让 CPU 一直满血跑——前台任务干完就回落低频。真正吃掉待机的是后台偷偷唤醒，所以推荐「**性能模式 + 限制后台**」，比压主频的「均衡」更快也更省。
> - **底层级加速**：原生支持 AOT Speed 全局静态预编译，消除 JIT 编译卡顿，显著压低应用冷启动延迟。

---

## 这是什么

Android 上想让不常用的应用「彻底别动」，常见办法是冻结。但主流方案要么要 root，要么要装
Shizuku / 冰箱 / 黑洞一整套，**让本就有限的存储空间更加拥挤**。

Mine App 换了个思路 —— **在保持极致轻量的前提下，做到单 APP 部署**：

**手机自己连自己的 adbd。**

只要在**首次安装时**执行一次 `adb tcpip 5555`（数据线或「无线调试」都行，见下文 **首次配置**），
adbd 就会在本机 `127.0.0.1:5555` 上开一个入口。App 内置了一个精简的 ADB 客户端，
用自己私有目录里的密钥完成认证，之后直接发 `pm` / `am` / `cmd` 命令。
密钥存在私有目录，覆盖安装时原样保留 —— **所以软件更新后授权自动继承，无需再次 `adb`**。

**从此电脑就不用再管了** —— 归档、限制、AOT 预编译、性能档位，全部在手机屏幕上点几下完成，
不需要再碰 adb 工具。只要不重启手机，这一次 `adb tcpip` 就一直有效；重启后补执行一次即可。

**装一个 App，就这一件事。** 553 KB 的单个 APK，零第三方依赖：不装 Shizuku、不装冰箱、
不装黑洞，不挂常驻守护进程，不申请多余权限。

<p align="center">
  <img src="docs/main.jpg" width="320" alt="主界面">
</p>

### 数据刷新：压低自身负载，也不让信息变旧

常驻类工具最怕自己变成新的耗电源头。Mine App 把取数分成两档：

| 频率 | 取什么 | 为什么这么分 |
|---|---|---|
| **每 3 秒** | 只跑一条 `cat /proc/meminfo`，刷新内存总量与占用比 | 命令极轻、开销可忽略，但足够让你随时看清手机内存的真实概况 |
| **每 60 秒** | 电池健康、归档数、限制数 | 这些数据一分钟才更新一次；而它们要跑 `dumpsys battery` + `dumpsys batterystats` + 4 条 `pm`/`appops`，比 meminfo 重得多，所以每分钟才做一回 |

主界面还留了**手动刷新按钮** —— 想立刻确认状态时点一下，不用等下一轮。

一句话：**高频只看最轻的指标，重活交给低频**。自身负载压到最低，信息也不会变旧。

### 和常见方案比

| | **Mine App** | 系统「强行停止」 | 冰箱 Ice Box | 黑洞 Hail |
|---|---|---|---|---|
| 需要 root | **否** | 否 | 否 | 否 |
| 依赖 Shizuku | **否 ✓** | 否 | 是 | 是 |
| 自带 ADB 客户端 | **是 ✓** | — | 否（走 Shizuku） | 否（走 Shizuku） |
| 需要常驻后台服务 | **否 ✓** | 否 | 是（Shizuku 常驻） | 是（Shizuku 常驻） |
| APK 体积 | **< 1MB ✓** | 系统自带 | 数 MB | 数 MB |
| 批量冻结 / 解冻 | **支持** | — | 支持 | 支持 |
| 冻结后重启仍有效 | **是** | 否 | 是 | 是 |
| 性能档位调优 | **支持 ✓** | — | — | — |
| 单应用 AOT 预编译 | **支持 ✓** | — | — | — |
| PSS 内存排序 | **支持 ✓** | — | — | — |

> **✓** 标出的是 Mine App 相对占优的项。
> 「自带 ADB 客户端」指应用自己实现了 ADB 协议（CNXN / AUTH / OPEN / WRTE），直接连手机本机的 adbd ——
> 所以不需要装 Shizuku 之类的外部工具，一次性授权后就能独立运行。
> 冰箱 / 黑洞两列依据其公开说明与常见用法整理，细节以各自文档为准。

## 功能

| 功能 | 说明 |
|---|---|
| **归档（冻结）** | `pm disable-user`，应用彻底不跑、不出现在最近任务，**桌面图标直接消失**（MIUI / HyperOS 不显示变灰态） |
| **限制** | `appops RUN_IN_BACKGROUND`，限制后台活动，比冻结温和，应用还能被拉起 |
| **应用查找**（3.8 新增、完善） | 点每行的定位按钮，就直接跳到该应用所在的桌面页并打开文件夹 —— 应用再多也能一眼找到。索引只需构建一次；没有索引或索引没建完时，列表上方会给出提示，点一下直达设置页补齐 |
| **白名单（免限制）** | 把应用加进系统的 doze / standby 白名单（`MILLET_NO_RESTRICT_APP`），系统不再按「长时间未使用」压制它的后台活动。**注意：这不等同于 MIUI 界面上的「省电策略 → 无限制」** —— 那个开关由 `com.miui.powerkeeper` 自己维护，写 settings 改不动，需要到应用信息页单独设置。名单内的应用会显示「白名单」胶囊并置顶；页底实时汇总已选应用的 PSS 合计 |
| **AOT 编译** | `cmd package compile -m speed`，把 dex 提前编译成本地码，冷启动更快；**可单应用针对性编译，也可批量**，编译时轨道内显示已用时。**状态自动识别**：重装或清数据后，会从系统 `dumpsys package dexopt` 批量读回哪些应用已经 AOT，界面照实显示，不用重新编译一遍 |
| **性能档位调优** | 一键切换系统性能档位，并在被系统回退后自动重新施加 |
| **内存排序** | 按 PSS 排序，一眼看出谁在吃内存；底部实时显示整机内存占用 |
| **活跃监测** | 还在跑的应用，内存行左侧点一个**呼吸绿点**（依据系统 oom 档位判断，纯缓存和"刚用完退后台"都不算）。谁在偷偷活动一眼就能看出来 |
| **单应用清理** | 每行一个 **×**，走 `am kill` 只清后台进程 —— **不影响自启动、不改启用状态**，下次打开还是正常的冷启动。清完会告诉你实际释放了多少运存 |
| **应用名解析** | 批量读「启动项 label + 应用 label」两轮解析，全部应用都显示真实名称（含中文名），不是包名 |
| **批量操作** | 多选后一次归档 / 还原 / 限制 / 解限 |
| **常驻通知** | 状态栏常显电池健康、内存占用、归档数量；内存 3 秒一跳，电池/计数 60 秒才取一次，几乎不耗电 |
| **外观** | 6 套图标（方块 / 四叶草 × 墨绿 / 金 / 黑）、两种应用名、深浅主题、中英文 |
| **列表缓存** | 拉到的列表存本地，1 分钟内重进不重复拉取 |

> **「限制」和「白名单」是两套独立开关，别混为一谈：**
>
> | | 改的是什么 | 影响 |
> |---|---|---|
> | **限制** | `appops RUN_IN_BACKGROUND` | 后台活动权限，应用级 |
> | **白名单** | 系统电源管理名单（doze 白名单 / `standby bucket = EXEMPTED`） | 系统是否对它有后台压制 |
>
> 两者互不覆盖，所以**一个应用可以同时是「已限制」和「白名单」**——限制的是权限，白名单管的是系统压制。
>
> 实测核对：22 个白名单应用**全部**对应 `standby bucket = 5 (EXEMPTED)`，
> 且全部出现在 `dumpsys deviceidle whitelist` 里；名单外的抽样应用都不是 5。
>
> ⚠️ 两者不是一回事：Millet 白名单决定的是**框架层的 standby bucket / doze 豁免**；
> MIUI 界面上的「省电策略」是 `com.miui.powerkeeper` 自己维护的另一套状态，写 settings 改不动它。
> 想让系统界面也显示「无限制」，需要在应用信息页手动选一次。

> **关于「性能模式」：它其实不费电**
>
> 「性能模式」听起来像个费电开关，其实相反——它只是**解除 CPU 的峰值主频上限**，
> 并不会让 CPU 一直满血跑。CPU 是按需调度的：前台任务几秒干完就回落到低频，
> 比被压着主频慢慢磨更省。
>
> 真正吃掉待机电量的，是**后台应用偷偷唤醒、联网、刷新**，不是峰值主频。
> 传统「省电 / 均衡」压主频，前台同样的事要跑更久，后台该醒还是醒——结果往往是
> **又卡又费电**。
>
> 所以推荐的是一个组合：
>
> | 动作 | 作用 |
> |---|---|
> | 打开**性能模式** | 放开主频上限，前台跑完就退，不拖时间 |
> | **限制**用不着的应用 | 让后台真正睡下去，砍掉唤醒 |
>
> 前台快、后台静，两头都省。

<p align="center">
  <img src="docs/tune.jpg" width="320" alt="性能调优">
</p>

<p align="center">
  <img src="docs/settings.jpg" width="320" alt="设置页">
</p>

<p align="center">
  <img src="docs/icon-set.png" width="760" alt="六套图标">
</p>

<p align="center">
  <sub>支持多种自定义图标：内置 6 套（方块 / 四叶草 × 墨绿 / 金 / 黑），名称与图标都能在设置里随时切换；默认只显示前 3 套，点「展开」看全部。<br>
  后续会持续增加，也欢迎在开源社区 PR 贡献版权合规的图标。</sub>
</p>

## 环境要求

- Android 8.0（API 26）及以上，targetSdk 35
- 开启开发者选项 → **USB 调试**
- 不需要 root

## 安装

1. 到 [Releases](https://github.com/henry-97/MineApp/releases) 下载 `app-manager.apk`（约 553 KB）
2. 或者自己编译（见下文「从源码构建」）

### 首次配置（只做一次）

App 连的是**本机 5555 端口的 adbd**，所以要先让 adbd 监听 TCP。这件事**只在首次安装时做一次**，
之后全在 App 里点按，不用再碰电脑。

**一次配置，永久生效**：之后在 App 里做的每一次归档和限制，都写进系统状态，**重启手机也不会失效**。
重启只是暂时读不到列表、不能新设应用限制，已经限制/归档的应用其限制/归档依然有效。

#### 路径 A：让 AI Agent 帮你配（零门槛，推荐新手）

不需要懂 adb，也不用敲任何命令 —— 把手机上的准备动作做完，剩下的交给任意 AI Agent
（ChatGPT / Claude / 通义 / 豆包 / Cursor 都行）。

**第 1 步：在手机上打开开发者选项和 USB 调试**

1. 设置 → 我的设备 → 全部参数与信息 → 连续点 **「OS 版本」7 次**，直到提示「已进入开发者模式」
2. 返回设置 → 更多设置 → **开发者选项** → 打开 **「USB 调试」**
3. 用数据线连电脑，手机弹出「允许 USB 调试吗？」→ 勾 **「一律允许使用这台计算机进行调试」** → 确定

**第 2 步：把下面这段话原样发给 AI Agent**

> 帮我在这台电脑上安装并配置 Mine App（安卓应用管理器）。
> 项目地址：<https://github.com/henry-97/MineApp>
>
> 请先读 README 的「首次配置」一节，然后依次完成：
> ① 确认 `adb devices` 能看到我的手机（没有 adb 就先装 platform-tools；装完命令还找不到就用全路径，或者按 README 里「先确认 adb 能用」加进 PATH）；
> ② 执行 `adb tcpip 5555`；
> ③ 从 Releases 下载最新的 APK，`adb install -r` 装上；
> ④ 在手机上打开 App，如果弹出 ADB 授权框，提醒我点「允许」；
> ⑤ 确认 App 能正常列出应用列表。
>
> 每一步执行前先说明你要做什么；遇到报错先诊断原因，不要让我手动敲命令。

Agent 会自己下载 platform-tools、跑 adb、装 APK。你只需要在手机上点两次「允许」。

**路径 B / C：手动安装其实也很方便 —— 配好 adb 之后，核心只有一条命令 `adb tcpip 5555`。**

#### 先确认 adb 能用（路径 B / C 都要这一步）

**情况一：`adb devices` 能列出设备** → 直接跑这一条就完事：

```bash
adb tcpip 5555
```

拔线 → 打开 App → 手机上勾「一律允许」→ 允许。

> 想自己一步步操作、或者没有数据线？看下面的 **路径 B**（有数据线）和 **路径 C**（无线调试），
> 那里有完整流程。也可以把整件事交给 AI Agent（**路径 A**）。

**情况二：报「不是内部或外部命令」/ `command not found`** → 没装，或者装了但没加进 PATH。
按下面的说明弄好，再回来跑上面那条命令。

**没装的话**：下载 Google 官方的 [platform-tools](https://developer.android.com/tools/releases/platform-tools)，
解压到一个固定目录 —— Windows 建议 `C:\platform-tools`，macOS / Linux 建议 `~/platform-tools`。

**装了但命令还是找不到**，把它加进 PATH：

```powershell
# Windows PowerShell —— 只对当前窗口有效，关掉就没了
$env:Path += ";C:\platform-tools"
adb devices
```

```bash
# macOS / Linux
export PATH="$HOME/platform-tools:$PATH"
adb devices
```

想永久生效（Windows，只改当前用户，不动系统级 PATH）：

```powershell
[Environment]::SetEnvironmentVariable("Path",
  [Environment]::GetEnvironmentVariable("Path","User") + ";C:\platform-tools", "User")
```

> 执行完必须**关掉终端重新打开**，已经开着的窗口不会生效。
> macOS / Linux 把那行 `export` 写进 `~/.zshrc` 或 `~/.bashrc` 即可。

**懒得改环境变量**也行，直接用全路径调用（Windows 下前面的 `&` 不能省）：

```powershell
& "C:\platform-tools\adb.exe" tcpip 5555
```

```bash
~/platform-tools/adb tcpip 5555
```

#### 路径 B：有数据线（自己敲一条命令）

```bash
# 1. 手机用数据线连电脑
adb tcpip 5555

# 2. 拔线。打开 App，手机上会弹 ADB 授权框 → 勾「一律允许」→ 允许
```

完事。

#### 路径 C：没有数据线（借电脑的「无线调试」跑那一条命令）

手机和电脑连**同一个 Wi-Fi**，然后：

```bash
# 1. 手机：设置 → 开发者选项 → 无线调试 → 「使用配对码配对设备」
#    屏幕上会显示 IP、配对端口、6 位配对码

# 2. 电脑：先配对（端口是「配对端口」，不是调试端口）
adb pair 192.168.1.23:37123
#    按提示输入 6 位配对码

# 3. 电脑：连上去（这次用「调试端口」，和配对端口不同）
adb connect 192.168.1.23:40555

# 4. 电脑：跑那一条关键命令
adb tcpip 5555

# 5. 手机上的无线调试现在可以关掉了 —— 关不关都不影响 App
```

之后打开 App，同样在手机上勾「一律允许」。

> **为什么不做「App 直连无线调试」？** 技术上可行，也能彻底摆脱电脑，但要重写整套配对与
> TLS 通信层，新增组件的体积会超过 App 本身，多机型适配的风险也不小 —— 与「极致轻量」的
> 初衷相悖。而相比「首次配置时跑一条 `adb` 命令」，它并没有明显优势，故暂不考虑此方案。

#### 手机重启后

5555 端口会关（`adb tcpip` 的效果不跨重启，`persist.adb.tcp.port` 非 root 写不了），
**补做一次上面任意一条路径**即可。App 会显示「调试通道未连接」并提示这条命令。

**但已经设置的限制和归档是永久的**：它们写在系统状态里，重启一次、十次都一样有效。
断掉的只是「读列表」这条通道 —— 不想马上补也没关系，等你方便时再补。

## 从源码构建

不依赖 Gradle，也不依赖 Android Studio，只用 JDK + Android SDK build-tools + python3：

```bash
git clone https://github.com/henry-97/MineApp.git
cd MineApp/appmanager

cp local.env.example local.env     # 填上你的 SDK / JDK 路径
bash build.sh                      # 产物在 out/app-manager.apk
```

需要：

- JDK 17
- Android SDK：`build-tools;35.0.0`、`platforms;android-35`
- Python 3 + Pillow（生成图标用）

`build.sh` 首次运行会自动生成一个自签名密钥，不用自己准备。
所有路径都可以用环境变量覆盖，见 `build.sh` 顶部注释。

> 自己编译出来的 APK 签名和官方 Release 不同，**不能覆盖安装**官方版，需要先卸载。
> 反过来说，官方版也不可能被第三方用别的密钥覆盖——这正是发布密钥的意义。

## 项目结构

```
MineApp/
├── appmanager/                   Android 工程（无 Gradle）
│   ├── build.sh                  aapt2 → javac → d8 → zipalign → apksigner
│   ├── src/java/.../
│   │   ├── AdbClient.java        ADB 协议实现（CNXN/AUTH/OPEN/WRTE/OKAY/CLSE）
│   │   ├── AdbService.java       业务层：列表、归档、限制、AOT、电池、内存、桌面索引
│   │   ├── Bridge.java           JS ↔ Java 桥，双线程池（普通 / 长任务分开）
│   │   ├── MainActivity.java     WebView 宿主
│   │   └── KeepAliveService.java 常驻通知
│   ├── res/                      资源（图标由 tools/gen_icon.py 生成）
│   ├── assets/                   打包进 APK 的前端（生成物，不要手改）
│   └── tools/
│       ├── gen_assets.py         从前端源文件抽取 HTML/CSS/JS + 包名映射
│       ├── gen_icon.py           生成 6 套自适应图标
│       └── gen_manifest.py       生成 12 个 activity-alias
├── termux/webui/
│   └── server.py                 前端唯一真源（内嵌整个 HTML/CSS/JS）
├── docs/                         各版本发布说明、图标与截图
└── dist/                         构建产物（APK）
```

**前端只有一份。** `server.py` 里内嵌了整个 HTML/CSS/JS，`gen_assets.py` 把它原样抽出来，
再注入一层 `fetch → Java 桥` 的 shim，APK 里跑的就是这份界面 —— 不存在两份实现需要同步。

> 本项目不需要提前装 Termux。`termux/` 这个目录名是历史遗留：早期提供过「Termux + Python 脚本免 root」的分发形态，
> 现在只作为前端源码的存放位置，不再随项目分发。

## 已知限制

- **手机重启后 5555 端口会关**，需要重新执行一次 `adb tcpip 5555`。
  `persist.adb.tcp.port` 在非 root 下写不了（SELinux 拦 shell），所以没有免重启的办法。
- **AOT 全量编译很吃内存**：dex2oat 峰值可占到 2GB 以上，可用内存不足 2.5GB 时 App 会直接拦下并说明原因；
  极少数超大应用（插件 dex 极多，如抖音极速版）即使内存充足也可能被 dex2oat 放弃，状态会停在 `speed-profile`。
- 归档应用后，**桌面图标会直接消失**（实测 MIUI / HyperOS 不显示「变灰」的禁用态）。
  这是系统行为，不是被卸载 —— 在 App 里点「还原」就会回到桌面原来的位置。

- **公共网络下别乱点「允许 USB 调试」**：`adb tcpip 5555` 会让 adbd 监听所有网卡，
  同网段的人能触发你的授权弹窗；误点「允许」等于把手机控制权交出去，可能导致资料泄露。
  来源不明的弹窗一律点「拒绝」。

<a id="sponsor"></a>

## 赞助

如果这个项目帮到了你，可以请我喝杯咖啡。**完全自愿，不影响任何功能。**

**推荐渠道：[GitHub Sponsors](https://github.com/sponsors/henry-97)** —— 按月或一次性都行，
支持记录公开、也方便我致谢。

也可以直接扫码：

<div align="center">
  <table>
    <tr>
      <td align="center"><b>微信支付</b></td>
      <td align="center"><b>支付宝</b></td>
    </tr>
    <tr>
      <td><img src="docs/sponsor-wechat.png" width="260" alt="微信支付"></td>
      <td><img src="docs/sponsor-alipay.png" width="260" alt="支付宝"></td>
    </tr>
  </table>
</div>

也欢迎提 Issue 和 PR，那同样是很实在的支持。

## 开源协议

[GPL-3.0](LICENSE)。

你可以自由使用、修改、分发，但**分发时必须同样开源，严禁拿去加广告闭源再发布**。
如果你需要把它用进闭源产品，可以开 Issue 谈授权。

## 使用须知

本软件通过 ADB 直接调用系统 `pm` / `am` / `cmd` 命令，**能力很强**。

请**不要随意冻结或限制系统必要组件**（时钟、桌面启动器、输入法、电话 / 短信等），
否则可能导致闹钟不响、桌面异常、收不到来电等问题。

如果不慎误操作、且本软件已经无法打开，可以用数据线连电脑执行：

```bash
adb shell pm enable <包名>
```

把对应组件重新启用即可恢复。

**因使用不当造成的数据丢失或设备异常，作者不承担责任。**
