# Mine App 3.0.8

> 设置页再压缩一行；两处状态文案收尾。

---

## 一、界面提示改成滑块，与「显示后台卡片」合一行

原来是独立一行，用 `[显示] [隐藏]` 分段按钮。现在：

- 改成和「显示后台卡片」一致的**滑块**
- 标题改为「显示界面提示(ⓘ)」，半角括号
- 挪到「显示后台卡片」右侧，**两组合占一行**

标题里的 ⓘ 仍可点击，会解释「界面提示」指的是什么。

## 二、按钮不再显示「更新中」

「更新中」三个字会把按钮撑宽，而按钮本身就是进度条 —— 宽度一变，填充的观感就不对了。

装包阶段直接保持「完成」：进度条已经满了，文字也稳定，按钮宽度自始至终不变。
左侧的「将自动重启」已经把「正在做什么」说清楚了。

## 三、「检查中」持续显示到出结果

原来 `checking` 也走「2 秒后回落版本号」的逻辑，网络慢的时候会出现：

```
点检查 → 检查中 → （2 秒）→ v 3.0.7 → ……结果才回来
```

中间那段空档会让人以为「检查完了，没结果」。

现在区分两类状态：

| 类型 | 状态 | 行为 |
|---|---|---|
| **进行中** | 检查中、将自动重启 | 常驻，直到被下一个状态替换 |
| **终态** | 已是最新、发现新版本、检查失败 | 显示 2 秒后回落版本号 |

## 四、电池说明的间距

「可查看后手动输入」→「可　查看　后手动输入」，链接左右各留两格，不再和正文黏在一起。

---

## What's new (English)

- The hint toggle is now a switch, titled "Show hints (ⓘ)", sitting on the same row as the
  recents-card toggle. The ⓘ in the label is still tappable.
- The update button no longer switches to "Updating" — that third character widened the button, and the
  button *is* the progress bar. It stays at "Done" with a full bar.
- "Checking" now persists until a result arrives instead of reverting to the version number after 2 s.
- Two spaces now separate the inline "check here" link in the battery hint.
