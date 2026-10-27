# Mine App 2.0.9

> 修一个会影响所有人的问题：电池设计容量原来是写死的 5000 mAh。

---

## 问题

健康度 = 学习容量 ÷ 设计容量。但「设计容量」在代码里是个常量：

```java
private static final int BATTERY_DESIGN_MAH = 5000;
```

只对 5000 mAh 的机型成立。换成别的容量，数字就全错：

| 实际设计容量 | 满血电池会显示 | 偏差 |
|---|---|---|
| 4500 mAh | 90% | 虚低 10% |
| 5000 mAh | 100% | 正确 |
| 5500 mAh | 110% | 虚高 10% |
| 6000 mAh | 120% | 虚高 20% |

而且用户完全看不出来 —— 数字看着挺正常。

## 为什么不能直接读系统

**Android 没有公开 API 能拿到设计容量。** `BatteryManager` 只给电量、健康状态、温度、电压、当前电量计数，没有设计容量这一项。

只能去读 sysfs：

```
/sys/class/power_supply/battery/charge_full_design
```

实测 Redmi Note 12 Turbo（HyperOS）：**整个目录对 shell 都是 Permission denied**，连 `ls` 都进不去。普通 App 的权限只会更低。

所以那些能显示设计容量的软件（AccuBattery、DevCheck、CPU-Z）走的是另外两条路：内置机型对照表，或者**让用户自己填**。

## 改法：三级兜底

```
① 系统节点 charge_full_design（µAh）—— 能读就用，最准
   ↓ 读不到
② 用户在设置里填的值
   ↓ 没填
③ 兜底 5000，并在界面标注「系统读不到，估算」
```

设置页新增「设计容量」一项，留空表示自动；填了就按填的算。电池详情页会显示当前值**和它的来源**（系统读取 / 手动设置 / 估算）。

## 顺手加了个入口

设置页新增「去系统设置看电池健康度 →」，点了直接跳到系统的「省电与电池」页。

系统里能看到「电池保护 → 电池健康度」，但它只给状态词（正常 / 需更换），不给百分比 —— 所以本应用的百分比仍然有参考价值。

---

## What's new (English)

- **Fixed a wrong assumption**: the battery design capacity was hardcoded to 5000 mAh, so the health
  percentage was off by up to ±20% on any other device.
- Android exposes **no public API** for design capacity, and the sysfs node
  (`charge_full_design`) is unreadable for `shell` on many ROMs — including the test device.
- Now resolved in three tiers: read the sysfs node → fall back to a value you enter in Settings →
  fall back to 5000 and label it as an estimate. The battery sheet shows which source is in use.
- Settings also gained a shortcut that opens the system battery page, where you can check the
  manufacturer's own health reading (it only gives a status word, not a percentage).
