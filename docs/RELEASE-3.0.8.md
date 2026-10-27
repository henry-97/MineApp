# Mine App 3.0.8

> 两处状态文案的收尾：按钮不再被撑宽，「检查中」不再提前消失。

---

## 一、按钮不再显示「更新中」

「更新中」三个字会把按钮撑宽，而按钮本身就是进度条 —— 宽度一变，填充的观感就不对了。

装包阶段直接保持「完成」：进度条已经满了，文字也稳定，按钮宽度自始至终不变。
左侧的「将自动重启」已经把「正在做什么」说清楚了。

## 二、「检查中」持续显示到出结果

原来 `checking` 也走「2 秒后回落版本号」的逻辑，于是网络慢的时候会出现：

```
点检查 → 检查中 → （2 秒）→ v 3.0.7 → ……结果才回来
```

中间那段空档会让人以为「检查完了，没结果」。

现在区分两类状态：

| 类型 | 状态 | 行为 |
|---|---|---|
| **进行中** | 检查中、将自动重启 | 常驻，直到被下一个状态替换 |
| **终态** | 已是最新、发现新版本、检查失败 | 显示 2 秒后回落版本号 |

---

## What's new (English)

- The update button no longer switches to "Updating" — that third character widened the button, and the
  button *is* the progress bar. It stays at "Done" with a full bar; the label on the left already says
  "Will restart".
- "Checking" now persists until a result arrives. Previously it shared the 2-second auto-revert used by
  terminal states, so on a slow network the label dropped back to the version number mid-check.
