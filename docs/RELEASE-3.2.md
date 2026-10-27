# Mine App 3.2

> 性能模式真正「常开」了。

---

## 问题

3.1 里性能模式的纠正挂在**列表刷新**上（`applyData`）—— 只有打开 App、手动刷新、
或执行归档/限制之类的操作时才会检查一次。两次事件之间 MIUI 把
`POWER_PERFORMANCE_MODE_OPEN` 改回去，界面就一直显示关闭。

MIUI 确实会改回去：电量低、温度高、切换省电模式时，powerkeeper 会把性能模式撤掉。

## 改法

把检查搬进**常驻服务**（`KeepAliveService`，每 3 秒一轮）：

- 用户开过性能模式（`perfWanted` 记在 SharedPreferences）→ 每轮检查一次实际状态
- 被系统改回去 → 重新施加（同时关掉省电/均衡，三者互斥）
- **电量低于 20% 时不再强推**，交给系统决定
- 最多 30 秒纠正一次，避免和系统来回拉锯

界面侧原来那次检查保留，作为双保险。

## 效果

不再依赖你打开 App 或点刷新 —— 后台就守着。只有在低电量时会让步。

---

## What's new (English)

**Performance mode now actually stays on.**

In 3.1 the correction lived in the list-refresh path, so it only ran when you opened the app,
pulled to refresh, or performed an action. Between those events MIUI's powerkeeper could flip
`POWER_PERFORMANCE_MODE_OPEN` back — and it does so on low battery, high temperature, or when the
battery-saver profile switches.

The check now lives in the keep-alive service and runs every 3 seconds: if you asked for performance
mode and the system turned it off, it is re-applied (disabling saver/balanced, which are mutually
exclusive). **Below 20% battery it stands down** and lets the system decide. Corrections are throttled
to once every 30 seconds so it doesn't fight the system in a loop.
