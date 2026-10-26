# Mine App 2.0.5

> 这一版把「提示」和「AOT 编译」两块磨了一遍。上个正式版是 2.0.3，所以下面包含 2.0.4 的改动。

---

## 提示条重做

之前的提示只有 2.2 秒、宽度还按内容自适应，长文案会被顶成一条竖长条。

- **固定 70% 屏宽**（长文案 88% 并左对齐），不再被挤成细条
- **停留时间随字数走**：20 字内 2.2 秒，每多 1 字加 80 毫秒，**最长 5 秒**
- **点两下屏幕可提前关掉**（单次点击不打断，避免误触就没了）

## AOT 编译：查清了失败的真因

抖音极速版一直编译失败，查下来是 **ArtService 给 dex2oat 子进程设了 570 秒（9.5 分钟）硬上限**，不可配置：

```
ArtService: Failed to run dex2oat: ... the child process
            timed out after 570000ms (code 1)
```

实测对照：

| 命令 | 耗时 | 结果 |
|---|---|---|
| `-m speed`（全量） | 573 秒 | ❌ 超时 |
| `-m speed --primary-dex`（只编主 dex） | 579 秒 | ❌ 超时 |
| `-m speed-profile` | 84 秒 | ✅ 成功 |

连只编一个 `classes.dex` 都编不完。**这类应用只能停在 speed-profile**——而它本来就是系统默认档，`bg-dexopt` 会自动维护，不用管。

## AOT 开关的三处修正

**① 两个方向都短路**

之前只有「开」方向会跳过（已经是 speed 就直接返回），「关」方向没有——对一个本来就是 speed-profile 的应用点「关」，会白跑一次 verify 编译，大应用容易超时，表现就是「一会成功一会失败」。

**② 「取消」改用 `--reset`**

原来用 `-m verify`，会把产物做成「只校验、不含机器码」，**比系统默认还差**。
`--reset` 只撤掉我们那次显式编译，回到安装时的默认状态，之后系统会自己优化。实测 2.8 秒完成。

**③ 编译记录绑定 versionCode**

记录从「包名」改成「包名@versionCode」。应用一更新 versionCode 变了，记录自动失效，开关回落——因为 dexopt 产物和 APK 内容绑定，更新后系统会退回 speed-profile，继续显示「已编译」是骗人。

## 记住编译超时的应用

撞过 570 秒上限的应用会被记下来。下次点它的 AOT 先弹确认：

> 上次全量编译超时了（系统上限 9.5 分钟，这个应用太大编不完）。
> 系统默认的 speed-profile 已经在生效，不影响日常使用。再试一次吗？

不会再让你白等 10 分钟。编译成功后记录自动清掉。

## 应用装卸/更新后自动刷新

监听 `PACKAGE_REPLACED` / `ADDED` / `REMOVED`，收到就重拉列表。

以前应用更新完切回来，看到的还是旧状态，得手动点刷新才纠正。现在会自动跟上。

---

## What's new (English)

- **Toasts rebuilt**: fixed 70% screen width, duration scales with text length (max 5 s),
  and a double-tap dismisses them early.
- **AOT failures explained properly**: the real cause is ArtService's hard **570-second cap**
  on dex2oat, not memory. Apps too big for that budget stay at `speed-profile` — which is the
  system default anyway.
- **AOT toggle fixes**: both directions short-circuit now; "turn off" uses `--reset` instead of
  the harsher `-m verify`; and the record is keyed by `package@versionCode`, so an app update
  automatically invalidates it.
- **Timed-out apps are remembered** — retrying asks for confirmation first.
- **Auto-refresh on install/update/uninstall**, so the list never shows stale state.
