# Mine App 4.0.2（多通道版）

> 本版只发布多通道版（`com.henry.appmanager.multi`）。
> 标准版没有 Root 通道，功能与 4.0.1 一致，因此不单独发版。

## 改动

- **Root 通道改用绝对路径解析 su**（`/system/bin/su`、`/system/xbin/su`、`/sbin/su` 等），
  不再依赖 `PATH` 查找，解析结果缓存。属于写法上的健壮性改进。

> 需要说明的是：这只影响「去哪里找 su」，**并不解决**
> `Cannot run program "su": error=13` 这类权限报错 —— 那是文件权限与
> SELinux 策略导致的，必须到真机上才能确认。

## 通道验证状态

- **Shizuku 通道**：已在真机充分验证（授权、执行、切换、冷启动）。
- **Root 通道**：目前**只在模拟器中验证**（代码路径、失败降级），
  真实提权尚未在 root 真机上验证。待开发者的 root 测试机到手后，
  会补充真机验证并在此说明。

## 下载

`app-manager-multi.apk`（包名 `com.henry.appmanager.multi`），可与标准版共存。
