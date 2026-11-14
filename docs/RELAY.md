# Mine App 跨应用执行通道授权（Relay）

> 适用版本：5.2 (235) 及以后
> 最后更新：2026-11-14
> 相关代码：`appmanager/src/java/com/henry/appmanager/RelayProvider.java`、`RelayOpenProvider.java`、`RelayGrant.java`、`Bridge.java`、`termux/webui/frontend.py`

---

## 一、要解决什么问题

Mine App 自己有一整套 shell 执行能力（ADB / Root / Shizuku 三选一），但同系列或第三方应用拿不到：

- **Lighto** 需要读「其它应用当前停在哪一页」，再用 `am start -n` 直接把用户送到那个内部页面（例如百度网盘的文件传输页）。
- 这些操作都需要 shell 权限，而让用户为每个小工具单独授权一次，体验很差。

于是 Mine App 开放一个**受控的中继**：应用向 Mine App 申请，用户在面板里手动授权，之后该应用就能借用 Mine App 的执行通道。

---

## 二、整体结构

```
  第三方应用（Lighto 等）
        │  ContentResolver.call(uri, "request" / "shell")
        ▼
  ┌─────────────────────────────────────┐
  │ ContentProvider（Mine App 进程内）    │
  │   RelayProvider / RelayOpenProvider  │
  │   ① 申请：记包名 + 签名指纹            │
  │   ② 调用：查名单 → 校验签名            │
  └──────────────┬──────────────────────┘
                 │ AdbService.shellLong(cmd)
                 ▼
  ┌─────────────────────────────────────┐
  │ 执行通道（按用户选择分发）              │
  │   ShizukuShell → RootShell → ADB      │
  └─────────────────────────────────────┘
```

要点：**中继本身不实现任何命令逻辑**，它只是把已经存在的执行通道按「授权名单」对外开放。

---

## 三、唯一入口（authority）

| 版本 | authority |
|---|---|
| 普通版 | `com.henry.appmanager.relay` |
| 多通道版 | `com.henry.appmanager.multi.relay` |

- 只有一个 provider：`RelayProvider`。
- 多通道版包名是 `com.henry.appmanager.multi`，authority 是字面量、不会被 `aapt2 --rename-manifest-package` 重写，所以在 `tools/gen_manifest.py` 里按版本拼好。
- 入口**没有声明任何清单权限**，全部靠运行期名单校验（原因见第 8 节）。
- 5.2 (235) 的发布包里还有一个 `relay2`，是 5.1 时代留下的「申请入口」，功能与 `relay` 完全重合。**已在源码中删除**，下一个发布版起不再存在；Lighto 早已改为只访问 `relay`。

## 四、接口契约

三个方法，全部通过 `ContentProvider.call()` 调用，返回 `Bundle`：

| method | 入参 | 返回 | 需要授权吗 |
|---|---|---|---|
| `request` | 无 | `out` = `ok` / `ERR: not an authorizable app`，`code` = 0 / -1 | **不需要**（唯一对陌生应用开放的方法） |
| `state` | 无 | `out` = `ok` / `unauthorized` / `disconnected`，`code` = 0 / -1 | 需要 |
| `shell` | `extras.getString("cmd")` 或 `arg` | `out` = 命令输出（≤256 KB），`code` = 0 成功 / -1 失败 | 需要 |

- `shell` 返回的 `code` 为 0 只代表命令被执行，**不代表命令本身成功**（`am start` 的失败信息在 `out` 里）。
- `AdbService.shellLong()` 内部把异常收敛成 `ERR: ...` 字符串，Provider 会把它翻成 `code = -1`，避免调用方把报错当成正常输出。

### 调用示例

```java
Uri uri = Uri.parse("content://com.henry.appmanager.multi.relay");

// 1) 报到（幂等，每次启动调一次即可）
getContentResolver().call(uri, "request", null, null);

// 2) 执行
Bundle in = new Bundle();
in.putString("cmd", "am start -n com.example/.DetailActivity");
Bundle r = getContentResolver().call(uri, "shell", null, in);
if (r != null && r.getInt("code", -1) == 0) {
    String out = r.getString("out");
}
```

---

## 五、授权模型（三层）

### 1. 默认拒绝

`RelayProvider.trustedCaller()` 只认一件事：**调用方包名在授权名单里，且签名指纹一致**。

```java
for (String p : pm.getPackagesForUid(Binder.getCallingUid())) {
    if (RelayGrant.allowed(ctx, p)) return p;
}
return null;   // 一律拒绝
```

同进程调用（`uid == myUid`）直接放行，用于 App 内部复用。

### 2. 申请制：只有主动申请过的应用才会出现在面板里

- 应用调一次 `request` → Mine App 把 `包名 → SHA-256(签名证书)` 存进 `requested` 名单。
- 面板（设置页 → 盾牌图标）**只列这份名单**，不再把手机里几百个应用全列一遍。
- 这样用户看到的每一行，都是「有应用真的想用」，动机清晰。

### 3. 签名钉死

授权时把该应用当时的签名证书 SHA-256 存下来，每次调用重新计算比对：

- 正常升级（签名不变）→ 授权继续有效
- 卸载后装一个同包名的重打包假货 → 指纹对不上，**自动失效**，不需要用户记得撤销

### 4. 撤销

关掉开关 → `RelayGrant.revoke()` + `forgetRequest()`：

- 该应用立刻失去权限（下一次调用即被拒）
- 同时从面板列表里消失；想再用必须重新申请

### 存储位置

`SharedPreferences("relay")`：

| key | 内容 |
|---|---|
| `granted` | `{ "包名": "签名指纹" }` 已授权 |
| `requested` | `{ "包名": "签名指纹" }` 申请过的 |

---

## 六、实现细节

### 调用链

```
ContentResolver.call()
  → RelayProvider.call(method, arg, extras)
      ├─ "request" → callerPackage()（不校验信任）→ RelayGrant.request()
      ├─ trustedCaller() 校验名单 + 签名
      ├─ "state" → AdbService.state()
      └─ "shell" → AdbService.state()（预热）→ AdbService.shellLong(cmd)
```

### 冷启动预热（重要）

Lighto 往往是「今天第一个唤醒 Mine App 的东西」。这时进程刚起、Shizuku 的 binder 还没送达，`ShizukuShell.active()` 判 false 会直接掉到 ADB 分支，而 ADB 没开 tcpip 时必然失败。

所以 `shell` 分支在真正执行前先调一次 `AdbService.state()` —— 它内部对 binder 有 1.5 秒等待期（`waitBinderOnce`），把通道等回来再执行。

### 执行通道分发

`AdbService.shellLong()` 依次判断：

1. `ShizukuShell.active()` → 走 Shizuku（不需要 ADB 常驻）
2. `RootShell.active()` → 走本机 `su`
3. 否则走 ADB（`127.0.0.1:5555`，**需要先 `adb tcpip 5555`**）

### 资源保护

| 项 | 值 | 作用 |
|---|---|---|
| 单条命令长度 | ≤ 4096 字符 | 防异常输入 |
| 单次输出 | ≤ 256 KB（超出截断） | 防 Binder 事务超限 |

### 面板（前端）

- 入口：设置页头部盾牌图标（时钟左侧），`#setRelay` → `openRelay()`
- 数据：`/api/relaylist` → `Bridge.relaylist()` → 返回 `[{pkg,label,granted}]`
- 开关：`/api/relayset`（`"包名|1"` / `"包名|0"`）→ `Bridge.relayset()`
- 同一个应用名解析失败（已卸载）时用包名兜底

---

## 七、安全边界与注意事项

### 硬保证

1. **默认零权限**：没有任何应用天生可用，包括同签名应用。
2. **身份不可伪造**：包名取自 `Binder.getCallingUid()`，应用无法自我声明。
3. **重打包自动失效**：名单里存的是签名指纹，不是包名。
4. **可随时收回**：撤销立即生效，无需重启。
5. **拒绝非应用身份申请**：`uid ≤ 1000` 或 `com.android.shell` 调 `request` 直接拒绝，避免 `adb shell` 往列表里塞一条 "Shell"。

### 必须知道的取舍

- **授权 = 完整 shell**。通过校验的调用方拿到的是与本应用相同的执行能力，**没有命令白名单**。这是 2026-11-14 的产品决定（此前只放行 4 类命令）。所以面板那句话是认真的：只授权你信任的应用。
- **卸载重装 Mine App 会清空名单**（SharedPreferences 随之删除），已授权应用需要重新申请 + 重新授权。
- **双版本各自独立**：普通版与多通道版是不同包名，授权名单互不相通。
- **ADB 通道的前置条件**：重启后 `adb tcpip 5555` 失效，中继会返回 `disconnected`；走 Shizuku 通道没有这个问题。

### 踩过的坑

| 坑 | 现象 | 结论 |
|---|---|---|
| 同一个类声明两个 `<provider>` | `SecurityException: The authority ... does not match` | 每个 authority 必须有独立子类 |
| XML 注释里写 `--` | aapt2 报 `not well-formed (invalid token)` | 注释里不能出现双连字符 |
| 同签名自动放行 + 面板开关 | 关了开关照样能用，用户以为开关坏了 | 取消自动放行，开关成为唯一入口 |
| 面板列出全部应用 | 600+ 行、找不到目标 | 改成申请制 |
| 冷启动首次调用失败 | `ERR: ... port 5555 ... ConnectException` | 先 `state()` 预热 binder |

---

## 八、版本演进（为什么现在是这个样子）

| 版本 | 授权模型 |
|---|---|
| 5.1 | 清单声明 `protectionLevel="signature"` 权限 + 4 类命令白名单；同签名应用**自动放行** |
| 5.2 | 取消清单权限与白名单；改为**申请制 + 手动授权 + 签名钉死**，授权即完整 shell |
| 5.2 之后 | 删掉与主入口功能重合的 `relay2`，只保留 `relay` |

改成现在这套的原因：

1. `signature` 权限只能区分「同签名 / 不同签名」，无法表达「同签名但用户不想给」。
2. 白名单挡不住真正的需求（不同应用要的页面跳转五花八门），却让人误以为「很安全」。
3. 申请制把决定权交回用户，且每一条授权都能追溯到具体应用。

---

## 九、排错手册

| 现象 | 原因 | 处理 |
|---|---|---|
| `ERR: caller not authorized` | 未在面板授权，或已撤销 | 面板里打开开关 |
| `ERR: not an authorizable app` | `request` 来自 shell/系统 uid，或读不到签名 | 由正常应用发起申请 |
| 面板里看不到某应用 | 它没调用过 `request` | 应用启动时调一次 |
| 打开开关提示「读不到该应用的签名」 | 应用已卸载残留 / 模块包 / APEX | 无法授权，属预期 |
| 首次调用要等 1~1.5 秒 | 冷启动等 Shizuku binder | 正常；调用方应允许超时 |
| `state` 返回 `disconnected` | ADB 未开 tcpip，Shizuku 未授权 | 检查设置页「执行通道」 |
| `code = 0` 但 `out` 为空 | 命令本身没有输出 | 正常 |
| 更新 Mine App 后授权全没了 | 覆盖安装不会清数据，但**卸载重装会** | 重新申请 + 授权 |

---

## 十、接入清单（给要接的应用）

1. 不需要在清单里声明任何权限（5.2 起已无 `signature` 权限可用）；authority 只用 `com.henry.appmanager[.multi].relay`。
2. 启动时调一次 `request`，让用户能在 Mine App 里看到你。
3. 调 `shell` 前先用 `state` 探测，`code != 0` 时退回你自己的降级路径。
4. 输出可能被截断到 256 KB，解析要能容错。
5. 拿到的命令结果 `code = 0` 只代表执行了，语义成败要看 `out`。
