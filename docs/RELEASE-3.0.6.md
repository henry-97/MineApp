# Mine App 3.0.6

> 更新过程的观感修正：进度条搬进按钮、满格再重启、须知只在全新安装时弹。

---

## 一、进度条就是「更新」按钮本身

原来在按钮下面又加了一条独立进度条，占一行、割裂感强。现在直接让按钮自己变进度条：

- 背景按百分比从左往右填充
- 文字跟着变：`42%` → `更新中` → `完成`

省掉一整行，而且视线不用在按钮和进度条之间来回跳。

实现上用 CSS 变量 + 背景渐变，不动 DOM 结构：

```css
.edit.prog{background-image:linear-gradient(to right,
  #cfe8dc var(--p,0%), transparent var(--p,0%))}
```

## 二、满格停留 1 秒再重启

以前进度条还在 40% 就被重启了 —— 因为 `pm install` 一旦开始，进程随时会死。

现在下载完成后先切到 `finishing` 阶段，让界面把进度条填满，**停留 1 秒**，再进入安装：

```java
instPct = 100;
instPhase = "finishing";
Thread.sleep(1000);      // 让用户看到「完成」
instPhase = "install";
```

## 三、覆盖安装不再弹「使用前须知」

原来按 versionCode 记「这个版本已同意」，所以每发一版都要再点一次。

改成按「是否同意过」记：

```js
localStorage.setItem('riskOk', '1');
```

localStorage 在覆盖安装时保留、卸载时清空 —— 正好对应「只有全新安装才需要再看一次」。

老用户的 `riskOkVer` 会自动迁移，不会多弹一次。

---

## What's new (English)

- The "Update" button doubles as the progress bar — its background fills by percentage and the label
  cycles through `42%` → `Updating` → `Done`. The separate progress row is gone.
- The bar now stays full for one second before the install starts, so it never restarts while still
  looking empty.
- The first-run risk notice only appears on a fresh install. It is keyed on "accepted once" rather
  than per version code, so updates no longer re-prompt. Existing users are migrated silently.
