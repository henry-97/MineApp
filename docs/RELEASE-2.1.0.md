# Mine App 2.1.0

> 这次做的是「怎么让自己活下来」。

---

## 一、自动加入电源管理白名单

启动时把自己写进系统的 `MILLET_NO_RESTRICT_APP` 名单，之后系统就不会按「很少使用」把它压进 RARE 桶。

**这条记录不在界面上展示。** 白名单页本来就只列第三方应用（列表排除本应用），所以用户看不到自己，也就没有机会误删。

实现上做了幂等：已在名单里就不重复写，不会把名单越写越长。

## 二、设置页新增「保持运行」

```
保持运行
后台卡片   [隐藏] [显示]
```

选「隐藏」后，本应用不再出现在最近任务里——手滑划掉后台的路径被切断。

**一个实测出来的细节**：已经存在的任务不会立刻从最近任务移除，需要重新打开一次应用才生效；而「显示」方向是立即生效的。这个不对称已写进界面文案，免得用户以为开关坏了。

## 三、为什么不能自动上锁

MIUI 的「最近任务上锁」**没有公开 API**。查过三处：

| 查的地方 | 结果 |
|---|---|
| `dumpsys activity recents` | 只有 `mLockTaskAuth`（那是**屏幕固定**，不是上锁） |
| `settings list system` | 没有任何 lock / recent 相关的键 |
| `am task` | 只有屏幕固定、resize、focus 这些 |

上锁状态是 MIUI 桌面自己维护的界面状态，存在它自己的数据库里，ADB 读不到也写不进。

所以只能引导用户手动操作，界面里写明了：

> 手动方法：最近任务里下拉卡片上锁 + 允许通知，两步能明显提升存活率。

## 四、关于耗电

界面里也写清楚了：

> 后台只做少量 shell 查询（内存 3 秒、电池 60 秒各一次），能耗极低，可放心常驻。

这不是营销话术——常驻通知的刷新就是两条 `dumpsys`，没有常驻循环、没有定时任务、没有网络请求（除了你主动点的「检查更新」）。

---

## What's new (English)

- **Auto-allowlist**: on launch the app adds itself to the system's power-management allowlist
  (`MILLET_NO_RESTRICT_APP`) so the OS stops demoting it. The entry is never shown in the UI — the
  app list already excludes itself, so there is nothing for the user to remove by accident.
- **Settings → Staying alive**: a "Recents card" switch that hides the app from the recents screen
  (`AppTask.setExcludeFromRecents`). Hiding an *existing* task only takes effect after the app is
  reopened once; showing is immediate. The UI says so.
- **No automatic recents lock**: MIUI's recents lock has no public API — `dumpsys` only exposes
  screen-pinning, `settings` has no related key, and `am task` offers nothing usable. The UI guides
  the user to lock it manually and allow notifications instead.
- Background cost is documented in-app: a few `dumpsys` queries every 3 s (memory) and 60 s
  (battery), nothing else.
