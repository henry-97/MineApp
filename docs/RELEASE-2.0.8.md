# Mine App 2.0.8

> 性能调优页补了说明和截图；设置页加了「检查更新」。

---

## 性能模式：它其实不费电

「性能模式」这名字像个费电开关，很多人因此不敢开，宁可留在「均衡」档，结果又卡、续航也没好。

实际情况是反过来的：

- 它只是**解除 CPU 的峰值主频上限**，不会让 CPU 一直满血跑
- CPU 按需调度：前台任务几秒干完就回落到低频，比被压着慢慢磨更省
- 真正吃掉待机电量的，是**后台应用偷偷唤醒、联网、刷新**，不是峰值主频
- 而「均衡」压主频但压不住后台 —— 前台跑得更久、后台照样醒，所以**又卡又费电**

推荐组合：**性能模式 + 限制用不着的应用**。前台快、后台静，两头都省。

这段说明放在「性能调优」页里，紧跟性能模式开关下方。

## 两个不建议手动改的开关

「GPU 调优」和「游戏周期优化」系统默认就是开着的，改错了反而掉性能。现在两行都标注了：

> 系统默认已开启，不建议手动修改

（之前这两行显示的是系统属性名 `GPUTUNER_SWITCH`、`cloud_periodic_game_enable`，对普通用户毫无意义。）

## 设置页新增「检查更新」

- **手动检查**：点一下就查 GitHub 上的最新 Release，有新版本会给出下载入口
- **自动检查**：开关默认关闭，开启后每 24 小时最多查一次

关于隐私，说明白：

- **默认不联网**。不开自动检查的话，只有你点「检查」时才会发出一次请求
- 请求只访问 GitHub 公开的 Releases API，不带任何设备信息、不做统计

> ⚠️ 这个功能依赖仓库是公开的 —— 私有仓库下匿名访问 GitHub API 会返回 404。

---

## What's new (English)

- **Performance tuning sheet** now explains why "Performance" mode does not drain the battery:
  it only lifts the CPU's peak-frequency cap rather than pinning the CPU at full tilt. The real
  standby drain is background activity — which is what *Restrict* is for.
- **GPU tuning / Game periodic tuning** are now labelled *"On by default — best left untouched"*
  instead of showing raw system property names.
- **Settings → Check for updates**: a manual check plus an opt-in 24-hour auto-check (off by
  default). Nothing is sent unless you ask for it, and the request only hits GitHub's public
  Releases API.
