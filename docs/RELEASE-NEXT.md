# 从旧包名迁到 `com.henry.appmanager.next`（分三步）

> 一句话原理：**执行安装动作的永远是"已经装在用户手机上的旧版本"**。
> 旧版本只会 `pm install -r` **同包名**覆盖安装，所以任何"直接换成新包名"的做法
> 都装不上、也带不走数据。必须先塞一个**同包名的迁移包**进去，由它（新代码）完成换包。

## 0. 三个标签

| 标签 | 内容 | 谁在查 |
|---|---|---|
| `next` | **5.4 迁移包**（同包名！先普通版、后多通道版） | 旧包名 5.3 / ≤5.2 |
| `next6pre` | **落地包** `com.henry.appmanager.next`（先放 5.3.2） | 只有迁移包去取 |
| `next6x` | **6.0 及以后**（`.next` 正式车道） | 已经是 `.next` 的用户 |

代码依据：`Bridge.updateTag()`（`.next` → `next6x`，其余 → `next`）；
`Bridge.landingUrl()` 从 `next6pre` 取落地包地址。

## 1. 五个构建目标

| 命令 | 产物 | 包名 | 用途 |
|---|---|---|---|
| `bash build.sh main` | `app-manager.apk` | `com.henry.appmanager` | 普通版（不再发） |
| `bash build.sh multi` | `app-manager-multi.apk` | `...multi` | 多通道版（不再发） |
| `bash build.sh next` | `app-manager-next.apk` | `...next` | 落地包 / 6.x 正式版 |
| `bash build.sh mig1` | `app-manager-mig.apk` | `com.henry.appmanager` | **迁移包·普通版** |
| `bash build.sh mig2` | `app-manager-mig-multi.apk` | `...multi` | **迁移包·多通道版** |

`mig1/mig2` 与 `main/multi` **包名完全一样**，唯一区别是 `BuildCfg.MIGRATE=true`，
编译进了「开机自动换成 `.next`」的逻辑。发布时务必用 `-mig` 那个文件。

## 2. 三步发布

### 第 1 步：`next6pre` = 落地包（先发，用户无感）

```
tag    next6pre
title  MineApp 5.3.2 NEXT
asset  MineApp-5.3.2-NEXT.apk     ← out/app-manager-next.apk
```
没有人查这个标签，它只是给迁移包当下载源。发完不用等。

### 第 2 步：`next` = 迁移包·**普通版**

```
tag    next
title  MineApp 5.4 迁移版
asset  MineApp-5.4-migrate.apk    ← out/app-manager-mig.apk
```

普通版用户点更新 → `pm install -r` 同包名覆盖（MIUI 不拦）→ 应用重启成迁移包
→ 6 秒后自动下载落地包 → `pm install`（MIUI 会拦）→ 拉起系统安装器
→ 用户点「允许」→ 迁移包自己 `am start --es migrate` 拉起新包
→ 新包导入数据 → 卸载迁移包。

**用户只点两次**：更新、允许安装。

### 第 3 步：`next` = 迁移包·**多通道版**（隔 1–2 天）

```
tag    next
title  MineApp 5.4 迁移版
asset  MineApp-5.4-migrate-multi.apk   ← out/app-manager-mig-multi.apk
```

普通版用户此刻已经在 `.next` 上、查的是 `next6x`，换内容对他们毫无影响。

### 第 4 步：`next6x` = 6.0

```
tag    next6x
title  MineApp 6.0 NEXT
asset  MineApp-6.0-NEXT.apk       ← build.sh next（版本号升到 6.0）
```
与迁移包装出来的 `.next` **同包名**，普通 `-r` 覆盖更新，数据原地保留。

## 3. 安全设计（都已实测）

| 机制 | 行为 |
|---|---|
| 判成败 | 只认 `pm path <包名>`，不解析日志（日志误判出过一次事故） |
| 日志 | 每次 `>` 截断重写，绝不 `>>` |
| 超时 | 安装类命令 180 秒，`shellLong` 的 0 超时不再用于安装 |
| 拉起新包 | 看 `am start` 的 `Starting:` / `Warning: Activity not started`，不用 `dumpsys \| grep`（PTY 下会死锁） |
| **全新安装不迁移** | `lastUpdateTime - firstInstallTime < 60s` → 判为"误装的另一版"，跳过（避免多出一个空的 `.next`） |
| 迁移失败不删旧包 | 只有新包写出 `migrated` 标记，`uninstallOldAfterMigrate()` 才动手 |
| 数据二通道 | 文件（跨包 cp）+ Intent base64（我们亲自 `am start` 递过去），任一成功即可 |

## 4. 发版前自查

```
[ ] aapt2 dump badging：包名 / 版本 / 与上表一致
[ ] apksigner verify 通过
[ ] mig1 与 main 的区别只有 BuildCfg.MIGRATE（文件名别搞混）
[ ] 发布后 curl /releases/tags/<tag> 确认 title 解析出的版本正确
[ ] 资产只挂一个 .apk
```

## 5. 回滚

三个标签都可以各自删 Release / 转 draft：
- 删 `next6pre` → 迁移包取不到落地包，只会在日志里报错，不会乱装；
- 删 `next` → 旧用户显示"已是最新"；
- 删 `next6x` → `.next` 用户显示"已是最新"。

## 6. 验证记录（模拟器 2026-11-15）

```
① 装 main(5.3.2) → 等 70s → 覆盖成 mig1 → 启动
   迁移包开始换包 → phase=done 1811ms
   migrated 6 keys + 2 keyfile(s) from com.henry.appmanager 5.3.2
   已卸载旧包 com.henry.appmanager
   → 只剩 com.henry.appmanager.next     ✅

② 全新安装 mig1（不是覆盖更新）
   全新安装（gap=0ms），不自动迁移
   → 只剩 com.henry.appmanager          ✅（不会平白多出 .next）

③ 喂坏 APK（模拟安装失败）
   phase=fail 604ms，无残留、不挂死、不误判   ✅
```

真机唯一未覆盖的是 MIUI 的「USB 安装」闸门（需要用户点一次允许）。
