# Mine App 3.1

> **3.x 以来最大的一次更新。** 界面更紧凑、响应更快、AOT 状态不再丢失，
> 依然是一个 553 KB、零第三方依赖的 APK。

---

## 一、AOT 状态自动识别（重装不再清零）

以前「哪些应用已 AOT」记在本地，`pm clear` 或重装后就全变成未开启 —— 而设备上的编译结果其实还在。

现在启动后会从 `dumpsys package dexopt` **一次性批量读回**所有应用的真实编译状态，
`status=speed` 的自动点亮。设备上报 599 个包，实测其中 58 个是 AOT，界面照实显示。

> 为什么能全量：全量 dump 有 430 KB / 4058 行，传不回来；在设备侧先用 grep 过滤，
> 只剩 22 KB，最坏情况（所有包都 AOT）也就 22 KB。

## 二、界面更紧凑

| 改动 | 效果 |
|---|---|
| 图标网格默认折叠 | 只显示前 3 套，点「展开」看全部，设置页少一行 |
| 界面提示改滑块 | 和「显示后台卡片」并排一行，两个开关一行搞定 |
| 名称按钮换序 | 默认项（Mine App）放左边，和主题、语言的规律一致 |

## 三、面板即点即开

桥的执行队列是单线程，刷新时 `/api/list` 会独占几秒。原来「Millet 白名单」要等接口回来
才显示面板 —— 那几秒里点了完全没反应。

现在**先弹面板、再拉数据**，点下去立刻有反馈。「性能调优」同理，数据未到时先给占位。

## 四、三个一键直达

设置页的说明里，「通知」「自启动」「电源管理设置无限制」都是可点的：

| 点击 | 跳到 |
|---|---|
| 通知 | 本应用的通知设置页 |
| 自启动 | MIUI 自启动管理页（没有则退应用详情） |
| 电源管理设置无限制 | **直达「电量详情」页**（带省电策略四个单选项） |

## 五、修复

- **进度条永远停在 0%**：进度查询原来排在单线程队列里，被 `installupdate` 独占，
  等轮到它时 App 已被重启。改为绕开队列直接读 volatile 字段。
- **按钮被撑宽**：装包阶段不再显示「更新中」，保持「完成」，进度条宽度自始至终不变。
- **「检查中」提前消失**：原来也走 2 秒回落逻辑，网络慢时会出现空档。
- **白名单写了等于没写**：CSV 用 `","` 追加（没有空格），MIUI 按 `", "` 解析，
  结果两个包名被当成一个条目。改为整表规范化。
- **英文文案里的单引号截断 JS**：`this app's` 让整段 `<script>` 解析失败，
  页面能显示但什么都不工作。已重写并加入 `node --check` 校验。
- **白名单 ⓘ 被界面提示开关隐藏**：关掉提示后失去唯一的说明入口，已加例外。

## 六、文档

- 首次配置新增**路径 A：让 AI Agent 帮你配** —— 手机开好开发者选项和 USB 调试，
  把一段现成提示词发给任意 AI Agent，由它装 platform-tools、跑 adb、装 APK。零基础可用。
- 白名单说明改口径：这份名单管**框架层的 doze/standby 豁免**，
  不等于 MIUI 界面上的「省电策略」，后者需到应用信息页单独设置。
- 全部截图更新为新版界面。

---

## What's new (English)

**The largest update since 3.x.** Tighter UI, faster response, AOT state no longer lost —
and still a single 553 KB APK with zero third-party dependencies.

- **AOT state is auto-detected.** After a `pm clear` or reinstall it reads back every app's real
  compilation state from `dumpsys package dexopt` in one pass (599 packages reported, 58 AOT on the
  test device) instead of showing everything as off.
- **Tighter UI.** The icon grid folds to three by default; the hint toggle became a switch sharing a
  row with the recents-card toggle; the default name button moved to the left.
- **Panels open instantly.** They used to wait for their API call, which queued behind the single-thread
  bridge during a refresh — so clicks appeared dead for seconds. They now render first and fill in after.
- **Three one-tap shortcuts** for notifications, autostart and the battery policy — the last one lands
  directly on the per-app "Battery detail" page.
- **Fixes:** progress bar stuck at 0%, button width jitter, "checking" label vanishing early, the
  allowlist CSV separator, an apostrophe that broke the whole script, and the allowlist ⓘ being hidden
  by the hints toggle.
- **Docs:** a new zero-friction setup path that hands the work to an AI agent, a corrected explanation
  of what the allowlist actually does, and refreshed screenshots.
