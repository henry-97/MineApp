# Mine App 2.0.1

> 只修一个白名单页的可读性问题，功能没变。

---

## 问题

打开 Millet 白名单页，看到的是一份应用列表 + 一排勾选框。
22 个已经在名单里的应用被**预先勾上**，和「我刚选的」长得一模一样——
根本看不出谁本来就在名单里，也分不清勾选框到底代表什么。

## 改法

**白名单页**（只改这个页面，主界面完全不受影响）：

- 每行显示一个绿色「**白名单**」胶囊，压在「已限制 / 已归档」上方
- **默认排序**把白名单应用排在最前，一眼就能看出名单里有谁
- **不再预先勾选**：勾选框只表示「本次要加入」，不再兼职表示「已经在名单里」
- 底部信息变成 `已选 N · 共 597 · 当前名单 22`，两个数一眼分清

顺手补了两处保护：

- 保存是**整体覆盖**，勾得比原来少时会先弹确认
  （`白名单会从 22 个变成 3 个（整体覆盖），确定吗？`），避免静默丢配置
- 保存后自动刷新主列表

## 升级

覆盖安装即可，白名单配置不受影响。

---

## What's new (English)

- **The allowlist page is readable now.** Each row shows a green "Allowlisted" pill above the
  Restricted / Archived state, and allowlisted apps sort to the top of the list.
- **No more pre-checked boxes.** The checkbox now only means "add this one", so current members
  and your pending selection are no longer visually identical.
- **Footer shows both counts**: `selected N · total · currently 22`.
- **Guarded save.** Saving fewer entries than before asks for confirmation first.
