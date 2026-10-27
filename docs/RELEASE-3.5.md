# Mine App 3.5

> 两个重要修复：**更新后能自动回到 App**，**性能模式不再和系统反复拉锯**。

---

## 一、更新后自动拉起（修好了）

3.2 及更早版本，从 App 内更新完成后会停在桌面 —— 需要手动点回来。

**根因**：拉起脚本在 `pm install` **之前**就脱离启动，它判断「成功」的条件是
「本应用在前台」。而那一刻安装还没开始，应用本来就还在前台，于是第一轮就判定成功退出；
紧接着安装杀掉进程，就没人再拉它了。

**改法**：拆成两段，判据换成「安装真的开始了」：

1. 先等 `pidof <包名>` 消失（= 系统开始替换进程），最多等 15 秒
2. 进程没了再 `am start`，确认真的到了前台才退出（最多 20 轮）

## 二、性能模式：不再无脑对抗

3.2 是每 3 秒检查一次、发现被系统改回就重新施加。问题是 MIUI 的 powerkeeper
在某些状态下会持续取消，双方来回拉锯 —— 既费电，也让界面状态一直跳。

**新逻辑**：

| 情况 | 行为 |
|---|---|
| 系统没动它 | 什么都不做 |
| 1 分钟内被取消 ≤ 3 次 | 每 3 秒重新施加 |
| 1 分钟内被取消 **> 3 次** | **停止纠正**，弹窗说明并列出各次时间 |
| 用户手动再打开 | 清空计数，恢复自动维持 |
| 电量 < 20% | 始终让位给系统 |

弹窗文案：

> **系统频繁关闭性能模式**
> 最近 1 分钟内被系统关闭了 4 次（12 秒前、25 秒前、41 秒前、56 秒前）。
> 为避免和系统反复拉锯，已暂停自动维持性能模式。
> 建议先到系统「省电与电池」里检查电源管理设置，或确认是否处于低电量 / 高温保护状态。
> 再次手动打开性能模式即可恢复自动维持。

原来的 30 秒节流已去掉 —— 正常纠正不再被拖延，频率由「4 次/分钟」这个判据自然控制。

---

## What's new (English)

**Two important fixes: the app comes back after an update, and performance mode stops fighting the system.**

**Auto-relaunch after update.** Up to 3.2, updating from inside the app left you on the launcher.
The relaunch script detached *before* `pm install`, and its success check was "am I the top
activity?" — true at that moment simply because the install hadn't started yet, so the loop exited
immediately and the install then killed the process. It now waits for `pidof` to go empty (the real
signal that the install began) before relaunching.

**Performance mode no longer thrashes.** 3.2 re-applied it every 3 seconds, which turned into a tug
of war with MIUI's powerkeeper. Now: up to 3 reverts per minute are corrected silently; beyond that
the app stands down, shows a dialog listing when each revert happened, and suggests checking the
system battery settings. Toggling it back on resumes auto-maintenance; below 20% battery it always
defers to the system. The old 30-second throttle is gone.
