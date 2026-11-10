# Mine App 4.0.2（多通道版）

> **本版只发布多通道版**（`com.henry.appmanager.multi`）。
> 标准版没有 Root 通道（`RootShell.enabled = isMulti() && ...`，标准版恒为 false），
> 本次修复对它没有任何作用，功能与 4.0.1 完全一致，因此不单独发版。

## 修复

- **Root 通道找 su 的方式**：改为按绝对路径逐个解析（`/system/bin/su`、`/system/xbin/su` 等），
  不再依赖 `PATH`。之前的写法在 su 不在 PATH 里的机型上会直接报
  `Cannot run program "su": error=13`，导致 Root 通道不可用。

## 通道验证状态

- **Shizuku 通道**：已在真机充分验证（授权、执行、切换、冷启动）。
- **Root 通道**：维护者正在等待自购的 root 测试机，**目前只在模拟器中验证过**
  （代码路径、失败降级），真实提权尚未在 root 真机上验证。
