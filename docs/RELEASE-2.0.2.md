# Mine App 2.0.2

> 修一个打扰人的问题：风险提示弹窗每次启动都弹。

---

## 问题

「使用前请留意」这个风险提示，原本设计成**每次启动**都弹。
实际用起来很烦——每天开几次就弹几次，确认过也记不住。

## 改法

改成**每次安装只提示一次**：

- 点过「同意并使用」后，记住当前 `versionCode`
- 之后启动不再弹
- **升级到新版本会再提示一次**（版本变了，条款可能需要重新确认）
- 重装（清除数据）也会重新提示

判断依据是 `versionCode` 而不是一个布尔标记——覆盖安装时 localStorage 是保留的：
只记「同意过」就再也不会弹，只记布尔值又每次启动都弹，都不对。

## 升级提示

如果你是从 2.0.1 升级上来的，**这次还会看到一次**（旧版本没有记录过同意状态），
确认之后就彻底安静了。

---

## What's new (English)

- **The startup risk notice no longer appears on every launch.** It is now shown **once per
  install**: after you tap "Agree and continue", the current `versionCode` is remembered and the
  dialog stays away — until the next version is installed, which shows it once again.
- Upgrading from 2.0.1 will show it one last time (the old build never recorded your consent).
