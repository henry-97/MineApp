# Mine App 扩展（LSPosed 模块）

为 [Mine App（应用管理器）](https://github.com/henry-97/MineApp) 加上 **Root** 和 **Shizuku** 两种执行通道。用了这两种通道，手机重启后就不必再执行 `adb tcpip 5555`。

- 基于 **libxposed API 102**，最低兼容 API 101。需要支持 modern API 的 LSPosed。
- 作用域固定为 `com.henry.appmanager`。不修改 Mine App 的 APK，也不改它的签名。
- **所有操作都在 Mine App 里完成**：选择通道、申请 su、申请 Shizuku 授权、执行命令。模块 App 不需要在后台常驻。

## 用法

1. 安装模块，在 LSPosed 里启用（作用域选 Mine App）。然后强行停止 Mine App，再重新打开。
2. 在 Mine App 里打开 **设置 → 执行通道**，有三个选项：
   | 选项 | 说明 |
   |---|---|
   | ADB | 原版行为 |
   | Root | Mine App 申请 `su`，Magisk 或 KernelSU 的授权框里显示的是 **Mine App** |
   | Shizuku | Mine App 以自己的身份申请 Shizuku 授权，授权框里显示的也是 **Mine App**。尚未授权时，切换到这个选项会自动弹出授权框 |
3. 「不可用时回退 ADB」默认打开：Root 或 Shizuku 不可用时，会退回原来的 adb tcpip 通道。

### 冻结应用快捷方式

在 Mine App 里依次打开 **设置 → 冻结应用快捷方式 → 打开**，会进入一个新页面：

- 页面列出所有**已冻结**的应用，点「添加」就会在桌面上放一个这个应用的快捷方式。
- 点桌面快捷方式时，会先**解冻再打开**这个应用。全程不显示 Mine App 的界面，只弹一个「正在解冻」的提示。
- **锁屏后自动冻结**可以选：不自动、锁屏立即、1、5、10、30 分钟或 1 小时，默认 5 分钟。
  它只对通过快捷方式（或下面的弹窗入口）解冻的应用生效。在 Mine App 里手动冻结或解冻过的应用会被移出这个名单，之后不会被自动冻结。
- **弹窗入口**：点页面上的「添加弹窗入口到桌面」，桌面会多一个雪花图标。点开是一个弹窗，列出全部已冻结的应用（以及已被解冻、还没冻回去的），上面有搜索框，点一项就解冻并打开。
- **弹窗里的操作**：右侧有 A–Z 字母条，点或滑动就跳到首字母对应的应用（汉字按拼音首字母）。长按一项有三个选项：**从列表移除**（会先解冻，之后它再次被冻结时才会重新出现在列表里）、**打开应用详情**、**卸载**（会确认；系统应用只对当前用户卸载）。
- **全部冻结**：弹窗底部和快捷方式页面里各有一个「全部冻结」按钮，把通过快捷方式或弹窗解冻过的应用一次冻回去。它不会碰你没解冻过的应用。

模块自己的界面只有两项：**是否已激活**，以及 **隐藏桌面图标** 开关。
从 LSPosed 的模块列表点进去就能打开这个界面。注意：桌面图标要在第一次打开这个界面之后才会出现（原因见下文）。

## 实现

### 替换传输层
Mine App 的所有命令都只经过 `AdbClient` 的四个方法：`connect`、`shell`、`isConnected`、`close`。包括普通连接、AOT 长任务连接和 `state()` 探活。

模块 hook 这四个方法：`connect` 时给这个 `AdbClient` 实例挂一个常驻 shell 会话，之后的 `shell` 调用都走这个会话。
Mine App 上层的锁、重试和超时逻辑都不受影响。
命令开始执行之前如果失败，模块抛 Mine App 自己的 `StreamNotOpenedException`，让 App 按原有逻辑安全地重试一次。

两种通道用的是同一套常驻会话协议（`PersistentShell`）：
- **Root**：`su` 进程。
- **Shizuku**：`Shizuku.newProcess("sh")`，执行身份是 Shizuku 服务端的身份，通常是 shell（uid 2000）。

每条命令包装成 `( eval '<cmd>' ) </dev/null 2>&1; printf '\n<随机 marker>\n'`，用 marker 切分各条命令的输出。
用常驻进程而不是每条命令起一个进程，是因为 Mine App 每 3 秒就要读一次 meminfo，而 Magisk 每次授予 su 都会弹 Toast。

### Shizuku 在 Mine App 进程里运行
Shizuku 服务端只会把 binder 推给清单里声明了 `API_V23` 权限和 `ShizukuProvider` 的应用。注入的代码没法改 Mine App 的清单，所以 Mine App 按以下顺序拿 binder：

1. **Sui**：直接用 `Sui.init` 获取，完全不经过模块 App。
2. **模块的 `BinderProvider`**：Mine App 调一次 `call()`，系统会按需拉起模块进程，Shizuku 把 binder 推给模块，模块再交给 Mine App。之后模块进程就可以被系统回收。

拿到 binder 之后，Mine App 以 `com.henry.appmanager` 的身份调用 `attachApplication`、`requestPermission` 和 `newProcess`。
Shizuku 服务端按调用方 uid 鉴权，所以授权记在 Mine App 名下。`BinderProvider` 只接受 Mine App 的 uid 调用。

### 冻结应用快捷方式的实现
- **判断是否冻结**：直接用 PackageManager 判断，不跑 shell。
  - 应用状态为 `DISABLED` 或 `DISABLED_USER` 的，算冻结。
  - 当前用户下未安装、但 APK 还在的系统应用，也算冻结。这对应 Mine App 用 `uninstall --user 0` 冻结的情况。
- **快捷方式的归属**：快捷方式归 Mine App 所有，它的 intent 显式指向 Mine App 自己的 `MainActivity`，并带一个私有 action。
  模块 hook 了 `MainActivity.onCreate`：遇到这个 action 时，只用 `invokeSpecial` 执行父类 `Activity.onCreate`，并把窗口设成透明。WebView、常驻服务等初始化全部跳过。
- **解冻和冻结**：通过反射调用 Mine App 自己的 `AdbService.batch(…, "unfreeze" / "freeze")`，复用它区分两种冻结方式的逻辑，命令走当前选择的执行通道。
- **自动冻结的计时**：在 Mine App 进程里监听亮屏和灭屏广播。Mine App 的 `KeepAliveService` 是前台服务，进程会一直在。
  延时用 `AlarmManager.setAndAllowWhileIdle`，因为手机深睡时 Handler 的计时会停住。
  闹钟触发时如果屏幕已经亮了，就不冻结。

### 其他
- 对 `AdbService` 和 `AdbClient` 的所有方法调用 `deoptimize`。Mine App 推荐用 `speed` 做 AOT 编译，`isConnected()` 这类小方法会被内联到调用方，hook 就会被绕过。
- 设置项是在 `onPageFinished` 之后注入页面的，前端通过 `window.MineExt` 和 Java 层异步通信。
- **隐藏桌面图标**：用的是一个 activity-alias，清单里默认就是 disabled。
  原因是在 Android 10 及以上，如果一个应用申请了权限、清单里默认有桌面入口、但这个入口后来被禁用，系统会补一个指向应用信息页的占位图标。
  入口默认就禁用则不受这条规则影响。

## 已知限制

- 在 Shizuku 授权框里选 **「拒绝」会被永久记住**。而 Mine App 没有声明 `API_V23`，**不会出现在 Shizuku 的授权列表里**，没法在那里改回来。
  要重新申请，只能卸载重装 Mine App（Shizuku 会清理已经不存在的 uid 的记录），或者删除 `/data/user_de/0/com.android.shell/shizuku.json` 后重启 Shizuku。
  基于同一个原因，撤销授权也只能用这两种方法。
- 如果不用 Sui，拿 binder 依赖 Shizuku 在模块进程启动时把 binder 推过来（服务端会监听进程状态变化）。
  如果 Mine App 提示 Shizuku 未运行，但 Shizuku 实际在运行，可以先打开一次模块界面，再点「重新连接」。
- **MIUI / HyperOS 的桌面快捷方式权限**：这类系统里有单独的「桌面快捷方式」权限，默认可能是关闭的。权限关闭时，点「添加」不会有任何反应。
  可以点快捷方式页面上的「去设置」，给 Mine App 打开这项权限。
- **自动冻结要靠 Mine App 的常驻通知**：快捷方式解冻应用后，模块会顺手启动 Mine App 的常驻服务（通知栏会出现 Mine App 的状态通知），不然进程一退出就可能被系统回收，关屏监听也就丢了。在 Mine App 里点「退出」会停掉这个服务，之后自动冻结不再生效，直到再用一次快捷方式。
- **自动冻结的时间不精确**：Mine App 没有精确闹钟权限，只能用非精确闹钟，在 Doze 模式下可能会晚几分钟。
  如果锁屏期间 Mine App 进程被系统杀掉，这次就不会自动冻结。下次 Mine App 进程起来后再锁屏，会重新计时。
- **快捷方式归 Mine App 所有**：卸载 Mine App，或者停用本模块，桌面上已有的快捷方式会失效。
- 遇到不兼容的 Mine App 版本时，模块不会安装通道 hook，设置页会显示「不兼容」，Mine App 保持原版行为。

## 构建

需要 JDK 17 以上和 Android SDK Platform 37（libxposed 102 的 AAR 要求 `minCompileSdk=37`）。

```bash
./gradlew assembleRelease
```

产物路径是 `app/build/outputs/apk/release/app-release.apk`。目前用的是 debug 签名。
