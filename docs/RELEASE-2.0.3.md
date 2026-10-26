# Mine App 2.0.3

> 小改动：白名单页补一句说明，让「这份名单到底是什么」不用猜。

---

## 改了什么

白名单页标题旁加了一个 **ⓘ**，点一下展开说明：

> 本名单就是系统「电源管理 → 无限制」那份名单。
> 在小米 HyperOS 实测，与应用「省电策略 → 无限制」完全等价。

再点一下收起。默认不展开，不占地方。

## 为什么加这个

「Millet 白名单」这个名字太内部了，第一次看到的人会问：这是我自己的一套名单，还是跟系统设置里那个是两回事？

实测答案：**就是同一份**。22 个名单内应用逐个核对过——

| 检查项 | 结果 |
|---|---|
| `am get-standby-bucket` | 22/22 = 5 (EXEMPTED) |
| `dumpsys deviceidle whitelist` | 22/22 全部在列 |
| 名单外抽样（TIM / Chrome / 小红书） | 20 / 30 / 40，都不是 5 |

所以在这里勾选，等于去「设置 → 应用 → 省电策略 → 无限制」逐个点一遍，只是快得多。

## 顺带澄清

白名单和「限制」是**两套独立的开关**，互不覆盖：

- **限制** 改的是 `appops RUN_IN_BACKGROUND`（后台活动权限）
- **白名单** 改的是系统电源管理名单（doze / standby 压制）

所以一个应用可以同时显示「已限制」和「白名单」——这是正常的，不是 bug。

---

## What's new (English)

- An **ⓘ** next to the allowlist page title explains what the list actually is: **the very same
  list as the system's Battery → No restrictions**, verified on Xiaomi HyperOS.
- Reminder: *Restrict* and *Allowlist* are independent switches — an app can legitimately be
  both.
