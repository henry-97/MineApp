# Mine App 3.0.1

> 修 3.0 的一个显示 bug：电池那一行会变成「电量 —」。

---

## 问题

3.0 里我加保活初始化时，在 `applyData()` 里写了这么一段：

```js
if (d.battery) updateBattery(d.battery);
if (!keepInited){ keepInited = true; keepAliveInit(); }
else document.getElementById('battery').textContent = '电量 —';   // ← 问题在这
```

本意是「首次之后不再重复初始化」，但那个 `else` 会在**每次刷新时把电池行覆盖成「电量 —」**。

所以表现是：

- 冷启动第 1 秒：正常显示「电池 良好 · 健康 87% · 36.8°C」
- 数据加载完成（约 4~5 秒）：变成「电量 —」，再也不恢复

## 修法

删掉那个 `else`。电池行只由 `updateBattery()` 负责，没有第二处代码能覆盖它。

## 验证

- 启动后等 8 秒（缓存 + 网络两轮数据都应用完）：正常显示
- 手动刷新：正常显示

---

## What's new (English)

- Fixed a display bug introduced in 3.0: the battery line in the header turned into "电量 —"
  a few seconds after launch and never recovered.
- Cause: a stray `else` branch in `applyData()` that overwrote the battery text on every refresh
  after the first one. Removed — the battery line now has exactly one writer.
