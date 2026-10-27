# Mine App 3.0.5

> 修一个隐蔽的问题：每次启动都会无条件重设 12 个 activity-alias 的启用状态。

---

## 问题

`restoreAlias()` 在每次 `onCreate` 都跑，而它无条件调用：

```java
pm.setComponentEnabledSetting(target, ENABLED, ...);      // 不管现在是不是已经启用
for (其他 11 个) pm.setComponentEnabledSetting(..., DISABLED, ...);
```

组件启用状态一变，系统就广播 `PACKAGE_CHANGED`，桌面重新查询组件时会把它当成**新组件**重新摆放 ——
表现就是「更新后图标跑到应用列表末尾」。

而且这不只发生在更新时，**每次打开 App 都在触发**。

## 修法

只在状态真的不一致时才改：

```java
if (pm.getComponentEnabledSetting(on) != en) {
    pm.setComponentEnabledSetting(on, en, flags);
}
```

默认名称/图标的情况下，覆盖安装后 manifest 里的状态本来就是对的，这里一次都不会调 —— 桌面也就不会重排。

## 仍然可能移动的情况

如果用户改过名称或图标（非默认 alias），覆盖安装会把 manifest 重置成默认值，我们必须把状态改回来 ——
这一次改动无法避免，图标位置仍可能被桌面重排。默认配置下没有这个问题。

---

## What's new (English)

- `restoreAlias()` no longer resets all 12 activity-aliases on every launch — it only touches a component
  when its enabled state actually differs. The redundant calls triggered a `PACKAGE_CHANGED` broadcast,
  which made the launcher re-register the icon as new and move it to the end of the app list.
- Users who kept the default name/icon should see the icon stay in place after an update. Custom
  name/icon choices still require one state change, so the launcher may re-place it.
