<p align="center">
  <b>简体中文</b> · <a href="README.en.md">English</a>
</p>

<p align="center">
  <img src="docs/app-icon.png" width="96" alt="Mine App">
  &nbsp;&nbsp;
  <img src="docs/app-icon-clover.png" width="96" alt="Mine App">
</p>

<h1 align="center">Mine App · 应用管理器</h1>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-2.0.2-0F6E56">
  <img alt="APK" src="https://img.shields.io/badge/APK-525%20KB-0F6E56">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-26-0F6E56">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-35-0F6E56">
  <img alt="license" src="https://img.shields.io/badge/license-GPL--3.0-0F6E56">
</p>

> **Mine App（中文名「应用管理器」）是一款专让卡顿安卓设备焕发活力的纯净应用管理器。**
>
> - **真·零依赖**：无 Gradle 冗余，不依赖 Root，无需常驻 Shizuku，APK 体积不足 1MB，加上本地数据不足 10MB。
> - **增效提速·降能耗**：通过「归档冻结」与「后台限制」彻底封锁流氓进程与冗余唤醒；在常驻性能模式确保前台高响应的同时，大幅降低整机待机功耗。
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

**从此电脑就不用再管了** —— 归档、限制、AOT 预编译、性能档位，全部在手机屏幕上点几下完成，
不需要再碰 adb 工具。只要不重启手机，这一次 `adb tcpip` 就一直有效；重启后补执行一次即可。

**装一个 App，就这一件事。** 525 KB 的单个 APK，零第三方依赖：不装 Shizuku、不装冰箱、
不装黑洞，不挂常驻守护进程，不申请多余权限。

<p align="center">
  <img src="docs/main.jpg" width="320" alt="主界面">
</p>

### 数据刷新：压低自身负载，也不让信息变旧

常驻类工具最怕自己变成新的耗电源头。Mine App 把取数分成两档：

| 频率 | 取什么 | 为什么这么分 |
|---|---|---|
| **每 3 秒** | 只跑一条 `cat /proc/meminfo`，刷新内存总量与占用比 | 命令极轻、开销可忽略，但足够让你随时看清手机内存的真实概况 |
| **每 60 秒** | 电池健康、归档数、限制数 | 这些数据几十秒才变一次；而它们要跑 `dumpsys battery` + `dumpsys batterystats` + 4 条 `pm`/`appops`，比 meminfo 重得多，所以每分钟才做一回 |

主界面还留了**手动刷新按钮** —— 想立刻确认状态时点一下，不用等下一轮。

一句话：**高频只看最轻的指标，重活交给低频**。自身负载压到最低，信息也不会变旧。

### 和常见方案比

| | **Mine App** | 系统「强行停止」 | 冰箱 Ice Box | 黑洞 Hail |
|---|---|---|---|---|
| 需要 root | **否** | 否 | 否 | 否 |
| 依赖 Shizuku | **否 ✓** | 否 | 是 | 是 |
| 单应用部署 | **支持 ✓** | — | 不支持 | 不支持 |
| 需要常驻后台服务 | **否 ✓** | 否 | 是（Shizuku 常驻） | 是（Shizuku 常驻） |
| APK 体积 | **< 1MB ✓** | 系统自带 | 数 MB | 数 MB |
| 批量冻结 / 解冻 | **支持** | — | 支持 | 支持 |
| 冻结后重启仍有效 | **是** | 否 | 是 | 是 |
| 性能档位调优 | **支持 ✓** | — | — | — |
| 单应用 AOT 预编译 | **支持 ✓** | — | — | — |
| PSS 内存排序 | **支持 ✓** | — | — | — |

> **✓** 标出的是 Mine App 相对占优的项。
> 「单应用部署」指可以只对某一个应用单独下发归档 / 限制 / AOT 策略，不影响其它应用。
> 冰箱 / 黑洞两列依据其公开说明与常见用法整理，细节以各自文档为准。

## 功能

| 功能 | 说明 |
|---|---|
| **归档（冻结）** | `pm disable-user`，应用彻底不跑、不出现在最近任务，桌面图标变灰 |
| **限制** | `appops RUN_IN_BACKGROUND`，限制后台活动，比冻结温和，应用还能被拉起 |
| **白名单（免限制）** | 把应用加进系统的「电源管理 → 无限制」名单（`MILLET_NO_RESTRICT_APP`），系统不再压它的后台活动。**和系统设置里那份名单是同一份**，这里只是省得一层层点进去。名单内的应用会显示「白名单」胶囊并置顶 |
| **AOT 编译** | `cmd package compile -m speed`，把 dex 提前编译成本地码，冷启动更快；**可单应用针对性编译，也可批量**，编译时轨道内显示已用时 |
| **性能档位调优** | 一键切换系统性能档位，并在被系统回退后自动重新施加 |
| **内存排序** | 按 PSS 排序，一眼看出谁在吃内存；底部实时汇总已选应用的内存占用 |
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
> 也就是说，App 里的「Millet 白名单」和系统「电源管理 → 无限制」操作的是**同一份名单**。

<p align="center">
  <img src="docs/settings.jpg" width="320" alt="设置页">
</p>

<p align="center">
  <img src="docs/icon-set.png" width="760" alt="六套图标">
</p>

## 环境要求

- Android 8.0（API 26）及以上，targetSdk 35
- 开启开发者选项 → **USB 调试**
- 不需要 root

## 安装

1. 到 [Releases](https://github.com/henry-97/MineApp/releases) 下载 `app-manager.apk`（约 525 KB）
2. 或者自己编译（见下文「从源码构建」）

### 首次配置（只做一次）

App 连的是**本机 5555 端口的 adbd**，所以要先让 adbd 监听 TCP。这件事**只在首次安装时做一次**，
之后只要不重启手机，就全在 App 里点按，不用再碰电脑。

#### 路径 A：有数据线（最简单）

```bash
# 1. 手机用数据线连电脑
adb tcpip 5555

# 2. 拔线。打开 App，手机上会弹 ADB 授权框 → 勾「一律允许」→ 允许
```

完事。

#### 路径 B：没有数据线（借电脑的「无线调试」跑那一条命令）

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
│   │   ├── AdbService.java       业务层：列表、归档、限制、AOT、电池、内存
│   │   ├── Bridge.java           JS ↔ Java 桥，双线程池（普通 / 长任务分开）
│   │   ├── MainActivity.java     WebView 宿主
│   │   └── KeepAliveService.java 常驻通知
│   ├── res/                      资源（图标由 tools/gen_icon.py 生成）
│   ├── assets/                   打包进 APK 的前端
│   └── tools/
│       ├── gen_assets.py         从 server.py 提取前端 + 包名映射
│       ├── gen_icon.py           生成 6 套自适应图标
│       └── gen_manifest.py       生成 12 个 activity-alias
└── termux/
    └── webui/server.py           前端唯一真源（同时也是 Termux 版服务端）
```

**前端只有一份。** `server.py` 里内嵌了整个 HTML/CSS/JS，`gen_assets.py` 把它原样抽出来，
再注入一层 `fetch → Java 桥` 的 shim，所以 Termux 版和 APK 版的界面逻辑完全一致，
不存在两份实现需要同步。

## 已知限制

- **手机重启后 5555 端口会关**，需要重新执行一次 `adb tcpip 5555`。
  `persist.adb.tcp.port` 在非 root 下写不了（SELinux 拦 shell），所以没有免重启的办法。
- **AOT 全量编译很吃内存**：dex2oat 峰值可占到 2GB 以上，可用内存不足 2.5GB 时 App 会直接拦下并说明原因；
  极少数超大应用（插件 dex 极多，如抖音极速版）即使内存充足也可能被 dex2oat 放弃，状态会停在 `speed-profile`。
- 归档应用后，桌面图标会变灰但不消失；想彻底隐藏需要 launcher 配合。

- **公共网络下别乱点「允许 USB 调试」**：`adb tcpip 5555` 会让 adbd 监听所有网卡，
  同网段的人能触发你的授权弹窗；误点「允许」等于把手机控制权交出去，可能导致资料泄露。
  来源不明的弹窗一律点「拒绝」。

<a id="sponsor"></a>

## 赞助

如果这个项目帮到了你，可以请我喝杯咖啡。**完全自愿，不影响任何功能。**

<div align="center">
  <table>
    <tr>
      <td align="center"><b>微信支付</b></td>
      <td align="center"><b>支付宝</b></td>
    </tr>
    <tr>
      <td><img src="docs/sponsor-wechat.png" width="260" alt="微信支付"></td>
      <td><img src="docs/sponsor-alipay.jpg" width="260" alt="支付宝"></td>
    </tr>
  </table>
</div>

也欢迎提 Issue 和 PR，那同样是很实在的支持。

## 开源协议

[GPL-3.0](LICENSE)。

简单说：你可以自由使用、修改、分发，但**分发时必须同样开源**，不能拿去加广告闭源再发布。
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
